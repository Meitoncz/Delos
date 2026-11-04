package com.limelight.binding.video;

import android.content.Context;
import android.view.Surface;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Temporary direct-codec GPU output. Only lifecycle events enqueue work, never animation frames. */
final class DirectLaunchArrival {
    private final MediaCodecDecoderRenderer owner;
    private final Context context;
    private final Surface output;
    private final int width, height;
    private final boolean tenBit;
    private final Object submissionLock = new Object();
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1,
            runnable -> new Thread(runnable, "Video - Launch arrival"));
    private final LaunchArrivalHandover handover = new LaunchArrivalHandover();
    private final Runnable activateTask = this::activate;
    private final Runnable restoreTask = this::restore;
    private final Runnable releaseTask = this::release;
    private volatile SgsrRenderer renderer;
    private volatile boolean hdrActive;
    private volatile byte[] hdrMetadata;
    private volatile float progress = -1f;
    private volatile boolean cancelled, decoderReleased, redirected, awaitingDirectFrame;
    private volatile boolean detachedForCodecRecovery;
    private volatile boolean activationRequested, identityRequested, finished;
    private volatile String error;
    // submissionLock protects queue lifetime and the two one-shot timeout tasks.
    private boolean executorClosed;
    private ScheduledFuture<?> identityTimeout, directTimeout;
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
        executor.setRemoveOnCancelPolicy(true);
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
        // Ordinary progress writes enqueue nothing. Only activation and final identity are events.
        if (!activationRequested || (progress >= 1f && !identityRequested)) {
            synchronized (submissionLock) {
                if (executorClosed || cancelled) return;
                if (!activationRequested && handover.activate()) {
                    activationRequested = true;
                    executor.execute(activateTask);
                }
                if (progress >= 1f && handover.requestIdentity(System.nanoTime())) {
                    identityRequested = true;
                    identityTimeout = executor.schedule(restoreTask,
                            LaunchArrivalHandover.IDENTITY_WAIT_NS, TimeUnit.NANOSECONDS);
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
            if (!owner.attachLaunchArrivalOutput(this, current)) {
                fail("Stream arrival decoder output is unavailable", null);
                releaseIfDisconnected();
            }
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
    boolean isDetachedForCodecRecovery() { return detachedForCodecRecovery; }
    Surface getDecoderSurface() {
        SgsrRenderer current = renderer;
        return current != null ? current.getDecoderSurface() : null;
    }
    void detachForCodecRecovery() {
        SgsrRenderer current = renderer;
        if (current != null && current.getDecoderSurface() != null) current.detachArrivalOutput();
        detachedForCodecRecovery = true;
    }
    String getError() {
        SgsrRenderer current = renderer;
        String rendererError = current != null ? current.getLaunchArrivalError() : null;
        return error != null ? error : rendererError;
    }

    void didRedirect() {
        detachedForCodecRecovery = false;
        redirected = true;
    }

    /** Called under the codec recovery monitor, after setOutputSurface(real) has returned. */
    void didRestoreDirect(long lastAssignedPtsUs, long nowNs) {
        handover.restored(lastAssignedPtsUs, nowNs);
        redirected = false;
        awaitingDirectFrame = true;
        synchronized (submissionLock) {
            if (executorClosed) return;
            if (directTimeout != null) directTimeout.cancel(false);
            directTimeout = executor.schedule(() -> {
                if (handover.finishDirectWait(System.nanoTime())) {
                    awaitingDirectFrame = false;
                    release();
                }
            }, LaunchArrivalHandover.DIRECT_WAIT_NS, TimeUnit.NANOSECONDS);
        }
    }

    void onFrameRendered(long presentationTimeUs, long renderTimeNanos) {
        if (!awaitingDirectFrame || !handover.directFrame(presentationTimeUs, renderTimeNanos)) return;
        synchronized (submissionLock) {
            if (executorClosed) return;
            // Reject delayed OES callbacks using both the assigned PTS and handover time.
            awaitingDirectFrame = false;
            executor.execute(releaseTask);
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
        handover.cancel();
        SgsrRenderer current = renderer;
        if (current != null) current.setLaunchArrivalProgress(-1f);
        enqueue(restoreTask);
    }

    void onDecoderReleased() {
        decoderReleased = true;
        cancelled = true;
        handover.cancel();
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
        boolean identity = current.isLaunchArrivalComplete();
        long nowNs = System.nanoTime();
        if (!cancelled && error == null && !handover.canRestore(identity, nowNs)) return;
        if (handover.identityTimedOut(nowNs)) {
            fail("Stream arrival identity presentation timed out", null);
        }
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
            current.closeAsync();
            renderer = null;
        }
        synchronized (submissionLock) {
            executorClosed = true;
            if (identityTimeout != null) identityTimeout.cancel(false);
            if (directTimeout != null) directTimeout.cancel(false);
            executor.shutdown();
        }
        owner.onDirectLaunchArrivalClosed(this, error, !cancelled && error == null
                && handover.hasIdentityPresentation());
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
