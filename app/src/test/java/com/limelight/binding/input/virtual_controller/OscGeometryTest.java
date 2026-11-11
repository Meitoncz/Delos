package com.limelight.binding.input.virtual_controller;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The on-screen controls on every screen shape of docs/HANDHELD_DEVICES.md: 1:1 up to 21:9, with 16:9 as it was. */
public class OscGeometryTest {
    private static final int EID_DPAD = VirtualControllerElement.EID_DPAD;

    /** name, width, height */
    private static final Object[][] SHAPES = {
            {"1:1 RG Cube", 720, 720},
            {"5:4", 1280, 1024},
            {"31:27 Thor bottom", 1240, 1080},
            {"4:3 RG 406H", 960, 720},
            {"4:3 RG 476H", 1280, 960},
            {"4:3 Pocket DS bottom", 1024, 768},
            {"3:2 Pocket Micro", 960, 640},
            {"3:2 Pocket ACE", 1620, 1080},
            {"16:10", 1920, 1200},
            {"16:9 1080p", 1920, 1080},
            {"16:9 720p", 1280, 720},
            {"16:9 Retroid 4 Pro", 1334, 750},
            {"16:9 1440p", 2560, 1440},
            {"20:9 Razer Edge", 2400, 1080},
            {"21:9", 2520, 1080},
            {"32:9", 3840, 1080},
            {"portrait 9:16", 1080, 1920},
    };

    /** The pre-phase-D arithmetic of VirtualControllerConfigurationLoader.createDefaultLayout, element by element. */
    private static Map<Integer, int[]> legacy(int width, int height, boolean onlyL3R3, boolean showGuide) {
        Map<Integer, int[]> m = new LinkedHashMap<>();
        int rd = width - height * 16 / 9;
        int center = width / 2;
        int middleWidth = s(12, height);
        int middleGap = s(2, height);
        int guideX = center - middleWidth / 2;
        int backX = guideX - middleGap - middleWidth;
        int startX = guideX + middleWidth + middleGap;
        int trackpadWidth = s(20, height);
        int trackpadHeight = s(10, height);
        int trackpadY = s(64 - 2, height) - trackpadHeight;
        if (!onlyL3R3) {
            m.put(VirtualControllerElement.EID_DPAD, new int[] { s(4, height), s(41, height), s(30, height), s(30, height) });
            m.put(VirtualControllerElement.EID_A, new int[] { s(106, height) + rd, s(21, height), s(10, height), s(10, height) });
            m.put(VirtualControllerElement.EID_B, new int[] { s(116, height) + rd, s(11, height), s(10, height), s(10, height) });
            m.put(VirtualControllerElement.EID_X, new int[] { s(96, height) + rd, s(11, height), s(10, height), s(10, height) });
            m.put(VirtualControllerElement.EID_Y, new int[] { s(106, height) + rd, s(1, height), s(10, height), s(10, height) });
            m.put(VirtualControllerElement.EID_LT, new int[] { s(1, height), s(31, height), s(12, height), s(9, height) });
            m.put(VirtualControllerElement.EID_RT, new int[] { s(92 + 23, height) + rd, s(31, height), s(12, height), s(9, height) });
            m.put(VirtualControllerElement.EID_LB, new int[] { s(1 + 23, height), s(31, height), s(12, height), s(9, height) });
            m.put(VirtualControllerElement.EID_RB, new int[] { s(92, height) + rd, s(31, height), s(12, height), s(9, height) });
            m.put(VirtualControllerElement.EID_LS, new int[] { s(6, height), s(4, height), s(26, height), s(26, height) });
            m.put(VirtualControllerElement.EID_RS, new int[] { s(98, height) + rd, s(42, height), s(26, height), s(26, height) });
            m.put(VirtualControllerElement.EID_BACK, new int[] { backX, s(64, height), s(12, height), s(7, height) });
            m.put(VirtualControllerElement.EID_START, new int[] { startX, s(64, height), s(12, height), s(7, height) });
            m.put(VirtualControllerElement.EID_LSB, new int[] { s(1, height), s(60, height), s(12, height), s(9, height) });
            m.put(VirtualControllerElement.EID_RSB, new int[] { s(92 + 23, height) + rd, s(60, height), s(12, height), s(9, height) });
            m.put(VirtualControllerElement.EID_TOUCHPAD, new int[] { center - trackpadWidth / 2, trackpadY, trackpadWidth, trackpadHeight });
        } else {
            m.put(VirtualControllerElement.EID_LSB, new int[] { s(1, height), s(60, height), s(12, height), s(9, height) });
            m.put(VirtualControllerElement.EID_RSB, new int[] { s(92 + 23, height) + rd, s(60, height), s(12, height), s(9, height) });
        }
        if (showGuide) {
            m.put(VirtualControllerElement.EID_GDB, new int[] { guideX, s(64, height), s(12, height), s(7, height) });
        }
        return m;
    }

