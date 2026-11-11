package com.limelight.binding.input.virtual_controller;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the default on-screen controls go on a screen: the numbers of the 128 x 72 grid of
 * {@link VirtualControllerConfigurationLoader}, placed by {@link OscGeometry.Panel}. Pure, so the layout can be checked for every
 * shape. The map is in the order the loader adds the elements (a later element is drawn and touched on top of an earlier one).
 */
public final class OscDefaultLayout {
    // Left and right clusters, triggers: the grid numbers of the classic layout
    static final int TRIGGER_L_BASE_X = 1;
    static final int TRIGGER_R_BASE_X = 92;
    static final int TRIGGER_DISTANCE = 23;
    static final int TRIGGER_BASE_Y = 31;
    static final int TRIGGER_WIDTH = 12;
    static final int TRIGGER_HEIGHT = 9;

    // Face buttons are defined based on the Y button (button number 9)
    static final int BUTTON_BASE_X = 106;
    static final int BUTTON_BASE_Y = 1;
    static final int BUTTON_SIZE = 10;

    static final int DPAD_BASE_X = 4;
    static final int DPAD_BASE_Y = 41;
    static final int DPAD_SIZE = 30;

    static final int ANALOG_L_BASE_X = 6;
    static final int ANALOG_L_BASE_Y = 4;
    static final int ANALOG_R_BASE_X = 98;
    static final int ANALOG_R_BASE_Y = 42;
    static final int ANALOG_SIZE = 26;

    static final int L3_R3_BASE_Y = 60;

    static final int START_BACK_Y = 64;
    static final int START_BACK_WIDTH = 12;
    static final int START_BACK_HEIGHT = 7;

    // Delos: the gap between the middle buttons, and the trackpad above them
    static final int MIDDLE_GAP = 2;
    static final int TRACKPAD_WIDTH = 20;
    static final int TRACKPAD_HEIGHT = 10;
    static final int GUIDE_Y = START_BACK_Y;

    private OscDefaultLayout() {
    }

    /** One element's rectangle in pixels. */
    public static final class Slot {
        public final int left;
        public final int top;
        public final int width;
        public final int height;

        Slot(int left, int top, int width, int height) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }

        public int right() {
            return left + width;
        }

