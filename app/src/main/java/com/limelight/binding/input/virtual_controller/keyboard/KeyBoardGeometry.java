package com.limelight.binding.input.virtual_controller.keyboard;

import com.limelight.binding.input.virtual_controller.OscGeometry;

/**
 * Where the keys of the on-screen key grid (the editable {@link KeyBoardController}, not the Full keyboard) go on a screen. Pure, so
 * {@code KeyBoardGeometryTest} can check every screen shape. The default layout is specified on the same grid of 128 x 72 units
 * (a 16:9 screen) as the on-screen controls: a key is 10 units, but never more than an eighteenth of the screen's width, so the row of
 * 14 keys fits any shape; the stick is kept against the left edge and the D-pad against the right one, both on the screen.
 */
public final class KeyBoardGeometry {
    /** Keys per row of the default layout. */
    public static final int KEYS_PER_ROW = 14;

    private static final int KEY_UNITS = 10;
    private static final int KEY_ROW_LEFT_UNITS = 1;
    private static final int PAD_X_UNITS = 92;
    private static final int STICK_X_UNITS = 4;
    private static final int PAD_Y_UNITS = 41;

    private KeyBoardGeometry() {
    }

    /** The default controls are specified using a grid of 128*72 cells at 16:9. */
    public static int screenScale(int units, int height) {
        return (int) (((float) height / (float) 72) * (float) units);
    }

    public static int screenScaleSwitch(int result, int height) {
        return result * 72 / height;
    }

    /** A key's size in grid units on a screen: 10, less when the row of {@link #KEYS_PER_ROW} would not fit the width. */
    public static int keyUnits(int width, int height) {
        int units = KEY_UNITS;
        int maxW = width / 18;
        if (screenScale(units, height) > maxW) {
            units = screenScaleSwitch(maxW, height);
        }
        return units;
    }

    /** A key's side in pixels. */
    public static int keySize(int width, int height) {
        return screenScale(keyUnits(width, height), height);
    }

    /** The key at {@code index} of the default layout, rows of {@link #KEYS_PER_ROW} below the top row: {left, top, width, height}. */
    public static int[] key(int index, int width, int height) {
        int units = keyUnits(width, height);
        int size = screenScale(units, height);
        int column = index % KEYS_PER_ROW;
        int row = index / KEYS_PER_ROW;
        return new int[] {
                screenScale(KEY_ROW_LEFT_UNITS + column * units, height),
                screenScale(units + row * units, height),
                size, size
        };
    }

    /** The D-pad: {left, top, width, height}, 2.5 keys wide against the right edge of 16:9 (kept on a narrower screen). */
    public static int[] dpad(int width, int height) {
        int rightDisplacement = width - height * 16 / 9;
        int size = (int) (keySize(width, height) * 2.5);
        return OscGeometry.clamp(screenScale(PAD_X_UNITS, height) + rightDisplacement, screenScale(PAD_Y_UNITS, height), size, size, width, height);
    }

    /** The stick: {left, top, width, height}, 2.5 keys wide against the left edge. */
    public static int[] stick(int width, int height) {
        int size = (int) (keySize(width, height) * 2.5);
        return OscGeometry.clamp(screenScale(STICK_X_UNITS, height), screenScale(PAD_Y_UNITS, height), size, size, width, height);
    }
}