    private static int s(int units, int height) {
        return (int) (((float) height / (float) 72) * (float) units);
    }

    private static Map<Integer, int[]> layout(int width, int height, boolean onlyL3R3, boolean showGuide) {
        Map<Integer, int[]> m = new LinkedHashMap<>();
        OscDefaultLayout.compute(new OscGeometry.Panel(width, height), onlyL3R3, showGuide).forEach((id, slot) ->
                m.put(id, new int[] { slot.left, slot.top, slot.width, slot.height }));
        return m;
    }

    private static void assertSameLayout(String what, Map<Integer, int[]> expected, Map<Integer, int[]> actual) {
        assertEquals(what + ": same elements, in the same order", new ArrayList<>(expected.keySet()), new ArrayList<>(actual.keySet()));
        for (Integer id : expected.keySet()) {
            assertArrayEquals(what + ": element " + id, expected.get(id), actual.get(id));
        }
    }

    @Test
    public void sixteenByNineIsExactlyTheOldLayoutAtEveryResolution() {
        int[][] sizes = { { 1920, 1080 }, { 1280, 720 }, { 1334, 750 }, { 2560, 1440 }, { 3840, 2160 }, { 1600, 900 }, { 854, 480 } };
        for (int[] size : sizes) {
            for (boolean onlyL3R3 : new boolean[] { false, true }) {
                for (boolean guide : new boolean[] { false, true }) {
                    assertSameLayout(size[0] + "x" + size[1] + " l3r3=" + onlyL3R3 + " guide=" + guide,
                            legacy(size[0], size[1], onlyL3R3, guide), layout(size[0], size[1], onlyL3R3, guide));
                }
            }
        }
    }

    @Test
    public void pinnedPositionsOf1080p() {
        // The numbers the Odin has today, by hand: unit = 15 px
        Map<Integer, int[]> m = layout(1920, 1080, false, true);
        assertArrayEquals(new int[] { 60, 615, 450, 450 }, m.get(VirtualControllerElement.EID_DPAD));
        assertArrayEquals(new int[] { 1590, 315, 150, 150 }, m.get(VirtualControllerElement.EID_A));
        assertArrayEquals(new int[] { 1470, 630, 390, 390 }, m.get(VirtualControllerElement.EID_RS));
        assertArrayEquals(new int[] { 870, 960, 180, 105 }, m.get(VirtualControllerElement.EID_GDB));
    }

    @Test
    public void threeToTwoAndWiderKeepTheClassicLayout() {
        // 3:2, 16:10, 20:9, 21:9, 32:9: nothing shrinks, the right cluster sits against the right edge, only the gap in the middle changes
        int[][] sizes = { { 960, 640 }, { 1620, 1080 }, { 1920, 1200 }, { 2400, 1080 }, { 2520, 1080 }, { 3840, 1080 } };
        for (int[] size : sizes) {
            assertEquals(1f, new OscGeometry.Panel(size[0], size[1]).scale, 0f);
            assertSameLayout(size[0] + "x" + size[1], legacy(size[0], size[1], false, true), layout(size[0], size[1], false, true));
        }
    }

    @Test
    public void squarerThanThreeToTwoShrinksByTheWidth() {
        // scale = width / (1.5 * height): 1:1 is 2/3, 5:4 is 5/6, 4:3 is 8/9, 3:2 and up is 1
        assertEquals(2f / 3f, new OscGeometry.Panel(720, 720).scale, 1e-6f);
        assertEquals(5f / 6f, new OscGeometry.Panel(1280, 1024).scale, 1e-6f);
        assertEquals(8f / 9f, new OscGeometry.Panel(960, 720).scale, 1e-6f);
        assertEquals(1f, new OscGeometry.Panel(960, 640).scale, 0f);
    }

    @Test
    public void everyElementIsOnTheScreenOnEveryShape() {
        for (Object[] shape : SHAPES) {
            int w = (Integer) shape[1];
            int h = (Integer) shape[2];
            for (Map.Entry<Integer, int[]> e : layout(w, h, false, true).entrySet()) {
                int[] r = e.getValue();
                String what = shape[0] + " element " + e.getKey() + " " + java.util.Arrays.toString(r);
                assertTrue(what, r[0] >= 0 && r[1] >= 0);
                assertTrue(what, r[0] + r[2] <= w);
                assertTrue(what, r[1] + r[3] <= h);
                assertTrue(what, r[2] > 0 && r[3] > 0);
            }
        }
    }

