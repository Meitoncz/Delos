"""Fallback generator of the 12 illustrated profile avatars of Delos (flat-vector game mascots).

The real avatars are generated images imported by tools/avatars/import.py; this script only draws the
procedural stand-ins for slugs that have no generated image yet (it overwrites every webp it renders,
so pass --only for slugs that were not imported).

Usage: python tools/avatars/generate.py [--preview] [--only slug ...] [--fast]
Dependencies: Pillow 11.3.0 with WebP support (pip install Pillow==11.3.0). Nothing is downloaded.
Run with the same Pillow/libwebp for byte-identical files. The drawing is fully procedural and
deterministic (no randomness, no timestamps). Each avatar is the character only, on a transparent
384x384 canvas: the app puts it over the profile's hue gradient (see AvatarBadge), so the color of a
profile stays recognizable. Everything lives in the central ~72% because the badge is a circle.
Output: app/src/main/assets/avatars/<slug>.webp (the folder stays well under 1.2 MB).
--preview also writes build/avatars-preview/ (ignored by git): a contact sheet on the real
profile gradients, a small-size legibility strip and a stress sheet on foreign hues.
The drawing works at SS times the output size and is downscaled with Lanczos.
"""

import argparse
import colorsys
import math
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

REPO = Path(__file__).resolve().parents[2]
OUTPUT = REPO / "app/src/main/assets/avatars"
PREVIEW = REPO / "build/avatars-preview"
SIZE = 384
BUDGET = 1_200_000

# Drawing coordinates are 0..100 "units" on the square canvas; SS is the supersampling factor.
SS = 4
K = SIZE * SS / 100.0


def hsl(h: float, s: float, l: float) -> tuple[int, int, int]:
    return tuple(round(c * 255) for c in colorsys.hls_to_rgb((h % 360) / 360, l, s))


OUT = (24, 20, 48)        # deep shadow: the outline of everything
INK = (30, 24, 58)        # eyes and mouths
WHITE = (255, 255, 255)
CREAM = (255, 245, 226)
PINK = hsl(340, .85, .72)


@dataclass(frozen=True)
class G:
    """A linear gradient; angle 90 runs from c1 at the top to c2 at the bottom."""
    c1: tuple
    c2: tuple
    angle: float = 90.0


@dataclass(frozen=True)
class R:
    """A radial gradient: c1 at (cx, cy), c2 at distance r (units)."""
    c1: tuple
    c2: tuple
    cx: float
    cy: float
    r: float


# ---------------------------------------------------------------- geometry (units)

def ell(cx, cy, rx, ry=None, rot=0.0, n=120, a0=0.0, a1=360.0):
    ry = rx if ry is None else ry
    c, s = math.cos(math.radians(rot)), math.sin(math.radians(rot))
    full = a1 - a0 >= 360
    out = []
    for i in range(n if full else n + 1):
        a = math.radians(a0 + (a1 - a0) * i / n)
        x, y = rx * math.cos(a), ry * math.sin(a)
        out.append((cx + x * c - y * s, cy + x * s + y * c))
    return out


def bez(p0, p1, p2, p3, n=28):
    out = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        out.append(tuple(u ** 3 * p0[k] + 3 * u * u * t * p1[k] + 3 * u * t * t * p2[k] + t ** 3 * p3[k] for k in (0, 1)))
    return out


def cr(pts, closed=True, n=14):
    """Catmull-Rom spline through pts."""
    m = len(pts)
    out = []
    count = m if closed else m - 1
    for i in range(count):
        if closed:
            p0, p1, p2, p3 = pts[(i - 1) % m], pts[i], pts[(i + 1) % m], pts[(i + 2) % m]
        else:
            p0, p1, p2, p3 = pts[max(i - 1, 0)], pts[i], pts[i + 1], pts[min(i + 2, m - 1)]
        for j in range(n):
            t = j / n
            out.append(tuple(
                .5 * (2 * p1[k] + (-p0[k] + p2[k]) * t + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * t * t
                      + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * t ** 3)
                for k in (0, 1)))
    if not closed:
        out.append(pts[-1])
    return out


def rounded(pts, r, n=10):
    """Polygon with rounded corners; r is one radius or one per vertex."""
    m = len(pts)
    radii = [r] * m if isinstance(r, (int, float)) else list(r)
    out = []
    for i, v in enumerate(pts):
        a, b = pts[i - 1], pts[(i + 1) % m]
        d1 = (a[0] - v[0], a[1] - v[1])
        d2 = (b[0] - v[0], b[1] - v[1])
        l1, l2 = math.hypot(*d1), math.hypot(*d2)
        rr = min(radii[i], l1 / 2, l2 / 2)
        p_in = (v[0] + d1[0] / l1 * rr, v[1] + d1[1] / l1 * rr)
        p_out = (v[0] + d2[0] / l2 * rr, v[1] + d2[1] / l2 * rr)
        for j in range(n + 1):
            t = j / n
            u = 1 - t
            out.append((u * u * p_in[0] + 2 * u * t * v[0] + t * t * p_out[0], u * u * p_in[1] + 2 * u * t * v[1] + t * t * p_out[1]))
    return out


def rrect(x0, y0, x1, y1, r):
    return rounded([(x0, y0), (x1, y0), (x1, y1), (x0, y1)], r)


def rot(pts, deg, cx=50.0, cy=50.0):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return [(cx + (x - cx) * c - (y - cy) * s, cy + (x - cx) * s + (y - cy) * c) for x, y in pts]


def mirror(pts):
    return [(100 - x, y) for x, y in pts]


def scale(pts, f, cx=50.0, cy=50.0):
    return [(cx + (x - cx) * f, cy + (y - cy) * f) for x, y in pts]


def petal(cx, cy, length, width, angle, sharp=.78):
    """A pointed petal/leaf growing from (cx, cy) upwards, turned by angle degrees."""
    left, right = [], []
    for i in range(41):
        t = i / 40
        half = width / 2 * math.sin(math.pi * t) ** sharp * (1 - .35 * t)
        left.append((cx - half, cy - length * t))
        right.append((cx + half, cy - length * t))
    return rot(left + right[::-1], angle, cx, cy)


