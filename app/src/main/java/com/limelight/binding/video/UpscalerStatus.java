package com.limelight.binding.video;

import com.limelight.preferences.UpscalerMode;

/**
 * What the upscaler is doing right now, as the renderer reports it. A typed value: the stream menu turns it into text in
 * the language of the app, and reads {@link #isChanging()} instead of comparing words.
 */
public final class UpscalerStatus {
    public enum State {
        /** Not requested and not running. */
        OFF,
        /** Requested; its output stage is still being built. */
        STARTING,
        /** Removed; its output stage is still being torn down. */
        STOPPING,
        /** Running; {@link #mode} says which method when the renderer knows it. */
        ON,
        /** Requested but impossible on this stream: the picture is drawn directly. */
        UNAVAILABLE
    }

    public static final UpscalerStatus OFF = new UpscalerStatus(State.OFF, null);
    public static final UpscalerStatus STARTING = new UpscalerStatus(State.STARTING, null);
    public static final UpscalerStatus STOPPING = new UpscalerStatus(State.STOPPING, null);
    /** Running with no method named. */
    public static final UpscalerStatus ON = new UpscalerStatus(State.ON, null);
    public static final UpscalerStatus UNAVAILABLE = new UpscalerStatus(State.UNAVAILABLE, null);

    public final State state;
    /** The method that runs, only with {@link State#ON}; null when the renderer does not say. */
    public final UpscalerMode mode;

    private UpscalerStatus(State state, UpscalerMode mode) {
        this.state = state;
        this.mode = mode;
    }

    /** Running with [mode]. */
    public static UpscalerStatus on(UpscalerMode mode) {
        return mode == null ? ON : new UpscalerStatus(State.ON, mode);
    }

    /** Starting or stopping: the output stage is being rebuilt, so the on/off switch waits. */
    public boolean isChanging() {
        return state == State.STARTING || state == State.STOPPING;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UpscalerStatus && ((UpscalerStatus) other).state == state && ((UpscalerStatus) other).mode == mode;
    }

    @Override
    public int hashCode() {
        return state.hashCode() * 31 + (mode == null ? 0 : mode.hashCode());
    }

    @Override
    public String toString() {
        return mode == null ? state.name() : state.name() + ":" + mode.name();
    }
}
