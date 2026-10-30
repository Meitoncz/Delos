#!/usr/bin/env python3
"""Generate the Delos Android mark and mask previews with Pillow (no network).

Run: python tools/icon/generate_launcher.py
Geometry is LogoMark's 40-unit ring, 3-unit stroke, moon (38.5, 7.5),
5.5-unit moon and 9-unit 35%-opacity halo, uniformly scaled by 1.15.
The enclosing ring-plus-halo bounds are centered on the 108dp canvas.
All foreground pixels fit inside Android's centered 66dp safe circle.
"""
from pathlib import Path
import colorsys
import math

from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
PREVIEW = ROOT / "build/icon-preview"
SCALE = 1.15
# Center the complete visible bounds, not just the ring, in the canvas.
CX, CY = 54 - 3.75 * SCALE, 54 + .75 * SCALE
RADIUS, STROKE = 18.5 * SCALE, 3 * SCALE
MOON_X, MOON_Y = CX + 18.5 * SCALE, CY - 12.5 * SCALE
MOON_RADIUS, HALO_RADIUS = 5.5 * SCALE, 9 * SCALE
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def hsl(hue, saturation, lightness):
    return tuple(round(v * 255) for v in colorsys.hls_to_rgb(hue / 360, lightness / 100, saturation / 100))


# Colors.kt dark Delos palette: base 222, accent 40, accent2 192.
BACKGROUND = hsl(222, 38, 7)
ACCENT = hsl(40, 95, 62)
MOON = hsl(192, 85, 62)


def hex_color(rgb):
    return "#" + "".join(f"{value:02X}" for value in rgb)


def text_file(path, content):
    """Preserve the newline convention of existing generated XML files."""
    eol = b"\r\n" if path.exists() and b"\r\n" in path.read_bytes() else b"\n"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content.replace("\r\n", "\n").encode("utf-8").replace(b"\n", eol))


def circle_path(x, y, radius):
    return (f"M{x-radius:g},{y:g}a{radius:g},{radius:g} 0,1 0 {2*radius:g},0"
            f"a{radius:g},{radius:g} 0,1 0 {-2*radius:g},0")


def vector(monochrome=False):
    color = "#FFFFFF" if monochrome else hex_color(ACCENT)
    paths = [f'    <path android:fillColor="#00000000" android:strokeColor="{color}" android:strokeWidth="{STROKE:g}" android:pathData="{circle_path(CX, CY, RADIUS)}"/>']
    if not monochrome:
        paths.append(f'    <path android:fillColor="{hex_color(MOON)}" android:fillAlpha="0.35" android:pathData="{circle_path(MOON_X, MOON_Y, HALO_RADIUS)}"/>')
    paths.append(f'    <path android:fillColor="{"#FFFFFF" if monochrome else hex_color(MOON)}" android:pathData="{circle_path(MOON_X, MOON_Y, MOON_RADIUS)}"/>')
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
            + "\n".join(paths) + '\n</vector>\n')


def raster(size, monochrome=False):
    # Supersampled circles use the exact same center/radius/stroke as the vectors.
    side = size * 4
    k = side / 108
    image = Image.new("RGBA", (side, side), BACKGROUND + (255,))
    def disk(x, y, radius, fill):
        layer = Image.new("RGBA", image.size)
        draw = ImageDraw.Draw(layer)
        draw.ellipse(((x-radius)*k, (y-radius)*k, (x+radius)*k, (y+radius)*k), fill=fill)
        image.alpha_composite(layer)
    # Make a ring on a transparent layer so its interior stays truly empty.
    ring = Image.new("RGBA", image.size)
    draw = ImageDraw.Draw(ring)
    outer, inner = RADIUS + STROKE/2, RADIUS - STROKE/2
    draw.ellipse(((CX-outer)*k, (CY-outer)*k, (CX+outer)*k, (CY+outer)*k), fill=(255,255,255,255) if monochrome else ACCENT+(255,))
    draw.ellipse(((CX-inner)*k, (CY-inner)*k, (CX+inner)*k, (CY+inner)*k), fill=(0,0,0,0))
    image.alpha_composite(ring)
    if not monochrome:
        disk(MOON_X, MOON_Y, HALO_RADIUS, MOON+(round(.35*255),))
    disk(MOON_X, MOON_Y, MOON_RADIUS, (255,255,255,255) if monochrome else MOON+(255,))
    return image.resize((size, size), Image.Resampling.LANCZOS)


def mask_preview(image, shape):
    side = image.width
    mask = Image.new("L", image.size)
    draw = ImageDraw.Draw(mask)
    if shape == "circle":
        draw.ellipse((0, 0, side-1, side-1), fill=255)
    elif shape == "rounded-square":
        draw.rounded_rectangle((0, 0, side-1, side-1), radius=side*.22, fill=255)
    else:
        # Mathematical fourth-power superellipse (not a rounded-square alias).
        points = []
        for i in range(1440):
            angle = i * math.tau / 1440
            x, y = math.cos(angle), math.sin(angle)
            points.append(((1 + math.copysign(abs(x)**.5, x))*(side-1)/2,
                           (1 + math.copysign(abs(y)**.5, y))*(side-1)/2))
        draw.polygon(points, fill=255)
    result = image.copy()
    result.putalpha(ImageChops.multiply(result.getchannel("A"), mask))
    return result


def main():
    PREVIEW.mkdir(parents=True, exist_ok=True)
    text_file(RES / "drawable/ic_launcher_foreground.xml", vector())
    text_file(RES / "drawable/ic_launcher_monochrome.xml", vector(True))
    text_file(RES / "values/ic_launcher_background.xml", '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">'+hex_color(BACKGROUND)+'</color>\n</resources>\n')
    text_file(RES / "mipmap-anydpi-v26/ic_launcher.xml", '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@color/ic_launcher_background"/>\n    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n</adaptive-icon>\n')
    text_file(RES / "drawable/ic_channel.xml", '<?xml version="1.0" encoding="utf-8"?>\n<layer-list xmlns:android="http://schemas.android.com/apk/res/android">\n    <item android:drawable="@color/ic_launcher_background"/>\n    <item android:drawable="@drawable/ic_launcher_foreground"/>\n</layer-list>\n')
    for density, size in DENSITIES.items():
        raster(size).save(RES / f"mipmap-{density}/ic_launcher.png", optimize=False, compress_level=9)
    raster(512).save(ROOT / "app/src/main/ic_launcher-web.png", optimize=False, compress_level=9)
    for monochrome in (False, True):
        image = raster(1024, monochrome)
        for shape in ("circle", "squircle", "rounded-square"):
            mask_preview(image, shape).resize((512,512), Image.Resampling.LANCZOS).save(PREVIEW / f'{"monochrome" if monochrome else "color"}-{shape}.png', optimize=False, compress_level=9)
    print(f"Generated launcher resources and six previews in {PREVIEW}")


if __name__ == "__main__":
    main()
