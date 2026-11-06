package com.limelight.utils;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.Vibrator;
import android.os.VibratorManager;

/** TEMPORARY (#66): what this device's vibrator can do, for the laboratory and for preset availability. */
public final class RumbleCapabilities {
    private static RumbleCapabilities cached;

    public final int vibratorCount;
    public final boolean amplitudeControl;

    public RumbleCapabilities(int vibratorCount, boolean amplitudeControl) {
        this.vibratorCount = vibratorCount;
        this.amplitudeControl = amplitudeControl;
    }

    /** Why [technique] cannot run here, or null when it can. */
    public String unavailableReason(RumbleTechnique technique) {
        return unavailableReason(technique, amplitudeControl);
    }

    /** Why [technique] cannot run on a vibrator with or without amplitude control, or null when it can. */
    static String unavailableReason(RumbleTechnique technique, boolean amplitudeControl) {
        return technique.isPulse() && !amplitudeControl ? "This vibrator has no amplitude control" : null;
    }

    public static synchronized RumbleCapabilities get(Context context) {
        if (cached == null) {
            cached = probe(context.getApplicationContext());
        }
        return cached;
    }

    private static RumbleCapabilities probe(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return new RumbleCapabilities(1, false);
        }
        return probeS(context);
    }

    @TargetApi(31)
    private static RumbleCapabilities probeS(Context context) {
        VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
        Vibrator vibrator = manager == null ? null : manager.getDefaultVibrator();
        int count = manager == null ? 0 : manager.getVibratorIds().length;
        return new RumbleCapabilities(count, vibrator != null && vibrator.hasAmplitudeControl());
    }
}
