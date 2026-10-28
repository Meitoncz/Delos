"""Builds the fonts bundled with the Delos UI (Phase 6, docs/UI_PLAN.md).

- Unbounded (display) and Figtree (body), SIL Open Font License 1.1, variable weight axis limited to 400-700
  (Figtree 400-800, for the hint bar's button letters).
  Unbounded is subset to Latin (incl. Czech and other extended Latin) and Cyrillic; Android falls
  back to system fonts for other scripts.
- Phosphor Regular and Fill 2.1.2, MIT, subset to the semantic mappings in tools/fonts/icons.txt.
  Android and the HTML prototype share the same two fonts and code points.

Also writes the code point constants to app/src/main/java/com/limelight/shell/ui/Symbols.kt and copies
the licences to app/src/main/assets/licenses/.

Usage (needs fontTools: pip install fonttools):
    python tools/fonts/subset_fonts.py [--work DIR] [--icons-only]

Source fonts are downloaded from the official Google and Phosphor repositories into the work
directory (a fresh temporary directory by default). Phosphor downloads are versioned and hash-checked.
"""

import argparse
import hashlib
import re
import shutil
import tempfile
import urllib.request
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

REPO = Path(__file__).resolve().parents[2]
RES_FONT = REPO / "app/src/main/res/font"
LICENSES = REPO / "app/src/main/assets/licenses"
SYMBOLS_KT = REPO / "app/src/main/java/com/limelight/shell/ui/Symbols.kt"
ICONS_TXT = Path(__file__).with_name("icons.txt")
PROTOTYPE_CSS = REPO / "docs/prototype/phosphor-icons.css"

GOOGLE_FONTS = "https://raw.githubusercontent.com/google/fonts/main/ofl"
PHOSPHOR_VERSION = "2.1.2"
PHOSPHOR = f"https://raw.githubusercontent.com/phosphor-icons/web/v{PHOSPHOR_VERSION}"
SOURCES = {
    "Unbounded.ttf": f"{GOOGLE_FONTS}/unbounded/Unbounded%5Bwght%5D.ttf",
    "Unbounded-OFL.txt": f"{GOOGLE_FONTS}/unbounded/OFL.txt",
    "Figtree.ttf": f"{GOOGLE_FONTS}/figtree/Figtree%5Bwght%5D.ttf",
    "Figtree-OFL.txt": f"{GOOGLE_FONTS}/figtree/OFL.txt",
}
ICON_SOURCES = {
    "Phosphor.ttf": (f"{PHOSPHOR}/src/regular/Phosphor.ttf", "06b91e022b7ee899a63efced879392a74f0bacbda54e4467e9f663220d173a10"),
    "Phosphor-Fill.ttf": (f"{PHOSPHOR}/src/fill/Phosphor-Fill.ttf", "a53f5d2630cab5e3b7536ecb9d69d71519a2190298c22b1f8d770dd37bc2940a"),
    "regular.css": (f"{PHOSPHOR}/src/regular/style.css", "873761b8711147dc516b6102936e9ad005f3a3015349efcde1a496f0326f1051"),
    "Phosphor-LICENSE.txt": (f"{PHOSPHOR}/LICENSE", "687fbe52d0eb5c2353eca27a4037e889145ee0b584d3b1abf67581c4a4a4e47c"),
}

# Latin, Latin-1, Latin Extended A/B, IPA schwa, spacing modifiers, combining marks, Cyrillic,
# Latin Extended Additional, general punctuation, currency, letterlike symbols, arrows, math minus
TEXT_RANGES = [
    (0x0020, 0x007E), (0x00A0, 0x024F), (0x0259, 0x0259), (0x02B0, 0x036F), (0x0400, 0x04FF),
    (0x1E00, 0x1EFF), (0x2000, 0x206F), (0x20A0, 0x20CF), (0x2100, 0x214F), (0x2190, 0x21FF),
    (0x2212, 0x2212), (0xFFFD, 0xFFFD),
]


def fetch(work: Path, icons_only: bool) -> None:
    sources = {} if icons_only else SOURCES.copy()
    sources.update({name: url for name, (url, _) in ICON_SOURCES.items()})
    for name, url in sources.items():
        target = work / name
        if not target.exists():
            print(f"download {name}")
            urllib.request.urlretrieve(url, target)
        if name in ICON_SOURCES and hashlib.sha256(target.read_bytes()).hexdigest() != ICON_SOURCES[name][1]:
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
    if weights:
        # Only the weight range the UI uses (after subsetting: the instancer does not cope with lazily loaded glyphs)
        font = instancer.instantiateVariableFont(font, {"wght": weights})
    font.save(dst)
    print(f"{dst.relative_to(REPO)}: {dst.stat().st_size / 1024:.0f} KiB")


def read_icons() -> dict[str, str]:
    names = {}
    for number, line in enumerate(ICONS_TXT.read_text(encoding="utf-8").splitlines(), 1):
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        match = re.fullmatch(r"([a-z][a-z0-9_]*)\s*=\s*([a-z][a-z0-9-]*)", line)
        if not match or match[1] in names:
            raise SystemExit(f"invalid or duplicate mapping at icons.txt:{number}: {line}")
        names[match[1]] = match[2]
    return names


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
        f"/** Phosphor {PHOSPHOR_VERSION} code points shared by the bundled Regular and Fill subsets. */",
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
        "/* Generated by tools/fonts/subset_fonts.py. Phosphor 2.1.2, MIT. */",
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
    missing = set(names.values()) - codepoints.keys()
    if missing:
        raise SystemExit(f"unknown Phosphor icon names in icons.txt: {', '.join(sorted(missing))}")
    icons = {name: codepoints[phosphor] for name, phosphor in names.items()}
    for source, target in (("Phosphor.ttf", "phosphor_regular.ttf"), ("Phosphor-Fill.ttf", "phosphor_fill.ttf")):
        subset_font(work / source, RES_FONT / target, sorted(set(icons.values())), keep_layout=False)
    write_symbols(icons)
    write_prototype_css(icons)
    shutil.copyfile(work / "Phosphor-LICENSE.txt", LICENSES / "Phosphor-LICENSE.txt")


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
