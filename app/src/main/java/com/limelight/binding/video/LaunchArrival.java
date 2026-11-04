package com.limelight.binding.video;

import com.limelight.shell.ui.HyperspaceConfig;

/** Pure, allocation-free arrival pose shared by MediaCodec, SGSR and PyroWave. */
public final class LaunchArrival {
    public static final int POSE_FLOATS = 6;
    public static final int SCALE_X = 0;
    public static final int SCALE_Y = 1;
    public static final int SKEW = 2;
    public static final int WARP = 3;
    public static final int BLUR = 4;
    public static final int CHROMATIC = 5;
    public static final int DURATION_MS = HyperspaceConfig.OPENING_MS;
    public static final float SETTLE_START = HyperspaceConfig.STREAM_SETTLE_START;

    private LaunchArrival() { }

    /** Negative/inactive, invalid, and completed progress always produce the exact direct pose. */
    public static void writePose(float opening, float[] target) {
        if (!(opening >= 0f && opening < 1f)) {
            target[SCALE_X] = 1f;
            target[SCALE_Y] = 1f;
            target[SKEW] = 0f;
            target[WARP] = 0f;
            target[BLUR] = 0f;
            target[CHROMATIC] = 0f;
            return;
        }
        float x = unit((opening - SETTLE_START) / (1f - SETTLE_START));
        float end = unit((x - .80f) / .20f);
        float envelope = Math.max(0f, 1f - end * end * end * (end * (end * 6f - 15f) + 10f));
        float residual = (float) (Math.exp(-HyperspaceConfig.STREAM_DECAY * x)
                * (1f + HyperspaceConfig.STREAM_DECAY * x)) * envelope;
        float spring = (float) (Math.exp(-HyperspaceConfig.STREAM_SPRING_DAMPING * x)
                * Math.sin(HyperspaceConfig.STREAM_SPRING_FREQUENCY * x)) * envelope;
        float scale = 1f + (HyperspaceConfig.STREAM_INITIAL_ZOOM - 1f) * residual
                + HyperspaceConfig.STREAM_SPRING_ZOOM * spring;
        float stretch = 1f + HyperspaceConfig.STREAM_SQUISH * spring;
        target[SCALE_X] = scale * stretch;
        target[SCALE_Y] = scale / stretch;
        target[SKEW] = (float) Math.tan(spring * .55 * Math.PI / 180.0);
        target[WARP] = HyperspaceConfig.STREAM_WARP * spring;
        target[BLUR] = HyperspaceConfig.STREAM_BLUR * residual;
        target[CHROMATIC] = (float) (HyperspaceConfig.STREAM_CHROMATIC * Math.sin(Math.PI * x)
                * Math.exp(-1.2 * x)) * envelope;
    }

    /** A requested endpoint is not sufficient: an identity image must actually reach the output. */
    public static boolean canSwitchToDirect(float opening, boolean identityPresented) {
        return Float.isFinite(opening) && opening >= 1f && identityPresented;
    }

    /** Keep the temporary last picture until the new producer has supplied its first direct image. */
    public static boolean canReleaseTemporaryOutput(boolean producerSwitched, boolean directFramePresented) {
        return producerSwitched && directFramePresented;
    }

    private static float unit(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