        public int bottom() {
            return top + height;
        }
    }

    /**
     * The slots of the default layout, by element id ({@code VirtualControllerElement.EID_*}), for the buttons the settings turn on:
     * everything but Guide when {@code onlyL3R3} is off, only L3 and R3 when it is on, and Guide when {@code showGuide} is on.
     */
    public static Map<Integer, Slot> compute(OscGeometry.Panel p, boolean onlyL3R3, boolean showGuide) {
        Map<Integer, Slot> slots = new LinkedHashMap<>();

        // Back, Guide and Start in a row centred at the bottom (Guide in the middle, also when it is hidden, so Back and Start
        // stay symmetric), the trackpad centred above them
        int center = p.centerX();
        int middleWidth = p.size(START_BACK_WIDTH);
        int middleGap = p.size(MIDDLE_GAP);
        int guideX = center - middleWidth / 2;
        int backX = guideX - middleGap - middleWidth;
        int startX = guideX + middleWidth + middleGap;
        int trackpadWidth = p.size(TRACKPAD_WIDTH);
        int trackpadHeight = p.size(TRACKPAD_HEIGHT);
        int trackpadY = p.bottomLine(START_BACK_Y - MIDDLE_GAP) - trackpadHeight;

        if (!onlyL3R3) {
            slots.put(VirtualControllerElement.EID_DPAD, new Slot(
                    p.left(DPAD_BASE_X), p.bottom(DPAD_BASE_Y, DPAD_SIZE), p.size(DPAD_SIZE), p.size(DPAD_SIZE)));

            slots.put(VirtualControllerElement.EID_A, face(p, BUTTON_BASE_X, BUTTON_BASE_Y + 2 * BUTTON_SIZE));
            slots.put(VirtualControllerElement.EID_B, face(p, BUTTON_BASE_X + BUTTON_SIZE, BUTTON_BASE_Y + BUTTON_SIZE));
            slots.put(VirtualControllerElement.EID_X, face(p, BUTTON_BASE_X - BUTTON_SIZE, BUTTON_BASE_Y + BUTTON_SIZE));
            slots.put(VirtualControllerElement.EID_Y, face(p, BUTTON_BASE_X, BUTTON_BASE_Y));

            slots.put(VirtualControllerElement.EID_LT, trigger(p, false, TRIGGER_L_BASE_X, TRIGGER_BASE_Y));
            slots.put(VirtualControllerElement.EID_RT, trigger(p, true, TRIGGER_R_BASE_X + TRIGGER_DISTANCE, TRIGGER_BASE_Y));
            slots.put(VirtualControllerElement.EID_LB, trigger(p, false, TRIGGER_L_BASE_X + TRIGGER_DISTANCE, TRIGGER_BASE_Y));
            slots.put(VirtualControllerElement.EID_RB, trigger(p, true, TRIGGER_R_BASE_X, TRIGGER_BASE_Y));

            slots.put(VirtualControllerElement.EID_LS, new Slot(
                    p.left(ANALOG_L_BASE_X), p.top(ANALOG_L_BASE_Y), p.size(ANALOG_SIZE), p.size(ANALOG_SIZE)));
            slots.put(VirtualControllerElement.EID_RS, new Slot(
                    p.right(ANALOG_R_BASE_X, ANALOG_SIZE), p.bottom(ANALOG_R_BASE_Y, ANALOG_SIZE), p.size(ANALOG_SIZE), p.size(ANALOG_SIZE)));

            slots.put(VirtualControllerElement.EID_BACK, new Slot(
                    backX, p.bottom(START_BACK_Y, START_BACK_HEIGHT), p.size(START_BACK_WIDTH), p.size(START_BACK_HEIGHT)));
            slots.put(VirtualControllerElement.EID_START, new Slot(
                    startX, p.bottom(START_BACK_Y, START_BACK_HEIGHT), p.size(START_BACK_WIDTH), p.size(START_BACK_HEIGHT)));

            slots.put(VirtualControllerElement.EID_LSB, shoulderClick(p, false, TRIGGER_L_BASE_X));
            slots.put(VirtualControllerElement.EID_RSB, shoulderClick(p, true, TRIGGER_R_BASE_X + TRIGGER_DISTANCE));

            slots.put(VirtualControllerElement.EID_TOUCHPAD, new Slot(center - trackpadWidth / 2, trackpadY, trackpadWidth, trackpadHeight));
        } else {
            slots.put(VirtualControllerElement.EID_LSB, shoulderClick(p, false, TRIGGER_L_BASE_X));
            slots.put(VirtualControllerElement.EID_RSB, shoulderClick(p, true, TRIGGER_R_BASE_X + TRIGGER_DISTANCE));
        }

        if (showGuide) {
            slots.put(VirtualControllerElement.EID_GDB, new Slot(
                    guideX, p.bottom(GUIDE_Y, START_BACK_HEIGHT), p.size(START_BACK_WIDTH), p.size(START_BACK_HEIGHT)));
        }
        return slots;
    }

    /** A face button, the right cluster against the right edge, the top row against the top. */
    private static Slot face(OscGeometry.Panel p, int x, int y) {
        return new Slot(p.right(x, BUTTON_SIZE), p.top(y), p.size(BUTTON_SIZE), p.size(BUTTON_SIZE));
    }

    /** A trigger or bumper: the upper half, against the top edge. */
    private static Slot trigger(OscGeometry.Panel p, boolean rightSide, int x, int y) {
        int left = rightSide ? p.right(x, TRIGGER_WIDTH) : p.left(x);
        return new Slot(left, p.top(y), p.size(TRIGGER_WIDTH), p.size(TRIGGER_HEIGHT));
    }

    /** L3 or R3: the lower half, against the bottom edge. */
    private static Slot shoulderClick(OscGeometry.Panel p, boolean rightSide, int x) {
        int left = rightSide ? p.right(x, TRIGGER_WIDTH) : p.left(x);
        return new Slot(left, p.bottom(L3_R3_BASE_Y, TRIGGER_HEIGHT), p.size(TRIGGER_WIDTH), p.size(TRIGGER_HEIGHT));
    }
}
