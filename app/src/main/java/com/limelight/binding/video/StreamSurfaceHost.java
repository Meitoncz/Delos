package com.limelight.binding.video;

import android.view.View;

import androidx.annotation.Nullable;

/**
 * What the video renderers need from the owner of the stream's surface, so they do not depend on the
 * stream activity itself.
 */
public interface StreamSurfaceHost {
    /** The view the stream is shown in, or null before it exists. */
    @Nullable
    View getStreamSurface();

    /**
     * Lets the surface's buffers follow the layout's size. Called from the decoder's setup thread; it returns once
     * the UI thread has resized the surface (or after a short wait).
     */
    void useLayoutSizedStreamSurface();
}
