package com.limelight.utils;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/** TEMPORARY (#66): what this device's vibrator can do, for the laboratory and for preset availability. */
public final class RumbleCapabilities {
    private static RumbleCapabilities cached;

    public final int vibratorCount;
    public final boolean amplitudeControl;
    public final boolean thud;
    public final boolean lowTick;
    /** Names of all supported primitives among the eight the laboratory knows, e.g. "CLICK, THUD". */
    public final String supportedPrimitives;

    public RumbleCapabilities(int vibratorCount, boolean amplitudeControl, boolean thud, boolean lowTick,
                              String supportedPrimitives) {
        this.vibratorCount = vibratorCount;
        this.amplitudeControl = amplitudeControl;
        this.thud = thud;
        this.lowTick = lowTick;
        this.supportedPrimitives = supportedPrimitives;
    }

    /** Why [technique] cannot run here, or null when it can. */
    public String unavailableReason(RumbleTechnique technique) {
        return reason(technique, amplitudeControl, thud, lowTick);
    }

    /** Why [technique] cannot run on [vibrator], or null when it can. */
    static String unavailableReason(RumbleTechnique technique, Vibrator vibrator) {
        return reason(technique, vibrator.hasAmplitudeControl(),
                supportsPrimitive(vibrator, RumbleTechnique.PRIMITIVE_THUD),
                supportsPrimitive(vibrator, RumbleTechnique.PRIMITIVE_LOW_TICK));
    }

    private static String reason(RumbleTechnique technique, boolean amplitudeControl, boolean thud, boolean lowTick) {
        switch (technique) {
            case PRIMITIVE_THUD:
                return thud ? null : "This vibrator has no THUD primitive";
            case PRIMITIVE_LOW_TICK:
                return lowTick ? null : "This vibrator has no LOW_TICK primitive";
            case PULSE_25:
            case PULSE_40:
            case PULSE_60:
                return amplitudeControl ? null : "This vibrator has no amplitude control";
            default:
                return null;
        }
    }

    public static synchronized RumbleCapabilities get(Context context) {
        if (cached == null) {
            cached = probe(context.getApplicationContext());
        }
        return cached;
    }

    private static RumbleCapabilities probe(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return new RumbleCapabilities(1, false, false, false, "none (needs Android 12)");
        }
        return probeS(context);
    }

    @TargetApi(31)
    private static RumbleCapabilities probeS(Context context) {
        VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
        Vibrator vibrator = manager == null ? null : manager.getDefaultVibrator();
        int count = manager == null ? 0 : manager.getVibratorIds().length;
        if (vibrator == null) {
            return new RumbleCapabilities(count, false, false, false, "none");
        }
        int[] ids = PRIMITIVE_IDS;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            if (vibrator.areAllPrimitivesSupported(ids[i])) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(PRIMITIVE_NAMES[i]);
            }
        }
        return new RumbleCapabilities(count, vibrator.hasAmplitudeControl(),
                supportsPrimitive(vibrator, RumbleTechnique.PRIMITIVE_THUD),
                supportsPrimitive(vibrator, RumbleTechnique.PRIMITIVE_LOW_TICK),
                names.length() == 0 ? "none" : names.toString());
    }

    private static final String[] PRIMITIVE_NAMES = {"CLICK", "THUD", "SPIN", "QUICK_RISE", "SLOW_RISE",
            "QUICK_FALL", "TICK", "LOW_TICK"};
    private static final int[] PRIMITIVE_IDS = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ? new int[0] : primitiveIds();

    @TargetApi(31)
    private static int[] primitiveIds() {
        return new int[] {VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_THUD,
                VibrationEffect.Composition.PRIMITIVE_SPIN, VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
                VibrationEffect.Composition.PRIMITIVE_TICK, VibrationEffect.Composition.PRIMITIVE_LOW_TICK};
    }

    /** The composition primitive of a primitive technique; only valid on Android 12+. */
    @TargetApi(31)
    static int primitiveId(RumbleTechnique technique) {
        return technique == RumbleTechnique.PRIMITIVE_THUD ? VibrationEffect.Composition.PRIMITIVE_THUD
                : VibrationEffect.Composition.PRIMITIVE_LOW_TICK;
    }

    /** True when [vibrator] supports the primitive of a primitive technique. */
    static boolean supportsPrimitive(Vibrator vibrator, RumbleTechnique technique) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && supportsPrimitiveS(vibrator, technique);
    }

    @TargetApi(31)
    private static boolean supportsPrimitiveS(Vibrator vibrator, RumbleTechnique technique) {
        return vibrator.areAllPrimitivesSupported(primitiveId(technique));
    }
}