def star4(cx, cy, r, pinch=.28):
    pts = []
    for i in range(8):
        a = math.radians(i * 45 - 90)
        rr = r if i % 2 == 0 else r * pinch
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    return pts


def star5(cx, cy, r, inner=.45, turn=0.0):
    pts = []
    for i in range(10):
        a = math.radians(i * 36 - 90 + turn)
        rr = r if i % 2 == 0 else r * inner
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    return pts


def bolt(cx, cy, h, angle=0.0):
    """A lightning bolt with its top at (cx, cy) and height h."""
    base = [(.40, 0), (-.34, .56), (.04, .56), (-.36, 1.0), (.50, .38), (.08, .38)]
    pts = [(cx + x * h, cy + y * h) for x, y in base]
    return rot(rounded(pts, h * .035, 4), angle, cx, cy)


# ---------------------------------------------------------------- drawing

_FIT = (1.0, 50.0, 50.0)    # (factor, cx, cy): the avatar being drawn is shrunk around its center


def px(pts):
    f, cx, cy = _FIT
    return [((cx + (x - cx) * f) * K, (cy + (y - cy) * f) * K) for x, y in pts]


def blank():
    return Image.new("L", (round(SIZE * SS),) * 2, 0)


def poly_mask(pts):
    m = blank()
    ImageDraw.Draw(m).polygon(px(pts), fill=255)
    return m


