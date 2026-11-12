"""Import the generated avatar images (gpt-image-2) into the Delos app assets.

Usage: python tools/avatars/import.py [INPUT_DIR] [--preview-dir DIR] [--preview-all]
INPUT_DIR defaults to C:\\Vibecoding\\Delos\\.gradle-agent\\avatars-in and holds <index>_<slug>.png files
(00_sailor.png ... 15_dragon.png): 1024x1024 RGBA, the character on a transparent background and filling
about 74% of the canvas. Any subset may be present; run it again whenever more files arrive.
Dependencies: Pillow 11.3.0 with WebP support. Deterministic (no randomness, no timestamps).

For every PNG found it
  * validates it (RGBA, square, alpha present, bounding box of the character centered within 6% and
    filling 60-85% of the canvas); a failed check only prints a WARN with the numbers,
  * resizes it to 384x384 with Lanczos (a non-square canvas is padded to a square first, nothing is cut)
    and writes app/src/main/assets/avatars/<slug>.webp, replacing the procedural file of that slug,
  * computes the badge background (see `background`) and writes it to app/src/main/assets/avatars/avatars.json:
    {"fox": {"c0": "#rrggbb", "c1": "#rrggbb"}}.
Every imported slug gets an entry. A slug that is not in this run keeps the entry it has (it is dropped
only when the slug is unknown or its webp is gone), and a slug without an image keeps the app's fixed-hue
gradient and font icon (AVATARS / Profiles.kt). Run it with all sixteen PNGs to refresh the whole file.
Slugs 00..11 without an imported image keep their procedural webp (tools/avatars/generate.py, the fallback
generator); owl, hacker, wolf and dragon have no procedural drawing and need an image.

The badge background is ALWAYS a dark, muted radial gradient in the tone of the avatar: with the avatar's
hue h, c0 = hsl(h, .32, .22) in the lit center and c1 = hsl(h, .26, .10) at the edge (the numbers of the
approved set sheet). h is the entry of HUE_HINTS for the slug when there is one, else the character's
dominant color, found like the app's cover colors do it (HeroBitmap.dominantHues: 16-level RGB buckets,
CAM16 chroma squared weights, grey / near-black / near-white clusters ignored, hue groups of 15 degrees),
ported with a real CAM16/HCT conversion (the material color utilities' math, default viewing conditions);
a mostly grey character has no reliable dominant hue and uses the avatar's own hue (Avatar.hue).
HUE_HINTS exists because the detector picks a skin tone for the sailor and a leaf for the calm stones.

--preview-dir (default build/avatars-preview) gets contact sheets: every imported avatar at 280/96/72/56 px
on its dark badge and on the app's fixed-hue gradient (what the app draws without a json entry), plus a
family sheet of all sixteen in two rows of eight with the colors the app draws.
--preview-all also computes and shows the dark badge of the slugs that were not imported (from their
current webp; nothing is written for them).
"""

import argparse
import colorsys
import json
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

REPO = Path(__file__).resolve().parents[2]
ASSETS = REPO / "app/src/main/assets/avatars"
DEFAULT_INPUT = Path(r"C:\Vibecoding\Delos\.gradle-agent\avatars-in")
DEFAULT_PREVIEW = REPO / "build/avatars-preview"
SIZE = 384
FAMILY_COLUMNS = 8   # the family sheet: two rows of eight, like the picker

# Order = stored index; hues are Avatar.hue of Profiles.kt (used for the fixed-hue gradient of a slug without
# a json entry and as the base hue of mostly grey characters).
AVATARS = (
    ("sailor", 205), ("voyager", 262), ("player", 330), ("fox", 24), ("spark", 48), ("calm", 150),
    ("knight", 232), ("robot", 182), ("cat", 84), ("wizard", 292), ("racer", 356), ("ghost", 118),
    ("owl", 150), ("hacker", 320), ("wolf", 200), ("dragon", 110),
)

# The hue (degrees) of each avatar's badge, chosen by eye on the previews; it wins over the detected dominant
# hue (which took the sailor's skin and the calm stones' leaf for the character's color).
HUE_HINTS = {
    "sailor": 187, "calm": 95, "spark": 43, "cat": 25, "racer": 358, "fox": 15, "robot": 183, "voyager": 250,
    "player": 213, "knight": 212, "wizard": 250, "ghost": 210, "owl": 150, "hacker": 320, "wolf": 205, "dragon": 110,
}

