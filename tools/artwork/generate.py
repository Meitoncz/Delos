"""Generate the bundled host-app artwork for Delos (no logo assets).

Usage: python tools/artwork/generate.py [--preview]
Dependencies: Pillow 11.3.0, fonttools 4.67.0 (pip install Pillow==11.3.0 fonttools==4.67.0).
Run with the same Pillow/FreeType versions for byte-identical JPEGs. All randomness
is locally seeded; no timestamps or machine-specific metadata enter the images.
Phosphor 2.1.2's full Fill font and name mapping are fetched and SHA-256 checked
by the existing font builder. Steam uses the checked-in, checksum-pinned canonical
Valve emblem paths in steam-emblem.svg, not a Phosphor approximation. Downloads
and optional previews stay under build/. The checked-in Unbounded variable font
is rendered at SemiBold (weight 600).
"""

import argparse
import colorsys
import hashlib
import importlib.util
import math
import random
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree

from fontTools.pens.basePen import BasePen
from fontTools.svgLib.path import parse_path
from fontTools.ttLib import TTFont
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

REPO = Path(__file__).resolve().parents[2]
OUTPUT = REPO / "app/src/main/assets/artwork"
CACHE = REPO / "build/phosphor-icons"
PREVIEW = REPO / "build/artwork-preview"
TEXT_FONT = REPO / "app/src/main/res/font/unbounded.ttf"
SCALE = 2
FORMATS = {"cover": (600, 900), "wide": (920, 430), "hero": (1920, 1080)}


@dataclass(frozen=True)
class Artwork:
    slug: str
    title: tuple[str, ...]
    icon: str
    hue: int
    secondary: int
    seed: int


APPS = (
    Artwork("desktop", ("Desktop",), "monitor", 215, 252, 1051),
    Artwork("remote-input", ("Remote", "Input"), "mouse", 160, 194, 2053),
    Artwork("remote-monitor", ("Remote", "Monitor"), "monitor-play", 275, 231, 3067),
    Artwork("steam-big-picture", ("Steam", "Big Picture"), "steam-emblem", 205, 241, 4091),
)


def source_icons() -> tuple[Path, dict[str, int]]:
    """Reuse the font builder's verified, pinned sources without running its build."""
    spec = importlib.util.spec_from_file_location("delos_font_sources", REPO / "tools/fonts/subset_fonts.py")
    module = importlib.util.module_from_spec(spec)
    # Importing the shared helper must not leave generated files under tools/.
    previous = sys.dont_write_bytecode
    sys.dont_write_bytecode = True
    try:
        spec.loader.exec_module(module)
    finally:
        sys.dont_write_bytecode = previous
    CACHE.mkdir(parents=True, exist_ok=True)
    module.fetch(CACHE, icons_only=True)
    codepoints = {
        name: int(cp, 16)
        for name, cp in re.findall(
            r'\.ph\.ph-([\w-]+):before\s*\{\s*content:\s*"\\([0-9a-f]+)";',
            (CACHE / "regular.css").read_text(encoding="utf-8"),
        )
    }
    # These code points are shared by Regular and Fill, but always validate the
    # full Fill source rather than silently rendering a subset's missing glyph.
    with TTFont(CACHE / "Phosphor-Fill.ttf") as font:
        available = font.getBestCmap()
        for name in {app.icon for app in APPS if app.icon != "steam-emblem"}:
            if name not in codepoints or codepoints[name] not in available:
                raise SystemExit(f"Missing Phosphor Fill glyph: {name}")
    return CACHE / "Phosphor-Fill.ttf", codepoints


def hsl(hue: float, saturation: float, lightness: float) -> tuple[int, int, int]:
    return tuple(round(channel * 255) for channel in colorsys.hls_to_rgb(hue / 360, lightness, saturation))