    @Test
    public void buttonsKeepTheirProportionsOnEveryShape() {
        Map<Integer, int[]> design = layout(1920, 1080, false, true);
        for (Object[] shape : SHAPES) {
            Map<Integer, int[]> m = layout((Integer) shape[1], (Integer) shape[2], false, true);
            for (Integer id : design.keySet()) {
                int[] d = design.get(id);
                int[] r = m.get(id);
                double designRatio = (double) d[2] / d[3];
                double ratio = (double) r[2] / r[3];
                assertEquals(shape[0] + " element " + id + " keeps its proportions", designRatio, ratio, 0.04 * designRatio);
            }
            int[] dpad = m.get(EID_DPAD);
            assertEquals(shape[0] + " d-pad is square", dpad[2], dpad[3]);
            int[] a = m.get(VirtualControllerElement.EID_A);
            assertEquals(shape[0] + " A is square", a[2], a[3]);
        }
    }

    @Test
    public void noShapeAddsAnOverlapTheCircleDesignDoesNotHave() {
        Map<Integer, int[]> design = layout(1920, 1080, false, true);
        List<String> designOverlaps = overlaps(design);
        // By design: L3 and R3 sit in the corners of the D-pad's and the right stick's squares (nothing else)
        assertTrue(designOverlaps.toString(), designOverlaps.size() <= 3);
        for (Object[] shape : SHAPES) {
            int w = (Integer) shape[1];
            int h = (Integer) shape[2];
            if (h > w) {
                continue; // portrait is only kept on the screen; the controls there are as small as that needs
            }
            List<String> found = overlaps(layout(w, h, false, true));
            for (String pair : found) {
                assertTrue(shape[0] + " overlaps " + pair + " which 16:9 does not", designOverlaps.contains(pair));
            }
        }
    }

    @Test
    public void leftControlsStayLeftOfTheRightOnes() {
        for (Object[] shape : SHAPES) {
            int w = (Integer) shape[1];
            int h = (Integer) shape[2];
            if (h > w) {
                continue;
            }
            Map<Integer, int[]> m = layout(w, h, false, true);
            int leftEdge = Math.max(Math.max(right(m, EID_DPAD), right(m, VirtualControllerElement.EID_LS)), right(m, VirtualControllerElement.EID_LB));
            int rightEdge = Math.min(Math.min(m.get(VirtualControllerElement.EID_RS)[0], m.get(VirtualControllerElement.EID_RB)[0]),
                    m.get(VirtualControllerElement.EID_X)[0]);
            assertTrue(shape[0] + " clusters apart: " + leftEdge + " < " + rightEdge, leftEdge < rightEdge);
            int middleLeft = m.get(VirtualControllerElement.EID_BACK)[0];
            int middleRight = right(m, VirtualControllerElement.EID_START);
            // (a pixel or two of rounding)
            assertTrue(shape[0] + " Back/Start between the D-pad and the right stick",
                    middleLeft >= right(m, EID_DPAD) - 2 && middleRight <= m.get(VirtualControllerElement.EID_RS)[0] + 2);
        }
    }

    @Test
    public void theMiddleRowStaysCentered() {
        for (Object[] shape : SHAPES) {
            int w = (Integer) shape[1];
            int h = (Integer) shape[2];
            Map<Integer, int[]> m = layout(w, h, false, true);
            int[] guide = m.get(VirtualControllerElement.EID_GDB);
            assertEquals(shape[0] + " guide centered", w / 2.0, guide[0] + guide[2] / 2.0, 1.5);
        }
    }

    private static int right(Map<Integer, int[]> m, int id) {
        return m.get(id)[0] + m.get(id)[2];
    }