# Validation limits
CENTER_TOLERANCE = .06
FILL_MIN, FILL_MAX = .60, .85

# ------------------------------------------------------------ background constants (the approved set sheet's numbers)
CENTER_S, CENTER_L = .32, .22          # c0: the lit center of the radial gradient
EDGE_S, EDGE_L = .26, .10              # c1: the edge, darker and a little greyer
MIN_COLORFUL = .20                     # share of opaque pixels in counted clusters below which the hue is the base hue


# ------------------------------------------------------------ CAM16 / HCT (Material color utilities)

def _linear(c: int) -> float:
    v = c / 255
    return v / 12.92 if v <= .04045 else ((v + .055) / 1.055) ** 2.4


def _xyz(rgb):
    r, g, b = (_linear(c) * 100 for c in rgb)
    return (.41233895 * r + .35762064 * g + .18051042 * b,
            .2126 * r + .7152 * g + .0722 * b,
            .01932141 * r + .11916382 * g + .95034478 * b)


def _lstar(y: float) -> float:
    y /= 100
    e = 216 / 24389
    return 116 * y ** (1 / 3) - 16 if y > e else y * (24389 / 27)


_WHITE = (95.047, 100.0, 108.883)
_LA = 200 / math.pi * (100 * ((50 + 16) / 116) ** 3) / 100   # adapting luminance, background L* 50
_N = (100 * ((50 + 16) / 116) ** 3) / 100                    # background Y / white Y
_C, _NC = .69, 1.0                                          # average surround
_K = 1 / (5 * _LA + 1)
_FL = _K ** 4 * _LA + .1 * (1 - _K ** 4) ** 2 * (5 * _LA) ** (1 / 3)
_Z = 1.48 + math.sqrt(_N)
_NBB = .725 / _N ** .2
_M16 = ((.401288, .650173, -.051461), (-.250268, 1.204414, .045854), (-.002079, .048952, .953127))
_D = max(0.0, min(1.0, 1 - (1 / 3.6) * math.exp((-_LA - 42) / 92)))   # surround factor 1 (average)


def _adapted(rgb_c):
    out = []
    for i, c in enumerate(rgb_c):
        scaled = (_D * (100 / _rgb_white[i]) + 1 - _D) * c
        af = (_FL * abs(scaled) / 100) ** .42
        out.append(math.copysign(1, scaled) * 400 * af / (af + 27.13))
    return out


_rgb_white = [sum(m * w for m, w in zip(row, _WHITE)) for row in _M16]
_AW = (2 * _adapted(_rgb_white)[0] + _adapted(_rgb_white)[1] + .05 * _adapted(_rgb_white)[2] - .305) * _NBB


def hct(rgb):
    """(hue degrees, chroma, tone) of an sRGB color, like Material's Hct.fromInt."""
    x, y, z = _xyz(rgb)
    rgb_c = [m[0] * x + m[1] * y + m[2] * z for m in _M16]
    ra, ga, ba = _adapted(rgb_c)
    a = (11 * ra - 12 * ga + ba) / 11
    b = (ra + ga - 2 * ba) / 9
    u = (20 * ra + 20 * ga + 21 * ba) / 20
    p2 = (40 * ra + 20 * ga + ba) / 20
    hue = math.degrees(math.atan2(b, a)) % 360
    ac = p2 * _NBB
    j = 100 * (ac / _AW) ** (_C * _Z)
    hue_prime = hue + 360 if hue < 20.14 else hue
    e_hue = .25 * (math.cos(math.radians(hue_prime) + 2) + 3.8)
    p1 = 50000 / 13 * e_hue * _NC * _NBB
    t = p1 * math.hypot(a, b) / (u + .305)
    alpha = max(t, 0) ** .9 * (1.64 - .29 ** _N) ** .73
    return hue, alpha * math.sqrt(max(j, 0) / 100), _lstar(y)


# ------------------------------------------------------------ dominant color (HeroBitmap.dominantHues)

MIN_CHROMA = 16.0


