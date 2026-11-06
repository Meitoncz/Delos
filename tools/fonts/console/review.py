"""Render the unapproved console candidates beside the actual bundled Phosphor fonts.

Standard library only; the output HTML embeds every SVG and font and makes no network requests.
This does not modify Symbols, icon mappings, Android resources or the font generator.
"""

import argparse
import base64
import html
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SOURCES = Path(__file__).resolve().parent
SIZES = (24, 32, 48, 96)
CANDIDATES = (
    ("a-open-seam", "A · Open seam", "Straight tapered front panel, separate rear wing and a low disc slot."),
    ("b-swept-wing", "B · Swept wing", "A curved front wing, wider lower body and the same open rear seam."),
)
PHOSPHOR = {"Desktop": "\ue560", "Gamepad": "\ue26e", "Devices": "\ueba4"}


def embedded_svg(name: str, style: str) -> str:
    source = (SOURCES / f"{name}-{style}.svg").read_text(encoding="utf-8")
    return source[source.index("<svg"):].replace("<svg ", '<svg aria-hidden="true" ', 1)


def font_rule(style: str) -> str:
    font = ROOT / "app/src/main/res/font" / f"phosphor_{style}.ttf"
    encoded = base64.b64encode(font.read_bytes()).decode("ascii")
    return f"@font-face{{font-family:Phosphor-{style};src:url(data:font/ttf;base64,{encoded}) format('truetype');font-display:block}}"


def real_icon(label: str, style: str, size: int) -> str:
    return f'<span class="glyph font-{style}" style="--size:{size}px" aria-label="Phosphor {label}">{PHOSPHOR[label]}</span>'


def custom_icon(name: str, style: str, size: int) -> str:
    return f'<span class="glyph console" style="--size:{size}px" aria-label="Console candidate">{embedded_svg(name, style)}</span>'


def size_matrix(name: str, style: str) -> str:
    rows = []
    for size in SIZES:
        icons = custom_icon(name, style, size) + "".join(real_icon(label, style, size) for label in PHOSPHOR)
        rows.append(f'<div class="size-row" data-size="{size}"><span class="size">{size} px</span>{icons}</div>')
    heading = '<div class="column-labels"><span></span><span>Console</span><span>Desktop</span><span>Gamepad</span><span>Devices</span></div>'
    return f'<section class="style {style}"><h3>{style.title()}</h3>{heading}{"".join(rows)}</section>'


def pill_context(name: str) -> str:
    cells = [
        (real_icon("Desktop", "regular", 32), "PC", ""),
        (custom_icon(name, "fill", 32), "Console", " selected"),
        (real_icon("Gamepad", "regular", 32), "Controllers", ""),
        (real_icon("Devices", "regular", 32), "Devices", ""),
    ]
    return '<div class="pill">' + "".join(f'<div class="pill-item{state}">{icon}<span>{label}</span></div>' for icon, label, state in cells) + '</div>'


def candidate_card(name: str, title: str, detail: str) -> str:
    light = "".join(custom_icon(name, style, 24) + real_icon("Desktop", style, 24) + real_icon("Gamepad", style, 24) for style in ("regular", "fill"))
    return f'''<article id="{name}">
<header class="candidate-header"><h2>{html.escape(title)}</h2><p>{html.escape(detail)}</p></header>
<div class="matrices">{size_matrix(name, "regular")}{size_matrix(name, "fill")}</div>
<div class="contexts"><div><h4>32 px · device pill</h4>{pill_context(name)}</div><div><h4>24 px · light surface</h4><div class="light">{light}</div></div></div>
</article>'''


def render() -> str:
    font_css = "\n".join(font_rule(style) for style in ("regular", "fill"))
    cards = "\n".join(candidate_card(*candidate) for candidate in CANDIDATES)
    return f'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Delos · Console glyph review · Round 4</title>
<style>
{font_css}
*{{box-sizing:border-box}}body{{margin:0;background:#0d1220;color:#eef2fa;font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}}
main{{width:1120px;max-width:100%;margin:auto;padding:32px}}.page-header{{padding:0 4px 18px}}h1{{margin:0 0 8px;font-size:28px;letter-spacing:-.7px}}.page-header p{{margin:4px 0;color:#adb9ce}}.status{{display:inline-block;margin-bottom:10px;font-size:11px;letter-spacing:1.5px;color:#f2c459;text-transform:uppercase}}
article{{margin:14px 0;padding:22px 24px;background:#151d2f;border:1px solid #2b3650;border-radius:22px}}h2{{font-size:20px;letter-spacing:-.3px;margin:0}}.candidate-header p{{margin:3px 0 14px;color:#a7b5cf}}h3{{margin:0 0 4px;font-size:13px;letter-spacing:1px;text-transform:uppercase}}.matrices{{display:grid;grid-template-columns:1fr 1fr;gap:22px}}.style.fill{{color:#f3c45d}}
.column-labels,.size-row{{display:grid;grid-template-columns:42px repeat(4,minmax(0,1fr));align-items:center;justify-items:center;gap:4px}}.column-labels{{font-size:10px;color:#94a3bf;margin-bottom:4px}}.size-row{{padding:6px 0;border-bottom:1px solid #253047;min-height:38px}}.size-row:last-child{{border-bottom:0}}.size{{justify-self:start;color:#a6b4ca;font-size:11px;font-variant-numeric:tabular-nums}}
.glyph{{display:inline-flex;align-items:center;justify-content:center;flex:none;width:var(--size);height:var(--size);font-size:var(--size);line-height:1;vertical-align:middle}}.font-regular{{font-family:Phosphor-regular}}.font-fill{{font-family:Phosphor-fill}}.console svg{{display:block;width:100%;height:100%;fill:currentColor}}
.contexts{{display:grid;grid-template-columns:1.4fr 1fr;gap:22px;margin-top:12px;padding-top:12px;border-top:1px solid #2a354b}}h4{{font-size:10px;color:#94a3bf;font-weight:500;text-transform:uppercase;letter-spacing:1px;margin:0 0 7px}}.pill{{display:flex;align-items:center;justify-content:space-around;border:1px solid #344059;background:#1c273a;border-radius:28px;padding:6px;gap:4px;height:58px}}.pill-item{{display:flex;align-items:center;justify-content:center;gap:7px;padding:7px 10px;border-radius:22px;color:#b8c5dc;font-size:11px}}.pill-item.selected{{background:#f3c45d;color:#151d2f;font-weight:700}}
.light{{height:58px;display:flex;align-items:center;justify-content:space-around;gap:12px;background:#eef2f8;color:#192235;border-radius:15px;padding:8px 12px}}footer{{color:#94a3bf;font-size:11px;padding:5px 4px 0}}footer p{{margin:4px 0}}
</style></head><body><main>
<header class="page-header"><span class="status">Review only · not added to the app</span><h1>Console glyph · round 4</h1><p>Original split-panel silhouettes. A narrow rear wing stays separate from the tapered front shell; the low slot remains a cut-out in Fill.</p><p>256-unit grid · Regular drawn at 16 units with round joins/caps, exported as closed paths · real bundled Phosphor Regular / Fill beside every size</p></header>
{cards}
<footer><p>Each SVG and the exact bundled Phosphor fonts are embedded. This sheet works offline; no external assets, scripts or fonts.</p><p>No logo or wordmark is used. These are candidates, not an approved icon or a Remote Play implementation.</p></footer>
</main></body></html>'''


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build/ps5-icon-proto/round4/index.html")
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render(), encoding="utf-8")
    print(args.output)


if __name__ == "__main__":
    main()
