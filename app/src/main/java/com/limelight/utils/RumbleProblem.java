package com.limelight.utils;

/**
 * Why a running rumble could not use the technique of its preset, so plain amplitude is used instead. A typed value:
 * the screens turn it into a sentence in the language of the app (see {@code RumbleText.kt}).
 */
public final class RumbleProblem {
    public enum Kind {
        /** The vibrator cannot vary its amplitude, which the technique needs. */
        NO_AMPLITUDE_CONTROL,
        /** The device has several vibrators (a manager output), and the technique runs on a single one only. */
        SINGLE_VIBRATOR_ONLY,
        /** The technique threw while it ran; {@link #detail} is the exception text. */
        FAILED
    }

    public final Kind kind;
    public final RumbleTechnique technique;
    /** The exception text of {@link Kind#FAILED}; developer text that stays as it is. Null for the other kinds. */
    public final String detail;

    private RumbleProblem(Kind kind, RumbleTechnique technique, String detail) {
        this.kind = kind;
        this.technique = technique;
        this.detail = detail;
    }

    public static RumbleProblem noAmplitudeControl(RumbleTechnique technique) {
        return new RumbleProblem(Kind.NO_AMPLITUDE_CONTROL, technique, null);
    }

    public static RumbleProblem singleVibratorOnly(RumbleTechnique technique) {
        return new RumbleProblem(Kind.SINGLE_VIBRATOR_ONLY, technique, null);
    }

    public static RumbleProblem failed(RumbleTechnique technique, Throwable error) {
        return new RumbleProblem(Kind.FAILED, technique, String.valueOf(error));
    }
}
