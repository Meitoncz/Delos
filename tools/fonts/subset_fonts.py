"""Builds the fonts bundled with the Delos UI (see docs/ICONS.md).

- Unbounded (display) and Figtree (body), SIL Open Font License 1.1, variable weight axis limited to 400-700
  (Figtree 400-800, for the hint bar's button letters).
  Unbounded is subset to Latin (incl. Czech and other extended Latin) and Cyrillic; Android falls
  back to system fonts for other scripts.
- Phosphor Regular and Fill 2.1.2, MIT, subset to the semantic mappings in tools/fonts/icons.txt.
  Android and the HTML prototype share the same two fonts and code points.
- Tabler Icons 3.51.0 (second pack, MIT): only the glyphs that icons.txt marks `tabler:<name>`, where no Phosphor
  glyph fits the meaning. They are imported into the same two fonts, scaled to the Phosphor em box, at code points
  from U+F000 (above Phosphor's range). Regular gets the 300 weight (1.5 px stroke on the 24 px grid, the same
  stroke ratio as Phosphor Regular's 16/256); Fill gets the 400 weight, a heavier outline (Tabler has no filled
  variant of the glyphs in use).

Also writes the code point constants to app/src/main/java/com/limelight/shell/ui/Symbols.kt and copies
the licences to app/src/main/assets/licenses/.

Usage (needs fontTools: pip install fonttools):
    python tools/fonts/subset_fonts.py [--work DIR] [--icons-only]

Source fonts are downloaded from the official Google (pinned to a google/fonts commit) and Phosphor
(versioned) repositories and the Tabler Icons npm package (versioned) into the work directory (a fresh temporary directory by default). Every download
is checked against a SHA-256 digest.
"""

import argparse
import hashlib
import re
import shutil
import tempfile
import urllib.request
from pathlib import Path

from fontTools import subset
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

REPO = Path(__file__).resolve().parents[2]
RES_FONT = REPO / "app/src/main/res/font"
LICENSES = REPO / "app/src/main/assets/licenses"
SYMBOLS_KT = REPO / "app/src/main/java/com/limelight/shell/ui/Symbols.kt"
ICONS_TXT = Path(__file__).with_name("icons.txt")
PROTOTYPE_CSS = REPO / "docs/prototype/phosphor-icons.css"

# google/fonts is pinned to a commit so a rebuild cannot pick up a newer upstream font by accident.
GOOGLE_FONTS_COMMIT = "e7db6ca603fd4862010383ff17ffd6cc4ffac1c0"
GOOGLE_FONTS = f"https://raw.githubusercontent.com/google/fonts/{GOOGLE_FONTS_COMMIT}/ofl"
PHOSPHOR_VERSION = "2.1.2"
PHOSPHOR = f"https://raw.githubusercontent.com/phosphor-icons/web/v{PHOSPHOR_VERSION}"
TABLER_VERSION = "3.51.0"
TABLER = f"https://unpkg.com/@tabler/icons-webfont@{TABLER_VERSION}"
SOURCES = {
    "Unbounded.ttf": (f"{GOOGLE_FONTS}/unbounded/Unbounded%5Bwght%5D.ttf", "323b511be380c8d474ef030686b71aedde501f8d9cd46da558b7c40454372c3f"),
    "Unbounded-OFL.txt": (f"{GOOGLE_FONTS}/unbounded/OFL.txt", "31e5d4e83955e7103c34570dd49b0570ef490800bd65b42923c0dd02445263b3"),
    "Figtree.ttf": (f"{GOOGLE_FONTS}/figtree/Figtree%5Bwght%5D.ttf", "26ad3db9b31ff7dde67a91ff515d022d2f495cd506590699cf264f0bfe6fb714"),
    "Figtree-OFL.txt": (f"{GOOGLE_FONTS}/figtree/OFL.txt", "140d37233e7f3ce7313798befa9600893bcceaf41a55fa0fa5ad52f7f657a268"),
}
ICON_SOURCES = {
    "Phosphor.ttf": (f"{PHOSPHOR}/src/regular/Phosphor.ttf", "06b91e022b7ee899a63efced879392a74f0bacbda54e4467e9f663220d173a10"),
    "Phosphor-Fill.ttf": (f"{PHOSPHOR}/src/fill/Phosphor-Fill.ttf", "a53f5d2630cab5e3b7536ecb9d69d71519a2190298c22b1f8d770dd37bc2940a"),
    "regular.css": (f"{PHOSPHOR}/src/regular/style.css", "873761b8711147dc516b6102936e9ad005f3a3015349efcde1a496f0326f1051"),
    "Phosphor-LICENSE.txt": (f"{PHOSPHOR}/LICENSE", "687fbe52d0eb5c2353eca27a4037e889145ee0b584d3b1abf67581c4a4a4e47c"),
    "Tabler-300.ttf": (f"{TABLER}/dist/fonts/tabler-icons-300.ttf", "c19e294c29f3fe6e495c3ec5908c219eceff9e558f0f0589d5f9ac3bbde2b18e"),
    "Tabler-400.ttf": (f"{TABLER}/dist/fonts/tabler-icons.ttf", "050228e487993da1a1f2a5320ed20dc52c3f26eb8eecace4285ade89c1e21ec6"),
    "Tabler-LICENSE.txt": (f"{TABLER}/LICENSE", "b740a1d46122672da62833e97f7e7c8a13fa85cbc7445b584b297cc00dde93db"),
}