def background(app: Artwork, kind: str, size: tuple[int, int]) -> Image.Image:
    """Smooth two-hue fields on the same 7%-lightness dark base as Delos."""
    width, height = size
    # A small continuous field is sufficient; icon and text edges remain at 2x.
    small = (width // 4, height // 4)
    base = hsl(228, .38, .07)
    first = hsl(app.hue, .71, .28)
    second = hsl(app.secondary, .67, .24)
    glow = hsl(app.hue, .77, .47)
    hero = kind == "hero"
    center_x, center_y = ((.78, .46) if hero else ((.71, .4) if kind == "wide" else (.52, .34)))
    pixels = bytearray()
    for py in range(small[1]):
        y = py / (small[1] - 1)
        for px in range(small[0]):
            x = px / (small[0] - 1)
            a = math.exp(-(((x - center_x) / .62) ** 2 + ((y - center_y) / .73) ** 2) * 2)
            b = math.exp(-(((x - .93) / .66) ** 2 + ((y - .03) / .7) ** 2) * 2.2)
            radial = math.exp(-(((x - center_x) / .31) ** 2 + ((y - center_y) / .43) ** 2) * 1.4)
            # Hero deliberately reserves its left half for foreground UI.
            quiet = .16 + .84 * (x * x * (3 - 2 * x)) if hero else 1.0
            vignette = 1 - .21 * min(1, ((x - .5) / .7) ** 2 + ((y - .5) / .7) ** 2)
            lower = 1 - (.25 * y ** 3 if not hero else 0)
            for channel in range(3):
                value = base[channel] + quiet * (
                    (first[channel] - base[channel]) * a * .64
                    + (second[channel] - base[channel]) * b * .47
                    + (glow[channel] - base[channel]) * radial * .19
                )
                pixels.append(round(max(0, min(255, value * vignette * lower))))
    image = Image.frombytes("RGB", small, bytes(pixels))
    return image.resize((width * SCALE, height * SCALE), Image.Resampling.BICUBIC)


def icon_mask(font_path: Path, glyph: str, box: tuple[int, int]) -> Image.Image:
    """Fit visible glyph bounds, not the font's generous bearings, into the box."""
    font = ImageFont.truetype(str(font_path), size=1024)
    bounds = font.getbbox(glyph)
    mask = Image.new("L", (bounds[2] - bounds[0], bounds[3] - bounds[1]))
    ImageDraw.Draw(mask).text((-bounds[0], -bounds[1]), glyph, font=font, fill=255)
    ratio = min(box[0] / mask.width, box[1] / mask.height)
    return mask.resize((round(mask.width * ratio), round(mask.height * ratio)), Image.Resampling.LANCZOS)


class SteamMaskPen(BasePen):
    """Flatten Valve's cubic paths directly into a supersampled Pillow mask."""

    def __init__(self, size: int):
        super().__init__()
        self.size = size
        self.mask = Image.new("1", (size, size))
        self.points = []

    def _moveTo(self, point):
        self.points = [point]

    def _lineTo(self, point):
        self.points.append(point)

    def _curveToOne(self, first, second, end):
        start = self._getCurrentPoint()
        # 48 segments per cubic keep the original curves smooth even in heroes.
        for step in range(1, 49):
            t = step / 48
            u = 1 - t
            self.points.append(tuple(
                u ** 3 * start[axis] + 3 * u * u * t * first[axis]
                + 3 * u * t * t * second[axis] + t ** 3 * end[axis]
                for axis in (0, 1)
            ))

    def _closePath(self):
        contour = Image.new("1", self.mask.size)
        factor = self.size / 89.333
        ImageDraw.Draw(contour).polygon(
            [(round(x * factor), round(y * factor)) for x, y in self.points], fill=1,
        )
        # The canonical concentric joint contours are holes, not solid disks.
        self.mask = ImageChops.logical_xor(self.mask, contour)

    def _endPath(self):
        self._closePath()


def steam_mask(box: tuple[int, int]) -> Image.Image:
    # Git may check this text asset out with CRLF on Windows; pin its canonical LF representation.
    source = Path(__file__).with_name("steam-emblem.svg").read_bytes().replace(b"\r\n", b"\n")
    if hashlib.sha256(source).hexdigest() != "b08a780e342bfa5d117882e20294a8dbec9da78748fa305b8d373d7223892169":
        raise SystemExit("Canonical Steam emblem checksum mismatch")
    size = min(box)
    mask = Image.new("L", (size, size))
    for path in ElementTree.fromstring(source).findall("{http://www.w3.org/2000/svg}path"):
        pen = SteamMaskPen(size)
        parse_path(path.attrib["d"], pen)
        mask = ImageChops.lighter(mask, pen.mask.convert("L"))
    return mask


def add_icon(image: Image.Image, app: Artwork, kind: str, font: Path, glyph: str) -> None:
    width, height = image.size
    if kind == "cover":
        center, box = (.5, .40), (390, 390)
    elif kind == "wide":
        center, box = (.77, .47), (300, 285)
    else:
        center, box = (.78, .46), (650, 650)
    icon_box = (box[0] * SCALE, box[1] * SCALE)
    mask = steam_mask(icon_box) if app.icon == "steam-emblem" else icon_mask(font, glyph, icon_box)
    x = round(width * center[0] - mask.width / 2)
    y = round(height * center[1] - mask.height / 2)
    full = Image.new("L", image.size)
    full.paste(mask, (x, y))
    opacity = .58 if kind == "hero" else .90
    shadow = Image.new("L", image.size)
    shadow.paste(mask, (x, y + 12 * SCALE))
    image.paste((0, 0, 0), mask=shadow.filter(ImageFilter.GaussianBlur(18 * SCALE)).point(lambda value: round(value * .36)))
    halo = full.filter(ImageFilter.GaussianBlur(34 * SCALE)).point(lambda value: round(value * .20))
    image.paste(hsl(app.hue, .76, .65), mask=halo)
    # Very light cool-white tint, with a small vertical light falloff.
    top, bottom = hsl(app.hue, .42, .94), hsl(app.hue, .39, .66)
    ramp = Image.new("RGB", (1, mask.height))
    ramp.putdata([
        tuple(round(a + (b - a) * row / max(1, mask.height - 1)) for a, b in zip(top, bottom))
        for row in range(mask.height)
    ])
    image.paste(ramp.resize(mask.size), (x, y), mask.point(lambda value: round(value * opacity)))


def display_font(size: int) -> ImageFont.FreeTypeFont:
    font = ImageFont.truetype(str(TEXT_FONT), size=size)
    font.set_variation_by_axes([600])
    return font


def add_title(image: Image.Image, app: Artwork, kind: str) -> None:
    width, height = image.size
    inset = 54 * SCALE
    max_width = (492 if kind == "cover" else 455) * SCALE
    size = (52 if kind == "cover" else 54) * SCALE
    font = display_font(size)
    while max(font.getlength(line) for line in app.title) > max_width:
        size -= 1
        font = display_font(size)
    line_height = round(size * 1.22)
    bounds = [font.getbbox(line) for line in app.title]
    visible_height = bounds[-1][3] - bounds[0][1] + line_height * (len(app.title) - 1)
    bottom = (78 if kind == "cover" else 64) * SCALE
    top = height - bottom - visible_height
    mask = Image.new("L", image.size)
    draw = ImageDraw.Draw(mask)
    for index, (line, bbox) in enumerate(zip(app.title, bounds)):
        origin = (inset - bbox[0], top - bounds[0][1] + index * line_height)
        # Keep text comfortably inside its image, even if a future name changes.
        actual = draw.textbbox(origin, line, font=font)
        if not (inset <= actual[0] < actual[2] <= width - inset and 0 < actual[1] < actual[3] < height):
            raise SystemExit(f"Title does not fit: {app.slug}/{kind}: {actual}")
        draw.text(origin, line, font=font, fill=255)
    shadow = ImageChops.offset(mask, 0, 4 * SCALE).filter(ImageFilter.GaussianBlur(11 * SCALE))
    image.paste((0, 0, 0), mask=shadow.point(lambda value: round(value * .52)))
    image.paste(hsl(app.hue, .20, .96), mask=mask)


def render(app: Artwork, kind: str, font: Path, codepoint: int | None) -> Image.Image:
    size = FORMATS[kind]
    image = background(app, kind, size)
    add_icon(image, app, kind, font, chr(codepoint) if codepoint is not None else "")
    if kind != "hero":
        add_title(image, app, kind)
    image = image.resize(size, Image.Resampling.LANCZOS)
    # One fine monochrome grain layer breaks smooth-gradient banding without
    # inflating JPEGs with conspicuous noise. Stable across Python hash seeds.
    seed = app.seed + {"cover": 0, "wide": 100, "hero": 200}[kind]
    noise = Image.frombytes("L", size, random.Random(seed).randbytes(size[0] * size[1])).convert("RGB")
    return Image.blend(image, noise, .009)


def contact_sheet() -> Path:
    sheet = Image.new("RGB", (1460, 4 * 340 + 40), (13, 17, 28))
    draw = ImageDraw.Draw(sheet)
    label = display_font(16)
    for row, app in enumerate(APPS):
        top = 20 + row * 340
        draw.text((22, top), " ".join(app.title), font=label, fill=(231, 236, 247))
        for kind, x, box in (("cover", 22, (180, 270)), ("wide", 224, (460, 215)), ("hero", 708, (730, 270))):
            with Image.open(OUTPUT / app.slug / f"{kind}.jpg") as source:
                source.thumbnail(box, Image.Resampling.LANCZOS)
                sheet.paste(source, (x, top + 35))
            draw.text((x, top + 310), f"{kind.upper()}  {FORMATS[kind][0]} x {FORMATS[kind][1]}", font=label, fill=(139, 153, 177))
    PREVIEW.mkdir(parents=True, exist_ok=True)
    path = PREVIEW / "contact-sheet.jpg"
    sheet.save(path, quality=94, subsampling=0, optimize=True)
    return path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--preview", action="store_true", help="also write build/artwork-preview/contact-sheet.jpg")
    args = parser.parse_args()
    font, codepoints = source_icons()
    total = 0
    for app in APPS:
        codepoint = codepoints.get(app.icon)
        if app.icon == "steam-emblem":
            print(f"{app.slug}: canonical Valve Steam emblem (steam-emblem.svg)")
        else:
            print(f"{app.slug}: Phosphor Fill {app.icon} U+{codepoint:04X}")
        for kind in FORMATS:
            target = OUTPUT / app.slug / f"{kind}.jpg"
            target.parent.mkdir(parents=True, exist_ok=True)
            render(app, kind, font, codepoint).save(target, quality=90, subsampling=0, optimize=True, progressive=True)
            size = target.stat().st_size
            total += size
            print(f"  {target.relative_to(REPO)}: {size:,} bytes")
    if total > 2_500_000:
        raise SystemExit(f"Artwork exceeds the 2.5 MB budget: {total:,} bytes")
    print(f"12 JPEGs: {total:,} bytes total")
    if args.preview:
        print(f"Preview: {contact_sheet()}")


if __name__ == "__main__":
    main()
