"""Hand-run checks of the importer's colour rule: python3 -m unittest tools/avatars/test_import.py -v (from the repository root).

Needs only Pillow, like the importer itself. Nothing is read from or written to app/src/main/assets.
"""

import colorsys
import importlib.util
import re
import unittest
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("avatar_importer", HERE / "import.py")   # "import" is a keyword: no plain import
imp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(imp)

PROFILES = HERE.parents[1] / "app/src/main/java/com/limelight/shell/data/Profiles.kt"


def solid(rgb, size=64):
    """A square, opaque-centered test character: a disc of one color on a transparent canvas."""
    image = Image.new("RGBA", (size, size))
    for y in range(size):
        for x in range(size):
            if (x - size / 2) ** 2 + (y - size / 2) ** 2 < (size * .35) ** 2:
                image.putpixel((x, y), (*rgb, 255))
    return image


def apart(a, b):
    """The distance of two hues in degrees, around the circle."""
    d = abs(a - b) % 360
    return min(d, 360 - d)


def hue_of(rgb):
    return colorsys.rgb_to_hls(*(c / 255 for c in rgb))[0] * 360


class ColourRuleTest(unittest.TestCase):
    def test_every_avatar_has_a_hint_and_the_hints_are_degrees(self):
        self.assertEqual({slug for slug, _ in imp.AVATARS}, set(imp.HUE_HINTS))
        for slug, hue in imp.HUE_HINTS.items():
            self.assertTrue(0 <= hue < 360, slug)

    def test_the_slugs_match_the_app(self):
        found = re.findall(r'Avatar\(Symbols\.\w+, "(\w+)", (\d+)f, "(\w+)\.webp"', PROFILES.read_text(encoding="utf-8"))
        self.assertEqual([(name.lower(), int(hue)) for name, hue, _ in found], list(imp.AVATARS))
        self.assertEqual([slug for _, _, slug in found], [slug for slug, _ in imp.AVATARS])

    def test_the_colours_are_the_dark_muted_gradient_of_the_hue(self):
        c0, c1 = imp.colors_of_hue(187)
        self.assertEqual(c0, imp.hsl(187, .32, .22))
        self.assertEqual(c1, imp.hsl(187, .26, .10))
        for hue in (0, 15, 95, 187, 250, 320, 358):
            c0, c1 = imp.colors_of_hue(hue)
            h0, l0, s0 = colorsys.rgb_to_hls(*(c / 255 for c in c0))
            h1, l1, s1 = colorsys.rgb_to_hls(*(c / 255 for c in c1))
            self.assertLess(l1, l0)         # the edge is darker than the lit center
            self.assertLess(l0, .25)        # and both are dark and muted
            self.assertLess(s0, .4)
            self.assertLess(apart(h0 * 360, hue), 3)
            self.assertLess(apart(h1 * 360, hue), 3)

    def test_a_hint_wins_over_the_detected_colour(self):
        red_character = solid((200, 40, 40))            # detected hue about 0
        hue, source, detected = imp.character_hue(red_character, "sailor", 205)
        self.assertEqual((hue, source), (187.0, "hint"))
        self.assertIsNotNone(detected)
        self.assertLess(apart(detected, 0), 10)
        c0, c1, _ = imp.background(red_character, "sailor", 205)
        self.assertEqual((c0, c1), imp.colors_of_hue(187))

    def test_without_a_hint_the_dominant_hue_is_used(self):
        green = solid((40, 190, 60))
        hue, source, _ = imp.character_hue(green, "no-such-slug", 10)
        self.assertEqual(source, "dominant")
        self.assertAlmostEqual(hue, hue_of((40, 190, 60)), delta=25)

    def test_a_grey_character_without_a_hint_uses_the_base_hue(self):
        hue, source, detected = imp.character_hue(solid((128, 128, 128)), "no-such-slug", 321)
        self.assertEqual((hue, source, detected), (321.0, "base", None))


if __name__ == "__main__":
    unittest.main()