# Tabler glyphs get code points from here up; Phosphor's end at U+EE74, so the two packs cannot collide.
TABLER_FIRST_CODEPOINT = 0xF000
# Tabler draws on a 24 unit grid in a 1000 unit em whose box runs from -100 to 900; Phosphor's em is 1024 units
# from -64 to 960. Scaling by 1024/1000 and shifting the box keeps size and centring.
TABLER_SCALE = 1024 / 1000
TABLER_SHIFT_Y = round(100 * TABLER_SCALE - 64)

# Latin, Latin-1, Latin Extended A/B, IPA schwa, spacing modifiers, combining marks, Cyrillic,
# Latin Extended Additional, general punctuation, currency, letterlike symbols, arrows, math minus
TEXT_RANGES = [
    (0x0020, 0x007E), (0x00A0, 0x024F), (0x0259, 0x0259), (0x02B0, 0x036F), (0x0400, 0x04FF),
    (0x1E00, 0x1EFF), (0x2000, 0x206F), (0x20A0, 0x20CF), (0x2100, 0x214F), (0x2190, 0x21FF),
    (0x2212, 0x2212), (0xFFFD, 0xFFFD),
]


def fetch(work: Path, icons_only: bool) -> None:
    sources = {} if icons_only else SOURCES.copy()
    sources.update(ICON_SOURCES)
    for name, (url, digest) in sources.items():
        target = work / name
        if not target.exists():
            print(f"download {name}")
            urllib.request.urlretrieve(url, target)
        if hashlib.sha256(target.read_bytes()).hexdigest() != digest:
            raise SystemExit(f"source checksum mismatch: {target}")


def subset_font(src: Path, dst: Path, unicodes: list[int], keep_layout: bool, weights: tuple | None = None) -> None:
    options = subset.Options()
    options.layout_features = ["*"] if keep_layout else []
    options.name_IDs = ["*"]
    options.notdef_outline = True
    font = TTFont(src)
    # Stable output across rebuilds; source timestamps are retained.
    font.recalcTimestamp = False
    missing = set(unicodes) - font.getBestCmap().keys()
    if missing and not keep_layout:
        raise SystemExit(f"missing icon glyphs in {src.name}: {sorted(missing)}")
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=unicodes)
    subsetter.subset(font)
    if not keep_layout:
        # Phosphor stores loose xMin bounds. Saving tightens them; preserve the original
        # xMin - lsb origin so rasterizers do not pull the artwork towards the left.
        glyf = font["glyf"]
        metrics = font["hmtx"].metrics
        for name in font.getGlyphOrder():
            glyph = glyf[name]
            if glyph.numberOfContours == 0:
                continue
            advance, bearing = metrics[name]
            origin = glyph.xMin - bearing
            glyph.recalcBounds(glyf)
            metrics[name] = (advance, glyph.xMin - origin)
    if weights:
        # Only the weight range the UI uses (after subsetting: the instancer does not cope with lazily loaded glyphs)
        font = instancer.instantiateVariableFont(font, {"wght": weights})
    font.save(dst)
    print(f"{dst.relative_to(REPO)}: {dst.stat().st_size / 1024:.0f} KiB")


