package com.limelight.utils;

/**
 * What the stream shows while it connects: the classic {@link SpinnerDialog}, or the loader of the new
 * Delos UI ({@code com.limelight.shell.StreamLoader}) when the stream was started from it.
 */
public interface ConnectionProgress {
    /** Shows the current connection stage. May be called from any thread. */
    void setMessage(String message);

    /** Hides the progress once the stream runs (or failed). */
    void dismiss();
}
