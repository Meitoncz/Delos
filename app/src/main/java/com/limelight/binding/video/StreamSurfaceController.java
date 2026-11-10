package com.limelight.binding.video;

import android.os.Build;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.limelight.LimeLog;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.UpscalerMode;
import com.limelight.shell.link.LinkMonitor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * The stream's surface and the live upscaler controls: the SurfaceView's buffer size for the upscaler, the
 * surface callbacks' decoder side (render target, stop on loss), the stream menu's upscaler and supersampling
 * controls and the launch arrival's link to the decoder. {@code DelosStreamActivity} forwards the {@code StreamContainer}
 * callbacks and owns the connection, which the controller starts and stops through {@link Host}.
 */
public final class StreamSurfaceController implements StreamSurfaceHost {
    /** How long the decoder's setup thread waits for the UI thread to resize the surface. */
    static final long RESIZE_WAIT_MS = 1000;

    /** What the controller needs from the stream activity. */
    public interface Host {
        /** The stream's SurfaceView, null before the stream container exists. */
        @Nullable
        SurfaceView streamSurfaceView();

        /** The stream container's currently active surface, the decoder's render target. */
        Surface activeSurface();

        /** The video size the activity renders, used when the preferences carry no stream size. */
        int displayWidth();

        int displayHeight();

        boolean isConnected();

        /** Starts the connection; the decoder already has its render target. */
        void startConnection();

        void stopConnection();
    }

    /** What a live upscaler change does to an 8-bit surface's buffer size. */
    enum Resize {
        /** 10-bit output has its own layer, so the surface keeps its size. */
        NONE,
        /** A scaling mode draws into window-sized buffers. */
        LAYOUT,
        /** Sharpen only, and the decoder without an upscaler, draw at the stream's own size. */
        STREAM
    }

    private final Executor uiThread;
    private final PreferenceConfiguration prefConfig;
    private final Host host;
    private MediaCodecDecoderRenderer decoder;
    private boolean surfaceCreated = false;
    private boolean attemptedConnection = false;

    /** {@code uiThread} runs a task on the UI thread, at once when called on it (Activity.runOnUiThread). */
    public StreamSurfaceController(Executor uiThread, PreferenceConfiguration prefConfig, Host host) {
        this.uiThread = uiThread;
        this.prefConfig = prefConfig;
        this.host = host;
    }

    /** The decoder the controls apply to; until it is set the launch arrival reads as not started. */
    public void setDecoder(MediaCodecDecoderRenderer decoder) {
        this.decoder = decoder;
    }

    /** SDR SGSR in a scaling mode owns the window buffers from the start; everything else starts at the stream's size. */
    static boolean layoutSizedAtStart(boolean upscalerEnabled, UpscalerMode mode, boolean hdrRequested) {
        return upscalerEnabled && mode.scales() && !hdrRequested;
    }

