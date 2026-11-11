#!/usr/bin/env python3
"""Contact sheets and pixel comparison for tools/layout/sheets.sh (issue #9). Python 3.9 compatible, Pillow only.

  sheets.py sheets  <run dir>                              builds <run dir>/sheets/by-screen/<screen>.png and by-shape/<shape>.png
  sheets.py compare <run A> <run B> <out dir> <tolerance> <mask>
        compares every <shape>/<screen>.png (or native/<screen>.png) present in both runs pixel by pixel and writes an
        amplified difference image of every differing screen to <out dir>; exit code 1 when anything differs or is missing.
        <tolerance>: largest per-channel difference that still counts as equal. <mask>: x0,y0,x1,y1 as fractions of the
        image to ignore (the status bar's Wi-Fi, battery and clock change between runs), or "none".
"""
import os
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFont

CELL_W = 480
LABEL_H = 26
GAP = 12
BG = (18, 20, 26)
FG = (225, 228, 235)
COLUMNS = 4


def font(size):
    for path in ("/System/Library/Fonts/Helvetica.ttc", "/System/Library/Fonts/Supplemental/Arial.ttf",
                 "/Library/Fonts/Arial.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(path, size)
        except (IOError, OSError):
            continue
    return ImageFont.load_default()


def shots(run):
    """{shape: {screen: path}} for every png under run/<shape>/ (no sheets, no _before)."""
    result = {}
    for shape in sorted(os.listdir(run)):
        folder = os.path.join(run, shape)
        if not os.path.isdir(folder) or shape in ("sheets",):
            continue
        files = {}
        for name in sorted(os.listdir(folder)):
            if name.endswith(".png"):
                files[name[:-4]] = os.path.join(folder, name)
        if files:
            result[shape] = files
    return result


def sheet(items, out_path, title):
    """items: [(label, png path)]; a grid of thumbnails with their labels under a title."""
    if not items:
        return
    thumbs = []
    for label, path in items:
        with Image.open(path) as im:
            im = im.convert("RGB")
            h = max(1, round(im.height * CELL_W / float(im.width)))
            thumbs.append((label, im.resize((CELL_W, h), Image.LANCZOS)))
    cell_h = max(t.height for _, t in thumbs)
    cols = min(COLUMNS, len(thumbs))
    rows = (len(thumbs) + cols - 1) // cols
    title_h = 44
    width = GAP + cols * (CELL_W + GAP)
    height = title_h + rows * (cell_h + LABEL_H + GAP) + GAP
    canvas = Image.new("RGB", (width, height), BG)
    draw = ImageDraw.Draw(canvas)
    draw.text((GAP, 10), title, fill=FG, font=font(22))
    small = font(16)
    for i, (label, thumb) in enumerate(thumbs):
        x = GAP + (i % cols) * (CELL_W + GAP)
        y = title_h + (i // cols) * (cell_h + LABEL_H + GAP)
        canvas.paste(thumb, (x, y))
        draw.rectangle((x - 1, y - 1, x + CELL_W, y + thumb.height), outline=(70, 76, 90))
        draw.text((x, y + thumb.height + 4), label, fill=FG, font=small)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    canvas.save(out_path)
    print("  " + out_path)


def build_sheets(run):
    data = shots(run)
    if not data:
        print("no screenshots under " + run)
        return 1
    label = os.path.basename(os.path.normpath(run))
    out = os.path.join(run, "sheets")
    screens = sorted({screen for files in data.values() for screen in files})
    for screen in screens:
        items = [(shape, files[screen]) for shape, files in data.items() if screen in files]
        sheet(items, os.path.join(out, "by-screen", screen + ".png"), "%s: %s in %d shape(s)" % (label, screen, len(items)))
    for shape, files in data.items():
        items = [(screen, path) for screen, path in sorted(files.items())]
        sheet(items, os.path.join(out, "by-shape", shape + ".png"), "%s: %s, %d screen(s)" % (label, shape, len(items)))
    return 0


def parse_mask(text, size):
    if text in ("", "none"):
        return None
    x0, y0, x1, y1 = [float(v) for v in text.split(",")]
    w, h = size
    return (int(x0 * w), int(y0 * h), int(x1 * w), int(y1 * h))


def compare(a_run, b_run, out_dir, tolerance, mask_text):
    a, b = shots(a_run), shots(b_run)
    differing, same, missing = [], 0, []
    for shape in sorted(set(a) | set(b)):
        for screen in sorted(set(a.get(shape, {})) | set(b.get(shape, {}))):
            pa, pb = a.get(shape, {}).get(screen), b.get(shape, {}).get(screen)
            name = "%s/%s" % (shape, screen)
            if pa is None or pb is None:
                missing.append("%s (only in %s)" % (name, "B" if pa is None else "A"))
                continue
            with Image.open(pa) as ia, Image.open(pb) as ib:
                ia, ib = ia.convert("RGB"), ib.convert("RGB")
                if ia.size != ib.size:
                    differing.append((name, "size %dx%d vs %dx%d" % (ia.width, ia.height, ib.width, ib.height)))
                    continue
                diff = ImageChops.difference(ia, ib)
                mask = parse_mask(mask_text, ia.size)
                if mask is not None:
                    ImageDraw.Draw(diff).rectangle(mask, fill=(0, 0, 0))
                # a pixel differs when any channel differs by more than the tolerance
                r, g, bl = diff.split()
                biggest = ImageChops.lighter(ImageChops.lighter(r, g), bl)
                flagged = biggest.point(lambda v: 255 if v > tolerance else 0)
                box = flagged.getbbox()
                if box is None:
                    same += 1
                    continue
                count = flagged.histogram()[255]
                percent = 100.0 * count / (ia.width * ia.height)
                differing.append((name, "%d px (%.3f %%) in %s" % (count, percent, box)))
                os.makedirs(os.path.join(out_dir, shape), exist_ok=True)
                amplified = biggest.point(lambda v: min(255, v * 8 if v > tolerance else 0)).convert("RGB")
                # the difference on top of a dimmed copy of the first image, so it can be located
                base = ImageChops.multiply(ia, Image.new("RGB", ia.size, (60, 60, 60)))
                out = ImageChops.add(base, amplified)
                out.save(os.path.join(out_dir, shape, screen + ".png"))
    print("compare %s vs %s (tolerance %s, mask %s)" % (os.path.basename(a_run), os.path.basename(b_run), tolerance, mask_text))
    print("identical: %d" % same)
    for name, info in differing:
        print("DIFFERS  %s: %s" % (name, info))
    for entry in missing:
        print("MISSING  %s" % entry)
    if differing:
        print("difference images: " + out_dir)
    return 1 if differing or missing else 0


def main(argv):
    if len(argv) >= 3 and argv[1] == "sheets":
        return build_sheets(argv[2])
    if len(argv) == 7 and argv[1] == "compare":
        return compare(argv[2], argv[3], argv[4], int(argv[5]), argv[6])
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
