package com.limelight.binding.input.virtual_controller;

/**
 * The geometry of the on-screen controls on any screen shape. Pure (pixels in, pixels out) so that
 * {@code OscGeometryTest} can check every shape from 1:1 to 21:9.
 *
 * <p>The default controls are drawn on a grid of 128 x 72 units, which is a 16:9 screen: one unit is
 * {@code height / 72} pixels. A screen at least as wide as 3:2 (108 units) takes that grid as it is, the left
 * controls against the left edge, the right ones against the right edge and the middle ones centered, so a wider
 * screen (16:10, 21:9) only leaves more room in the middle. A screen that is squarer than 3:2 (4:3, 5:4, 1:1) has
 * less width than the clusters need side by side, so the whole layout shrinks by the same factor (the buttons
 * keep their proportions) until the clusters, and the Back / Guide / Start row between them, fit; the controls on
 * the upper half keep to the top edge, those on the lower half to the bottom edge.
 *
 * <p>The layouts a user saved are absolute pixels of the screen they were saved on; {@link #mapSaved} carries
 * them to the screen they are loaded on.
 */
public final class OscGeometry {
    public static final int GRID_WIDTH = 128;
    public static final int GRID_HEIGHT = 72;

    /**
     * Width in grid units that the left cluster (34), the right cluster (30) and the Back / Guide / Start row (40)
     * need side by side; narrower than this the layout shrinks.
     */
    static final int FIT_WIDTH_UNITS = 108;

    private OscGeometry() {
    }

    /** The default layout's measures on one screen. */
    public static final class Panel {
        public final int width;
        public final int height;
        /** 1 from 3:2 up; below that, the factor the layout is shrunk by (width / 108 units). */
        public final float scale;
        private final float unit;
        private final int rightDisplacement;

        public Panel(int width, int height) {
            this.width = width;
            this.height = height;
            // 2 * width >= 3 * height is "108 units wide or more", without a float comparison
            this.scale = 2L * width >= 3L * height ? 1f : 2f * width / (3f * height);
            this.unit = ((float) height / (float) GRID_HEIGHT) * scale;
            this.rightDisplacement = width - height * 16 / 9;
        }

        /** {@code units} grid units in pixels. */
        public int size(int units) {
            return (int) (unit * (float) units);
        }

        /** The x of an element whose left edge is {@code units} from the left edge of the grid, kept to the left edge of the screen. */
        public int left(int units) {
            return size(units);
        }

        /**
         * The x of an element {@code widthUnits} wide whose left edge is at {@code units} on the grid, kept to the right edge of
         * the screen: its distance to that edge is the grid's. At 3:2 and wider this is the classic "displace the right-hand
         * controls by what the screen is wider than 16:9" (negative below 16:9, which is fine down to 3:2, where nothing overlaps yet).
         */
        public int right(int units, int widthUnits) {
            if (scale == 1f) {
                return size(units) + rightDisplacement;
            }
            return width - size(GRID_WIDTH - units - widthUnits) - size(widthUnits);
        }

        /** The y of an element whose top edge is {@code units} from the top of the grid, kept to the top edge of the screen. */
        public int top(int units) {
            return size(units);
        }

        /** The y of an element {@code heightUnits} high whose top edge is at {@code units} on the grid, kept to the bottom edge of the screen. */
        public int bottom(int units, int heightUnits) {
            if (scale == 1f) {
                return size(units);
            }
            return height - size(GRID_HEIGHT - units - heightUnits) - size(heightUnits);
        }

        /** The y of a horizontal line {@code units} down the grid, kept to the bottom edge of the screen (the lower half's controls). */
        public int bottomLine(int units) {
            return scale == 1f ? size(units) : height - size(GRID_HEIGHT - units);
        }

        public int centerX() {
            return width / 2;
        }
    }

    /**
     * A saved element's rectangle {left, top, width, height} on the screen it is loaded on.
     *
     * <p>Saved on a screen of {@code refWidth} x {@code refHeight} pixels. When that is the screen it is loaded on, the rectangle is
     * kept as it is. When it is unknown (layouts saved before the screen was stored, or imported from a file; {@code refWidth} or
     * {@code refHeight} at or under 0) it is kept too, unless its center is off the screen, then it is moved onto it. Otherwise sizes scale by the one factor that makes the old screen fit the new one ({@code min(width / refWidth, height / refHeight)},
     * so a button stays as round as it was), and the position keeps the element's distance to the edge it was nearest to: the
     * left or right edge for an element in the outer thirds of the old width, the top or bottom edge for one in the outer thirds
     * of the old height, the middle otherwise (a layout made on 16:9 and loaded on 21:9 keeps the right-hand controls on the
     * right edge instead of mid-screen). A rectangle that was mapped is always on the screen.
     */
    public static int[] mapSaved(int left, int top, int width, int height, int refWidth, int refHeight,
                                 int screenWidth, int screenHeight) {
        int newLeft = left;
        int newTop = top;
        int newWidth = width;
        int newHeight = height;
        if (refWidth > 0 && refHeight > 0) {
            if (refWidth == screenWidth && refHeight == screenHeight) {
                return new int[] { left, top, width, height };
            }
            float k = Math.min((float) screenWidth / refWidth, (float) screenHeight / refHeight);
            newWidth = Math.max(1, Math.round(width * k));
            newHeight = Math.max(1, Math.round(height * k));
            newLeft = place(left, width, refWidth, newWidth, screenWidth, k);
            newTop = place(top, height, refHeight, newHeight, screenHeight, k);
            return clamp(newLeft, newTop, newWidth, newHeight, screenWidth, screenHeight);
        }
        // Unknown screen: kept as saved (a control pushed half over an edge on this very screen stays so), unless its center is off the screen
        float centerX = left + width / 2f;
        float centerY = top + height / 2f;
        if (centerX >= 0 && centerX <= screenWidth && centerY >= 0 && centerY <= screenHeight) {
            return new int[] { left, top, width, height };
        }
        return clamp(left, top, width, height, screenWidth, screenHeight);
    }

    /** One axis of {@link #mapSaved}: the new start of a span of {@code size} at {@code start} on an axis of {@code refLength}. */
    private static int place(int start, int size, int refLength, int newSize, int length, float k) {
        float center = start + size / 2f;
        if (center < refLength / 3f) {
            return Math.round(start * k);
        }
        if (center > refLength * 2f / 3f) {
            return length - Math.round((refLength - start - size) * k) - newSize;
        }
        return Math.round(length / 2f + (center - refLength / 2f) * k - newSize / 2f);
    }

    /** The rectangle moved (and, when larger than the screen, shrunk) onto the screen. */
    public static int[] clamp(int left, int top, int width, int height, int screenWidth, int screenHeight) {
        int w = Math.max(1, Math.min(width, screenWidth));
        int h = Math.max(1, Math.min(height, screenHeight));
        int l = Math.max(0, Math.min(left, screenWidth - w));
        int t = Math.max(0, Math.min(top, screenHeight - h));
        return new int[] { l, t, w, h };
    }
}
