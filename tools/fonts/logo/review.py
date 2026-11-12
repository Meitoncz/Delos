"""Generate the offline, unintegrated Logo glyph comparison (standard library only)."""
import argparse
import base64
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SOURCE = Path(__file__).resolve().parent
CANDIDATES = (("a-wordmark-plate", "A — Wordmark plate"), ("b-scene-title", "B — Title over a scene"), ("c-emblem-title", "C — Emblem + title"))
ICONS = {"Cover": 0xE2CA, "Wide": 0xE3F0, "Hero": 0xEAA2, "Old TM": 0xE9F0}


def svg(name):
    return (SOURCE / name).read_text(encoding="utf-8")


def icon(label, style):
    return f'<span class="font {style}">&#{ICONS[label]};</span>'


def render(font_dir):
    fonts = []
    for style, filename in (("regular", "Phosphor.ttf"), ("fill", "Phosphor-Fill.ttf")):
        data = base64.b64encode((font_dir / filename).read_bytes()).decode("ascii")
        fonts.append(f'@font-face{{font-family:{style};src:url(data:font/ttf;base64,{data})}}')
    cards = []
    for name, title in CANDIDATES:
        sections = []
        for style in ("regular", "fill"):
            rows = []
            for size in (24, 32, 48, 96):
                cells = ''.join(f'<div class="sample">{icon(label, style)}<small>{label}</small></div>' for label in ICONS)
                rows.append(f'<div class="matrix" style="--size:{size}px"><small>{size}px</small>{cells}<div class="sample">{svg(name+"-"+style+".svg")}<small>New Logo</small></div></div>')
            pills = ''.join(f'<span class="pill">{icon(label, style)}{label}</span>' for label in ("Cover", "Wide", "Hero"))
            for size in (24, 32):
                rows.append(f'<div class="pills" style="--size:{size}px">{pills}<span class="pill selected">{svg(name+"-"+style+".svg")}Logo</span><small>{size}px</small></div>')
            sections.append(f'<section><h3>{style.title()}</h3>{"".join(rows)}</section>')
        light = ''.join(f'<div class="pills" style="--size:24px">{icon("Old TM",style)}{svg(name+"-"+style+".svg")}<span>{style.title()} · 24px</span></div>' for style in ("regular", "fill"))
        cards.append(f'<article id="{name}"><h2>{title}</h2><div class="styles">{"".join(sections)}</div><div class="light">{light}</div></article>')
    return '''<!doctype html><html lang="en"><meta charset="utf-8"><title>Delos · Logo glyph candidates</title><style>''' + ''.join(fonts) + '''
*{box-sizing:border-box}body{margin:0;padding:28px;background:#091323;color:#e8edf5;font:16px system-ui}h1{margin:0 0 8px}p{color:#b6c5d9}article{background:#152339;border:1px solid #344761;border-radius:22px;padding:24px;margin:24px 0}h2{margin:0 0 12px;color:#f6bf4b}h3{margin:8px 0}.styles{display:grid;grid-template-columns:1fr 1fr;gap:28px}.matrix{display:grid;grid-template-columns:42px repeat(5,1fr);align-items:center;min-height:calc(var(--size) + 34px);border-bottom:1px solid #334257;gap:8px}.sample{display:flex;align-items:center;flex-direction:column;gap:4px}small{font-size:12px;color:#aebdd0}.font{font-size:var(--size);width:var(--size);height:var(--size);line-height:1;display:inline-block}.regular{font-family:regular}.fill{font-family:fill}svg{display:inline-block;width:var(--size);height:var(--size);flex-shrink:0}.pills{display:flex;align-items:center;gap:10px;margin-top:16px}.pill{display:flex;align-items:center;gap:7px;border-radius:999px;padding:10px 13px;background:#26374d}.selected{background:#f6bf4b;color:#182033;font-weight:650}.light{display:flex;gap:48px;background:#f0f3f7;color:#182033;border-radius:14px;padding:0 20px 16px;margin-top:20px}.light .pills{gap:16px}
</style><h1>Logo image · three original candidates</h1><p>Actual full Phosphor fonts · Regular / Fill · native 24 / 32 / 48 / 96 px · no font or app integration.<br>A title artwork asset, not trademark ownership. Candidate selection remains with the user.</p>''' + ''.join(cards) + '</html>'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--font-dir', type=Path, default=ROOT / 'build/phosphor-icons')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/logo-icon-proto/index.html')
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render(args.font_dir), encoding='utf-8')
    print(args.output)


if __name__ == '__main__':
    main()
