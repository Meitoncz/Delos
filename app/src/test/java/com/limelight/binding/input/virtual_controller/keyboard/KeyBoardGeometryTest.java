package com.limelight.binding.input.virtual_controller.keyboard;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The on-screen key grid on every screen shape of docs/HANDHELD_DEVICES.md, with 16:9 as it was. */
public class KeyBoardGeometryTest {
    private static final int KEYS = 74 + 6; // 67 keys + 7 mouse buttons of keyboard.json, and a few custom keys

    private static final int[][] SHAPES = {
            { 720, 720 }, { 1280, 1024 }, { 1240, 1080 }, { 960, 720 }, { 1280, 960 }, { 960, 640 }, { 1620, 1080 },
            { 1920, 1200 }, { 1920, 1080 }, { 1280, 720 }, { 1334, 750 }, { 2560, 1440 }, { 2400, 1080 }, { 2520, 1080 }, { 3840, 1080 },
    };

    private static int s(int units, int height) {
        return (int) (((float) height / (float) 72) * (float) units);
    }

    /** The arithmetic of KeyBoardControllerConfigurationLoader.createDefaultLayout before the geometry was extracted. */
    private static int[] legacyKey(int i, int width, int height) {
        int buttonSize = 10;
        int w = s(buttonSize, height);
        int maxW = width / 18;
        if (w > maxW) {
            buttonSize = maxW * 72 / height;
            w = s(buttonSize, height);
        }
        double buttonSum = 14.0;
        int lastIndex = (int) (i / buttonSum);
        int x = s(1 + (int) (i % buttonSum) * buttonSize, height);
        int y = s(buttonSize + lastIndex * buttonSize, height);
        return new int[] { x, y, w, w };
    }

    private static int[] legacyDpad(int width, int height) {
        int w = legacyKey(0, width, height)[2];
        int rd = width - height * 16 / 9;
        return new int[] { s(92, height) + rd, s(41, height), (int) (w * 2.5), (int) (w * 2.5) };
    }

    private static int[] legacyStick(int width, int height) {
        int w = legacyKey(0, width, height)[2];
        return new int[] { s(4, height), s(41, height), (int) (w * 2.5), (int) (w * 2.5) };
    }

    @Test
    public void keysAreExactlyTheOldGridOnEveryShape() {
        // The keys, the stick and the D-pad are where they were on every shape of the list: the grid already fit them all (the keys are
        // limited by the width, the D-pad is anchored to the right edge); only the clamp is new, for a screen the grid could not fit
        for (int[] shape : SHAPES) {
            for (int i = 0; i < KEYS; i++) {
                assertArrayEquals(shape[0] + "x" + shape[1] + " key " + i, legacyKey(i, shape[0], shape[1]), KeyBoardGeometry.key(i, shape[0], shape[1]));
            }
            assertArrayEquals(shape[0] + "x" + shape[1] + " stick", legacyStick(shape[0], shape[1]), KeyBoardGeometry.stick(shape[0], shape[1]));
            assertArrayEquals(shape[0] + "x" + shape[1] + " d-pad", legacyDpad(shape[0], shape[1]), KeyBoardGeometry.dpad(shape[0], shape[1]));
        }
    }

    @Test
    public void everyKeyAndTheStickAndDpadAreOnTheScreen() {
        for (int[] shape : SHAPES) {
            int w = shape[0];
            int h = shape[1];
            for (int i = 0; i < KEYS; i++) {
                assertOnScreen(shape, "key " + i, KeyBoardGeometry.key(i, w, h));
            }
            assertOnScreen(shape, "stick", KeyBoardGeometry.stick(w, h));
            assertOnScreen(shape, "d-pad", KeyBoardGeometry.dpad(w, h));
        }
    }

    private static void assertOnScreen(int[] shape, String what, int[] r) {
        String name = shape[0] + "x" + shape[1] + " " + what + " " + java.util.Arrays.toString(r);
        assertTrue(name, r[0] >= 0 && r[1] >= 0 && r[0] + r[2] <= shape[0] && r[1] + r[3] <= shape[1]);
    }

    @Test
    public void aRowOfFourteenKeysFitsTheWidth() {
        for (int[] shape : SHAPES) {
            int[] last = KeyBoardGeometry.key(KeyBoardGeometry.KEYS_PER_ROW - 1, shape[0], shape[1]);
            assertTrue(shape[0] + "x" + shape[1], last[0] + last[2] <= shape[0]);
        }
    }

    @Test
    public void keysAreSquare() {
        for (int[] shape : SHAPES) {
            int[] k = KeyBoardGeometry.key(3, shape[0], shape[1]);
            assertEquals(k[2], k[3]);
            assertEquals(KeyBoardGeometry.keySize(shape[0], shape[1]), k[2]);
        }
    }

    @Test
    public void theStickAndTheDpadDoNotMeetInTheMiddle() {
        for (int[] shape : SHAPES) {
            int[] stick = KeyBoardGeometry.stick(shape[0], shape[1]);
            int[] dpad = KeyBoardGeometry.dpad(shape[0], shape[1]);
            assertTrue(shape[0] + "x" + shape[1], stick[0] + stick[2] <= dpad[0]);
        }
    }

    @Test
    public void sixteenByNineKeysArePinned() {
        // 1920x1080: a key is 7 units of 15 px (an eighteenth of the width is 106 px, 10 units would be 150), and the row starts at 15 px
        assertEquals(7, KeyBoardGeometry.keyUnits(1920, 1080));
        assertArrayEquals(new int[] { 15, 105, 105, 105 }, KeyBoardGeometry.key(0, 1920, 1080));
        assertArrayEquals(new int[] { 15 + 105, 105, 105, 105 }, KeyBoardGeometry.key(1, 1920, 1080));
        assertArrayEquals(new int[] { 15, 210, 105, 105 }, KeyBoardGeometry.key(14, 1920, 1080));
        assertArrayEquals(new int[] { 60, 615, 262, 262 }, KeyBoardGeometry.stick(1920, 1080));
        assertArrayEquals(new int[] { 1380, 615, 262, 262 }, KeyBoardGeometry.dpad(1920, 1080));
    }
}
