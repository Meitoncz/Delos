package com.limelight.utils;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.Vibrator;
import android.os.VibratorManager;

/** What this device's vibrator can do; used by the Debug laboratory and for preset availability. */
public final class RumbleCapabilities {
    private static RumbleCapabilities cached;

    public final int vibratorCount;
    public final boolean amplitudeControl;

    public RumbleCapabilities(int vibratorCount, boolean amplitudeControl) {
        this.vibratorCount = vibratorCount;
        this.amplitudeControl = amplitudeControl;
    }

    /** Why a technique cannot run on a device; the screens turn it into a sentence (see {@code RumbleText.kt}). */
    public enum Unavailable {
        /** The vibrator has no amplitude control, which the pulse technique needs. */
        NO_AMPLITUDE_CONTROL
    }

    /** Why [technique] cannot run here, or null when it can. */
    public Unavailable unavailableReason(RumbleTechnique technique) {
        return unavailableReason(technique, amplitudeControl);
    }

    /** Why [technique] cannot run on a vibrator with or without amplitude control, or null when it can. */
    static Unavailable unavailableReason(RumbleTechnique technique, boolean amplitudeControl) {
        return technique.isPulse() && !amplitudeControl ? Unavailable.NO_AMPLITUDE_CONTROL : null;
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
