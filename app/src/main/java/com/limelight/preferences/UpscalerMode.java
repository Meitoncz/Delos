package com.limelight.preferences;

/**
 * The modes of the upscaler (setting {@code list_sgsr_quality}). One definition for the stored value, the
 * stream menu's Mode row and the renderers: the declaration order is the stream menu's row order and the
 * {@link #index()} the renderers' mode number, so new modes are only ever appended.
 */
public enum UpscalerMode {
    STANDARD("standard", "SGSR 1 Standard"),
    HIGH("high", "SGSR 1 High Quality"),
    VIDEO("temporal", "SGSR 2 Video"),
    VIDEO_QUALITY("temporal-quality", "SGSR 2 Video Quality"),
    /** No scaling: the stream keeps its resolution and only a contrast-adaptive sharpen pass runs. */
    SHARPEN_ONLY("sharpen", "Sharpen only");

    private final String value;
    private final String label;

    UpscalerMode(String value, String label) {
        this.value = value;
        this.label = label;
    }

    /** The string stored in the settings. */
    public String value() { return value; }

    /** The name the stream menu shows. */
    public String label() { return label; }

    /** The position in the stream menu's Mode row; also the renderers' mode number. */
    public int index() { return ordinal(); }

    /** SGSR 2 Video modes keep a history of earlier frames. */
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
