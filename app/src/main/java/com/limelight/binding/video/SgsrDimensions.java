package com.limelight.binding.video;

/** Pure sizing policy shared by the SGSR renderer and its tests. */
final class SgsrDimensions {
    static final int MAX_INTERNAL_WIDTH = 3840;
    static final int MAX_INTERNAL_HEIGHT = 3840;
    static final long MAX_INTERNAL_PIXELS = 3840L * 2160L;
    static final int NATIVE_TOLERANCE = 2;

    static final class Plan {
        final int presentationWidth;
        final int presentationHeight;
        final int internalWidth;
        final int internalHeight;

        Plan(int presentationWidth, int presentationHeight, int internalWidth, int internalHeight) {
            this.presentationWidth = presentationWidth;
            this.presentationHeight = presentationHeight;
            this.internalWidth = internalWidth;
            this.internalHeight = internalHeight;
        }

        boolean isSupersampling() {
            return internalWidth > presentationWidth && internalHeight > presentationHeight;
        }
    }

    private SgsrDimensions() {}

    static Plan plan(int sourceWidth, int sourceHeight, int presentationWidth, int presentationHeight,
                     int maxTextureSize, int supersamplingLevel) {
        int outputWidth = even(Math.max(2, presentationWidth));
        int outputHeight = even(Math.max(2, presentationHeight));
        boolean nativeInput = Math.abs(sourceWidth - presentationWidth) <= NATIVE_TOLERANCE &&
                Math.abs(sourceHeight - presentationHeight) <= NATIVE_TOLERANCE;
        int numerator = supersamplingLevel >= 2 ? 2 : 4;
        int denominator = supersamplingLevel >= 2 ? 1 : 3;
        int supersampledWidth = even(outputWidth * numerator / denominator);
        int supersampledHeight = even(outputHeight * numerator / denominator);
        boolean fits = supersampledWidth <= maxTextureSize && supersampledHeight <= maxTextureSize &&
                supersampledWidth <= MAX_INTERNAL_WIDTH && supersampledHeight <= MAX_INTERNAL_HEIGHT &&
                (long) supersampledWidth * supersampledHeight <= MAX_INTERNAL_PIXELS;
        return nativeInput && fits
                ? new Plan(outputWidth, outputHeight, supersampledWidth, supersampledHeight)
                : new Plan(outputWidth, outputHeight, outputWidth, outputHeight);
    }

    static boolean isNativeInput(int sourceWidth, int sourceHeight, int presentationWidth, int presentationHeight) {
        return Math.abs(sourceWidth - presentationWidth) <= NATIVE_TOLERANCE &&
                Math.abs(sourceHeight - presentationHeight) <= NATIVE_TOLERANCE;
    }

    private static int even(int value) {
        return value & ~1;
    }
}
