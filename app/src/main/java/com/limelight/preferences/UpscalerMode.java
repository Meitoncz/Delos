package com.limelight.preferences;

import androidx.annotation.StringRes;

import com.limelight.R;

/**
 * The modes of the upscaler (setting {@code list_sgsr_quality}). One definition for the stored value, the
 * stream menu's Mode row and the renderers: the declaration order is the stream menu's row order and the
 * {@link #index()} the renderers' mode number, so new modes are only ever appended.
 */
public enum UpscalerMode {
    STANDARD("standard", R.string.sgsr_quality_standard),
    HIGH("high", R.string.sgsr_quality_high),
    VIDEO("temporal", R.string.sgsr_quality_temporal),
    VIDEO_QUALITY("temporal-quality", R.string.sgsr_quality_temporal_quality),
    /** No scaling: the stream keeps its resolution and only a contrast-adaptive sharpen pass runs. */
    SHARPEN_ONLY("sharpen", R.string.sgsr_quality_sharpen);

    private final String value;
    private final int labelRes;

    UpscalerMode(String value, @StringRes int labelRes) {
        this.value = value;
        this.labelRes = labelRes;
    }

    /** The string stored in the settings. */
    public String value() { return value; }

    /** The string resource of the name the stream menu and the Settings list show (the same text in both). */
    @StringRes public int labelRes() { return labelRes; }

    /** The position in the stream menu's Mode row; also the renderers' mode number. */
    public int index() { return ordinal(); }

    /** SGSR2-DV modes keep a history of earlier frames. */
    public boolean isTemporal() { return this == VIDEO || this == VIDEO_QUALITY; }

    /**
     * Whether the mode changes the picture's size (upscaling, or supersampling when the stream already matches
     * the display). Sharpen only does not: the supersampling quality has no meaning for it.
     */
    public boolean scales() { return this != SHARPEN_ONLY; }

    /** The mode of a stored value; anything unknown (an older or newer build's value) is Standard. */
    public static UpscalerMode fromValue(String value) {
        for (UpscalerMode mode : values()) {
            if (mode.value.equals(value)) return mode;
        }
        return STANDARD;
    }

    /** The mode at a stream menu position, clamped into the list. */
    public static UpscalerMode fromIndex(int index) {
        UpscalerMode[] all = values();
        return all[Math.max(0, Math.min(all.length - 1, index))];
    }
}
