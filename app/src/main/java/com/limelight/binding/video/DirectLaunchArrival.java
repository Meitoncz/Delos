package com.limelight.binding.video;

import android.content.Context;
import android.view.Surface;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Temporary direct-codec GPU output. Only lifecycle events enqueue work, never animation frames. */
final class DirectLaunchArrival {
    private final MediaCodecDecoderRenderer owner;
    private final Context context;
    private final Surface output;
    private final int width, height;
    private final boolean tenBit;
    private final Object submissionLock = new Object();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "Video - Launch arrival"));
    private final Runnable activateTask = this::activate;
    private final Runnable restoreTask = this::restore;
    private final Runnable releaseTask = this::release;
    private volatile SgsrRenderer renderer;
    private volatile boolean hdrActive;
    private volatile byte[] hdrMetadata;
    private volatile float progress = -1f;
    private volatile boolean cancelled, decoderReleased, redirected, awaitingDirectFrame;
    private volatile boolean activationRequested, finished;
    private volatile String error;
    private volatile long directPtsExclusive, directNotBeforeNs;
    // submissionLock protects queue lifetime and the one-shot direct-frame completion notification.
    private boolean executorClosed, directFrameConfirmed;
    // Worker-owned: a failed surface handover enters existing codec recovery exactly once.
    private boolean handoverRecoveryRequested;

    DirectLaunchArrival(MediaCodecDecoderRenderer owner, Context context, Surface output,
                        int width, int height, boolean tenBit, boolean hdrActive, byte[] hdrMetadata) {
        this.owner = owner;
        this.context = context.getApplicationContext();
        this.output = output;
        this.width = width;
        this.height = height;
        this.tenBit = tenBit;
        this.hdrActive = hdrActive;
        this.hdrMetadata = hdrMetadata;
    }

    void prepare() { enqueue(this::initialize); }

    private void initialize() {
        if (cancelled || decoderReleased) { release(); return; }
        try {
            boolean initialHdr;
            byte[] initialMetadata;
            synchronized (submissionLock) {
                initialHdr = hdrActive;
                initialMetadata = hdrMetadata;
            }
            SgsrRenderer created = SgsrRenderer.createArrival(context, output, width, height,
                    tenBit, initialHdr, initialMetadata, this::onRendererFailure);
            synchronized (submissionLock) {
                renderer = created;
                created.setHdrMode(hdrActive, hdrMetadata);
            }
            created.setLaunchArrivalCompletionListener(() -> enqueue(restoreTask));
            created.prepareLaunchArrival();
            if (cancelled || decoderReleased) release();
        } catch (RuntimeException exception) {
            fail("Stream arrival preparation failed", exception);
            release();
        }
    }

    void setProgress(float value) {
        if (!Float.isFinite(value) || value < 0f) { cancel(); return; }
        if (cancelled || finished || error != null) return;
        progress = Math.min(value, 1f);
        SgsrRenderer current = renderer;
        if (current != null) current.setLaunchArrivalProgress(progress);
        // This API has one animation producer. The lock only runs at activation, not every frame.
        if (!activationRequested) {
            synchronized (submissionLock) {
                if (!activationRequested && !executorClosed) {
                    activationRequested = true;
                    executor.execute(activateTask);
                }
            }
        }
    }

    private void activate() {
        if (cancelled || decoderReleased || error != null) { releaseIfDisconnected(); return; }
        SgsrRenderer current = renderer;
        if (current == null) return;
        try {
            current.setLaunchArrivalProgress(progress);
            if (!owner.attachLaunchArrivalOutput(this, current)) releaseIfDisconnected();
        } catch (RuntimeException exception) {
            fail("Stream arrival output attachment failed", exception);
            restore();
        }
    }

    boolean isReady() {
        SgsrRenderer current = renderer;
        return !cancelled && error == null && redirected && current != null
                && current.isLaunchArrivalReady();
    }

    boolean isRedirected() { return redirected; }
    boolean isAwaitingDirectFrame() { return awaitingDirectFrame; }
    Surface getDecoderSurface() {
        SgsrRenderer current = renderer;
        return current != null ? current.getDecoderSurface() : null;
    }
    void detachForCodecRecovery() {
        SgsrRenderer current = renderer;
        if (current != null && current.getDecoderSurface() != null) current.detachArrivalOutput();
    }
    String getError() {
        SgsrRenderer current = renderer;
        String rendererError = current != null ? current.getLaunchArrivalError() : null;
        return error != null ? error : rendererError;
    }

    void didRedirect() { redirected = true; }

    /** Called under the codec recovery monitor, after setOutputSurface(real) has returned. */
    void didRestoreDirect(long lastAssignedPtsUs, long nowNs) {
        directPtsExclusive = lastAssignedPtsUs;
        directNotBeforeNs = nowNs;
        redirected = false;
        awaitingDirectFrame = true;
    }

    void onFrameRendered(long presentationTimeUs, long renderTimeNanos) {
        if (!awaitingDirectFrame || presentationTimeUs <= directPtsExclusive
                || renderTimeNanos < directNotBeforeNs) return;
        synchronized (submissionLock) {
            if (executorClosed || directFrameConfirmed) return;
            // PTS was assigned after the switch: a delayed callback from the old OES producer
            // cannot remove the HDR child's retained identity image prematurely.
            directFrameConfirmed = true;
            if (LaunchArrival.canReleaseTemporaryOutput(!redirected, directFrameConfirmed)) {
                awaitingDirectFrame = false;
                executor.execute(releaseTask);
            }
        }
    }

    void setHdrMode(boolean active, byte[] metadata) {
        synchronized (submissionLock) {
            hdrActive = active;
            hdrMetadata = metadata;
            SgsrRenderer current = renderer;
            if (current != null) current.setHdrMode(active, metadata);
        }
    }

    void cancel() {
        cancelled = true;
        enqueue(restoreTask);
    }

    void onDecoderReleased() {
        decoderReleased = true;
        cancelled = true;
        enqueue(releaseTask);
    }

    private void onRendererFailure() {
        SgsrRenderer current = renderer;
        fail(current != null && current.getLaunchArrivalError() != null
                ? current.getLaunchArrivalError() : "Stream arrival GPU output failed", null);
        enqueue(restoreTask);
    }

    private void restore() {
        if (decoderReleased) { release(); return; }
        SgsrRenderer current = renderer;
        if (!redirected || current == null) { releaseIfDisconnected(); return; }
        if (handoverRecoveryRequested) return;
        if (!cancelled && error == null
                && !LaunchArrival.canSwitchToDirect(progress, current.isLaunchArrivalComplete())) return;
        try {
            owner.restoreLaunchArrivalOutput(this, current);
        } catch (RuntimeException exception) {
            // The codec might still own the temporary producer. Keep it alive until decoder cleanup
            // or recovery confirms a direct output; never close a Surface still owned by the codec.
            fail("Stream arrival direct-output handover failed", exception);
            handoverRecoveryRequested = true;
            owner.recoverFailedLaunchArrivalOutput(this);
        }
    }

    private void releaseIfDisconnected() {
        if (!redirected && !awaitingDirectFrame) release();
    }

    private void release() {
        if (finished || (redirected && !decoderReleased)) return;
        if (awaitingDirectFrame && !decoderReleased) return;
        finished = true;
        SgsrRenderer current = renderer;
        if (current != null) {
            if (error == null) error = current.getLaunchArrivalError();
            current.close();
            renderer = null;
        }
        synchronized (submissionLock) {
            executorClosed = true;
            executor.shutdown();
        }
        owner.onDirectLaunchArrivalClosed(this, error, !cancelled && directFrameConfirmed);
    }

    private void fail(String message, RuntimeException exception) {
        if (error != null) return;
        StringBuilder text = new StringBuilder(message);
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            text.append(": ").append(cause.getMessage() != null ? cause.getMessage() : cause.toString());
        }
        error = text.toString();
    }

    private void enqueue(Runnable task) {
        synchronized (submissionLock) {
            if (!executorClosed) executor.execute(task);
        }
    }
}