def read_icons() -> dict[str, tuple[str, str]]:
    """Semantic name -> (pack, glyph name); the pack is "phosphor" unless the line says `tabler:<name>`."""
    names = {}
    for number, line in enumerate(ICONS_TXT.read_text(encoding="utf-8").splitlines(), 1):
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        match = re.fullmatch(r"([a-z][a-z0-9_]*)\s*=\s*(?:(tabler):)?([a-z0-9][a-z0-9-]*)", line)
        if not match or match[1] in names:
            raise SystemExit(f"invalid or duplicate mapping at icons.txt:{number}: {line}")
        names[match[1]] = (match[2] or "phosphor", match[3])
    return names


def import_tabler(dst: Path, src: Path, glyphs: dict[str, int]) -> None:
    """Adds the Tabler glyphs (name -> code point) of `src` to the subset `dst`, scaled to its em box."""
    font = TTFont(dst)
    source = TTFont(src)
    source_glyphs = source.getGlyphSet()
    glyf, hmtx = font["glyf"], font["hmtx"]
    order = font.getGlyphOrder()
    for name, cp in glyphs.items():
        pen = TTGlyphPen(None)
        source_glyphs[name].draw(TransformPen(pen, (TABLER_SCALE, 0, 0, TABLER_SCALE, 0, TABLER_SHIFT_Y)))
        glyph = pen.glyph()
        glyph.recalcBounds(glyf)
        glyph_name = "tabler_" + name.replace("-", "_")
        order.append(glyph_name)
        glyf[glyph_name] = glyph
        hmtx[glyph_name] = (font["head"].unitsPerEm, glyph.xMin)
        for table in font["cmap"].tables:
            table.cmap[cp] = glyph_name
    font.setGlyphOrder(order)
    font.save(dst)
    print(f"{dst.relative_to(REPO)}: {dst.stat().st_size / 1024:.0f} KiB with {len(glyphs)} Tabler glyphs")


def kotlin_literal(cp: int) -> str:
    """Kotlin string escape for a code point (a surrogate pair above the BMP)."""
    if cp <= 0xFFFF:
        return f"\\u{cp:04X}"
    cp -= 0x10000
    return f"\\u{0xD800 + (cp >> 10):04X}\\u{0xDC00 + (cp & 0x3FF):04X}"


def write_symbols(icons: dict[str, int]) -> None:
    lines = [
        "package com.limelight.shell.ui",
        "",
        "// Generated by tools/fonts/subset_fonts.py from tools/fonts/icons.txt - do not edit by hand.",
        "",
        f"/** Phosphor {PHOSPHOR_VERSION} and Tabler {TABLER_VERSION} code points shared by the bundled Regular and Fill subsets. */",
        "object Symbols {",
    ]
    for name, cp in icons.items():
        lines.append(f'    const val {name.upper()} = "{kotlin_literal(cp)}"')
    lines += ["}", ""]
    SYMBOLS_KT.parent.mkdir(parents=True, exist_ok=True)
    SYMBOLS_KT.write_text("\n".join(lines), encoding="utf-8", newline="\n")
    print(f"{SYMBOLS_KT.relative_to(REPO)}: {len(icons)} icons")


