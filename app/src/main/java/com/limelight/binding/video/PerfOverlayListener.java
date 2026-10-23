package com.limelight.binding.video;

public interface PerfOverlayListener {
    void onPerfUpdate(final String text);

    // Delos: the same numbers for the Delos statistics panel
    default void onPerfStats(com.limelight.shell.StreamStats stats) {}
}