    static Resize resizeForUpscaler(int activeVideoFormat, boolean enabled, UpscalerMode mode) {
        if ((activeVideoFormat & MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0) return Resize.NONE;
        return enabled && mode.scales() ? Resize.LAYOUT : Resize.STREAM;
    }

    /** The stream's own size as the SurfaceView's buffer size, or the display's when the preferences have none. */
    static int streamSurfaceSize(int configured, int display) {
        return configured > 0 ? configured : display;
    }

    /** Fixed stream surface size and z-order (the frame rate is set on the Surface by the decoder). */
    public void configureStreamSurface(View root) {
        try {
            // No getIdentifier(): find the first SurfaceView in the layout
            SurfaceView streamSurfaceView = findFirstSurfaceViewFrom(root);

            if (streamSurfaceView != null) {
                // Avoid resizes/glitches that break the compositor
                int vw = streamSurfaceWidth();
                int vh = streamSurfaceHeight();
                try {
                    // SDR SGSR owns the window buffers. HDR SGSR uses a display-sized child layer,
                    // so keep its parent's source-sized coordinate space stable across live toggles.
                    // Sharpen only never enlarges anything: its window buffers have the stream's size.
                    if (layoutSizedAtStart(prefConfig.sgsrEnabled, prefConfig.sgsrMode, prefConfig.enableHdr))
                        streamSurfaceView.getHolder().setSizeFromLayout();
                    else streamSurfaceView.getHolder().setFixedSize(vw, vh);
                } catch (Throwable t) { LimeLog.warning("SurfaceView setFixedSize failed: " + t); }
                try { streamSurfaceView.setZOrderOnTop(false); } catch (Throwable t) { LimeLog.warning("SurfaceView setZOrderOnTop failed: " + t); }
                try { streamSurfaceView.setZOrderMediaOverlay(false); } catch (Throwable t) { LimeLog.warning("SurfaceView setZOrderMediaOverlay failed: " + t); }
            }
        } catch (Throwable t) { LimeLog.warning("Stream surface setup failed: " + t); }
    }

    /** Recursive helper that finds the first SurfaceView in the current layout */
    private static SurfaceView findFirstSurfaceViewFrom(View v) {
        if (v instanceof SurfaceView) return (SurfaceView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                SurfaceView found = findFirstSurfaceViewFrom(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The stream container has a valid surface: the first time, the decoder gets it and the connection starts. */
    public void onSurfaceAvailable() {
        if (!attemptedConnection) {
            LimeLog.info("Surface is available, starting connection...");
            attemptedConnection = true;

            // The decoder gets the container's currently active surface
            decoder.setRenderTarget(host.activeSurface());

            // Start the NvConnection
            host.startConnection();
        }
    }

    public void onSurfaceCreated(SurfaceHolder holder, float desiredFrameRate) {
        surfaceCreated = true;

        // Tell the OS about our frame rate to allow it to adapt the display refresh rate appropriately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // We want to change frame rate even if it's not seamless, since prepareDisplayForRendering()
            // will not set the display mode on S+ if it only differs by the refresh rate. It depends
            // on us to trigger the frame rate switch here.
            holder.getSurface().setFrameRate(desiredFrameRate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS);
        }
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            holder.getSurface().setFrameRate(desiredFrameRate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
        }

        // Disable producer throttling on the underlying surface for reduced latency
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            holder.getSurface().setProducerThrottlingEnabled(false);
        }
    }

    public void onSurfaceChanged(int width, int height) {
        if (!surfaceCreated) {
            throw new IllegalStateException("Surface changed before creation!");
        }

        LimeLog.info("surfaceChanged-->"+width+" x "+height + "----"+host.displayWidth()+" x "+host.displayHeight());
    }

    public void onSurfaceDestroyed() {
        if (!surfaceCreated) {
            throw new IllegalStateException("Surface destroyed before creation!");
        }

        if (attemptedConnection) {
            // Let the decoder know immediately that the surface is gone
            decoder.prepareForStop();

            if (host.isConnected()) {
                host.stopConnection();
            }
        }
    }

    // Delos: the stream's picture, which the portal back into the new UI zooms into a little
    @Override
    public View getStreamSurface() {
        return host.streamSurfaceView();
    }

    // Delos: SGSR on 8-bit output draws into window-sized buffers, while onCreate sized the surface for the
    // requested HDR. The decoder calls this from its setup thread when the host settled on 8-bit, before it
    // creates the SGSR stage; it returns once the surface has its new size (or after a short wait).
    @Override
    public void useLayoutSizedStreamSurface() {
        runAndAwait(uiThread, () -> {
            SurfaceView view = host.streamSurfaceView();
            if (view != null) view.getHolder().setSizeFromLayout();
        }, RESIZE_WAIT_MS);
    }

    /**
     * Runs {@code action} on {@code executor} and waits up to {@code timeoutMs} for it to finish. A failure of the
     * action is logged; an interrupt ends the wait and stays set on the calling thread.
     */
    static void runAndAwait(Executor executor, Runnable action, long timeoutMs) {
        final CountDownLatch resized = new CountDownLatch(1);
        executor.execute(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                LimeLog.warning("SurfaceView setSizeFromLayout failed: " + t);
            } finally {
                resized.countDown();
            }
        });
        try {
            resized.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int streamSurfaceWidth() {
        return streamSurfaceSize(prefConfig.width, host.displayWidth());
    }

    private int streamSurfaceHeight() {
        return streamSurfaceSize(prefConfig.height, host.displayHeight());
    }

    // Delos: an upscaler on 8-bit output draws into window-sized buffers, so a scaling mode needs the surface at
    // the layout's size, but Sharpen only draws at the stream's own size and must not enlarge it (the pass would
    // then cost the display's pixels instead of the stream's). Without an upscaler the surface goes back to the
    // stream's size, the size it starts with. 10-bit output has its own layer and is unaffected.
    private void sizeStreamSurfaceForUpscaler(boolean enabled, UpscalerMode mode) {
        SurfaceView view = host.streamSurfaceView();
        if (view == null) return;
        switch (resizeForUpscaler(decoder.getActiveVideoFormat(), enabled, mode)) {
            case LAYOUT:
                view.getHolder().setSizeFromLayout();
                break;
            case STREAM:
                view.getHolder().setFixedSize(streamSurfaceWidth(), streamSurfaceHeight());
                break;
            case NONE:
                break;
        }
    }

    // Delos: live stream-menu controls; only the local decoder changes output, not the connection.
    public void setUpscalingEnabled(boolean enabled) {
        // The surface first, as for a mode change: the decoder rebuilds its output stage on it
        sizeStreamSurfaceForUpscaler(enabled, prefConfig.sgsrMode);
        decoder.setUpscalingEnabled(enabled);
    }
    public boolean isUpscalingRequested() { return decoder.isUpscalingRequested(); }
    public boolean isPyroWave() { return decoder.isPyroWave(); }

    /** Delos: the PyroWave link monitor of the running decoder, null before the decoder exists. */
    @Nullable
    public LinkMonitor getLinkMonitor() {
        return decoder != null ? decoder.getLinkMonitor() : null;
    }
    public void setUpscalingMode(int mode) {
        // The surface first: the renderer is rebuilt on it when the mode changes between scaling and not
        if (decoder.isUpscalingRequested()) sizeStreamSurfaceForUpscaler(true, UpscalerMode.fromIndex(mode));
        decoder.setUpscalingMode(mode);
    }
    public String getUpscalingStatus() { return decoder.getUpscalingStatus(); }
    public void setUpscalingSharpness(int value) { decoder.setUpscalingSharpness(value); }
    public boolean isSupersamplingMode() { return decoder.isSupersamplingMode(); }
    public int getSupersamplingLevel() { return decoder.getSupersamplingLevel(); }
    public void setSupersamplingLevel(int level) { decoder.setSupersamplingLevel(level); }

    public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
        LimeLog.info("Display HDR mode: " + (enabled ? "enabled" : "disabled"));
        decoder.setHdrMode(enabled, hdrMetadata);
    }

    /** Actual frame presentation, not merely completion of the network handshake. */
    public boolean isLaunchFrameReady() {
        return decoder != null && decoder.hasPresentedFrame();
    }

    public void setLaunchArrivalProgress(float progress) {
        if (decoder != null) decoder.setLaunchArrivalProgress(progress);
    }

    public boolean isLaunchArrivalReady() {
        return decoder != null && decoder.isLaunchArrivalReady();
    }

    public boolean isLaunchArrivalComplete() {
        return decoder == null || decoder.isLaunchArrivalComplete();
    }

    @Nullable
    public String getLaunchArrivalError() {
        return decoder != null ? decoder.getLaunchArrivalError() : null;
    }

    /** Preserve the live game on a graphics-path failure, but never hide a missing arrival effect. */
    public void reportLaunchArrivalError(String error) {
        LimeLog.warning("Launch image effect unavailable: " + error);
        setLaunchArrivalProgress(-1f);
    }
}