def write_prototype_css(icons: dict[str, int]) -> None:
    lines = [
        "/* Generated by tools/fonts/subset_fonts.py. Phosphor 2.1.2 and Tabler 3.51.0, MIT. */",
        "/* The prototype uses the exact same offline subsets as the Android app. */",
    ]
    for weight in ("regular", "fill"):
        lines += [
            "@font-face {",
            f'  font-family: "Phosphor {weight.title()}";',
            f'  src: url("../../app/src/main/res/font/phosphor_{weight}.ttf") format("truetype");',
            "  font-weight: normal; font-style: normal; font-display: block;",
            "}",
        ]
    lines.append(f':root {{ --phosphor-check: "\\{icons["check"]:x}"; }}')
    for name, cp in icons.items():
        lines.append(f'.ms[data-icon="{name}"] {{ --phosphor-glyph: "\\{cp:x}"; }}')
    PROTOTYPE_CSS.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def build_icons(work: Path) -> None:
    codepoints = {
        name: int(cp, 16)
        for name, cp in re.findall(r'\.ph\.ph-([\w-]+):before\s*\{\s*content:\s*"\\([0-9a-f]+)";',
                                   (work / "regular.css").read_text(encoding="utf-8"))
    }
    names = read_icons()
    missing = {glyph for pack, glyph in names.values() if pack == "phosphor"} - codepoints.keys()
    if missing:
        raise SystemExit(f"unknown Phosphor icon names in icons.txt: {', '.join(sorted(missing))}")
    tabler_regular, tabler_heavy = TTFont(work / "Tabler-300.ttf"), TTFont(work / "Tabler-400.ttf")
    tabler_names = sorted({glyph for pack, glyph in names.values() if pack == "tabler"})
    missing = [n for n in tabler_names if n not in tabler_regular.getGlyphOrder() or n not in tabler_heavy.getGlyphOrder()]
    if missing:
        raise SystemExit(f"unknown Tabler icon names in icons.txt: {', '.join(missing)}")
    tabler_codepoints = {n: TABLER_FIRST_CODEPOINT + i for i, n in enumerate(tabler_names)}
    if tabler_codepoints and TABLER_FIRST_CODEPOINT <= max(codepoints.values()):
        raise SystemExit("Tabler code points would collide with Phosphor's")
    icons = {
        name: codepoints[glyph] if pack == "phosphor" else tabler_codepoints[glyph]
        for name, (pack, glyph) in names.items()
    }
    phosphor_unicodes = sorted({cp for name, cp in icons.items() if names[name][0] == "phosphor"})
    for source, tabler, target in (("Phosphor.ttf", "Tabler-300.ttf", "phosphor_regular.ttf"),
                                   ("Phosphor-Fill.ttf", "Tabler-400.ttf", "phosphor_fill.ttf")):
        subset_font(work / source, RES_FONT / target, phosphor_unicodes, keep_layout=False)
        if tabler_codepoints:
            import_tabler(RES_FONT / target, work / tabler, tabler_codepoints)
    write_symbols(icons)
    write_prototype_css(icons)
    for name in ("Phosphor-LICENSE.txt", "Tabler-LICENSE.txt"):
        shutil.copyfile(work / name, LICENSES / name)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--work", type=Path, help="directory for the downloaded source fonts")
    parser.add_argument("--icons-only", action="store_true", help="rebuild icon subsets without touching text fonts")
    args = parser.parse_args()
    work = args.work or Path(tempfile.mkdtemp(prefix="delos-fonts-"))
    work.mkdir(parents=True, exist_ok=True)
    fetch(work, args.icons_only)

    RES_FONT.mkdir(parents=True, exist_ok=True)
    LICENSES.mkdir(parents=True, exist_ok=True)
    build_icons(work)
    if args.icons_only:
        return
    text = [cp for lo, hi in TEXT_RANGES for cp in range(lo, hi + 1)]
    subset_font(work / "Unbounded.ttf", RES_FONT / "unbounded.ttf", text, keep_layout=True, weights=(400, 700))
    subset_font(work / "Figtree.ttf", RES_FONT / "figtree.ttf", text, keep_layout=True, weights=(400, 800))
    for name in ("Unbounded-OFL.txt", "Figtree-OFL.txt"):
        shutil.copyfile(work / name, LICENSES / name)


if __name__ == "__main__":
    main()