    /** The pairs of elements whose rectangles (shrunk by one pixel, the grid's rounding) overlap, as "a-b" with a < b. */
    private static List<String> overlaps(Map<Integer, int[]> m) {
        List<String> out = new ArrayList<>();
        List<Integer> ids = new ArrayList<>(m.keySet());
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                int[] a = m.get(ids.get(i));
                int[] b = m.get(ids.get(j));
                boolean x = a[0] + 1 < b[0] + b[2] - 1 && b[0] + 1 < a[0] + a[2] - 1;
                boolean y = a[1] + 1 < b[1] + b[3] - 1 && b[1] + 1 < a[1] + a[3] - 1;
                if (x && y) {
                    int lo = Math.min(ids.get(i), ids.get(j));
                    int hi = Math.max(ids.get(i), ids.get(j));
                    out.add(lo + "-" + hi);
                }
            }
        }
        return out;
    }

    // --- saved layouts

    @Test
    public void aSavedLayoutOnTheSameScreenIsKept() {
        assertArrayEquals(new int[] { 100, 200, 150, 150 }, OscGeometry.mapSaved(100, 200, 150, 150, 1920, 1080, 1920, 1080));
    }

    @Test
    public void aLayoutSavedBeforeTheScreenWasStoredIsKeptWhenItFits() {
        assertArrayEquals(new int[] { 1500, 300, 150, 150 }, OscGeometry.mapSaved(1500, 300, 150, 150, 0, 0, 1920, 1080));
    }

    @Test
    public void aLayoutSavedBeforeTheScreenWasStoredIsPulledOntoASmallerScreenWhenItsCenterIsOffIt() {
        // A right-hand button at x 1700 on a 960 wide screen used to be off it
        int[] r = OscGeometry.mapSaved(1700, 900, 150, 150, 0, 0, 960, 720);
        assertArrayEquals(new int[] { 810, 570, 150, 150 }, r);
        // one pushed half over the edge of the screen it was saved on stays so (its center is on the screen)
        assertArrayEquals(new int[] { 880, 300, 150, 150 }, OscGeometry.mapSaved(880, 300, 150, 150, 0, 0, 960, 720));
        // one whose center is off the screen is moved onto it
        assertArrayEquals(new int[] { 810, 570, 150, 150 }, OscGeometry.mapSaved(1000, 800, 150, 150, 0, 0, 960, 720));
    }

    @Test
    public void aLayoutSavedAtAnotherResolutionOfTheSameShapeScales() {
        assertArrayEquals(new int[] { 30, 307, 225, 225 }, OscGeometry.mapSaved(60, 615, 450, 450, 1920, 1080, 960, 540));
        assertArrayEquals(new int[] { 3180, 630, 300, 300 }, OscGeometry.mapSaved(1590, 315, 150, 150, 1920, 1080, 3840, 2160));
    }

    @Test
    public void aLayoutSavedOn16by9KeepsTheRightControlsOnTheRightEdgeOf21by9() {
        // The right edge of A is 1740 on 1920x1080: 180 from the edge
        int[] a = OscGeometry.mapSaved(1590, 315, 150, 150, 1920, 1080, 2520, 1080);
        assertEquals(2520 - 180, a[0] + a[2]);
        assertEquals(315, a[1]);
        assertEquals(150, a[2]);
        // the left control stays 60 from the left edge
        assertEquals(60, OscGeometry.mapSaved(60, 615, 450, 450, 1920, 1080, 2520, 1080)[0]);
        // and the middle one stays in the middle
        int[] guide = OscGeometry.mapSaved(870, 960, 180, 105, 1920, 1080, 2520, 1080);
        assertEquals(1260, guide[0] + guide[2] / 2);
    }

    @Test
    public void aLayoutSavedOn16by9FitsAndKeepsTheProportionsOnSquarerScreens() {
        Map<Integer, int[]> saved = layout(1920, 1080, false, true);
        for (Object[] shape : SHAPES) {
            int w = (Integer) shape[1];
            int h = (Integer) shape[2];
            float k = Math.min((float) w / 1920, (float) h / 1080);
            for (Map.Entry<Integer, int[]> e : saved.entrySet()) {
                int[] s = e.getValue();
                int[] r = OscGeometry.mapSaved(s[0], s[1], s[2], s[3], 1920, 1080, w, h);
                String what = shape[0] + " element " + e.getKey();
                assertTrue(what, r[0] >= 0 && r[1] >= 0 && r[0] + r[2] <= w && r[1] + r[3] <= h);
                assertEquals(what + " width", s[2] * k, r[2], 1.0);
                assertEquals(what + " height", s[3] * k, r[3], 1.0);
            }
            // The corner clusters stay in their corners
            int[] dpad = saved.get(EID_DPAD);
            int[] mappedDpad = OscGeometry.mapSaved(dpad[0], dpad[1], dpad[2], dpad[3], 1920, 1080, w, h);
            assertEquals(shape[0] + " d-pad left", Math.round(dpad[0] * k), mappedDpad[0]);
            int[] rs = saved.get(VirtualControllerElement.EID_RS);
            int[] mappedRs = OscGeometry.mapSaved(rs[0], rs[1], rs[2], rs[3], 1920, 1080, w, h);
            assertEquals(shape[0] + " right stick keeps its distance to the right edge",
                    Math.round((1920 - rs[0] - rs[2]) * k), w - mappedRs[0] - mappedRs[2], 1);
        }
    }

    @Test
    public void clampKeepsARectangleOnTheScreen() {
        assertArrayEquals(new int[] { 0, 0, 10, 10 }, OscGeometry.clamp(-5, -5, 10, 10, 100, 50));
        assertArrayEquals(new int[] { 90, 40, 10, 10 }, OscGeometry.clamp(200, 200, 10, 10, 100, 50));
        assertArrayEquals(new int[] { 0, 0, 100, 50 }, OscGeometry.clamp(30, 30, 300, 300, 100, 50));
    }
}