def dominant_hues(image: Image.Image):
    """Port of HeroBitmaps.dominantHues. Returns (hues, saturations, colorful share) or None for a grey image.

    hues are HSL hues in degrees of the (up to three) strongest hue groups, strongest first; the share is
    the part of the opaque pixels that sit in a cluster counted as colorful.
    """
    rgba = image.convert("RGBA")
    sums = {}
    total = 0
    for r, g, b, a in rgba.getdata():
        if a < 200:
            continue
        total += 1
        s = sums.setdefault(((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4), [0, 0, 0, 0])
        s[0] += r; s[1] += g; s[2] += b; s[3] += 1
    if not total:
        return None
    clusters = []   # (rgb, weight, bin, count)
    colorful = 0
    for key in sorted(sums):
        r, g, b, n = sums[key]
        rgb = (r // n, g // n, b // n)
        hue, chroma, tone = hct(rgb)
        if chroma < MIN_CHROMA or tone < 18.0 or tone > 94.0:
            continue
        clusters.append((rgb, n * chroma * chroma, min(max(int(hue / 15), 0), 23), n))
        colorful += n
    if not clusters:
        return None

    def bin_distance(a, b):
        d = (a - b) % 24
        return min(d, 24 - d)

    bins = [0.0] * 24
    for _, w, b, _ in clusters:
        bins[b] += w
    groups = [bins[(b + 23) % 24] * .5 + bins[b] + bins[(b + 1) % 24] * .5 for b in range(24)]
    top = max(groups)
    chosen, used = [], []
    for b in sorted(range(24), key=lambda i: (-groups[i], i)):
        if len(chosen) == 3 or groups[b] < top * .08:
            break
        if any(bin_distance(u, b) < 2 for u in used):
            continue
        pool = [c for c in clusters if bin_distance(c[2], b) <= 1 and c not in chosen]
        if not pool:
            continue
        chosen.append(max(pool, key=lambda c: c[1]))
        used.append(b)
    if not chosen:
        return None
    hues, sats = [], []
    for rgb, _, _, _ in chosen:
        h, l, s = colorsys.rgb_to_hls(*(c / 255 for c in rgb))
        hues.append(h * 360)
        sats.append(min(max(s / .85, .28), 1.18))
    return hues, sats, colorful / total


# ------------------------------------------------------------ the badge background

def hsl(h: float, s: float, l: float):
    return tuple(round(c * 255) for c in colorsys.hls_to_rgb((h % 360) / 360, l, s))


def hex_color(rgb) -> str:
    return "#%02x%02x%02x" % rgb


def character_hue(image: Image.Image, slug: str, base_hue: float):
    """(hue in degrees, where it comes from, detected dominant hue or None) of an avatar's badge: the HUE_HINTS
    entry of the slug, else the dominant hue of the character, else (a mostly grey one) the avatar's base hue."""
    found = dominant_hues(image)
    detected = found[0][0] if found is not None and found[2] >= MIN_COLORFUL else None
    if slug in HUE_HINTS:
        return float(HUE_HINTS[slug]), "hint", detected
    if detected is None:
        return float(base_hue), "base", None
    return detected, "dominant", detected


def colors_of_hue(hue: float):
    """(c0, c1): the dark, muted radial gradient in the tone of [hue]."""
    return hsl(hue, CENTER_S, CENTER_L), hsl(hue, EDGE_S, EDGE_L)


def background(image: Image.Image, slug: str, base_hue: float):
    """(c0, c1, info) for a character image: always the dark muted gradient of its hue, see the module docs."""
    hue, source, detected = character_hue(image, slug, base_hue)
    c0, c1 = colors_of_hue(hue)
    seen = "" if detected is None or source != "hint" else f", detected {detected:.0f}°"
    return c0, c1, f"{source} {hue:.0f}°{seen}"


# ------------------------------------------------------------ validation and resize

def alpha_box(image: Image.Image):
    return image.getchannel("A").point(lambda v: 255 if v > 24 else 0).getbbox()


def validate(path: Path, image: Image.Image):
    """Warnings (list of strings) and the (fill, offset) numbers of a loaded image."""
    warnings = []
    if image.mode != "RGBA":
        warnings.append(f"mode {image.mode}, expected RGBA")
    if image.width != image.height:
        warnings.append(f"not square: {image.width}x{image.height}")
    if image.width < SIZE:
        warnings.append(f"only {image.width}px wide")
    rgba = image.convert("RGBA")
    box = alpha_box(rgba)
    if box is None:
        warnings.append("no opaque pixels (alpha missing or empty)")
        return warnings, 0.0, 0.0
    if rgba.getchannel("A").getextrema()[0] == 255:
        warnings.append("alpha is fully opaque: no transparent background")
    side = max(rgba.size)
    fill = max(box[2] - box[0], box[3] - box[1]) / side
    dx = ((box[0] + box[2]) / 2 - rgba.width / 2) / side
    dy = ((box[1] + box[3]) / 2 - rgba.height / 2) / side
    offset = max(abs(dx), abs(dy))
    if not FILL_MIN <= fill <= FILL_MAX:
        warnings.append(f"character fills {fill * 100:.1f}% of the canvas (want {FILL_MIN * 100:.0f}-{FILL_MAX * 100:.0f}%)")
    if offset > CENTER_TOLERANCE:
        warnings.append(f"character off center by {dx * 100:+.1f}% / {dy * 100:+.1f}% (max {CENTER_TOLERANCE * 100:.0f}%)")
    return warnings, fill, offset


def to_asset(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    if rgba.width != rgba.height:
        side = max(rgba.size)
        square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
        square.paste(rgba, ((side - rgba.width) // 2, (side - rgba.height) // 2))
        rgba = square
    return rgba.resize((SIZE, SIZE), Image.Resampling.LANCZOS)


# ------------------------------------------------------------ previews

def gradient(c0, c1, size: int) -> Image.Image:
    """The app's radial-gradient(circle at 35% 30%, c0, c1): the ray reaches the farthest corner."""
    cx, cy = size * .35, size * .3
    reach = max(math.hypot(x, y) for x in (cx, size - cx) for y in (cy, size - cy))
    img = Image.new("RGB", (size, size))
    img.putdata([
        tuple(round(c0[c] + (c1[c] - c0[c]) * min(1, math.hypot(x - cx, y - cy) / reach)) for c in range(3))
        for y in range(size) for x in range(size)
    ])
    return img


def old_colors(hue):
    return hsl(hue, .8, .62), hsl(hue + 30, .7, .38)


_CACHE = {}


def badge(art: Image.Image, c0, c1, size: int) -> Image.Image:
    key = (c0, c1, size)
    if key not in _CACHE:
        _CACHE[key] = gradient(c0, c1, size).convert("RGBA")
    base = _CACHE[key].copy()
    base.alpha_composite(art.resize((size, size), Image.Resampling.LANCZOS))
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    base.putalpha(mask.resize((size, size), Image.Resampling.LANCZOS))
    return base


def sheets(rows, family, folder: Path):
    """rows: (slug, art, dark badge colors, fixed-hue colors); family: (slug, art, colors the app draws) for all sixteen."""
    folder.mkdir(parents=True, exist_ok=True)
    font = ImageFont.load_default(size=16)
    bg = (13, 17, 28, 255)
    sizes = (280, 96, 72, 56)
    gap = 20
    width = 150 + 2 * (sum(sizes) + gap * len(sizes)) + gap
    height = len(rows) * (280 + gap) + 60
    sheet = Image.new("RGBA", (width, height), bg)
    draw = ImageDraw.Draw(sheet)
    draw.text((150, 14), "dark muted (avatars.json)", fill=(235, 240, 250), font=font)
    draw.text((150 + sum(sizes) + gap * len(sizes) + gap, 14), "fixed-hue gradient (no json entry)", fill=(235, 240, 250), font=font)
    y = 50
    for slug, art, dark, fixed in rows:
        draw.text((14, y + 120), slug, fill=(235, 240, 250), font=font)
        x = 150
        for colors in (dark, fixed):
            for s in sizes:
                sheet.alpha_composite(badge(art, colors[0], colors[1], s), (x, y + (280 - s) // 2))
                x += s + gap
        y += 280 + gap
    sheet.convert("RGB").save(folder / "avatar-import-sheet.png")
    cell = 150
    cols = FAMILY_COLUMNS
    fam = Image.new("RGBA", (cols * (cell + gap) + gap, ((len(family) + cols - 1) // cols) * (cell + gap + 24) + gap), bg)
    draw = ImageDraw.Draw(fam)
    for i, (slug, art, colors) in enumerate(family):
        x, y = gap + (i % cols) * (cell + gap), gap + (i // cols) * (cell + gap + 24)
        fam.alpha_composite(badge(art, colors[0], colors[1], cell), (x, y))
        draw.text((x, y + cell + 4), slug, fill=(235, 240, 250), font=font)
    fam.convert("RGB").save(folder / "avatar-import-family.png")


# ------------------------------------------------------------ main

def read_colors():
    """The entries of the current avatars.json that are still valid: a known slug whose webp exists."""
    try:
        current = json.loads((ASSETS / "avatars.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    known = {slug for slug, _ in AVATARS if (ASSETS / f"{slug}.webp").is_file()}
    return {slug: entry for slug, entry in current.items() if slug in known}


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("input", nargs="?", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--preview-dir", type=Path, default=DEFAULT_PREVIEW)
    parser.add_argument("--preview-all", action="store_true", help="show the dark badge of the slugs that were not imported too")
    args = parser.parse_args()

    if not args.input.is_dir():
        sys.exit(f"input folder {args.input} does not exist")
    files = {}
    for path in sorted(args.input.glob("*.png")):
        index, _, slug = path.stem.partition("_")
        if index.isdigit() and int(index) < len(AVATARS) and AVATARS[int(index)][0] == slug:
            files[slug] = path
        else:
            print(f"skip {path.name}: not <index>_<slug>.png of a known avatar")

    colors_json = read_colors()
    report = []
    rows = []
    family = []
    for slug, hue in AVATARS:
        path = files.get(slug)
        target = ASSETS / f"{slug}.webp"
        if path is None:
            if not target.is_file():
                # No image yet (the four newest have no procedural drawing): the app shows the fixed-hue gradient and the font icon
                family.append((slug + "?", Image.new("RGBA", (SIZE, SIZE)), old_colors(hue)))
                report.append((slug, "no image", "-", "-", f"fixed hue {hue} and the font icon until it is imported", ""))
                continue
            art = Image.open(target).convert("RGBA")
            entry = colors_json.get(slug)
            shown = tuple(tuple(bytes.fromhex(entry[k].lstrip("#"))) for k in ("c0", "c1")) if entry else old_colors(hue)
            if args.preview_all:
                c0, c1, info = background(art, slug, hue)
                rows.append((slug + "*", art, (c0, c1), old_colors(hue)))
                family.append((slug + "*", art, (c0, c1)))
                text = f"kept; preview-all: {hex_color(c0)} {hex_color(c1)} ({info})"
            else:
                family.append((slug, art, shown))
                text = f"kept: {hex_color(shown[0])} {hex_color(shown[1])}" if entry else f"kept: fixed hue {hue}, no json entry"
            report.append((slug, "kept", "-", "-", text, ""))
            continue
        with Image.open(path) as source:
            source.load()
            warnings, fill, offset = validate(path, source)
            c0, c1, info = background(source, slug, hue)
            art = to_asset(source)
        art.save(target, "WEBP", quality=90, method=6, alpha_quality=100)
        colors_json[slug] = {"c0": hex_color(c0), "c1": hex_color(c1)}
        rows.append((slug, art, (c0, c1), old_colors(hue)))
        family.append((slug, art, (c0, c1)))
        report.append((slug, "imported", f"{fill * 100:.1f}%", f"{offset * 100:.1f}%", f"{hex_color(c0)} {hex_color(c1)} ({info})", "; ".join(warnings)))

    # json: one entry per imported slug, in avatar order
    ordered = {slug: colors_json[slug] for slug, _ in AVATARS if slug in colors_json}
    with open(ASSETS / "avatars.json", "w", encoding="utf-8", newline="\n") as out:
        out.write(json.dumps(ordered, indent=2) + "\n")

    print(f"{'slug':9} {'status':9} {'fill':>6} {'offset':>7}  gradient")
    for slug, status, fill, offset, gradient_text, warn in report:
        print(f"{slug:9} {status:9} {fill:>6} {offset:>7}  {gradient_text}")
        if warn:
            print(f"          WARN {warn}")
    print(f"{len(ordered)} json entries; slugs without one use their fixed hue and the font icon until they are imported")
    if family:
        sheets(rows, family, args.preview_dir)
        print(f"previews in {args.preview_dir}")


if __name__ == "__main__":
    main()