class Art:
    def __init__(self):
        self.img = Image.new("RGBA", (SIZE * SS,) * 2, (0, 0, 0, 0))

    # -- low level compositing, always limited to the bounding box
    def paint(self, mask, color, alpha=1.0):
        box = mask.getbbox()
        if not box:
            return
        m = mask.crop(box)
        if alpha < 1:
            m = m.point(lambda v: int(v * alpha + .5))
        layer = Image.new("RGBA", m.size, tuple(color) + (255,))
        layer.putalpha(m)
        self.img.alpha_composite(layer, (box[0], box[1]))

    def _ramp(self, fill, box):
        w, h = box[2] - box[0], box[3] - box[1]
        if isinstance(fill, R):
            f, cx, cy = _FIT
            side = max(2, round(fill.r * f * 2 * K))
            ramp = Image.new("L", (w, h), 255)
            ramp.paste(Image.radial_gradient("L").resize((side, side), Image.Resampling.BICUBIC),
                       (round((cx + (fill.cx - cx) * f) * K - side / 2) - box[0], round((cy + (fill.cy - cy) * f) * K - side / 2) - box[1]))
            return ramp
        d = math.hypot(w, h)
        d2 = int(d) + 2
        a = math.radians(fill.angle)
        length = abs(w * math.cos(a)) + abs(h * math.sin(a))
        lo = d2 / 2 - length / 2
        col = Image.new("L", (1, d2))
        col.putdata([max(0, min(255, round((y - lo) / max(length, 1) * 255))) for y in range(d2)])
        big = col.resize((d2, d2))
        big = big.rotate(90 - fill.angle, resample=Image.Resampling.BICUBIC, fillcolor=0 if fill.angle < 180 else 255)
        left, top = (d2 - w) // 2, (d2 - h) // 2
        return big.crop((left, top, left + w, top + h))

    def fill(self, mask, fill):
        if not isinstance(fill, (G, R)):
            self.paint(mask, fill)
            return
        box = mask.getbbox()
        if not box:
            return
        ramp = self._ramp(fill, box)
        size = (box[2] - box[0], box[3] - box[1])
        img = Image.composite(Image.new("RGB", size, tuple(fill.c2)), Image.new("RGB", size, tuple(fill.c1)), ramp)
        m = mask.crop(box)
        layer = img.convert("RGBA")
        layer.putalpha(m)
        self.img.alpha_composite(layer, (box[0], box[1]))

    def bevel(self, mask, strength=1.0):
        """Soft inner light on the upper left edge and soft inner shade on the lower right edge."""
        box = mask.getbbox()
        if not box:
            return
        pad = round(4 * K)
        box = (max(0, box[0] - pad), max(0, box[1] - pad), min(mask.width, box[2] + pad), min(mask.height, box[3] + pad))
        m = mask.crop(box)
        off = max(1, round(1.5 * K))
        blur = .9 * K
        for dx, color, alpha in ((-off, (20, 14, 56), .36), (off, WHITE, .5)):
            edge = ImageChops.subtract(m, ImageChops.offset(m, dx, dx))
            edge = ImageChops.multiply(edge.filter(ImageFilter.GaussianBlur(blur)).point(lambda v: min(255, v * 2)), m)
            full = blank()
            full.paste(edge, (box[0], box[1]))
            self.paint(full, color, alpha * strength)

    def shape(self, pts, fill, line=1.4, bevel=1.0, clip=None):
        """Outline, fill and bevel of a polygon; returns the filled mask (for clipping details)."""
        p = px(pts)
        m = poly_mask(pts)
        if line:
            k = blank()
            d = ImageDraw.Draw(k)
            d.polygon(p, fill=255)
            d.line(p + [p[0]], fill=255, width=max(1, round(2 * line * K)), joint="curve")
            if clip is not None:
                k = ImageChops.multiply(k, clip)
            self.paint(k, OUT)
        if clip is not None:
            m = ImageChops.multiply(m, clip)
        self.fill(m, fill)
        if bevel:
            self.bevel(m, bevel)
        return m

    def stroke(self, pts, width, color=OUT, alpha=1.0, clip=None):
        m = blank()
        d = ImageDraw.Draw(m)
        p = px(pts)
        w = max(1, round(width * K))
        d.line(p, fill=255, width=w, joint="curve")
        for x, y in (p[0], p[-1]):
            d.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=255)
        if clip is not None:
            m = ImageChops.multiply(m, clip)
        self.paint(m, color, alpha)

    def soft(self, pts, color, alpha, blur, clip=None):
        m = poly_mask(pts).filter(ImageFilter.GaussianBlur(blur * K))
        if clip is not None:
            m = ImageChops.multiply(m, clip)
        self.paint(m, color, alpha)

    def flat(self, pts, color, alpha=1.0, clip=None):
        m = poly_mask(pts)
        if clip is not None:
            m = ImageChops.multiply(m, clip)
        self.paint(m, color, alpha)

    # -- character parts
    def eye(self, cx, cy, rx, ry, color=INK, glint=True):
        self.flat(ell(cx, cy, rx, ry), color)
        if glint:
            self.flat(ell(cx - rx * .32, cy - ry * .36, rx * .36), WHITE)
            self.flat(ell(cx + rx * .35, cy + ry * .42, rx * .17), WHITE, .9)

    def blush(self, cx, cy, rx, ry, color=PINK, alpha=.6):
        self.soft(ell(cx, cy, rx, ry), color, alpha, 1.1)

    def smile(self, cx, cy, w, depth, width=1.4, color=INK):
        self.stroke(bez((cx - w / 2, cy), (cx - w / 4, cy + depth * 1.33), (cx + w / 4, cy + depth * 1.33), (cx + w / 2, cy)), width, color)

    def sparkle(self, cx, cy, r, color=CREAM, alpha=1.0):
        self.flat(star4(cx, cy, r), color, alpha)

    # -- final
    def finish(self):
        a = self.img.getchannel("A")
        # soft vignette on the character, strongest toward the circle's rim
        radial = Image.radial_gradient("L").resize(a.size, Image.Resampling.BICUBIC)
        vig = ImageChops.multiply(radial.point(lambda v: max(0, v - 140) * 36 // 100), a)
        self.paint(vig, (16, 12, 44), 1.0)
        # a soft drop shadow so it reads on every hue
        shadow = ImageChops.offset(a, 0, round(2.2 * K)).filter(ImageFilter.GaussianBlur(2.4 * K)).point(lambda v: int(v * .42))
        base = Image.new("RGBA", self.img.size, (0, 0, 0, 0))
        layer = Image.new("RGBA", self.img.size, (14, 10, 40, 255))
        layer.putalpha(shadow)
        base.alpha_composite(layer)
        base.alpha_composite(self.img)
        return base.resize((SIZE, SIZE), Image.Resampling.LANCZOS)


# ---------------------------------------------------------------- the 12 avatars

def sailor(a: Art):
    navy, navy2 = hsl(222, .6, .36), hsl(226, .6, .2)
    brown = hsl(24, .5, .26)
    a.shape(ell(50, 92, 31, 23), G(navy, navy2))
    a.shape(rounded([(27, 70), (42, 67), (50, 81), (58, 67), (73, 70), (66, 90), (34, 90)], 3), G(WHITE, hsl(214, .4, .8)))
    for sx in (-1, 1):
        a.stroke([(50 + sx * 20, 73.5), (50 + sx * 7, 70.5), (50, 81.5)], 1.3, navy)
        a.stroke([(50 + sx * 18.5, 78), (50 + sx * 6, 75.2), (50, 85)], 1.3, navy)
    a.shape(rounded([(45, 71), (55, 71), (50, 84)], 1.5), G(hsl(354, .85, .62), hsl(352, .8, .42)))
    # head
    a.shape(ell(50, 47, 21, 20), G(hsl(26, .55, .34), brown), bevel=.5)
    a.shape(ell(31.5, 53, 3.4, 4.4), hsl(26, .7, .78))
    a.shape(ell(68.5, 53, 3.4, 4.4), hsl(26, .7, .78))
    a.shape(ell(50, 51, 19, 18), R(hsl(28, .85, .9), hsl(24, .7, .72), 42, 42, 28))
    a.eye(42.5, 54, 2.7, 3.4)
    a.eye(57.5, 54, 2.7, 3.4)
    a.blush(36, 60, 3.8, 2.4)
    a.blush(64, 60, 3.8, 2.4)
    a.smile(50, 60, 7.5, 2.8)
    # the dixie cup cap
    crown = a.shape(rounded([(31, 44), (34, 25), (66, 25), (69, 44)], [3, 8, 8, 3]), G(WHITE, hsl(215, .4, .84)))
    a.shape(rrect(29.5, 38, 70.5, 47, 4), G(hsl(222, .6, .42), hsl(224, .62, .26)))
    a.stroke([(31, 40.8), (69, 40.8)], .9, WHITE, .9)
    a.stroke([(31, 44), (69, 44)], .9, WHITE, .9)
    a.shape(star5(50, 32, 4.4), G(hsl(48, .98, .72), hsl(38, .95, .52)), line=.9, bevel=.5)
    # the wave in front
    top = [(x, 80.5 + 2.8 * math.sin((x - 8) / 7.2)) for x in range(0, 101, 2)]
    wave = a.shape(top + [(100, 100), (0, 100)], G(hsl(196, .85, .58), hsl(216, .8, .34)), bevel=1.2)
    a.stroke(top, 2.2, WHITE, 1.0, clip=wave)
    for x, y, r in ((22, 84.5, 2.0), (47, 83.5, 1.6), (72, 84.5, 2.2), (84, 90, 1.4), (30, 91, 1.6)):
        a.flat(ell(x, y, r), WHITE, .95, clip=wave)
    a.stroke([(8, 93), (22, 89.5), (32, 93)], 1.0, WHITE, .55, clip=wave)
    a.stroke([(56, 94), (70, 90.5), (82, 94)], 1.0, WHITE, .55, clip=wave)


def voyager(a: Art):
    orange = G(hsl(30, .98, .66), hsl(18, .92, .5))
    a.shape(ell(50, 92, 32, 21), G(WHITE, hsl(252, .3, .72)))
    a.shape(rrect(40, 79, 60, 89, 3), G(hsl(255, .35, .35), hsl(255, .4, .2)))
    a.flat(ell(45, 84, 1.8), hsl(150, .9, .62))
    a.flat(ell(50, 84, 1.8), hsl(46, .98, .64))
    a.flat(ell(55, 84, 1.8), hsl(350, .9, .66))
    # comms pods on the sides
    a.shape(rrect(16, 39, 30, 57, 5), orange)
    a.shape(rrect(70, 39, 84, 57, 5), orange)
    # helmet
    a.shape(ell(50, 46, 28, 27), R(WHITE, hsl(252, .35, .68), 40, 32, 46))
    a.shape(ell(50, 48, 22.5, 19), G(hsl(250, .2, .9), hsl(250, .25, .62)), line=1.1, bevel=.6)
    visor = a.shape(ell(50, 48, 20.4, 16.8), G(hsl(262, .6, .34), hsl(248, .7, .1)), line=1.0, bevel=0)
    a.soft(ell(60, 57, 14, 9), hsl(190, .9, .5), .35, 3, clip=visor)
    a.eye(43, 49.5, 2.5, 3.2, hsl(185, .95, .88), glint=False)
    a.eye(57, 49.5, 2.5, 3.2, hsl(185, .95, .88), glint=False)
    a.smile(50, 56, 6, 2.0, 1.2, hsl(185, .95, .88))
    a.stroke(ell(50, 48, 16.8, 13.6, a0=198, a1=282, n=30), 2.0, WHITE, .75, clip=visor)
    a.flat(ell(36.5, 54, 1.2), WHITE, .8)
    a.sparkle(62, 41.5, 2.4, WHITE, .9)
    a.sparkle(58.5, 58.5, 1.5, WHITE, .8)
    # helmet shine and top lamp
    a.soft(ell(33, 26, 9, 5, rot=-35), WHITE, .7, 1.4)
    a.shape(rrect(44, 16, 56, 21, 2.4), orange, line=1.0)


def player(a: Art):
    def T(pts):
        return scale(pts, .9, 50, 52)
    body = [(27, 36), (73, 36), (83, 45), (88, 64), (84, 76), (74, 77), (66, 66), (34, 66), (26, 77), (16, 76), (12, 64), (17, 45)]
    deep = G(hsl(268, .45, .38), hsl(268, .5, .24))
    a.shape(T(rrect(23, 30, 41, 38, 3.2)), deep)
    a.shape(T(rrect(59, 30, 77, 38, 3.2)), deep)
    a.shape(T(rounded(body, [8, 8, 10, 8, 6, 6, 6, 6, 6, 6, 8, 10])), R(WHITE, hsl(292, .5, .76), 36, 40, 62), bevel=1.2)
    # d-pad
    cross = [(-2.7, -8), (2.7, -8), (2.7, -2.7), (8, -2.7), (8, 2.7), (2.7, 2.7), (2.7, 8), (-2.7, 8), (-2.7, 2.7), (-8, 2.7), (-8, -2.7), (-2.7, -2.7)]
    a.shape(T([(29 + x, 51 + y) for x, y in cross]), G(hsl(268, .45, .4), hsl(268, .5, .22)), line=1.1, bevel=.8)
    # buttons
    for dx, dy, hue in ((0, -5.6, 48), (5.6, 0, 168), (0, 5.6, 330), (-5.6, 0, 280)):
        c = T([(71 + dx, 51 + dy)])[0]
        a.shape(ell(c[0], c[1], 3.2), G(hsl(hue, .95, .7), hsl(hue, .85, .5)), line=1.1, bevel=.7)
    # sticks
    for cx in (42, 58):
        c = T([(cx, 63)])[0]
        a.shape(ell(c[0], c[1], 3.8), R(hsl(268, .4, .5), hsl(268, .5, .2), c[0] - 1.1, c[1] - 1.3, 5.4), line=1.1, bevel=.4)
    # face
    a.eye(44.5, 46, 3.5, 4.5)
    a.eye(55.5, 46, 3.5, 4.5)
    a.smile(50, 54.5, 6, 2.4)
    a.blush(39.5, 53.5, 2.8, 1.8)
    a.blush(60.5, 53.5, 2.8, 1.8)
    a.sparkle(24, 25, 4, CREAM)
    a.sparkle(79, 24, 3, CREAM)
    a.sparkle(81, 82, 2.4, CREAM)


def fox(a: Art):
    fur = G(hsl(18, .95, .6), hsl(8, .88, .42), 80)
    dark = hsl(350, .55, .22)
    left_ear = rounded([(18, 44), (21, 11), (47, 31)], 3)
    a.shape(left_ear, fur)
    a.shape(mirror(left_ear), fur)
    a.shape(rounded([(25, 35), (25.5, 20), (38, 31)], 2), G(hsl(348, .5, .32), dark), line=.8, bevel=.3)
    a.shape(mirror(rounded([(25, 35), (25.5, 20), (38, 31)], 2)), G(hsl(348, .5, .32), dark), line=.8, bevel=.3)
    head = a.shape(rounded([(16, 46), (26, 30), (74, 30), (84, 46), (73, 66), (50, 82), (27, 66)], [6, 9, 9, 6, 9, 5, 9]), fur)
    a.shape(rounded([(16, 47), (31, 50), (42, 58), (50, 54), (58, 58), (69, 50), (84, 47), (73, 66), (50, 82), (27, 66)], [3, 3, 6, 3, 6, 3, 3, 9, 5, 9]),
            G(WHITE, hsl(30, .6, .88), 80), line=1.0, bevel=.7, clip=head)
    # forehead blaze and cheek marks
    a.flat(rounded([(44, 30), (56, 30), (50, 42)], 2), hsl(26, .98, .74), .8, clip=head)
    for tilt, x in ((-18, 36.5), (18, 63.5)):
        a.shape(ell(x, 45.5, 3.3, 3.9, rot=tilt), INK, line=0, bevel=0)
        a.flat(ell(x - 1.1, 44, 1.2), WHITE)
    a.shape(ell(50, 70, 5, 3.5), G(hsl(350, .5, .3), hsl(350, .55, .12)), line=1.0, bevel=0)
    a.flat(ell(48.5, 69, 1.4, .8), WHITE, .8)
    a.stroke([(50, 73), (50, 75.5)], 1.1, INK)
    a.stroke(bez((50, 75.5), (48, 78.5), (45, 78), (43.5, 76.5), 10), 1.1, INK)
    a.stroke(bez((50, 75.5), (52, 78.5), (55, 78), (56.5, 76.5), 10), 1.1, INK)
    a.blush(30, 58, 3.8, 2.4, hsl(8, .9, .66), .55)
    a.blush(70, 58, 3.8, 2.4, hsl(8, .9, .66), .55)


def spark(a: Art):
    yellow = G(hsl(54, .98, .75), hsl(38, .95, .54), 70)
    indigo = R(hsl(252, .6, .5), hsl(252, .62, .2), 40, 42, 36)
    a.soft(ell(50, 56, 34, 32), hsl(52, 1, .88), .65, 4.5)
    a.shape(bolt(86, 60, 22, 70), yellow, bevel=.8)
    ear = bolt(73, 15, 29, 6)
    a.shape(mirror(ear), yellow, bevel=.8)
    a.shape(ear, yellow, bevel=.8)
    a.shape(ell(50, 57, 25, 23), indigo)
    a.eye(41, 53.5, 5.4, 6.4, WHITE, glint=False)
    a.eye(59, 53.5, 5.4, 6.4, WHITE, glint=False)
    for x in (41.8, 58.2):
        a.flat(ell(x, 54.5, 3.2, 4.2), INK)
        a.flat(ell(x - 1.1, 52.6, 1.3), WHITE)
    a.shape(ell(50, 63, 4.8, 3.8, a0=0, a1=180, n=24), INK, line=0, bevel=0)
    a.flat(ell(50, 65.4, 2.4, 1.4), hsl(350, .85, .66))
    for x in (32, 68):
        a.shape(ell(x, 62, 4, 4), G(hsl(40, .98, .62), hsl(26, .95, .5)), line=1.0, bevel=.5)
    a.shape(bolt(46, 70, 10, 0), yellow, line=.9, bevel=.4)
    a.sparkle(17, 40, 3.4, hsl(52, 1, .84))
    a.sparkle(88, 40, 2.6, hsl(52, 1, .84))
    a.sparkle(22, 80, 2.4, hsl(52, 1, .84))


def calm(a: Art):
    a.soft(ell(50, 52, 36, 32), hsl(150, .8, .92), .55, 5)
    leaf = G(hsl(150, .62, .42), hsl(160, .7, .2), 80)
    a.shape(petal(50, 85, 36, 14, -80), leaf)
    a.shape(petal(50, 85, 36, 14, 80), leaf)
    pink = G(hsl(330, .95, .9), hsl(338, .78, .64))
    for ang, length, width in ((-66, 36, 20), (66, 36, 20), (-34, 42, 22), (34, 42, 22)):
        a.shape(petal(50, 82, length, width, ang), pink)
    a.shape(petal(50, 82, 46, 29, 0), G(hsl(328, .95, .96), hsl(336, .75, .76)), bevel=1.2)
    # serene closed eyes
    for x in (42.5, 57.5):
        a.stroke(bez((x - 4, 54), (x - 2.4, 58), (x + 2.4, 58), (x + 4, 54), 12), 1.5, INK)
    a.smile(50, 62, 6.4, 2.2, 1.4)
    a.blush(37.5, 61, 3.6, 2.2, hsl(340, .9, .62), .7)
    a.blush(62.5, 61, 3.6, 2.2, hsl(340, .9, .62), .7)
    # water rings
    a.stroke(ell(50, 87, 33, 5.6, a0=10, a1=170, n=32), 1.2, CREAM, .9)
    a.stroke(ell(50, 87, 22, 3.4, a0=10, a1=170, n=32), 1.0, CREAM, .75)
    a.sparkle(24, 28, 3.2, CREAM)
    a.sparkle(78, 24, 2.4, CREAM)
    a.flat(ell(72, 35, 1.2), CREAM, .9)


def knight(a: Art):
    steel = G(hsl(215, .25, .94), hsl(222, .3, .5), 55)
    gold = G(hsl(48, .98, .72), hsl(38, .92, .5))
    red = G(hsl(355, .85, .6), hsl(350, .8, .36), 60)
    a.shape(rrect(31, 70, 69, 88, 6), G(hsl(222, .3, .62), hsl(224, .35, .32)))
    a.shape(cr([(44, 30), (49, 14), (62, 5), (77, 11), (84, 24), (72, 21), (62, 24), (56, 34)], n=14), red)
    a.stroke(bez((52, 16), (62, 11), (70, 12), (77, 17), 14), 1.1, hsl(355, .8, .8), .6)
    helmet = a.shape(rounded([(25, 77), (23, 46), (31, 28), (50, 21), (69, 28), (77, 46), (75, 77)], [5, 8, 11, 12, 11, 8, 5]), steel, bevel=1.4)
    a.shape(rrect(46.5, 21, 53.5, 78, 1.5), gold, line=1.0, bevel=.8, clip=helmet)
    a.shape(rrect(23, 69, 77, 78, 3), gold, line=1.0, bevel=.8, clip=helmet)
    a.shape(rrect(28, 44, 72, 53, 4), G(hsl(230, .5, .2), hsl(236, .5, .08)), line=1.1, bevel=0)
    for x in (40, 60):
        a.flat(ell(x, 48.5, 2.8, 1.9), hsl(190, .95, .86))
    for dx in (0, 1):
        for i in range(3):
            y = 58 + i * 3.3
            a.flat(ell(30.5 + dx * 4.4 + (i % 2) * 1.2, y, 1.1), (34, 30, 66), .85)
            a.flat(ell(65 + dx * 4.4 - (i % 2) * 1.2, y, 1.1), (34, 30, 66), .85)
    for x, y in ((30, 33), (70, 33)):
        a.flat(ell(x, y, 1.5), hsl(48, .98, .64))


def robot(a: Art):
    metal = G(hsl(195, .3, .98), hsl(210, .3, .7), 70)
    orange = G(hsl(34, .98, .66), hsl(20, .95, .5))
    a.shape(rrect(31, 76, 69, 96, 7), G(hsl(205, .3, .82), hsl(215, .3, .55)))
    a.shape(rrect(44, 68, 56, 79, 2.5), G(hsl(215, .25, .55), hsl(220, .3, .35)), bevel=.5)
    a.shape(rrect(48.2, 13, 51.8, 30, 1.8), G(hsl(215, .25, .85), hsl(215, .3, .55)), line=1.1, bevel=.5)
    a.shape(ell(50, 12, 5), orange, bevel=1.0)
    a.shape(rrect(13, 43, 25, 63, 4.5), orange)
    a.shape(rrect(75, 43, 87, 63, 4.5), orange)
    a.shape(rrect(21, 25, 79, 75, 14), metal, bevel=1.3)
    screen = a.shape(rrect(28.5, 33, 71.5, 63, 10), R(hsl(220, .55, .3), hsl(232, .6, .1), 38, 36, 40), line=1.2, bevel=0)
    a.soft(ell(40.5, 47, 7, 9), hsl(44, 1, .6), .45, 2.2, clip=screen)
    a.soft(ell(59.5, 47, 7, 9), hsl(44, 1, .6), .45, 2.2, clip=screen)
    for x in (40.5, 59.5):
        a.shape(rrect(x - 4.2, 40, x + 4.2, 54, 4.2), G(hsl(52, 1, .84), hsl(40, 1, .6)), line=0, bevel=0)
        a.flat(ell(x - 1.4, 43.5, 1.3), WHITE)
    a.smile(50, 56, 8, 2.4, 1.3, hsl(48, .98, .72))
    a.blush(33, 58, 2.4, 1.6, hsl(340, .9, .66), .6)
    a.blush(67, 58, 2.4, 1.6, hsl(340, .9, .66), .6)
    for i in range(3):
        a.flat(rrect(41 + i * 6.3 - 1.4, 66.5, 41 + i * 6.3 + 1.4, 71, 1.4), (46, 62, 94), .9)
    for x, y in ((26.5, 30.5), (73.5, 30.5), (26.5, 69.5), (73.5, 69.5)):
        a.flat(ell(x, y, 1.4), (88, 108, 140))


def cat(a: Art):
    fur = G(hsl(258, .32, .42), hsl(258, .4, .2), 80)
    pink = G(hsl(340, .85, .82), hsl(338, .75, .62))
    left_ear = rounded([(21, 46), (22, 12), (47, 30)], 3.5)
    a.shape(left_ear, fur)
    a.shape(mirror(left_ear), fur)
    a.shape(rounded([(26, 39), (26.5, 21), (39, 31)], 2), pink, line=.8, bevel=.3)
    a.shape(mirror(rounded([(26, 39), (26.5, 21), (39, 31)], 2)), pink, line=.8, bevel=.3)
    head = a.shape(ell(50, 55, 30, 25), fur, bevel=1.6)
    for x, y0, tilt in ((50, 30.5, 0), (42, 31.5, -12), (58, 31.5, 12)):
        a.stroke(rot([(x, y0), (x, y0 + 7 - (0 if x == 50 else 2))], tilt, x, y0), 1.7, hsl(262, .35, .62), .85, clip=head)
    a.shape(ell(43.5, 65, 8, 6), G(hsl(250, .3, .98), hsl(250, .25, .82)), line=1.0, bevel=.6)
    a.shape(ell(56.5, 65, 8, 6), G(hsl(250, .3, .98), hsl(250, .25, .82)), line=1.0, bevel=.6)
    for x, s in ((37.5, -1), (62.5, 1)):
        a.shape(ell(x, 53, 6.4, 7.2), G(hsl(52, .98, .78), hsl(38, .95, .5)), line=1.2, bevel=.5)
        a.flat(ell(x + s * .4, 53.5, 2.3, 5.6), INK)
        a.flat(ell(x - 2, 50, 1.9), WHITE)
        a.flat(ell(x + 1.6, 56.5, .9), WHITE, .9)
    a.shape(rounded([(46.2, 60), (53.8, 60), (50, 64.2)], 1.4), G(hsl(340, .9, .8), hsl(340, .8, .6)), line=.9, bevel=0)
    a.stroke([(50, 64.2), (50, 66.6)], 1.0, INK)
    a.stroke(bez((50, 66.6), (48.5, 69.6), (45.5, 69.4), (43.8, 67.6), 10), 1.0, INK)
    a.stroke(bez((50, 66.6), (51.5, 69.6), (54.5, 69.4), (56.2, 67.6), 10), 1.0, INK)
    for dy, ex, ey in ((0, 15, -3.2), (3.2, 16, 4), (6.2, 17, 11)):
        a.stroke([(36, 63 + dy), (ex, 61 + dy + ey)], .7, WHITE, .9)
        a.stroke([(64, 63 + dy), (100 - ex, 61 + dy + ey)], .7, WHITE, .9)
    a.blush(27, 61, 3.8, 2.4, hsl(340, .9, .66), .5)
    a.blush(73, 61, 3.8, 2.4, hsl(340, .9, .66), .5)
    a.shape(rrect(36, 76, 64, 82.5, 3.2), G(hsl(354, .85, .62), hsl(350, .8, .4)), bevel=.8)
    a.shape(ell(50, 85, 4.4), G(hsl(52, .98, .78), hsl(38, .95, .5)), line=1.1, bevel=.6)
    a.flat(rrect(48.6, 85.2, 51.4, 87, .8), (110, 70, 20), .8)


def wizard(a: Art):
    skin = R(hsl(28, .85, .9), hsl(24, .65, .72), 44, 48, 22)
    a.shape(ell(50, 56, 17, 16), skin, bevel=.8)
    a.shape(cr([(30, 56), (33, 68), (38, 80), (50, 90), (62, 80), (67, 68), (70, 56), (61, 62), (50, 66), (39, 62)], n=14), G(WHITE, hsl(232, .3, .78)), bevel=1.1)
    a.shape(ell(50, 59, 4.8, 4.2), G(hsl(18, .75, .84), hsl(12, .7, .68)), line=1.0, bevel=.6)
    a.shape(ell(43, 64.5, 6.4, 2.9, rot=-12), G(WHITE, hsl(232, .3, .8)), line=1.0, bevel=.5)
    a.shape(ell(57, 64.5, 6.4, 2.9, rot=12), G(WHITE, hsl(232, .3, .8)), line=1.0, bevel=.5)
    a.flat(ell(42.5, 54.5, 1.8), INK)
    a.flat(ell(57.5, 54.5, 1.8), INK)
    a.shape(ell(50, 43, 34, 8.2), G(hsl(238, .55, .5), hsl(240, .6, .24)), bevel=1.2)
    cone = a.shape(cr([(27, 44), (34, 28), (45, 16), (58, 9), (72, 8), (74, 14), (66, 19), (62, 30), (73, 44)], n=14), G(hsl(238, .55, .5), hsl(244, .6, .22), 65), bevel=1.3)
    a.shape(rrect(24, 37, 76, 44, 3), G(hsl(48, .98, .72), hsl(38, .92, .5)), line=1.0, bevel=.8, clip=cone)
    a.flat(star5(49, 27, 5), hsl(50, .98, .8), clip=cone)
    a.flat(star5(58.5, 18, 3.2), hsl(50, .98, .8), clip=cone)
    a.flat(star5(40, 33, 2.6), hsl(50, .98, .8), clip=cone)
    a.flat(ell(64, 25, 1.1), hsl(50, .98, .8), clip=cone)
    a.sparkle(83, 20, 3, CREAM)
    a.sparkle(17, 30, 2.4, CREAM)


def racer(a: Art):
    a.shape(ell(50, 99, 29, 17), G(hsl(224, .55, .4), hsl(228, .6, .2)))
    shell = a.shape(rounded([(22, 52), (25, 31), (40, 19), (60, 19), (75, 31), (78, 52), (75, 69), (62, 79), (38, 79), (25, 69)], [10, 12, 14, 14, 12, 10, 8, 10, 10, 8]),
                    R(WHITE, hsl(215, .3, .7), 38, 30, 50), bevel=1.5)
    a.flat([(45, 15), (55, 15), (55, 46), (45, 46)], hsl(226, .65, .3), clip=shell)
    a.flat([(40.5, 15), (44, 15), (44, 46), (40.5, 46)], hsl(48, .98, .62), clip=shell)
    a.flat([(56, 15), (59.5, 15), (59.5, 46), (56, 46)], hsl(48, .98, .62), clip=shell)
    for sx in (1, -1):
        a.flat([(50 + sx * 25, 56), (50 + sx * 29, 56), (50 + sx * 29, 72), (50 + sx * 25, 68)], hsl(48, .98, .62), clip=shell)
        a.flat([(50 + sx * 29, 56), (50 + sx * 33, 56), (50 + sx * 33, 76), (50 + sx * 29, 72)], hsl(226, .65, .3), clip=shell)
    pts = [(26, 47), (33, 40.5), (50, 38.5), (67, 40.5), (74, 47), (72, 57), (64, 61), (50, 58.5), (36, 61), (28, 57)]
    a.shape(scale(cr(pts, n=12), 1.12, 50, 49), G(hsl(228, .5, .34), hsl(230, .55, .14)), line=1.2, bevel=.8)
    visor = a.shape(cr(pts, n=12), G(hsl(198, .9, .52), hsl(236, .75, .14), 80), line=1.0, bevel=.4)
    a.flat([(33, 62), (39, 62), (49, 37), (43, 37)], WHITE, .5, clip=visor)
    a.flat([(45, 62), (48, 62), (58, 37), (55, 37)], WHITE, .35, clip=visor)
    a.soft(ell(56, 55, 12, 3.5), hsl(190, .95, .6), .5, 2, clip=visor)
    a.shape(rrect(40, 66, 60, 75, 3.2), G(hsl(226, .35, .5), hsl(226, .4, .28)), line=1.0, bevel=.5)
    for i in range(3):
        a.flat(rrect(43, 67.8 + i * 2.2, 57, 68.9 + i * 2.2, .5), (150, 162, 200), .9)
    a.soft(ell(33, 27, 8, 4, rot=-35), WHITE, .7, 1.2)
    a.stroke([(8, 30), (18, 30)], 1.4, WHITE, .8)
    a.stroke([(5, 38), (14, 38)], 1.2, WHITE, .6)
    a.stroke([(92, 30), (82, 30)], 1.4, WHITE, .8)
    a.stroke([(95, 38), (86, 38)], 1.2, WHITE, .6)


def ghost(a: Art):
    body_fill = G(WHITE, hsl(236, .5, .84), 80)
    a.soft(ell(50, 87, 20, 3.6), (14, 10, 40), .35, 1.4)
    a.shape(ell(22.5, 58, 7.5, 4.4, rot=-35), body_fill, bevel=.8)
    a.shape(ell(77.5, 58, 7.5, 4.4, rot=35), body_fill, bevel=.8)
    top = ell(50, 46, 26.5, 28, a0=180, a1=360, n=48)
    bumps = []
    for cx in (66.8, 50, 33.2):
        bumps += ell(cx, 74, 8.4, 8, a0=0, a1=180, n=20)
    a.shape(top + [(76.5, 74)] + bumps + [(23.5, 74)], body_fill, bevel=1.4)
    a.eye(41, 47, 4.4, 6.2)
    a.eye(59, 47, 4.4, 6.2)
    a.shape(ell(50, 58, 3.2, 3.9), INK, line=0, bevel=0)
    a.flat(ell(50, 60, 1.7, 1.4), hsl(350, .85, .66))
    a.blush(34, 57, 4.0, 2.4, hsl(340, .9, .7), .65)
    a.blush(66, 57, 4.0, 2.4, hsl(340, .9, .7), .65)
    a.soft(ell(36, 29, 8, 4, rot=-30), WHITE, .9, 1.3)
    a.sparkle(20, 26, 3.4, hsl(52, 1, .82))
    a.sparkle(82, 34, 2.6, hsl(52, 1, .82))
    a.sparkle(80, 80, 2.2, hsl(52, 1, .82))


@dataclass(frozen=True)
class Avatar:
    slug: str
    name: str
    hue: int
    draw: object
    fit: float = 1.0    # shrink around the center to keep the character circle-safe


# Order = stored index (the first six keep their original profile avatars).
AVATARS = (
    Avatar("sailor", "Sailor", 205, sailor),
    Avatar("voyager", "Voyager", 262, voyager),
    Avatar("player", "Player", 330, player),
    Avatar("fox", "Fox", 24, fox, .88),
    Avatar("spark", "Spark", 48, spark, .94),
    Avatar("calm", "Calm", 150, calm),
    Avatar("knight", "Knight", 232, knight, .9),
    Avatar("robot", "Robot", 182, robot, .93),
    Avatar("cat", "Cat", 84, cat, .92),
    Avatar("wizard", "Wizard", 292, wizard, .96),
    Avatar("racer", "Racer", 356, racer),
    Avatar("ghost", "Ghost", 118, ghost),
)


def render(avatar: Avatar) -> Image.Image:
    global _FIT
    _FIT = (avatar.fit, 50.0, 50.0)
    art = Art()
    avatar.draw(art)
    _FIT = (1.0, 50.0, 50.0)
    return art.finish()


def reach(image: Image.Image) -> float:
    """Farthest opaque pixel from the center, in percent of the canvas width (the badge circle is 50)."""
    small = image.getchannel("A").resize((96, 96), Image.Resampling.BOX)
    data = small.load()
    return max(math.hypot(x + .5 - 48, y + .5 - 48) for y in range(96) for x in range(96) if data[x, y] > 128) / .96


# ---------------------------------------------------------------- previews

def badge(hue: float, size: int) -> Image.Image:
    """The app's avatarBackground: radial-gradient(circle at 35% 30%, hsl(h 80 62), hsl(h+30 70 38))."""
    inner, outer = hsl(hue, .8, .62), hsl(hue + 30, .7, .38)
    cx, cy = size * .35, size * .3
    reach = max(math.hypot(x, y) for x in (cx, size - cx) for y in (cy, size - cy))
    img = Image.new("RGB", (size, size))
    img.putdata([
        tuple(round(inner[c] + (outer[c] - inner[c]) * min(1, math.hypot(x - cx, y - cy) / reach)) for c in range(3))
        for y in range(size) for x in range(size)
    ])
    return img


def on_badge(art: Image.Image, hue: float, size: int) -> Image.Image:
    base = badge(hue, size).convert("RGBA")
    base.alpha_composite(art.resize((size, size), Image.Resampling.LANCZOS))
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    base.putalpha(mask.resize((size, size), Image.Resampling.LANCZOS))
    return base


def contact_sheet(arts: dict[str, Image.Image], only: list[Avatar]) -> list[Path]:
    PREVIEW.mkdir(parents=True, exist_ok=True)
    font = ImageFont.load_default(size=16)
    bg = (13, 17, 28, 255)
    paths = []
    tile, pad = (384 if len(only) <= 3 else 256), 20
    cols = min(6, len(only))
    rows = (len(only) + cols - 1) // cols
    # below the big tiles: every avatar at the sizes of the app (144 picker, 96, 72 and 56 px)
    strip = (144 + 96 + 72 + 56) + 5 * pad
    sheet = Image.new("RGBA", (max(cols * (tile + pad) + pad, len(only) * 150 + pad), rows * (tile + 36 + pad) + pad + strip), bg)
    draw = ImageDraw.Draw(sheet)
    for i, av in enumerate(only):
        x, y = pad + (i % cols) * (tile + pad), pad + (i // cols) * (tile + 36 + pad)
        sheet.alpha_composite(on_badge(arts[av.slug], av.hue, tile), (x, y))
        draw.text((x, y + tile + 6), f"{i} {av.name} ({av.hue})", font=font, fill=(231, 236, 247))
    y = rows * (tile + 36 + pad) + pad
    for size in (144, 96, 72, 56):
        for i, av in enumerate(only):
            sheet.alpha_composite(on_badge(arts[av.slug], av.hue, size), (pad + i * (max(size, 100) + 6), y))
        y += size + pad
    suffix = "" if len(only) == len(AVATARS) else "-" + "-".join(av.slug for av in only)
    path = PREVIEW / f"contact-sheet{suffix}.png"
    sheet.save(path)
    paths.append(path)
    # stress: every avatar on four foreign hues
    hues = (0, 90, 180, 270)
    stress = Image.new("RGBA", (len(only) * 100 + 20, len(hues) * 100 + 20), bg)
    for i, av in enumerate(only):
        for j, hue in enumerate(hues):
            stress.alpha_composite(on_badge(arts[av.slug], hue, 92), (10 + i * 100, 10 + j * 100))
    path = PREVIEW / f"stress-hues{suffix}.png"
    stress.save(path)
    paths.append(path)
    return paths


def main() -> None:
    global SS, K
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--preview", action="store_true", help="also write build/avatars-preview/")
    parser.add_argument("--only", nargs="*", default=[], help="render only these slugs")
    parser.add_argument("--fast", action="store_true", help="2x supersampling for quick looks (do not commit the result)")
    args = parser.parse_args()
    if args.fast:
        SS = 2
        K = SIZE * SS / 100.0
    chosen = [a for a in AVATARS if not args.only or a.slug in args.only]
    OUTPUT.mkdir(parents=True, exist_ok=True)
    arts = {}
    for av in chosen:
        image = render(av)
        arts[av.slug] = image
        print(f"{av.slug}: reaches {reach(image):.1f}% of the canvas width from the center (circle edge is 50)")
        if not args.fast:
            target = OUTPUT / f"{av.slug}.webp"
            image.save(target, "WEBP", quality=90, method=6, alpha_quality=100)
            print(f"{target.relative_to(REPO)}: {target.stat().st_size:,} bytes")
    if not args.fast and not args.only:
        total = sum(p.stat().st_size for p in OUTPUT.glob("*.webp"))
        if total > BUDGET:
            raise SystemExit(f"Avatars exceed the {BUDGET:,} byte budget: {total:,} bytes")
        print(f"{len(chosen)} avatars: {total:,} bytes total")
    if args.preview or args.fast:
        for path in contact_sheet(arts, chosen):
            print(f"Preview: {path}")


if __name__ == "__main__":
    main()
