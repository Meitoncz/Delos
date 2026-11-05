package com.limelight.binding.video;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.jcodec.codecs.h264.H264Utils;
import org.jcodec.codecs.h264.io.model.SeqParameterSet;
import org.jcodec.codecs.h264.io.model.VUIParameters;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.TrafficStatsHelper;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.media.MediaCodec;
import android.os.Bundle;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaCodec.CodecException;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Range;
import android.view.Choreographer;
import android.view.Surface;

public class MediaCodecDecoderRenderer extends VideoDecoderRenderer implements Choreographer.FrameCallback {
    // Latency profile: favor minimal end-to-end delay over absolute smoothness.
    // Set true to enable a 'latest-only' fast path in the render loop.
    private boolean preferLowerDelays = false;


    // Decode latency tracking: enqueue time (ns) per PTS (us), oldest first. Primitive arrays keep
    // the per-frame path free of boxing.
    private static final int ENQUEUE_TRACK_CAP = 256;
    private final long[] enqueuePtsUs = new long[ENQUEUE_TRACK_CAP];
    private final long[] enqueueNsOfPts = new long[ENQUEUE_TRACK_CAP];
    private int enqueueCount; // guarded by enqueuePtsUs

    // Output dequeue timeout in microseconds (Game sets 500 with LFR, 2000 otherwise)
    private volatile int preferLowerDelaysTimeoutUs = 2000;
    public void setPreferLowerDelaysTimeoutUs(int us) { this.preferLowerDelaysTimeoutUs = Math.max(0, us); }


    // Helper: release with low-latency policy (immediate only when very near to now)
    private void releaseWithPolicy(int bufferIndex, long frameTimeNanos) {
        try {
            long now = System.nanoTime();
            boolean immediate = preferLowerDelays && (frameTimeNanos <= now + 300_000L);
            if (immediate) {
                videoDecoder.releaseOutputBuffer(bufferIndex, true);
            } else {
                videoDecoder.releaseOutputBuffer(bufferIndex, frameTimeNanos);
            }
        } catch (Throwable t) {
            try {
                // Fallback to immediate if timestamped release fails for any reason
                videoDecoder.releaseOutputBuffer(bufferIndex, true);
            } catch (Throwable ignored) { /* nothing else we can do for this frame */ }
        }
    }
    private int getOutputDequeueTimeoutUs(){ return preferLowerDelays ? Math.max(250, preferLowerDelaysTimeoutUs) : preferLowerDelaysTimeoutUs; }

    // Remember when the frame with this PTS went into the codec
    private void recordEnqueueTime(long presentationTimeUs, long enqueueNs) {
        synchronized (enqueuePtsUs) {
            if (enqueueCount == ENQUEUE_TRACK_CAP) {
                // Nothing came out for this many frames; forget the oldest
                System.arraycopy(enqueuePtsUs, 1, enqueuePtsUs, 0, ENQUEUE_TRACK_CAP - 1);
                System.arraycopy(enqueueNsOfPts, 1, enqueueNsOfPts, 0, ENQUEUE_TRACK_CAP - 1);
                enqueueCount--;
            }
            enqueuePtsUs[enqueueCount] = presentationTimeUs;
            enqueueNsOfPts[enqueueCount] = enqueueNs;
            enqueueCount++;
        }
    }

    // Update stats using real decode time: enqueue->dequeue, instead of uptime - PTS. dequeueNs is when
    // the frame left the codec; the caller stamps it before any pacer wait, so the figure is the
    // decoder's time, not decode plus pacing.
    private void updateDecodeLatencyStats(long presentationTimeUs, long dequeueNs) {
        synchronized (enqueuePtsUs) {
            // Submission times rise strictly with the PTS, so the table stays sorted
            int idx = Arrays.binarySearch(enqueuePtsUs, 0, enqueueCount, presentationTimeUs);
            if (idx >= 0) {
                long enqNs = enqueueNsOfPts[idx];
                // Also forget older entries: those frames were dropped and will never be presented
                int remaining = enqueueCount - (idx + 1);
                System.arraycopy(enqueuePtsUs, idx + 1, enqueuePtsUs, 0, remaining);
                System.arraycopy(enqueueNsOfPts, idx + 1, enqueueNsOfPts, 0, remaining);
                enqueueCount = remaining;
                long decMs = (dequeueNs - enqNs) / 1_000_000L;
                if (decMs >= 0 && decMs < 1000) {
                    activeWindowVideoStats.decoderTimeMs += decMs;
                    if (!USE_FRAME_RENDER_TIME) {
                        activeWindowVideoStats.totalTimeMs += decMs;
                    }
                }
            }
        }
    }

    public void setPreferLowerDelays(boolean v) { this.preferLowerDelays = v; }


    private static final boolean USE_FRAME_RENDER_TIME = false;
    private static final boolean FRAME_RENDER_TIME_ONLY = USE_FRAME_RENDER_TIME && false;

    private MediaCodecInfo avcDecoder;
    private MediaCodecInfo hevcDecoder;
    private MediaCodecInfo av1Decoder;

    private final ArrayList<byte[]> vpsBuffers = new ArrayList<>();
    private final ArrayList<byte[]> spsBuffers = new ArrayList<>();
    private final ArrayList<byte[]> ppsBuffers = new ArrayList<>();
    private boolean submittedCsd;
    private volatile byte[] currentHdrMetadata;

    private int nextInputBufferIndex = -1;
    private ByteBuffer nextInputBuffer;

    private Context context;
    private Activity activity;
    private volatile MediaCodec videoDecoder;
    private Thread rendererThread;
    // Delos: the callback adapter is adapted from Starwire/Artemide (GPL-3.0).
    private final AsyncCodecAdapter asyncCodec = new AsyncCodecAdapter();
    private boolean useAsyncCodec;
    private volatile CodecException pendingAsyncError;
    private final AsyncCodecAdapter.OutputRelease asyncRelease = (codec, index) -> codec.releaseOutputBuffer(index, false);
    private final AsyncCodecAdapter.Callbacks asyncCallbacks = new AsyncCodecAdapter.Callbacks() {
        @Override public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) { outputFormat = format; }
        @Override public void onCodecError(MediaCodec codec, CodecException error) { pendingAsyncError = error; }
    };
    private int dequeueInput(int timeoutUs) {
        return useAsyncCodec ? asyncCodec.dequeueInputIndex(timeoutUs) : videoDecoder.dequeueInputBuffer(timeoutUs);
    }
    private int dequeueOutput(BufferInfo info, int timeoutUs) {
        CodecException error = pendingAsyncError;
        if (error != null) { pendingAsyncError = null; throw error; }
        return useAsyncCodec ? asyncCodec.dequeueOutputIndex(videoDecoder, info, timeoutUs, asyncRelease)
                : videoDecoder.dequeueOutputBuffer(info, timeoutUs);
    }
    private void detachAsyncCodec() {
        if (useAsyncCodec) asyncCodec.detach(videoDecoder, asyncRelease);
        pendingAsyncError = null;
    }
    private final NanoPacer nanoPacer = new NanoPacer();
    private final NanoPacer.LatestOutput nanoLatest = new NanoPacer.LatestOutput();
    private final NanoPacer.OutputCallbacks nanoCallbacks = new NanoPacer.OutputCallbacks() {
        @Override public int nextOutputIndex(BufferInfo info, int timeoutUs) { return dequeueOutput(info, timeoutUs); }
        @Override public void releaseOutputBuffer(int index, boolean render) { videoDecoder.releaseOutputBuffer(index, render); }
    };

    private boolean needsSpsBitstreamFixup, isExynos4;
    private boolean adaptivePlayback, directSubmit, fusedIdrFrame;
    private boolean constrainedHighProfile;
    private boolean refFrameInvalidationAvc, refFrameInvalidationHevc, refFrameInvalidationAv1;
    private byte optimalSlicesPerFrame;
    private boolean refFrameInvalidationActive;
    private int initialWidth, initialHeight;
    private boolean invertResolution;
    private int videoFormat;
    private volatile Surface renderTarget;
    private volatile SgsrRenderer sgsr;
    private volatile boolean sgsrFailed;
    private volatile boolean sgsrRequested;
    private volatile boolean sgsrHdrActive;
    // Delos: the Vulkan renderer of a PyroWave stream; MediaCodec stays unused while it exists
    // Written by setup() and cleanup(), read by the stream menu, the stats thread and the HDR callback
    private volatile PyroWaveRenderer pyroWave;
    private Thread pyroWaveStatsThread;
    // Delos: when the drawn frames came, for the 1% low in the statistics (PyroWave keeps its own natively)
    private final FrameTimes frameTimes = new FrameTimes();
    private volatile boolean stopping;
    private volatile boolean firstFramePresented;
    private volatile DirectLaunchArrival directLaunchArrival;
    private volatile boolean decoderStarted;
    private volatile boolean launchArrivalRequested, launchArrivalReducedMotion, launchArrivalComplete;
    private volatile float launchArrivalProgress = -1f;
    private volatile String launchArrivalError;
    // Protected by codecRecoveryMonitor; a missing backend is never marked prepared.
    private volatile Object launchPreparedBackend;
    private volatile long launchActivationStartedNs;
    private boolean launchSurfaceChangeInProgress;
    private boolean codecRecoveryInProgress;
    private boolean firstCodecFrameRendered;
    private Boolean frameRenderedListenerEnabled;
    private Boolean pendingUpscalingAfterArrival;
    private CrashListener crashListener;
    private boolean reportedCrash;
    private int consecutiveCrashCount;
    private String glRenderer;
    private boolean foreground = true;
    private PerfOverlayListener perfListener;

    private static final int CR_MAX_TRIES = 10;
    private static final int CR_RECOVERY_TYPE_NONE = 0;
    private static final int CR_RECOVERY_TYPE_FLUSH = 1;
    private static final int CR_RECOVERY_TYPE_RESTART = 2;
    private static final int CR_RECOVERY_TYPE_RESET = 3;
    private AtomicInteger codecRecoveryType = new AtomicInteger(CR_RECOVERY_TYPE_NONE);
    private final Object codecRecoveryMonitor = new Object();

    // Streaming threads that retain codec buffers must have a quiescence flag and call recovery
    // regularly. The temporary surface worker retains no buffers: its entire setOutputSurface
    // operation is serialized with reset/release by codecRecoveryMonitor instead.
    private static final int CR_FLAG_INPUT_THREAD = 0x1;
    private static final int CR_FLAG_RENDER_THREAD = 0x2;
    private static final int CR_FLAG_CHOREOGRAPHER = 0x4;
    private static final int CR_FLAG_ALL = CR_FLAG_INPUT_THREAD | CR_FLAG_RENDER_THREAD | CR_FLAG_CHOREOGRAPHER;
    private int codecRecoveryThreadQuiescedFlags = 0;
    private int codecRecoveryAttempts = 0;

    private MediaFormat inputFormat;
    private MediaFormat outputFormat;
    private MediaFormat configuredFormat;

    private boolean needsBaselineSpsHack;
    private SeqParameterSet savedSps;

    private RendererException initialException;
    private long initialExceptionTimestamp;
    private static final int EXCEPTION_REPORT_DELAY_MS = 3000;

    private VideoStats activeWindowVideoStats;
    private VideoStats lastWindowVideoStats;
    private VideoStats globalVideoStats;

    private volatile long lastTimestampUs;
    private long baseTimestampUs;
    private int lastFrameNumber;
    private int refreshRate;
    private PreferenceConfiguration prefs;

    private long lastNetDataNum;
    private LinkedBlockingQueue<Integer> outputBufferQueue = new LinkedBlockingQueue<>();
    private static final int OUTPUT_BUFFER_QUEUE_LIMIT = 2;
    private long lastRenderedFrameTimeNanos;
    private HandlerThread choreographerHandlerThread;
    private Handler choreographerHandler;

    private int numSpsIn;
    private int numPpsIn;
    private int numVpsIn;
    private int numFramesIn;
    private int numFramesOut;

    private int targetFps = 0;

    private MediaCodecInfo findAvcDecoder() {
        MediaCodecInfo decoder = MediaCodecHelper.findProbableSafeDecoder("video/avc", MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        if (decoder == null) {
            decoder = MediaCodecHelper.findFirstDecoder("video/avc");
        }
        return decoder;
    }

    private boolean decoderCanMeetPerformancePoint(MediaCodecInfo.VideoCapabilities caps, PreferenceConfiguration prefs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaCodecInfo.VideoCapabilities.PerformancePoint targetPerfPoint = new MediaCodecInfo.VideoCapabilities.PerformancePoint(initialWidth, initialHeight, Math.round(prefs.fps));
            List<MediaCodecInfo.VideoCapabilities.PerformancePoint> perfPoints = caps.getSupportedPerformancePoints();
            if (perfPoints != null) {
                for (MediaCodecInfo.VideoCapabilities.PerformancePoint perfPoint : perfPoints) {
                    // If we find a performance point that covers our target, we're good to go
                    if (perfPoint.covers(targetPerfPoint)) {
                        return true;
                    }
                }

                // We had performance point data but none met the specified streaming settings
                return false;
            }

            // Fall-through to try the Android M API if there's no performance point data
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                // We'll ask the decoder what it can do for us at this resolution and see if our
                // requested frame rate falls below or inside the range of achievable frame rates.
                Range<Double> fpsRange = caps.getAchievableFrameRatesFor(initialWidth, initialHeight);
                if (fpsRange != null) {
                    return prefs.fps <= fpsRange.getUpper();
                }

                // Fall-through to try the Android L API if there's no performance point data
            } catch (IllegalArgumentException e) {
                // Video size not supported at any frame rate
                return false;
            }
        }

        // As a last resort, we will use areSizeAndRateSupported() which is explicitly NOT a
        // performance metric, but it can work at least for the purpose of determining if
        // the codec is going to die when given a stream with the specified settings.
        return caps.areSizeAndRateSupported(initialWidth, initialHeight, prefs.fps);
    }

    private boolean decoderCanMeetPerformancePointWithHevcAndNotAvc(MediaCodecInfo hevcDecoderInfo, MediaCodecInfo avcDecoderInfo, PreferenceConfiguration prefs) {
        MediaCodecInfo.VideoCapabilities avcCaps = avcDecoderInfo.getCapabilitiesForType("video/avc").getVideoCapabilities();
        MediaCodecInfo.VideoCapabilities hevcCaps = hevcDecoderInfo.getCapabilitiesForType("video/hevc").getVideoCapabilities();

        return !decoderCanMeetPerformancePoint(avcCaps, prefs) && decoderCanMeetPerformancePoint(hevcCaps, prefs);
    }

    private boolean decoderCanMeetPerformancePointWithAv1AndNotHevc(MediaCodecInfo av1DecoderInfo, MediaCodecInfo hevcDecoderInfo, PreferenceConfiguration prefs) {
        MediaCodecInfo.VideoCapabilities av1Caps = av1DecoderInfo.getCapabilitiesForType("video/av01").getVideoCapabilities();
        MediaCodecInfo.VideoCapabilities hevcCaps = hevcDecoderInfo.getCapabilitiesForType("video/hevc").getVideoCapabilities();

        return !decoderCanMeetPerformancePoint(hevcCaps, prefs) && decoderCanMeetPerformancePoint(av1Caps, prefs);
    }

    private boolean decoderCanMeetPerformancePointWithAv1AndNotAvc(MediaCodecInfo av1DecoderInfo, MediaCodecInfo avcDecoderInfo, PreferenceConfiguration prefs) {
        MediaCodecInfo.VideoCapabilities avcCaps = avcDecoderInfo.getCapabilitiesForType("video/avc").getVideoCapabilities();
        MediaCodecInfo.VideoCapabilities av1Caps = av1DecoderInfo.getCapabilitiesForType("video/av01").getVideoCapabilities();

        return !decoderCanMeetPerformancePoint(avcCaps, prefs) && decoderCanMeetPerformancePoint(av1Caps, prefs);
    }

    private MediaCodecInfo findHevcDecoder(PreferenceConfiguration prefs, boolean meteredNetwork, boolean requestedHdr) {
        // Don't return anything if H.264 is forced
        if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_H264) {
            return null;
        }

        // We don't try the first HEVC decoder. We'd rather fall back to hardware accelerated AVC instead
        //
        // We need HEVC Main profile, so we could pass that constant to findProbableSafeDecoder, however
        // some decoders (at least Qualcomm's Snapdragon 805) don't properly report support
        // for even required levels of HEVC.
        MediaCodecInfo hevcDecoderInfo = MediaCodecHelper.findProbableSafeDecoder("video/hevc", -1);
        if (hevcDecoderInfo != null) {
            if (!MediaCodecHelper.decoderIsWhitelistedForHevc(hevcDecoderInfo)) {
                LimeLog.info("Found HEVC decoder, but it's not whitelisted - "+hevcDecoderInfo.getName());

                // Force HEVC enabled if the user asked for it
                if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC) {
                    LimeLog.info("Forcing HEVC enabled despite non-whitelisted decoder");
                }
                // HDR implies HEVC forced on, since HEVCMain10HDR10 is required for HDR.
                else if (requestedHdr) {
                    LimeLog.info("Forcing HEVC enabled for HDR streaming");
                }
                // > 4K streaming also requires HEVC, so force it on there too.
                else if (initialWidth > 4096 || initialHeight > 4096) {
                    LimeLog.info("Forcing HEVC enabled for over 4K streaming");
                }
                // Use HEVC if the H.264 decoder is unable to meet the performance point
                else if (avcDecoder != null && decoderCanMeetPerformancePointWithHevcAndNotAvc(hevcDecoderInfo, avcDecoder, prefs)) {
                    LimeLog.info("Using non-whitelisted HEVC decoder to meet performance point");
                }
                else {
                    return null;
                }
            }
        }

        return hevcDecoderInfo;
    }

    private MediaCodecInfo findAv1Decoder(PreferenceConfiguration prefs) {
        // For now, don't use AV1 unless explicitly requested
        if (prefs.videoFormat != PreferenceConfiguration.FormatOption.FORCE_AV1) {
            return null;
        }

        MediaCodecInfo decoderInfo = MediaCodecHelper.findProbableSafeDecoder("video/av01", -1);
        if (decoderInfo != null) {
            if (!MediaCodecHelper.isDecoderWhitelistedForAv1(decoderInfo)) {
                LimeLog.info("Found AV1 decoder, but it's not whitelisted - "+decoderInfo.getName());

                // Force HEVC enabled if the user asked for it
                if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1) {
                    LimeLog.info("Forcing AV1 enabled despite non-whitelisted decoder");
                }
                // Use AV1 if the HEVC decoder is unable to meet the performance point
                else if (hevcDecoder != null && decoderCanMeetPerformancePointWithAv1AndNotHevc(decoderInfo, hevcDecoder, prefs)) {
                    LimeLog.info("Using non-whitelisted AV1 decoder to meet performance point");
                }
                // Use AV1 if the H.264 decoder is unable to meet the performance point and we have no HEVC decoder
                else if (hevcDecoder == null && decoderCanMeetPerformancePointWithAv1AndNotAvc(decoderInfo, avcDecoder, prefs)) {
                    LimeLog.info("Using non-whitelisted AV1 decoder to meet performance point");
                }
                else {
                    return null;
                }
            }
        }

        return decoderInfo;
    }

    public void setRenderTarget(Surface renderTarget) {
        // A Surface callback must never wait behind codec recovery or an EGL barrier.
        Surface previous = this.renderTarget;
        this.renderTarget = renderTarget;
        if (previous != null && previous != renderTarget) {
            cancelLaunchArrival("Stream surface changed during arrival");
        }
    }

    public MediaCodecDecoderRenderer(Activity activity, PreferenceConfiguration prefs,
                                     CrashListener crashListener, int consecutiveCrashCount,
                                     boolean meteredData, boolean requestedHdr, boolean invertResolution,
                                     String glRenderer, PerfOverlayListener perfListener) {
        //dumpDecoders();

        this.context = activity;
        this.activity = activity;
        this.prefs = prefs;
        sgsrRequested = prefs.sgsrEnabled;
        sgsrHdrActive = prefs.enableHdr;
        this.crashListener = crashListener;
        this.consecutiveCrashCount = consecutiveCrashCount;
        this.glRenderer = glRenderer;
        this.perfListener = perfListener;
        this.invertResolution = invertResolution;

        this.activeWindowVideoStats = new VideoStats();
        this.lastWindowVideoStats = new VideoStats();
        this.globalVideoStats = new VideoStats();

        avcDecoder = findAvcDecoder();
        if (avcDecoder != null) {
            LimeLog.info("Selected AVC decoder: "+avcDecoder.getName());
        }
        else {
            LimeLog.warning("No AVC decoder found");
        }

        hevcDecoder = findHevcDecoder(prefs, meteredData, requestedHdr);
        if (hevcDecoder != null) {
            LimeLog.info("Selected HEVC decoder: "+hevcDecoder.getName());
        }
        else {
            LimeLog.info("No HEVC decoder found");
        }

        av1Decoder = findAv1Decoder(prefs);
        if (av1Decoder != null) {
            LimeLog.info("Selected AV1 decoder: "+av1Decoder.getName());
        }
        else {
            LimeLog.info("No AV1 decoder found");
        }

        // Set attributes that are queried in getCapabilities(). This must be done here
        // because getCapabilities() may be called before setup() in current versions of the common
        // library. The limitation of this is that we don't know whether we're using HEVC or AVC.
        int avcOptimalSlicesPerFrame = 0;
        int hevcOptimalSlicesPerFrame = 0;
        if (avcDecoder != null) {
            directSubmit = MediaCodecHelper.decoderCanDirectSubmit(avcDecoder.getName());
            refFrameInvalidationAvc = MediaCodecHelper.decoderSupportsRefFrameInvalidationAvc(avcDecoder.getName(), initialHeight);
            avcOptimalSlicesPerFrame = MediaCodecHelper.getDecoderOptimalSlicesPerFrame(avcDecoder.getName());

            if (directSubmit) {
                LimeLog.info("Decoder "+avcDecoder.getName()+" will use direct submit");
            }
            if (refFrameInvalidationAvc) {
                LimeLog.info("Decoder "+avcDecoder.getName()+" will use reference frame invalidation for AVC");
            }
            LimeLog.info("Decoder "+avcDecoder.getName()+" wants "+avcOptimalSlicesPerFrame+" slices per frame");
        }

        if (hevcDecoder != null) {
            refFrameInvalidationHevc = MediaCodecHelper.decoderSupportsRefFrameInvalidationHevc(hevcDecoder);
            hevcOptimalSlicesPerFrame = MediaCodecHelper.getDecoderOptimalSlicesPerFrame(hevcDecoder.getName());

            if (refFrameInvalidationHevc) {
                LimeLog.info("Decoder "+hevcDecoder.getName()+" will use reference frame invalidation for HEVC");
            }

            LimeLog.info("Decoder "+hevcDecoder.getName()+" wants "+hevcOptimalSlicesPerFrame+" slices per frame");
        }

        if (av1Decoder != null) {
            refFrameInvalidationAv1 = MediaCodecHelper.decoderSupportsRefFrameInvalidationAv1(av1Decoder);

            if (refFrameInvalidationAv1) {
                LimeLog.info("Decoder "+av1Decoder.getName()+" will use reference frame invalidation for AV1");
            }
        }

        // Use the larger of the two slices per frame preferences
        optimalSlicesPerFrame = (byte)Math.max(avcOptimalSlicesPerFrame, hevcOptimalSlicesPerFrame);
        LimeLog.info("Requesting "+optimalSlicesPerFrame+" slices per frame");

        if (consecutiveCrashCount % 2 == 1) {
            refFrameInvalidationAvc = refFrameInvalidationHevc = false;
            LimeLog.warning("Disabling RFI due to previous crash");
        }
    }

    public boolean isHevcSupported() {
        return hevcDecoder != null;
    }

    public boolean isAvcSupported() {
        return avcDecoder != null;
    }

    public boolean isHevcMain10Hdr10Supported() {
        if (hevcDecoder == null) {
            return false;
        }

        for (MediaCodecInfo.CodecProfileLevel profileLevel : hevcDecoder.getCapabilitiesForType("video/hevc").profileLevels) {
            if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) {
                LimeLog.info("HEVC decoder "+hevcDecoder.getName()+" supports HEVC Main10 HDR10");
                return true;
            }
        }

        return false;
    }

    public boolean isAv1Supported() {
        return av1Decoder != null;
    }

    public boolean isAv1Main10Supported() {
        if (av1Decoder == null) {
            return false;
        }

        for (MediaCodecInfo.CodecProfileLevel profileLevel : av1Decoder.getCapabilitiesForType("video/av01").profileLevels) {
            if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10) {
                LimeLog.info("AV1 decoder "+av1Decoder.getName()+" supports AV1 Main 10 HDR10");
                return true;
            }
        }

        return false;
    }

    public int getPreferredColorSpace() {
        // Default to Rec 709 which is probably better supported on modern devices.
        //
        // We are sticking to Rec 601 on older devices unless the device has an HEVC decoder
        // to avoid possible regressions (and they are < 5% of installed devices). If we have
        // an HEVC decoder, we will use Rec 709 (even for H.264) since we can't choose a
        // colorspace by codec (and it's probably safe to say a SoC with HEVC decoding is
        // plenty modern enough to handle H.264 VUI colorspace info).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || hevcDecoder != null || av1Decoder != null) {
            return MoonBridge.COLORSPACE_REC_709;
        }
        else {
            return MoonBridge.COLORSPACE_REC_601;
        }
    }

    public int getPreferredColorRange() {
        if (prefs.fullRange) {
            return MoonBridge.COLOR_RANGE_FULL;
        }
        else {
            return MoonBridge.COLOR_RANGE_LIMITED;
        }
    }

    public void notifyVideoForeground() {
        foreground = true;
    }

    public void notifyVideoBackground() {
        foreground = false;
    }

    public int getActiveVideoFormat() {
        return this.videoFormat;
    }

    /** A decoded buffer alone is insufficient: the launch aperture waits for presentation. */
    public boolean hasPresentedFrame() {
        if (stopping) return false;
        PyroWaveRenderer pw = pyroWave;
        if (pw != null) return pw.hasPresentedFrame();
        SgsrRenderer current = sgsr;
        return current != null ? current.hasPresentedFrame() : firstFramePresented;
    }

    public int getStreamWidth() { return initialWidth; }
    public int getStreamHeight() { return initialHeight; }

    /** Warm only: neither network setup nor the direct codec waits for shader preparation. */
    public void prepareLaunchArrival(boolean reducedMotion) {
        synchronized (codecRecoveryMonitor) {
            if (stopping || launchArrivalRequested) return;
            launchArrivalRequested = true;
            launchArrivalReducedMotion = reducedMotion;
            launchArrivalComplete = reducedMotion;
            prepareLaunchArrivalBackend();
        }
    }

    // Called under codecRecoveryMonitor, once the selected backend exists.
    private void prepareLaunchArrivalBackend() {
        if (!launchArrivalRequested || launchArrivalReducedMotion || launchArrivalComplete
                || launchArrivalError != null || stopping) return;
        PyroWaveRenderer pw = pyroWave;
        SgsrRenderer current = sgsr;
        if (pw != null) {
            if (launchPreparedBackend == pw) return;
            launchPreparedBackend = pw;
            pw.prepareLaunchArrival();
            if (launchArrivalProgress >= 0f) pw.setLaunchArrivalProgress(launchArrivalProgress);
        } else if (current != null) {
            if (launchPreparedBackend == current) return;
            launchPreparedBackend = current;
            current.prepareLaunchArrival();
            if (launchArrivalProgress >= 0f) current.setLaunchArrivalProgress(launchArrivalProgress);
        } else if (directLaunchArrival == null && decoderStarted
                && renderTarget != null && renderTarget.isValid()) {
            DirectLaunchArrival arrival = new DirectLaunchArrival(this, context, renderTarget,
                    initialWidth, initialHeight, (videoFormat & MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0,
                    sgsrHdrActive, currentHdrMetadata);
            directLaunchArrival = arrival;
            launchPreparedBackend = arrival;
            arrival.prepare();
            if (launchArrivalProgress >= 0f) arrival.setProgress(launchArrivalProgress);
        }
    }

    public boolean isLaunchArrivalReady() {
        if (stopping || getLaunchArrivalError() != null) return false;
        if (launchArrivalReducedMotion) return hasPresentedFrame();
        DirectLaunchArrival arrival = directLaunchArrival;
        if (arrival != null) return arrival.isReady();
        PyroWaveRenderer pw = pyroWave;
        if (pw != null) return pw.isLaunchArrivalReady();
        SgsrRenderer current = sgsr;
        return current != null && current.isLaunchArrivalReady();
    }

    public void setLaunchArrivalProgress(float progress) {
        if (stopping || !launchArrivalRequested || launchArrivalReducedMotion || launchArrivalComplete) return;
        if (!Float.isFinite(progress) || progress < 0f) {
            cancelLaunchArrival(null);
            return;
        }
        if (launchActivationStartedNs == 0L) launchActivationStartedNs = System.nanoTime();
        if (launchPreparedBackend == null) {
            synchronized (codecRecoveryMonitor) {
                prepareLaunchArrivalBackend();
            }
        }
        launchArrivalProgress = Math.min(progress, 1f);
        DirectLaunchArrival arrival = directLaunchArrival;
        if (arrival != null) { arrival.setProgress(launchArrivalProgress); return; }
        PyroWaveRenderer pw = pyroWave;
        if (pw != null) { pw.setLaunchArrivalProgress(launchArrivalProgress); return; }
        SgsrRenderer current = sgsr;
        if (current != null) current.setLaunchArrivalProgress(launchArrivalProgress);
    }

    public boolean isLaunchArrivalComplete() {
        if (stopping || getLaunchArrivalError() != null) return false;
        if (launchArrivalComplete) return true;
        if (directLaunchArrival != null) return false;
        PyroWaveRenderer pw = pyroWave;
        if (pw != null) return pw.isLaunchArrivalComplete();
        SgsrRenderer current = sgsr;
        return current != null && current.isLaunchArrivalComplete();
    }

    public String getLaunchArrivalError() {
        if (launchArrivalError != null) return launchArrivalError;
        DirectLaunchArrival arrival = directLaunchArrival;
        if (arrival != null) return arrival.getError();
        PyroWaveRenderer pw = pyroWave;
        if (pw != null) return pw.getLaunchArrivalError();
        SgsrRenderer current = sgsr;
        if (current == null && launchArrivalRequested && !launchArrivalReducedMotion
                && launchActivationStartedNs != 0L
                && System.nanoTime() - launchActivationStartedNs >= 1_500_000_000L) {
            launchArrivalError = "Stream arrival has no usable decoder output backend";
            return launchArrivalError;
        }
        return current != null ? current.getLaunchArrivalError() : null;
    }

    private void cancelLaunchArrival(String error) {
        if (error != null && launchArrivalRequested && !launchArrivalComplete) launchArrivalError = error;
        launchArrivalRequested = false;
        launchArrivalProgress = -1f;
        DirectLaunchArrival arrival = directLaunchArrival;
        if (arrival != null) arrival.cancel();
        SgsrRenderer current = sgsr;
        if (current != null) current.setLaunchArrivalProgress(-1f);
        PyroWaveRenderer pw = pyroWave;
        if (pw != null) pw.setLaunchArrivalProgress(-1f);
    }

    // Only the temporary worker calls these methods. The same monitor excludes codec reset/release;
    // input/output callbacks keep running, as permitted by MediaCodec.setOutputSurface().
    boolean attachLaunchArrivalOutput(DirectLaunchArrival arrival, SgsrRenderer renderer) {
        synchronized (codecRecoveryMonitor) {
            awaitLaunchSurfaceChange();
            if (stopping || directLaunchArrival != arrival || !decoderStarted || videoDecoder == null) return false;
            if (renderTarget == null || !renderTarget.isValid()) {
                throw new IllegalStateException("Stream arrival output surface is unavailable");
            }
            launchSurfaceChangeInProgress = true;
        }
        try {
            synchronized (codecRecoveryMonitor) {
                // Disconnect the codec producer before EGL connects to the real SurfaceView.
                videoDecoder.setOutputSurface(renderer.getDecoderSurface());
                arrival.didRedirect();
                updateFrameRenderedListener();
            }
            renderer.attachArrivalOutput();
            return true;
        } finally {
            finishLaunchSurfaceChange();
        }
    }

    void restoreLaunchArrivalOutput(DirectLaunchArrival arrival, SgsrRenderer renderer) {
        synchronized (codecRecoveryMonitor) {
            awaitLaunchSurfaceChange();
            if (directLaunchArrival != arrival || !decoderStarted || videoDecoder == null) return;
            launchSurfaceChangeInProgress = true;
        }
        try {
            // Keep the codec's OES producer alive, but detach EGL outside the codec monitor.
            renderer.detachArrivalOutput();
            synchronized (codecRecoveryMonitor) {
                Surface target = renderTarget;
                if (target == null || !target.isValid()) {
                    throw new IllegalStateException("Direct stream output surface is unavailable");
                }
                videoDecoder.setOutputSurface(target);
                arrival.didRestoreDirect(lastTimestampUs, System.nanoTime());
                updateFrameRenderedListener();
            }
        } finally {
            finishLaunchSurfaceChange();
        }
    }

    // Recovery/cleanup wait with the codec monitor released while the worker changes EGL output.
    private void awaitLaunchSurfaceChange() {
        boolean interrupted = false;
        while (launchSurfaceChangeInProgress) {
            try { codecRecoveryMonitor.wait(); }
            catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void finishLaunchSurfaceChange() {
        synchronized (codecRecoveryMonitor) {
            launchSurfaceChangeInProgress = false;
            codecRecoveryMonitor.notifyAll();
        }
    }

    void recoverFailedLaunchArrivalOutput(DirectLaunchArrival arrival) {
        synchronized (codecRecoveryMonitor) {
            awaitLaunchSurfaceChange();
            if (stopping || directLaunchArrival != arrival || videoDecoder == null
                    || !arrival.isRedirected()) return;
            // Only exceptional failed handovers use the existing quiesced reset protocol.
            // Its final owner disconnects EGL outside this monitor before reconfiguration.
            codecRecoveryType.set(CR_RECOVERY_TYPE_RESET);
        }
    }

    void onDirectLaunchArrivalClosed(DirectLaunchArrival arrival, String error, boolean complete) {
        synchronized (codecRecoveryMonitor) {
            if (directLaunchArrival != arrival) return;
            if (error != null) launchArrivalError = error;
            launchArrivalComplete = complete;
            directLaunchArrival = null;
            if (decoderStarted && videoDecoder != null) updateFrameRenderedListener();
            if (pendingUpscalingAfterArrival != null && !stopping) {
                boolean enabled = pendingUpscalingAfterArrival;
                pendingUpscalingAfterArrival = null;
                setUpscalingEnabled(enabled);
            }
        }
    }

    private final MediaCodec.OnFrameRenderedListener frameRenderedListener = (codec, ptsUs, timeNs) -> {
        synchronized (codecRecoveryMonitor) {
            if (codec != videoDecoder || stopping) return;
            DirectLaunchArrival arrival = directLaunchArrival;
            if (arrival != null) arrival.onFrameRendered(ptsUs, timeNs);
            firstCodecFrameRendered = true;
            // A codec callback into OES is not proof of output presentation.
            if (sgsr == null && (arrival == null || !arrival.isRedirected())) firstFramePresented = true;
            long delta = timeNs / 1000000L - ptsUs / 1000;
            if (USE_FRAME_RENDER_TIME && delta >= 0 && delta < 1000) {
                activeWindowVideoStats.totalTimeMs += delta;
            }
            updateFrameRenderedListener();
        }
    };

    // The ordinary startup listener is one-shot. Only metrics or a handover window keep it live.
    private void updateFrameRenderedListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || videoDecoder == null || !decoderStarted) return;
        DirectLaunchArrival arrival = directLaunchArrival;
        boolean handover = arrival != null && (arrival.isRedirected() || arrival.isAwaitingDirectFrame());
        boolean enabled = USE_FRAME_RENDER_TIME || !firstCodecFrameRendered || handover;
        if (frameRenderedListenerEnabled != null && frameRenderedListenerEnabled == enabled) return;
        videoDecoder.setOnFrameRenderedListener(enabled ? frameRenderedListener : null, null);
        frameRenderedListenerEnabled = enabled;
    }

    private MediaFormat createBaseMediaFormat(String mimeType) {
        MediaFormat videoFormat = MediaFormat.createVideoFormat(mimeType, initialWidth, initialHeight);

        // Avoid setting KEY_FRAME_RATE on Lollipop and earlier to reduce compatibility risk
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, refreshRate);
        }

        // Populate keys for adaptive playback
        if (adaptivePlayback) {
            videoFormat.setInteger(MediaFormat.KEY_MAX_WIDTH, initialWidth);
            videoFormat.setInteger(MediaFormat.KEY_MAX_HEIGHT, initialHeight);
        }

        // Android 7.0 adds color options to the MediaFormat
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            videoFormat.setInteger(MediaFormat.KEY_COLOR_RANGE,
                    getPreferredColorRange() == MoonBridge.COLOR_RANGE_FULL ?
                            MediaFormat.COLOR_RANGE_FULL : MediaFormat.COLOR_RANGE_LIMITED);

            // If the stream is HDR-capable, the decoder will detect transitions in color standards
            // rather than us hardcoding them into the MediaFormat.
            if ((getActiveVideoFormat() & MoonBridge.VIDEO_FORMAT_MASK_10BIT) == 0) {
                // Set color format keys when not in HDR mode, since we know they won't change
                videoFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
                switch (getPreferredColorSpace()) {
                    case MoonBridge.COLORSPACE_REC_601:
                        videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC);
                        break;
                    case MoonBridge.COLORSPACE_REC_709:
                        videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
                        break;
                    case MoonBridge.COLORSPACE_REC_2020:
                        videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
                        break;
                }
            }
        }

        return videoFormat;
    }

    private void configureAndStartDecoder(MediaFormat format) {
        decoderStarted = false;
        // Set HDR metadata if present
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (currentHdrMetadata != null) {
                ByteBuffer hdrStaticInfo = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer hdrMetadata = ByteBuffer.wrap(currentHdrMetadata).order(ByteOrder.LITTLE_ENDIAN);

                // Create a HDMI Dynamic Range and Mastering InfoFrame as defined by CTA-861.3
                hdrStaticInfo.put((byte) 0); // Metadata type
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // RX
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // RY
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // GX
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // GY
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // BX
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // BY
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // White X
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // White Y
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // Max mastering luminance
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // Min mastering luminance
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // Max content luminance
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // Max frame average luminance

                hdrStaticInfo.rewind();
                format.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, hdrStaticInfo);
            }
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                format.removeKey(MediaFormat.KEY_HDR_STATIC_INFO);
            }
        }

        LimeLog.info("Configuring with format: "+format);

        useAsyncCodec = prefs.asyncDecode;
        if (useAsyncCodec) {
            asyncCodec.setPreferLowerDelays(preferLowerDelays);
            asyncCodec.attachIfNeeded(videoDecoder, Process.THREAD_PRIORITY_DISPLAY, asyncCallbacks, asyncRelease);
            useAsyncCodec = asyncCodec.isActive();
            LimeLog.info("Delos async decode: " + useAsyncCodec);
        }
        // Delos: an explicit codec reset changes output locally; the network session stays connected.
        if ((!sgsrRequested || sgsrFailed) && sgsr != null) { sgsr.close(); sgsr = null; }
        if (sgsr == null && !sgsrFailed && sgsrRequested) {
            sgsr = SgsrRenderer.create(context, renderTarget, initialWidth, initialHeight, prefs.sgsrSharpness,
                    prefs.sgsrTemporalQuality ? 3 : prefs.sgsrTemporal ? 2 : prefs.sgsrHighQuality ? 1 : 0,
                    prefs.sgsrSupersamplingLevel,
                    (videoFormat & MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0, sgsrHdrActive, currentHdrMetadata, () -> {
                SgsrRenderer failed = sgsr;
                String arrivalFailure = failed != null ? failed.getLaunchArrivalError() : null;
                if (arrivalFailure != null) {
                    // The steady SGSR path still works: cancellation restores it without a codec reset.
                    launchArrivalError = arrivalFailure;
                    return;
                }
                if (launchArrivalRequested && !launchArrivalReducedMotion) {
                    launchArrivalError = "Stream arrival SGSR output failed";
                }
                sgsrFailed = true;
                sgsrRequested = false;
                codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET);
            });
            if (sgsr == null) { sgsrFailed = true; sgsrRequested = false; }
        }
        if (sgsrFailed && sgsr != null) { sgsr.close(); sgsr = null; }
        DirectLaunchArrival arrival = directLaunchArrival;
        // Error publication may race recovery; keep OES until its EGL detach is confirmed.
        boolean continueArrival = arrival != null && arrival.isRedirected()
                && !arrival.isDetachedForCodecRecovery();
        Surface decoderOutput = sgsr != null ? sgsr.getDecoderSurface()
                : continueArrival ? arrival.getDecoderSurface() : renderTarget;
        videoDecoder.configure(format, decoderOutput, null, 0);
        LimeLog.info(sgsr != null ? "Delos video output: SGSR surface" : continueArrival
                ? "Delos video output: temporary stream arrival surface"
                : "Delos video output: direct MediaCodec surface; no Delos sharpening shader");

        try { applySurfaceFrameRate(renderTarget, targetFps); } catch (Throwable t) { LimeLog.warning("applySurfaceFrameRate failed: " + t); }

        try {
            MediaCodecInfo __info = videoDecoder.getCodecInfo();
            String __name = (__info != null) ? __info.getName() : "<unknown>";
            LimeLog.info("Decoder name: " + __name);
        } catch (Throwable t) {
            LimeLog.info("Decoder name: <unavailable>");
        }


        configuredFormat = format;

        // After reconfiguration, we must resubmit CSD buffers
        submittedCsd = false;
        vpsBuffers.clear();
        spsBuffers.clear();
        ppsBuffers.clear();

        // This will contain the actual accepted input format attributes
        inputFormat = videoDecoder.getInputFormat();
        LimeLog.info("Input format: "+inputFormat);

        videoDecoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);

        // Start the decoder
        videoDecoder.start();
        synchronized (codecRecoveryMonitor) {
            decoderStarted = true;
            frameRenderedListenerEnabled = null; // stop/reset may preserve or remove registration.
            if (arrival != null && (arrival.isRedirected() || arrival.isAwaitingDirectFrame())
                    && !continueArrival && sgsr == null) {
                arrival.didRestoreDirect(lastTimestampUs, System.nanoTime());
            }
            updateFrameRenderedListener();
            prepareLaunchArrivalBackend();
        }

// Diagnostics: dump negotiated input/output formats and check vendor keys acceptance
        try {
            MediaFormat __inF = videoDecoder.getInputFormat();
            MediaFormat __outF = videoDecoder.getOutputFormat();
            LimeLog.info("Decoder input format: " + (__inF != null ? __inF.toString() : "<null>"));
            LimeLog.info("Decoder output format: " + (__outF != null ? __outF.toString() : "<null>"));
        } catch (Throwable t) {
            LimeLog.info("Decoder formats unavailable after start");
        }
    }

    private boolean tryConfigureDecoder(MediaCodecInfo selectedDecoderInfo, MediaFormat format, boolean throwOnCodecError) {
        boolean configured = false;
        try {
            videoDecoder = MediaCodec.createByCodecName(selectedDecoderInfo.getName());
            configureAndStartDecoder(format);
            LimeLog.info("Using codec " + selectedDecoderInfo.getName() + " for hardware decoding " + format.getString(MediaFormat.KEY_MIME));
            configured = true;
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            if (throwOnCodecError) {
                throw e;
            }
        } catch (IllegalStateException e) {
            e.printStackTrace();
            if (throwOnCodecError) {
                throw e;
            }
        } catch (IOException e) {
            e.printStackTrace();
            if (throwOnCodecError) {
                throw new RuntimeException(e);
            }
        } finally {
            if (!configured && videoDecoder != null) {
                decoderStarted = false;
                detachAsyncCodec();
                videoDecoder.release();
                videoDecoder = null;
            }
            // The SGSR renderer made for this attempt; the connection never calls cleanup() when setup() fails
            if (!configured && sgsr != null) {
                sgsr.close();
                sgsr = null;
            }
        }
        return configured;
    }

    public int initializeDecoder(boolean throwOnCodecError) {
        String mimeType;
        MediaCodecInfo selectedDecoderInfo;

        if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
            mimeType = "video/avc";
            selectedDecoderInfo = avcDecoder;

            if (avcDecoder == null) {
                LimeLog.severe("No available AVC decoder!");
                return -1;
            }

            if (initialWidth > 4096 || initialHeight > 4096) {
                LimeLog.severe("> 4K streaming only supported on HEVC");
                return -1;
            }

            // These fixups only apply to H264 decoders
            needsSpsBitstreamFixup = MediaCodecHelper.decoderNeedsSpsBitstreamRestrictions(selectedDecoderInfo.getName());
            needsBaselineSpsHack = MediaCodecHelper.decoderNeedsBaselineSpsHack(selectedDecoderInfo.getName());
            constrainedHighProfile = MediaCodecHelper.decoderNeedsConstrainedHighProfile(selectedDecoderInfo.getName());
            isExynos4 = MediaCodecHelper.isExynos4Device();
            if (needsSpsBitstreamFixup) {
                LimeLog.info("Decoder "+selectedDecoderInfo.getName()+" needs SPS bitstream restrictions fixup");
            }
            if (needsBaselineSpsHack) {
                LimeLog.info("Decoder "+selectedDecoderInfo.getName()+" needs baseline SPS hack");
            }
            if (constrainedHighProfile) {
                LimeLog.info("Decoder "+selectedDecoderInfo.getName()+" needs constrained high profile");
            }
            if (isExynos4) {
                LimeLog.info("Decoder "+selectedDecoderInfo.getName()+" is on Exynos 4");
            }

            refFrameInvalidationActive = refFrameInvalidationAvc;
        }
        else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
            mimeType = "video/hevc";
            selectedDecoderInfo = hevcDecoder;

            if (hevcDecoder == null) {
                LimeLog.severe("No available HEVC decoder!");
                return -2;
            }

            refFrameInvalidationActive = refFrameInvalidationHevc;
        }
        else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
            mimeType = "video/av01";
            selectedDecoderInfo = av1Decoder;

            if (av1Decoder == null) {
                LimeLog.severe("No available AV1 decoder!");
                return -2;
            }

            refFrameInvalidationActive = refFrameInvalidationAv1;
        }
        else {
            // Unknown format
            LimeLog.severe("Unknown format");
            return -3;
        }
        adaptivePlayback = MediaCodecHelper.decoderSupportsAdaptivePlayback(selectedDecoderInfo, mimeType);
        fusedIdrFrame = MediaCodecHelper.decoderSupportsFusedIdrFrame(selectedDecoderInfo, mimeType);

        for (int tryNumber = 0;; tryNumber++) {
            LimeLog.info("Decoder configuration try: "+tryNumber);

            MediaFormat mediaFormat = createBaseMediaFormat(mimeType);
            // This will try low latency options until we find one that works (or we give up).
            boolean newFormat = MediaCodecHelper.setDecoderLowLatencyOptions(mediaFormat, selectedDecoderInfo, prefs.enableUltraLowLatency, tryNumber);
            // Throw the underlying codec exception on the last attempt if the caller requested it
            if (tryConfigureDecoder(selectedDecoderInfo, mediaFormat, !newFormat && throwOnCodecError)) {
                // Success!
                break;
            }

            if (!newFormat) {
                // We couldn't even configure a decoder without any low latency options
                return -5;
            }
        }



        return 0;
    }

    @Override
    public int setup(int format, int width, int height, int redrawRate) {
        this.targetFps = (redrawRate > 0 ? redrawRate : 60);
        this.initialWidth = invertResolution ? height : width;
        this.initialHeight = invertResolution ? width : height;
        this.videoFormat = format;
        this.refreshRate = redrawRate;

        // Delos: PyroWave frames go from moonlight-common-c straight to the native renderer
        if ((format & MoonBridge.VIDEO_FORMAT_MASK_PYROWAVE) != 0) {
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            android.view.WindowManager windowManager =
                    (android.view.WindowManager) context.getSystemService(android.content.Context.WINDOW_SERVICE);
            if (windowManager != null) windowManager.getDefaultDisplay().getRealMetrics(metrics);
            else metrics.setTo(context.getResources().getDisplayMetrics());
            pyroWave = PyroWaveRenderer.create(renderTarget, width, height, targetFps,
                    metrics.widthPixels, metrics.heightPixels,
                    (format & MoonBridge.VIDEO_FORMAT_MASK_YUV444) != 0, prefs.fullRange, getPreferredColorSpace(),
                    (format & MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0,
                    getRequestedPyroWaveUpscalingMode(), prefs.sgsrSupersamplingLevel, prefs.sgsrSharpness / 100f);
            if (pyroWave == null) {
                LimeLog.severe("Unable to create the PyroWave renderer");
                return -1;
            }
            synchronized (codecRecoveryMonitor) {
                // Native frame submission may begin as soon as setup returns. Establish the
                // temporary owning worker now, before any HDR EGL context is used directly.
                prepareLaunchArrivalBackend();
            }
            LimeLog.info("PyroWave renderer created");
            return 0;
        }

        // Delos: SGSR on SDR output draws into window-sized buffers; the surface was sized for the
        // requested HDR, so size it again if the host settled on 8-bit.
        if (sgsrRequested && prefs.enableHdr && (format & MoonBridge.VIDEO_FORMAT_MASK_10BIT) == 0 &&
                context instanceof com.limelight.Game) {
            ((com.limelight.Game) context).useLayoutSizedStreamSurface();
        }

        return initializeDecoder(false);
    }

    public boolean isPyroWave() {
        return pyroWave != null;
    }

    /** Delos: live post-processing controls; a decoder reset requests an IDR, not a reconnect. */
    public void setUpscalingEnabled(boolean enabled) {
        synchronized (codecRecoveryMonitor) {
            if (stopping) return;
            PyroWaveRenderer pw = pyroWave;
            if (pw != null) {
                sgsrRequested = enabled;
                prefs.sgsrEnabled = enabled;
                pw.setUpscalingMode(getRequestedPyroWaveUpscalingMode());
                return;
            }
            if (directLaunchArrival != null
                    && (enabled != sgsrRequested || pendingUpscalingAfterArrival != null)) {
                // A codec reset must not create a second EGL producer while temporary output owns
                // the window. Apply this explicit menu change after the direct handover is finished.
                pendingUpscalingAfterArrival = enabled;
                cancelLaunchArrival("Video output pipeline changed during stream arrival");
                return;
            }
            if (enabled == sgsrRequested && !sgsrFailed) return;
            if (launchArrivalRequested && !launchArrivalComplete) {
                cancelLaunchArrival("Video output pipeline changed during stream arrival");
            }
            sgsrRequested = enabled;
            sgsrFailed = false;
            prefs.sgsrEnabled = enabled;
            codecRecoveryAttempts = 0;
            codecRecoveryType.set(CR_RECOVERY_TYPE_RESET);
        }
    }
    public boolean isUpscalingRequested() { return sgsrRequested; }
    public String getUpscalingStatus() {
        PyroWaveRenderer pw = pyroWave;
        if (pw != null) {
            switch (pw.getUpscalingMode()) {
                case PyroWaveRenderer.UPSCALING_SGSR1_STANDARD: return "On: SGSR 1 Standard";
                case PyroWaveRenderer.UPSCALING_SGSR1_HIGH: return "On: SGSR 1 High Quality";
                case PyroWaveRenderer.UPSCALING_SGSR2_VIDEO: return "On: SGSR 2 Video";
                case PyroWaveRenderer.UPSCALING_SGSR2_VIDEO_QUALITY: return "On: SGSR 2 Video Quality";
                default: return sgsrRequested ? "Unavailable; direct rendering is active" : "Off";
            }
        }
        if (sgsrFailed) return "Unavailable; direct rendering is active";
        if (sgsrRequested && sgsr == null) return "Starting";
        if (!sgsrRequested && sgsr != null) return "Stopping";
        return sgsr != null ? "On" : "Off";
    }
    public void setUpscalingSharpness(int value) {
        prefs.sgsrSharpness = Math.max(0, Math.min(100, value));
        SgsrRenderer current = sgsr;
        if (current != null) current.setSharpness(prefs.sgsrSharpness);
        PyroWaveRenderer pw = pyroWave;
        if (pw != null) pw.setSharpness(prefs.sgsrSharpness / 100f);
    }

    public void setUpscalingMode(int mode) {
        prefs.sgsrHighQuality = mode == 1;
        prefs.sgsrTemporal = mode >= 2;
        prefs.sgsrTemporalQuality = mode == 3;
        SgsrRenderer current = sgsr;
        if (current != null) current.setMode(mode);
        PyroWaveRenderer pw = pyroWave;
        if (pw != null) pw.setUpscalingMode(getRequestedPyroWaveUpscalingMode());
    }

    private int getRequestedPyroWaveUpscalingMode() {
        if (!sgsrRequested) return PyroWaveRenderer.UPSCALING_OFF;
        return prefs.sgsrTemporalQuality ? PyroWaveRenderer.UPSCALING_SGSR2_VIDEO_QUALITY :
                prefs.sgsrTemporal ? PyroWaveRenderer.UPSCALING_SGSR2_VIDEO :
                prefs.sgsrHighQuality ? PyroWaveRenderer.UPSCALING_SGSR1_HIGH :
                PyroWaveRenderer.UPSCALING_SGSR1_STANDARD;
    }

    public void setSupersamplingLevel(int level) {
        prefs.sgsrSupersamplingLevel = level >= 2 ? 2 : 1;
        SgsrRenderer current = sgsr;
        if (current != null) current.setSupersamplingLevel(prefs.sgsrSupersamplingLevel);
        PyroWaveRenderer pw = pyroWave;
        if (pw != null) pw.setSupersamplingLevel(prefs.sgsrSupersamplingLevel);
    }

    /** The level in effect: the requested one at once, or 1 once PyroWave found that 2 does not fit. */
    public int getSupersamplingLevel() {
        PyroWaveRenderer pw = pyroWave;
        return pw != null ? pw.getSupersamplingLevel() : prefs.sgsrSupersamplingLevel;
    }

    public boolean isSupersamplingMode() {
        PyroWaveRenderer pw = pyroWave;
        if (pw != null) return pw.isSupersampling();
        SgsrRenderer current = sgsr;
        if (current != null && current.isSupersampling()) return true;
        if (!(context instanceof com.limelight.Game)) return false;
        android.view.View view = ((com.limelight.Game) context).getStreamSurface();
        return view != null && view.getWidth() > 0 && view.getHeight() > 0 &&
                SgsrDimensions.isNativeInput(initialWidth, initialHeight, view.getWidth(), view.getHeight());
    }

    // Delos: data received and sent since the last call in KB, -1 when unknown or on the first call
    private float measureNetDataKb() {
        float realtimeNetData = -1;
        if(TrafficStatsHelper.getPackageRxBytes(Process.myUid()) != TrafficStats.UNSUPPORTED){
            long netData=TrafficStatsHelper.getPackageRxBytes(Process.myUid())+TrafficStatsHelper.getPackageTxBytes(Process.myUid());
            if(lastNetDataNum!=0){
                realtimeNetData=(netData-lastNetDataNum)/1024f;
            }
            lastNetDataNum=netData;
        }
        return realtimeNetData;
    }

    // Delos: PyroWave frames never reach submitDecodeUnit(), so the overlay's numbers come from the
    // native renderer's statistics, once a second. It runs without the overlay too: taking the statistics
    // also logs a stalled video thread.
    private void startPyroWaveStatsThread() {
        pyroWaveStatsThread = new Thread(() -> {
            long windowStart = SystemClock.uptimeMillis();
            while (!stopping) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                long now = SystemClock.uptimeMillis();
                float seconds = Math.max(now - windowStart, 1) / 1000f;
                windowStart = now;
                PyroWaveRenderer pw = pyroWave;
                if (pw == null) return;
                long[] stats = pw.takeStats();
                if (stats[PyroWaveRenderer.STAT_RENDERED_FRAMES] > 0) firstFramePresented = true;
                if (prefs.enablePerfOverlay) {
                    reportPyroWaveStats(stats, seconds);
                }
            }
        }, "PyroWave stats");
        pyroWaveStatsThread.start();
    }

    private void reportPyroWaveStats(long[] stats, float seconds) {
        long total = stats[PyroWaveRenderer.STAT_TOTAL_FRAMES];
        float totalFps = total / seconds;
        float receivedFps = stats[PyroWaveRenderer.STAT_RECEIVED_FRAMES] / seconds;
        float renderedFps = stats[PyroWaveRenderer.STAT_RENDERED_FRAMES] / seconds;
        float lossPercent = total > 0 ? (float) stats[PyroWaveRenderer.STAT_LOST_FRAMES] / total * 100 : 0f;
        long gpuFrames = stats[PyroWaveRenderer.STAT_GPU_DECODE_FRAMES];
        float decodeTimeMs = gpuFrames > 0 ? stats[PyroWaveRenderer.STAT_GPU_DECODE_TOTAL_US] / 1000f / gpuFrames : 0f;
        long drawFrames = stats[PyroWaveRenderer.STAT_GPU_DRAW_FRAMES];
        float processingTimeMs = drawFrames > 0 ? stats[PyroWaveRenderer.STAT_GPU_DRAW_TOTAL_US] / 1000f / drawFrames : 0f;
        long hostFrames = stats[PyroWaveRenderer.STAT_HOST_LATENCY_FRAMES];
        long rttInfo = MoonBridge.getEstimatedRttInfo();
        float realtimeNetData = measureNetDataKb();
        String decoder = "PyroWave (Vulkan)";

        StringBuilder sb = new StringBuilder();
        sb.append(context.getString(R.string.perf_overlay_streamdetails, initialWidth + "x" + initialHeight, totalFps)).append('\n');
        sb.append(context.getString(R.string.perf_overlay_decoder, decoder)).append('\n');
        sb.append(context.getString(R.string.perf_overlay_incomingfps, receivedFps)).append('\n');
        sb.append(context.getString(R.string.perf_overlay_renderingfps, renderedFps)).append('\n');
        sb.append(context.getString(R.string.perf_overlay_netdrops, lossPercent)).append('\n');
        sb.append(context.getString(R.string.perf_overlay_netlatency, (int) (rttInfo >> 32), (int) rttInfo)).append('\n');
        sb.append(context.getString(R.string.perf_overlay_dectime, decodeTimeMs));
        perfListener.onPerfUpdate(sb.toString());

        PyroWaveRenderer pw = pyroWave;
        perfListener.onPerfStats(new com.limelight.shell.StreamStats(
                initialWidth, initialHeight, totalFps, receivedFps, renderedFps,
                "PyroWave", (videoFormat & MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0, decoder,
                pw != null ? pw.getScaleDescription() : null, lossPercent,
                (int) (rttInfo >> 32), (int) rttInfo, decodeTimeMs, processingTimeMs,
                hostFrames > 0 ? stats[PyroWaveRenderer.STAT_HOST_LATENCY_MIN] / 10f : -1f,
                hostFrames > 0 ? stats[PyroWaveRenderer.STAT_HOST_LATENCY_MAX] / 10f : -1f,
                hostFrames > 0 ? stats[PyroWaveRenderer.STAT_HOST_LATENCY_TOTAL] / 10f / hostFrames : -1f,
                realtimeNetData, stats[PyroWaveRenderer.STAT_ONE_PERCENT_LOW_FPS_X100] / 100f));
    }

    // All streaming input/output/buffer owners must call this function regularly.
    private boolean doCodecRecoveryIfRequired(int quiescenceFlag) {
        // NB: We cannot check 'stopping' here because we could end up bailing in a partially
        // quiesced state that will cause the quiesced threads to never wake up.
        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE) {
            // Common case
            return false;
        }

        // We need some sort of recovery, so quiesce all threads before starting that
        synchronized (codecRecoveryMonitor) {
            awaitLaunchSurfaceChange();
            if (choreographerHandlerThread == null) {
                // If we have no choreographer thread, we can just mark that as quiesced right now.
                codecRecoveryThreadQuiescedFlags |= CR_FLAG_CHOREOGRAPHER;
            }

            codecRecoveryThreadQuiescedFlags |= quiescenceFlag;

            // Reserve one recovery owner before dropping the monitor for exceptional EGL detach.
            if (codecRecoveryThreadQuiescedFlags == CR_FLAG_ALL && !codecRecoveryInProgress) {
                codecRecoveryInProgress = true;
                launchSurfaceChangeInProgress = true;
            } else {
                while (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                    try { codecRecoveryMonitor.wait(1000); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
                return true;
            }
        }

        try {
            DirectLaunchArrival arrival = directLaunchArrival;
            if (arrival != null && arrival.isRedirected() && arrival.getError() != null) {
                // The codec still owns OES here. Detach its EGL output before resetting to direct.
                arrival.detachForCodecRecovery();
            }
            synchronized (codecRecoveryMonitor) {
                nanoPacer.reset();
                if (useAsyncCodec) asyncCodec.quiesceAndRelease(videoDecoder, asyncRelease);
                // Input and output buffers are invalidated by stop() and reset().
                nextInputBuffer = null;
                nextInputBufferIndex = -1;
                outputBufferQueue.clear();

                // If we just need a flush, do so now with all threads quiesced.
                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_FLUSH) {
                    LimeLog.warning("Flushing decoder");
                    try {
                        videoDecoder.flush();
                        if (useAsyncCodec) { asyncCodec.resumeAfterFlush(); videoDecoder.start(); }
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalStateException e) {
                        e.printStackTrace();

                        // Something went wrong during the restart, let's use a bigger hammer
                        // and try a reset instead.
                        codecRecoveryType.set(CR_RECOVERY_TYPE_RESTART);
                    }
                }

                // We don't count flushes as codec recovery attempts
                if (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                    codecRecoveryAttempts++;
                    LimeLog.info("Codec recovery attempt: "+codecRecoveryAttempts);
                }

                // For "recoverable" exceptions, we can just stop, reconfigure, and restart.
                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESTART) {
                    LimeLog.warning("Trying to restart decoder after CodecException");
                    try {
                        detachAsyncCodec();
                        decoderStarted = false;
                        videoDecoder.stop();
                        configureAndStartDecoder(configuredFormat);
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();

                        // Our Surface is probably invalid, so just stop
                        stopping = true;
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalStateException e) {
                        e.printStackTrace();

                        // Something went wrong during the restart, let's use a bigger hammer
                        // and try a reset instead.
                        codecRecoveryType.set(CR_RECOVERY_TYPE_RESET);
                    }
                }

                // For "non-recoverable" exceptions on L+, we can call reset() to recover
                // without having to recreate the entire decoder again.
                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET) {
                    LimeLog.warning("Trying to reset decoder after CodecException");
                    try {
                        detachAsyncCodec();
                        decoderStarted = false;
                        videoDecoder.reset();
                        configureAndStartDecoder(configuredFormat);
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();

                        // Our Surface is probably invalid, so just stop
                        stopping = true;
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalStateException e) {
                        e.printStackTrace();

                        // Something went wrong during the reset, we'll have to resort to
                        // releasing and recreating the decoder now.
                    }
                }

                // If we _still_ haven't managed to recover, go for the nuclear option and just
                // throw away the old decoder and reinitialize a new one from scratch.
                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET) {
                    LimeLog.warning("Trying to recreate decoder after CodecException");
                    detachAsyncCodec();
                    decoderStarted = false;
                    videoDecoder.release();

                    try {
                        int err = initializeDecoder(true);
                        if (err != 0) {
                            throw new IllegalStateException("Decoder reset failed: " + err);
                        }
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();

                        // Our Surface is probably invalid, so just stop
                        stopping = true;
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalStateException e) {
                        // If we failed to recover after all of these attempts, just crash
                        if (!reportedCrash) {
                            reportedCrash = true;
                            crashListener.notifyCrash(e);
                        }
                        throw new RendererException(this, e);
                    }
                }

                // Wake all quiesced threads and allow them to begin work again
                codecRecoveryThreadQuiescedFlags = 0;
                codecRecoveryMonitor.notifyAll();
            }
        } catch (RuntimeException e) {
            synchronized (codecRecoveryMonitor) {
                stopping = true;
                codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                if (!reportedCrash) {
                    reportedCrash = true;
                    crashListener.notifyCrash(e);
                }
            }
            throw e;
        } finally {
            synchronized (codecRecoveryMonitor) {
                codecRecoveryInProgress = false;
                launchSurfaceChangeInProgress = false;
                codecRecoveryThreadQuiescedFlags = 0;
                codecRecoveryMonitor.notifyAll();
            }
        }

        return true;
    }

    // Returns true if the exception is transient
    private boolean handleDecoderException(IllegalStateException e) {
        // Eat decoder exceptions if we're in the process of stopping
        if (stopping) {
            return false;
        }

        if (e instanceof CodecException) {
            CodecException codecExc = (CodecException) e;

            if (codecExc.isTransient()) {
                // We'll let transient exceptions go
                LimeLog.warning(codecExc.getDiagnosticInfo());
                return true;
            }

            LimeLog.severe(codecExc.getDiagnosticInfo());

            // We can attempt a recovery or reset at this stage to try to start decoding again
            if (codecRecoveryAttempts < CR_MAX_TRIES) {
                // If the exception is non-recoverable or we already require a reset, perform a reset.
                // If we have no prior unrecoverable failure, we will try a restart instead.
                if (codecExc.isRecoverable()) {
                    if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART)) {
                        LimeLog.info("Decoder requires restart for recoverable CodecException");
                        e.printStackTrace();
                    }
                    else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART)) {
                        LimeLog.info("Decoder flush promoted to restart for recoverable CodecException");
                        e.printStackTrace();
                    }
                    else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET && codecRecoveryType.get() != CR_RECOVERY_TYPE_RESTART) {
                        throw new IllegalStateException("Unexpected codec recovery type: " + codecRecoveryType.get());
                    }
                }
                else if (!codecExc.isRecoverable()) {
                    if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET)) {
                        LimeLog.info("Decoder requires reset for non-recoverable CodecException");
                        e.printStackTrace();
                    }
                    else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESET)) {
                        LimeLog.info("Decoder flush promoted to reset for non-recoverable CodecException");
                        e.printStackTrace();
                    }
                    else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_RESET)) {
                        LimeLog.info("Decoder restart promoted to reset for non-recoverable CodecException");
                        e.printStackTrace();
                    }
                    else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET) {
                        throw new IllegalStateException("Unexpected codec recovery type: " + codecRecoveryType.get());
                    }
                }

                // The recovery will take place when all threads reach doCodecRecoveryIfRequired().
                return false;
            }
        }
        else {
            // IllegalStateException was primarily used prior to the introduction of CodecException.
            // Recovery from this requires a full decoder reset.
            //
            // NB: CodecException is an IllegalStateException, so we must check for it first.
            if (codecRecoveryAttempts < CR_MAX_TRIES) {
                if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET)) {
                    LimeLog.info("Decoder requires reset for IllegalStateException");
                    e.printStackTrace();
                }
                else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESET)) {
                    LimeLog.info("Decoder flush promoted to reset for IllegalStateException");
                    e.printStackTrace();
                }
                else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_RESET)) {
                    LimeLog.info("Decoder restart promoted to reset for IllegalStateException");
                    e.printStackTrace();
                }
                else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET) {
                    throw new IllegalStateException("Unexpected codec recovery type: " + codecRecoveryType.get());
                }

                return false;
            }
        }

        // Only throw if we're not in the middle of codec recovery
        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE) {
            //
            // There seems to be a race condition with decoder/surface teardown causing some
            // decoders to to throw IllegalStateExceptions even before 'stopping' is set.
            // To workaround this while allowing real exceptions to propagate, we will eat the
            // first exception. If we are still receiving exceptions 3 seconds later, we will
            // throw the original exception again.
            //
            if (initialException != null) {
                // This isn't the first time we've had an exception processing video
                if (SystemClock.uptimeMillis() - initialExceptionTimestamp >= EXCEPTION_REPORT_DELAY_MS) {
                    // It's been over 3 seconds and we're still getting exceptions. Throw the original now.
                    if (!reportedCrash) {
                        reportedCrash = true;
                        crashListener.notifyCrash(initialException);
                    }
                    throw initialException;
                }
            }
            else {
                // This is the first exception we've hit
                initialException = new RendererException(this, e);
                initialExceptionTimestamp = SystemClock.uptimeMillis();
            }
        }

        // Not transient
        return false;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        // Do nothing if we're stopping
        if (stopping) {
            return;
        }

        frameTimeNanos -= activity.getWindowManager().getDefaultDisplay().getAppVsyncOffsetNanos();

        // Don't render unless a new frame is due. This prevents microstutter when streaming
        // at a frame rate that doesn't match the display (such as 60 FPS on 120 Hz).
        long actualFrameTimeDeltaNs = frameTimeNanos - lastRenderedFrameTimeNanos;
        long expectedFrameTimeDeltaNs = 800000000 / refreshRate; // within 80% of the next frame
        if (actualFrameTimeDeltaNs >= expectedFrameTimeDeltaNs) {
            // Render up to one frame when in frame pacing mode.
            //
            // NB: Since the queue limit is 2, we won't starve the decoder of output buffers
            // by holding onto them for too long. This also ensures we will have that 1 extra
            // frame of buffer to smooth over network/rendering jitter.
            Integer nextOutputBuffer = outputBufferQueue.poll();
            if (nextOutputBuffer != null) {
                try {
                    if (preferLowerDelays) {
                        // LFR: present at the next vsync instead of the scheduled frame time
                        releaseWithPolicy(nextOutputBuffer, System.nanoTime());
                    } else {
                        videoDecoder.releaseOutputBuffer(nextOutputBuffer, frameTimeNanos);
                    }

                    lastRenderedFrameTimeNanos = frameTimeNanos;
                    activeWindowVideoStats.totalFramesRendered++;
                    frameTimes.add(System.nanoTime());
                } catch (IllegalStateException ignored) {
                    try {
                        // Try to avoid leaking the output buffer by releasing it without rendering
                        videoDecoder.releaseOutputBuffer(nextOutputBuffer, false);
                    } catch (IllegalStateException e) {
                        // This will leak nextOutputBuffer, but there's really nothing else we can do
                        e.printStackTrace();
                        handleDecoderException(e);
                    }
                }
            }
        }

        // Attempt codec recovery even if we have nothing to render right now. Recovery can still
        // be required even if the codec died before giving any output.
        doCodecRecoveryIfRequired(CR_FLAG_CHOREOGRAPHER);

        // Request another callback for next frame
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void startChoreographerThread() {
        if (prefs.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED) {
            // Not using Choreographer in this pacing mode
            return;
        }

        // We use a separate thread to avoid any main thread delays from delaying rendering
        choreographerHandlerThread = new HandlerThread("Video - Choreographer", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        choreographerHandlerThread.start();

        // Start the frame callbacks
        choreographerHandler = new Handler(choreographerHandlerThread.getLooper());
        choreographerHandler.post(new Runnable() {
            @Override
            public void run() {
                Choreographer.getInstance().postFrameCallback(MediaCodecDecoderRenderer.this);
            }
        });
    }

    private void startRendererThread()
    {
        rendererThread = new Thread() {
            @Override
            public void run() {
                // Boost thread priority to reduce decoding latency
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);

                BufferInfo info = new BufferInfo();
                int tryAgainStreak = 0;
                while (!stopping) {
                    try {
                        // Latency mode: show the newest decoded frame as soon as it is ready
                        if (prefs.framePacing == PreferenceConfiguration.FRAME_PACING_MIN_LATENCY &&
                                renderNewestFrameNow(info)) {
                            continue;
                        }

                        // Try to output a frame
                        if (useAsyncCodec) asyncCodec.drainNoRenderReleaseQueue(videoDecoder, asyncRelease);
                        int outIndex = dequeueOutput(info, getOutputDequeueTimeoutUs());
                        if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            // Short backoff before polling again
                            tryAgainStreak++;
                            outIndex = dequeueOutput(info, tryAgainStreak <= 2 ? 250 : 500);
                        } else {
                            tryAgainStreak = 0;
                        }

                        if (outIndex >= 0) {
                            long presentationTimeUs = info.presentationTimeUs;
                            int lastIndex = outIndex;
                            // When the newest frame left the codec: stamped before any pacer wait or
                            // release, so the decode statistics do not include time spent pacing.
                            long dequeueNs = System.nanoTime();

                            numFramesOut++;

                            // Render the latest frame now if frame pacing isn't in balanced mode
                            if (prefs.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED) {
                                // Get the last output buffer in the queue
                                while ((outIndex = dequeueOutput(info, 0)) >= 0) {
                                    videoDecoder.releaseOutputBuffer(lastIndex, false);

                                    numFramesOut++;

                                    lastIndex = outIndex;
                                    presentationTimeUs = info.presentationTimeUs;
                                    dequeueNs = System.nanoTime();
                                }

                                if (prefs.framePacing == PreferenceConfiguration.FRAME_PACING_FAST_VSYNC) {
                                    nanoPacer.updatePacingMode(true, refreshRate, activity.getWindowManager().getDefaultDisplay().getRefreshRate());
                                    nanoLatest.index = lastIndex;
                                    nanoLatest.ptsUs = presentationTimeUs;
                                    nanoLatest.dequeueNs = dequeueNs;
                                    numFramesOut += nanoPacer.waitAndDrainLatest(info, nanoLatest, nanoCallbacks);
                                    lastIndex = nanoLatest.index;
                                    presentationTimeUs = nanoLatest.ptsUs;
                                    dequeueNs = nanoLatest.dequeueNs;
                                    videoDecoder.releaseOutputBuffer(lastIndex, System.nanoTime());
                                }
                                else if (prefs.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS ||
                                        prefs.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
                                    // In max smoothness or cap FPS mode, we want to never drop frames
                                    // Use a PTS that will cause this frame to never be dropped
                                    videoDecoder.releaseOutputBuffer(lastIndex, 0);
                                }
                                else {
                                    // Use a PTS that will cause this frame to be dropped if another comes in within
                                    // the same V-sync period
                                    videoDecoder.releaseOutputBuffer(lastIndex, System.nanoTime());
                                }

                                activeWindowVideoStats.totalFramesRendered++;

                                frameTimes.add(System.nanoTime());
                            }
                            else {
                                // For balanced frame pacing case, the Choreographer callback will handle rendering.
                                // We just put all frames into the output buffer queue and let it handle things.

                                // Discard the oldest buffer if we've exceeded our limit.
                                //
                                // NB: We have to do this on the producer side because the consumer may not
                                // run for a while (if there is a huge mismatch between stream FPS and display
                                // refresh rate).
                                if (outputBufferQueue.size() == OUTPUT_BUFFER_QUEUE_LIMIT) {
                                    try {
                                        videoDecoder.releaseOutputBuffer(outputBufferQueue.take(), false);
                                    } catch (InterruptedException e) {
                                        // We're shutting down, so we can just drop this buffer on the floor
                                        // and it will be reclaimed when the codec is released.
                                        return;
                                    }
                                }

                                // Add this buffer
                                outputBufferQueue.add(lastIndex);
                            }

                            updateDecodeLatencyStats(presentationTimeUs, dequeueNs);
                        } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            handleOutputFormatChanged();
                        }
                    } catch (IllegalStateException e) {
                        handleDecoderException(e);
                    } finally {
                        doCodecRecoveryIfRequired(CR_FLAG_RENDER_THREAD);
                    }
                }
            }
        };
        rendererThread.setName("Video - Renderer (MediaCodec)");
        rendererThread.setPriority(Thread.NORM_PRIORITY + 2);
        rendererThread.start();
    }

    /**
     * Latency mode fast path: drains all decoded frames without blocking and renders only
     * the newest one immediately, dropping older ones.
     *
     * @return false if no decoded frame was ready
     */
    private boolean renderNewestFrameNow(BufferInfo info) {
        int newestIndex = -1;
        long newestPtsUs = 0;
        int index;
        while ((index = dequeueOutput(info, 0)) >= 0) {
            if (newestIndex >= 0) {
                // A newer frame is ready, so drop the older one
                videoDecoder.releaseOutputBuffer(newestIndex, false);
            }
            newestIndex = index;
            newestPtsUs = info.presentationTimeUs;
            numFramesOut++;
        }

        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            handleOutputFormatChanged();
        }

        if (newestIndex < 0) {
            return false;
        }

        final long dequeueNs = System.nanoTime();
        videoDecoder.releaseOutputBuffer(newestIndex, System.nanoTime());
        activeWindowVideoStats.totalFramesRendered++;
        frameTimes.add(System.nanoTime());
        updateDecodeLatencyStats(newestPtsUs, dequeueNs);
        return true;
    }

    private void handleOutputFormatChanged() {
        LimeLog.info("Output format changed");
        outputFormat = videoDecoder.getOutputFormat();
        LimeLog.info("New output format: " + outputFormat);
    }

    private boolean fetchNextInputBuffer() {
        long startTime;
        boolean codecRecovered;

        if (nextInputBuffer != null) {
            // We already have an input buffer
            return true;
        }

        startTime = SystemClock.uptimeMillis();

        try {
            // If we don't have an input buffer index yet, fetch one now
            while (nextInputBufferIndex < 0 && !stopping && (!useAsyncCodec || codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE)) {
                nextInputBufferIndex = dequeueInput(10000);
            }

            // Get the backing ByteBuffer for the input buffer index
            if (nextInputBufferIndex >= 0) {
                // Using the new getInputBuffer() API on Lollipop allows
                // the framework to do some performance optimizations for us
                nextInputBuffer = videoDecoder.getInputBuffer(nextInputBufferIndex);
                if (nextInputBuffer == null) {
                    // According to the Android docs, getInputBuffer() can return null "if the
                    // index is not a dequeued input buffer". I don't think this ever should
                    // happen but if it does, let's try to get a new input buffer next time.
                    nextInputBufferIndex = -1;
                }
            }
        } catch (IllegalStateException e) {
            handleDecoderException(e);
            return false;
        } finally {
            codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD);
        }

        // If codec recovery is required, always return false to ensure the caller will request
        // an IDR frame to complete the codec recovery.
        if (codecRecovered) {
            return false;
        }

        int deltaMs = (int)(SystemClock.uptimeMillis() - startTime);

        if (deltaMs >= 20) {
            LimeLog.warning("Dequeue input buffer ran long: " + deltaMs + " ms");
        }

        if (nextInputBuffer == null) {
            // We've been hung for 5 seconds and no other exception was reported,
            // so generate a decoder hung exception
            if (deltaMs >= 5000 && initialException == null) {
                DecoderHungException decoderHungException = new DecoderHungException(deltaMs);
                if (!reportedCrash) {
                    reportedCrash = true;
                    crashListener.notifyCrash(decoderHungException);
                }
                throw new RendererException(this, decoderHungException);
            }

            return false;
        }

        return true;
    }

    @Override
    public void start() {
        synchronized (codecRecoveryMonitor) {
            prepareLaunchArrivalBackend();
        }
        if (pyroWave != null) {
            startPyroWaveStatsThread();
            return;
        }
        startRendererThread();
        startChoreographerThread();
    }

    // !!! May be called even if setup()/start() fails !!!
    public void prepareForStop() {
        // Let the decoding code know to ignore codec exceptions now
        stopping = true;
        cancelLaunchArrival(null);

        // Halt the rendering thread
        if (rendererThread != null) {
            rendererThread.interrupt();
        }
        if (pyroWaveStatsThread != null) {
            pyroWaveStatsThread.interrupt();
        }

        // Stop any active codec recovery operations
        synchronized (codecRecoveryMonitor) {
            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
            codecRecoveryMonitor.notifyAll();
        }

        // Post a quit message to the Choreographer looper (if we have one)
        if (choreographerHandler != null) {
            choreographerHandler.post(new Runnable() {
                @Override
                public void run() {
                    // Don't allow any further messages to be queued
                    choreographerHandlerThread.quit();

                    // Deregister the frame callback (if registered)
                    Choreographer.getInstance().removeFrameCallback(MediaCodecDecoderRenderer.this);
                }
            });
        }
    }

    @Override
    public void stop() {
        // May be called already, but we'll call it now to be safe
        prepareForStop();

        if (pyroWave != null) {
            if (pyroWaveStatsThread != null) {
                try {
                    pyroWaveStatsThread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return;
        }

        // Wait for the Choreographer looper to shut down (if we have one)
        if (choreographerHandlerThread != null) {
            try {
                choreographerHandlerThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();

                // InterruptedException clears the thread's interrupt status. Since we can't
                // handle that here, we will re-interrupt the thread to set the interrupt
                // status back to true.
                Thread.currentThread().interrupt();
            }
        }

        // Wait for the renderer thread to shut down
        try {
            if (rendererThread != null) rendererThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();

            // InterruptedException clears the thread's interrupt status. Since we can't
            // handle that here, we will re-interrupt the thread to set the interrupt
            // status back to true.
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void cleanup() {
        PyroWaveRenderer pw = pyroWave;
        if (pw != null) {
            pyroWave = null;
            pw.destroy();
            return;
        }
        DirectLaunchArrival arrival;
        synchronized (codecRecoveryMonitor) {
            awaitLaunchSurfaceChange();
            decoderStarted = false;
            detachAsyncCodec();
            if (videoDecoder != null) {
                videoDecoder.release();
                videoDecoder = null;
            }
            arrival = directLaunchArrival;
            directLaunchArrival = null;
        }
        // No UI joins: the worker releases GL/SurfaceTexture/HDR and shuts itself down after
        // codec release, even if preparation was still in flight when the surface disappeared.
        if (arrival != null) arrival.onDecoderReleased();
        if (sgsr != null) { sgsr.close(); sgsr = null; }
    }

    @Override
    public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
        sgsrHdrActive = enabled;
        DirectLaunchArrival arrival = directLaunchArrival;
        if (arrival != null) arrival.setHdrMode(enabled, enabled ? hdrMetadata : null);
        SgsrRenderer currentSgsr = sgsr;
        if (currentSgsr != null) currentSgsr.setHdrMode(enabled, hdrMetadata);
        PyroWaveRenderer pw = pyroWave;
        if (pw != null) {
            // The renderer reads the host's metadata itself
            pw.setHdrMode(enabled);
            return;
        }
        // HDR metadata is only supported in Android 7.0 and later, so don't bother
        // restarting the codec on anything earlier than that.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (currentHdrMetadata != null && (!enabled || hdrMetadata == null)) {
                currentHdrMetadata = null;
            }
            else if (enabled && hdrMetadata != null && !Arrays.equals(currentHdrMetadata, hdrMetadata)) {
                currentHdrMetadata = hdrMetadata;
            }
            else {
                // Nothing to do
                return;
            }

            // If we reach this point, we need to restart the MediaCodec instance to
            // pick up the HDR metadata change. This will happen on the next input
            // or output buffer.

            // HACK: Reset codec recovery attempt counter, since this is an expected "recovery"
            codecRecoveryAttempts = 0;

            // Promote None/Flush to Restart and leave Reset alone
            if (!codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART)) {
                codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART);
            }
        }
    }

    private boolean queueNextInputBuffer(long timestampUs, int codecFlags) {
        boolean codecRecovered;

        try {
            videoDecoder.queueInputBuffer(nextInputBufferIndex,
                    0, nextInputBuffer.position(),
                    timestampUs, codecFlags);

            // Track enqueue time for this PTS
            recordEnqueueTime(timestampUs, System.nanoTime());

            // We need a new buffer now
            nextInputBufferIndex = -1;
            nextInputBuffer = null;
        } catch (IllegalStateException e) {
            if (handleDecoderException(e)) {
                // We encountered a transient error. In this case, just hold onto the buffer
                // (to avoid leaking it), clear it, and keep it for the next frame. We'll return
                // false to trigger an IDR frame to recover.
                nextInputBuffer.clear();
            }
            else {
                // We encountered a non-transient error. In this case, we will simply leak the
                // buffer because we cannot be sure we will ever succeed in queuing it.
                nextInputBufferIndex = -1;
                nextInputBuffer = null;
            }
            return false;
        } finally {
            codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD);
        }

        // If codec recovery is required, always return false to ensure the caller will request
        // an IDR frame to complete the codec recovery.
        if (codecRecovered) {
            return false;
        }

        // Fetch a new input buffer now while we have some time between frames
        // to have it ready immediately when the next frame arrives.
        //
        // We must propagate the return value here in order to properly handle
        // codec recovery happening in fetchNextInputBuffer(). If we don't, we'll
        // never get an IDR frame to complete the recovery process.
        // Async callbacks own the input indices. Do not retain their direct ByteBuffer between
        // frames: a concurrent flush/reset invalidates its native address and a later put() can
        // crash in libc before Java has a chance to throw. Fetch it for the next frame instead.
        return useAsyncCodec || fetchNextInputBuffer();
    }

    private void doProfileSpecificSpsPatching(SeqParameterSet sps) {
        // Some devices benefit from setting constraint flags 4 & 5 to make this Constrained
        // High Profile which allows the decoder to assume there will be no B-frames and
        // reduce delay and buffering accordingly. Some devices (Marvell, Exynos 4) don't
        // like it so we only set them on devices that are confirmed to benefit from it.
        if (sps.profileIdc == 100 && constrainedHighProfile) {
            LimeLog.info("Setting constraint set flags for constrained high profile");
            sps.constraintSet4Flag = true;
            sps.constraintSet5Flag = true;
        }
        else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Force the constraints unset otherwise (some may be set by default)
            sps.constraintSet4Flag = false;
            sps.constraintSet5Flag = false;
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                int frameNumber, int frameType, char frameHostProcessingLatency,
                                long receiveTimeUs, long enqueueTimeUs) {
        if (stopping) {
            // Don't bother if we're stopping
            return MoonBridge.DR_OK;
        }

        if (lastFrameNumber == 0) {
            activeWindowVideoStats.measurementStartTimestamp = SystemClock.uptimeMillis();
        } else if (frameNumber != lastFrameNumber && frameNumber != lastFrameNumber + 1) {
            // We can receive the same "frame" multiple times if it's an IDR frame.
            // In that case, each frame start NALU is submitted independently.
            activeWindowVideoStats.framesLost += frameNumber - lastFrameNumber - 1;
            activeWindowVideoStats.totalFrames += frameNumber - lastFrameNumber - 1;
            activeWindowVideoStats.frameLossEvents++;
        }

        // Reset CSD data for each IDR frame
        if (lastFrameNumber != frameNumber && frameType == MoonBridge.FRAME_TYPE_IDR) {
            vpsBuffers.clear();
            spsBuffers.clear();
            ppsBuffers.clear();
        }

        lastFrameNumber = frameNumber;

        // Flip stats windows roughly every second
        if (SystemClock.uptimeMillis() >= activeWindowVideoStats.measurementStartTimestamp + 1000) {
            if (prefs.enablePerfOverlay) {
                VideoStats lastTwo = new VideoStats();
                lastTwo.add(lastWindowVideoStats);
                lastTwo.add(activeWindowVideoStats);
                VideoStatsFps fps = lastTwo.getFps();
                String decoder;

                if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
                    decoder = avcDecoder.getName();
                } else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
                    decoder = hevcDecoder.getName();
                } else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
                    decoder = av1Decoder.getName();
                } else {
                    decoder = "(unknown)";
                }

                float decodeTimeMs = lastTwo.totalFramesReceived > 0 ?
                        (float)lastTwo.decoderTimeMs / lastTwo.totalFramesReceived : 0f;
                SgsrRenderer activeSgsr = sgsr;
                float processingTimeMs = activeSgsr != null ? activeSgsr.getGpuTimeMs() : 0f;
                long rttInfo = MoonBridge.getEstimatedRttInfo();
                // Delos: measured once for both overlays, in KB per stats window (about a second); -1 if unknown
                float realtimeNetData = measureNetDataKb();
                StringBuilder sb = new StringBuilder();
                if(prefs.enablePerfOverlayLite){
                    if(realtimeNetData>=0){
                        sb.append(context.getString(R.string.perf_overlay_lite_bandwidth) + ": ");
                        if(realtimeNetData>=1000){
                            sb.append(String.format("%.2f", realtimeNetData/1024f) +"M/s\t ");
                        }else{
                            sb.append(String.format("%.2f", realtimeNetData) +"K/s\t ");
                        }
                    }
                    sb.append(context.getString(R.string.perf_overlay_lite_network_decoding_delay) + ": ");
                    sb.append(context.getString(R.string.perf_overlay_lite_net,(int)(rttInfo >> 32)));
                    sb.append(" / ");
                    sb.append(context.getString(R.string.perf_overlay_lite_dectime,decodeTimeMs));
                    sb.append("\t");
                    sb.append(context.getString(R.string.perf_overlay_lite_packet_loss) + ": ");
                    sb.append(context.getString(R.string.perf_overlay_lite_netdrops,(float)lastTwo.framesLost / lastTwo.totalFrames * 100));
                    sb.append("\t FPS: ");
                    sb.append(context.getString(R.string.perf_overlay_lite_fps, fps.totalFps));
                }else{
                    // If GPU renders the frames, the render FPS is the actual drawn and visible fps for the user
                    sb.append(context.getString(R.string.perf_overlay_streamdetails, initialWidth + "x" + initialHeight, fps.totalFps));
                    sb.append('\n');
                    sb.append(context.getString(R.string.perf_overlay_decoder, decoder)).append('\n');
                    sb.append(context.getString(R.string.perf_overlay_incomingfps, fps.receivedFps)).append('\n');
                    sb.append(context.getString(R.string.perf_overlay_renderingfps, fps.renderedFps)).append('\n');
                    sb.append(context.getString(R.string.perf_overlay_netdrops,
                            (float)lastTwo.framesLost / lastTwo.totalFrames * 100)).append('\n');
                    if(realtimeNetData>=0){
                        sb.append(context.getString(R.string.perf_overlay_lite_bandwidth) + ": ");
                        if(realtimeNetData>=1000){
                            sb.append(String.format("%.2f", realtimeNetData/1024f) +"M/s\n");
                        }else{
                            sb.append(String.format("%.2f", realtimeNetData) +"K/s\n");
                        }
                    }
                    sb.append(context.getString(R.string.perf_overlay_netlatency,
                            (int)(rttInfo >> 32), (int)rttInfo)).append('\n');
                    if (lastTwo.framesWithHostProcessingLatency > 0) {
                        sb.append(context.getString(R.string.perf_overlay_hostprocessinglatency,
                                (float)lastTwo.minHostProcessingLatency / 10,
                                (float)lastTwo.maxHostProcessingLatency / 10,
                                (float)lastTwo.totalHostProcessingLatency / 10 / lastTwo.framesWithHostProcessingLatency)).append('\n');
                    }
                    sb.append(context.getString(R.string.perf_overlay_dectime, decodeTimeMs));
                }
                perfListener.onPerfUpdate(sb.toString());
                // Delos: the same numbers for the Delos statistics panel
                boolean hostLatency = lastTwo.framesWithHostProcessingLatency > 0;
                perfListener.onPerfStats(new com.limelight.shell.StreamStats(
                        initialWidth, initialHeight, fps.totalFps, fps.receivedFps, fps.renderedFps,
                        (videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0 ? "AV1" : (videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0 ? "HEVC" : "H.264",
                        (videoFormat & MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0, decoder,
                        sgsr != null ? sgsr.getScaleDescription() : null,
                        lastTwo.totalFrames > 0 ? (float) lastTwo.framesLost / lastTwo.totalFrames * 100 : 0f,
                        (int) (rttInfo >> 32), (int) rttInfo, decodeTimeMs, processingTimeMs,
                        hostLatency ? (float) lastTwo.minHostProcessingLatency / 10 : -1f,
                        hostLatency ? (float) lastTwo.maxHostProcessingLatency / 10 : -1f,
                        hostLatency ? (float) lastTwo.totalHostProcessingLatency / 10 / lastTwo.framesWithHostProcessingLatency : -1f,
                        realtimeNetData, frameTimes.onePercentLowFps()));
            }
            globalVideoStats.add(activeWindowVideoStats);
            lastWindowVideoStats.copy(activeWindowVideoStats);
            activeWindowVideoStats.clear();
            activeWindowVideoStats.measurementStartTimestamp = SystemClock.uptimeMillis();
        }

        boolean csdSubmittedForThisFrame = false;

        // IDR frames require special handling for CSD buffer submission
        if (frameType == MoonBridge.FRAME_TYPE_IDR) {
            // H264 SPS
            if (decodeUnitType == MoonBridge.BUFFER_TYPE_SPS && (videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
                numSpsIn++;

                ByteBuffer spsBuf = ByteBuffer.wrap(decodeUnitData);
                int startSeqLen = decodeUnitData[2] == 0x01 ? 3 : 4;

                // Skip to the start of the NALU data
                spsBuf.position(startSeqLen + 1);

                // The H264Utils.readSPS function safely handles
                // Annex B NALUs (including NALUs with escape sequences)
                SeqParameterSet sps = H264Utils.readSPS(spsBuf);

                // Some decoders rely on H264 level to decide how many buffers are needed
                // Since we only need one frame buffered, we'll set the level as low as we can
                // for known resolution combinations. Reference frame invalidation may need
                // these, so leave them be for those decoders.
                if (!refFrameInvalidationActive && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    if (initialWidth <= 720 && initialHeight <= 480 && refreshRate <= 60) {
                        // Max 5 buffered frames at 720x480x60
                        LimeLog.info("Patching level_idc to 31");
                        sps.levelIdc = 31;
                    }
                    else if (initialWidth <= 1280 && initialHeight <= 720 && refreshRate <= 60) {
                        // Max 5 buffered frames at 1280x720x60
                        LimeLog.info("Patching level_idc to 32");
                        sps.levelIdc = 32;
                    }
                    else if (initialWidth <= 1920 && initialHeight <= 1080 && refreshRate <= 60) {
                        // Max 4 buffered frames at 1920x1080x64
                        LimeLog.info("Patching level_idc to 42");
                        sps.levelIdc = 42;
                    }
                    else {
                        // Leave the profile alone (currently 5.0)
                    }
                }

                // TI OMAP4 requires a reference frame count of 1 to decode successfully. Exynos 4
                // also requires this fixup.
                //
                // I'm doing this fixup for all devices because I haven't seen any devices that
                // this causes issues for. At worst, it seems to do nothing and at best it fixes
                // issues with video lag, hangs, and crashes.
                //
                // It does break reference frame invalidation, so we will not do that for decoders
                // where we've enabled reference frame invalidation.
                if (!refFrameInvalidationActive) {
                    LimeLog.info("Patching num_ref_frames in SPS");
                    sps.numRefFrames = 1;
                }

                // GFE 2.5.11 changed the SPS to add additional extensions. Some devices don't like these
                // so we remove them here on old devices unless these devices also support HEVC.
                // See getPreferredColorSpace() for further information.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O &&
                        sps.vuiParams != null &&
                        hevcDecoder == null &&
                        av1Decoder == null) {
                    sps.vuiParams.videoSignalTypePresentFlag = false;
                    sps.vuiParams.colourDescriptionPresentFlag = false;
                    sps.vuiParams.chromaLocInfoPresentFlag = false;
                }

                // Some older devices used to choke on a bitstream restrictions, so we won't provide them
                // unless explicitly whitelisted. For newer devices, leave the bitstream restrictions present.
                if (needsSpsBitstreamFixup || isExynos4 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // The SPS that comes in the current H264 bytestream doesn't set bitstream_restriction_flag
                    // or max_dec_frame_buffering which increases decoding latency on Tegra.

                    // If the encoder didn't include VUI parameters in the SPS, add them now
                    if (sps.vuiParams == null) {
                        LimeLog.info("Adding VUI parameters");
                        sps.vuiParams = new VUIParameters();
                    }

                    // GFE 2.5.11 started sending bitstream restrictions
                    if (sps.vuiParams.bitstreamRestriction == null) {
                        LimeLog.info("Adding bitstream restrictions");
                        sps.vuiParams.bitstreamRestriction = new VUIParameters.BitstreamRestriction();
                        sps.vuiParams.bitstreamRestriction.motionVectorsOverPicBoundariesFlag = true;
                        sps.vuiParams.bitstreamRestriction.maxBytesPerPicDenom = 2;
                        sps.vuiParams.bitstreamRestriction.maxBitsPerMbDenom = 1;
                        sps.vuiParams.bitstreamRestriction.log2MaxMvLengthHorizontal = 16;
                        sps.vuiParams.bitstreamRestriction.log2MaxMvLengthVertical = 16;
                        sps.vuiParams.bitstreamRestriction.numReorderFrames = 0;
                    }
                    else {
                        LimeLog.info("Patching bitstream restrictions");
                    }

                    // Some devices throw errors if maxDecFrameBuffering < numRefFrames
                    sps.vuiParams.bitstreamRestriction.maxDecFrameBuffering = sps.numRefFrames;

                    // These values are the defaults for the fields, but they are more aggressive
                    // than what GFE sends in 2.5.11, but it doesn't seem to cause picture problems.
                    // We'll leave these alone for "modern" devices just in case they care.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        sps.vuiParams.bitstreamRestriction.maxBytesPerPicDenom = 2;
                        sps.vuiParams.bitstreamRestriction.maxBitsPerMbDenom = 1;
                    }

                    // log2_max_mv_length_horizontal and log2_max_mv_length_vertical are set to more
                    // conservative values by GFE 2.5.11. We'll let those values stand.
                }
                else if (sps.vuiParams != null) {
                    // Devices that didn't/couldn't get bitstream restrictions before GFE 2.5.11
                    // will continue to not receive them now
                    sps.vuiParams.bitstreamRestriction = null;
                }

                // If we need to hack this SPS to say we're baseline, do so now
                if (needsBaselineSpsHack) {
                    LimeLog.info("Hacking SPS to baseline");
                    sps.profileIdc = 66;
                    savedSps = sps;
                }

                // Patch the SPS constraint flags
                doProfileSpecificSpsPatching(sps);

                // The H264Utils.writeSPS function safely handles
                // Annex B NALUs (including NALUs with escape sequences)
                ByteBuffer escapedNalu = H264Utils.writeSPS(sps, decodeUnitLength);

                // Construct the patched SPS
                byte[] naluBuffer = new byte[startSeqLen + 1 + escapedNalu.limit()];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, startSeqLen + 1);
                escapedNalu.get(naluBuffer, startSeqLen + 1, escapedNalu.limit());

                // Batch this to submit together with other CSD per AOSP docs
                spsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            }
            else if (decodeUnitType == MoonBridge.BUFFER_TYPE_VPS) {
                numVpsIn++;

                // Batch this to submit together with other CSD per AOSP docs
                byte[] naluBuffer = new byte[decodeUnitLength];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength);
                vpsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            }
            // Only the HEVC SPS hits this path (H.264 is handled above)
            else if (decodeUnitType == MoonBridge.BUFFER_TYPE_SPS) {
                numSpsIn++;

                // Batch this to submit together with other CSD per AOSP docs
                byte[] naluBuffer = new byte[decodeUnitLength];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength);
                spsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            }
            else if (decodeUnitType == MoonBridge.BUFFER_TYPE_PPS) {
                numPpsIn++;

                // Batch this to submit together with other CSD per AOSP docs
                byte[] naluBuffer = new byte[decodeUnitLength];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength);
                ppsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            }
            else if ((videoFormat & (MoonBridge.VIDEO_FORMAT_MASK_H264 | MoonBridge.VIDEO_FORMAT_MASK_H265)) != 0) {
                // If this is the first CSD blob or we aren't supporting fused IDR frames, we will
                // submit the CSD blob in a separate input buffer for each IDR frame.
                if (!submittedCsd || !fusedIdrFrame) {
                    if (!fetchNextInputBuffer()) {
                        return MoonBridge.DR_NEED_IDR;
                    }

                    // Submit all CSD when we receive the first non-CSD blob in an IDR frame
                    for (byte[] vpsBuffer : vpsBuffers) {
                        nextInputBuffer.put(vpsBuffer);
                    }
                    for (byte[] spsBuffer : spsBuffers) {
                        nextInputBuffer.put(spsBuffer);
                    }
                    for (byte[] ppsBuffer : ppsBuffers) {
                        nextInputBuffer.put(ppsBuffer);
                    }

                    if (!queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) {
                        return MoonBridge.DR_NEED_IDR;
                    }

                    // Remember that we already submitted CSD for this frame, so we don't do it
                    // again in the fused IDR case below.
                    csdSubmittedForThisFrame = true;

                    // Remember that we submitted CSD globally for this MediaCodec instance
                    submittedCsd = true;

                    if (needsBaselineSpsHack) {
                        needsBaselineSpsHack = false;

                        if (!replaySps()) {
                            return MoonBridge.DR_NEED_IDR;
                        }

                        LimeLog.info("SPS replay complete");
                    }
                }
            }
        }

        if (frameHostProcessingLatency != 0) {
            if (activeWindowVideoStats.minHostProcessingLatency != 0) {
                activeWindowVideoStats.minHostProcessingLatency = (char) Math.min(activeWindowVideoStats.minHostProcessingLatency, frameHostProcessingLatency);
            } else {
                activeWindowVideoStats.minHostProcessingLatency = frameHostProcessingLatency;
            }
            activeWindowVideoStats.framesWithHostProcessingLatency += 1;
        }
        activeWindowVideoStats.maxHostProcessingLatency = (char) Math.max(activeWindowVideoStats.maxHostProcessingLatency, frameHostProcessingLatency);
        activeWindowVideoStats.totalHostProcessingLatency += frameHostProcessingLatency;

        activeWindowVideoStats.totalFramesReceived++;
        activeWindowVideoStats.totalFrames++;

        if (!FRAME_RENDER_TIME_ONLY) {
            // Count time from first packet received to enqueue time as receive time
            // We will count DU queue time as part of decoding, because it is directly
            // caused by a slow decoder.
            activeWindowVideoStats.totalTimeMs += (enqueueTimeUs - receiveTimeUs) / 1000;
        }

        if (!fetchNextInputBuffer()) {
            return MoonBridge.DR_NEED_IDR;
        }

        int codecFlags = 0;

        if (frameType == MoonBridge.FRAME_TYPE_IDR) {
            codecFlags |= MediaCodec.BUFFER_FLAG_SYNC_FRAME;

            // If we are using fused IDR frames, submit the CSD with each IDR frame
            if (fusedIdrFrame && !csdSubmittedForThisFrame) {
                for (byte[] vpsBuffer : vpsBuffers) {
                    nextInputBuffer.put(vpsBuffer);
                }
                for (byte[] spsBuffer : spsBuffers) {
                    nextInputBuffer.put(spsBuffer);
                }
                for (byte[] ppsBuffer : ppsBuffers) {
                    nextInputBuffer.put(ppsBuffer);
                }
            }
        }

        // The frame timestamps use an undefined epoch, so normalize them to uptime microseconds.
        if (baseTimestampUs == 0) {
            baseTimestampUs = (SystemClock.uptimeMillis() * 1000) - enqueueTimeUs;
        }

        long timestampUs = baseTimestampUs + enqueueTimeUs;
        if (timestampUs <= lastTimestampUs) {
            // We can't submit multiple buffers with the same timestamp
            // so bump it up by one before queuing
            timestampUs = lastTimestampUs + 1;
        }
        lastTimestampUs = timestampUs;

        numFramesIn++;

        if (decodeUnitLength > nextInputBuffer.limit() - nextInputBuffer.position()) {
            IllegalArgumentException exception = new IllegalArgumentException(
                    "Decode unit length "+decodeUnitLength+" too large for input buffer "+nextInputBuffer.limit());
            if (!reportedCrash) {
                reportedCrash = true;
                crashListener.notifyCrash(exception);
            }
            throw new RendererException(this, exception);
        }

        // Copy data from our buffer list into the input buffer
        nextInputBuffer.put(decodeUnitData, 0, decodeUnitLength);

        if (!queueNextInputBuffer(timestampUs, codecFlags)) {
            return MoonBridge.DR_NEED_IDR;
        }

        return MoonBridge.DR_OK;
    }

    private boolean replaySps() {
        if (!fetchNextInputBuffer()) {
            return false;
        }

        // Write the Annex B header
        nextInputBuffer.put(new byte[]{0x00, 0x00, 0x00, 0x01, 0x67});

        // Switch the H264 profile back to high
        savedSps.profileIdc = 100;

        // Patch the SPS constraint flags
        doProfileSpecificSpsPatching(savedSps);

        // The H264Utils.writeSPS function safely handles
        // Annex B NALUs (including NALUs with escape sequences)
        ByteBuffer escapedNalu = H264Utils.writeSPS(savedSps, 128);
        nextInputBuffer.put(escapedNalu);

        // No need for the SPS anymore
        savedSps = null;

        // Queue the new SPS
        return queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
    }

    @Override
    public int getCapabilities() {
        int capabilities = 0;

        // Request the optimal number of slices per frame for this decoder
        capabilities |= MoonBridge.CAPABILITY_SLICES_PER_FRAME(optimalSlicesPerFrame);

        // Enable reference frame invalidation on supported hardware
        if (refFrameInvalidationAvc) {
            capabilities |= MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC;
        }
        if (refFrameInvalidationHevc) {
            capabilities |= MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC;
        }
        if (refFrameInvalidationAv1) {
            capabilities |= MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1;
        }

        // Enable direct submit on supported hardware
        if (directSubmit) {
            capabilities |= MoonBridge.CAPABILITY_DIRECT_SUBMIT;
        }

        return capabilities;
    }

    public int getAverageEndToEndLatency() {
        if (globalVideoStats.totalFramesReceived == 0) {
            return 0;
        }
        return (int)(globalVideoStats.totalTimeMs / globalVideoStats.totalFramesReceived);
    }

    public int getAverageDecoderLatency() {
        if (globalVideoStats.totalFramesReceived == 0) {
            return 0;
        }
        return (int)(globalVideoStats.decoderTimeMs / globalVideoStats.totalFramesReceived);
    }

    static class DecoderHungException extends RuntimeException {
        private int hangTimeMs;

        DecoderHungException(int hangTimeMs) {
            this.hangTimeMs = hangTimeMs;
        }

        public String toString() {
            String str = "";

            str += "Hang time: "+hangTimeMs+" ms"+ RendererException.DELIMITER;
            str += super.toString();

            return str;
        }
    }

    static class RendererException extends RuntimeException {
        private static final long serialVersionUID = 8985937536997012406L;
        protected static final String DELIMITER = BuildConfig.DEBUG ? "\n" : " | ";

        private String text;

        RendererException(MediaCodecDecoderRenderer renderer, Exception e) {
            this.text = generateText(renderer, e);
        }

        public String toString() {
            return text;
        }

        private String generateText(MediaCodecDecoderRenderer renderer, Exception originalException) {
            String str;

            if (renderer.numVpsIn == 0 && renderer.numSpsIn == 0 && renderer.numPpsIn == 0) {
                str = "PreSPSError";
            }
            else if (renderer.numSpsIn > 0 && renderer.numPpsIn == 0) {
                str = "PrePPSError";
            }
            else if (renderer.numPpsIn > 0 && renderer.numFramesIn == 0) {
                str = "PreIFrameError";
            }
            else if (renderer.numFramesIn > 0 && renderer.outputFormat == null) {
                str = "PreOutputConfigError";
            }
            else if (renderer.outputFormat != null && renderer.numFramesOut == 0) {
                str = "PreOutputError";
            }
            else if (renderer.numFramesOut <= renderer.refreshRate * 30) {
                str = "EarlyOutputError";
            }
            else {
                str = "ErrorWhileStreaming";
            }

            str += "Format: "+String.format("%x", renderer.videoFormat)+DELIMITER;
            str += "AVC Decoder: "+((renderer.avcDecoder != null) ? renderer.avcDecoder.getName():"(none)")+DELIMITER;
            str += "HEVC Decoder: "+((renderer.hevcDecoder != null) ? renderer.hevcDecoder.getName():"(none)")+DELIMITER;
            str += "AV1 Decoder: "+((renderer.av1Decoder != null) ? renderer.av1Decoder.getName():"(none)")+DELIMITER;
            if (renderer.avcDecoder != null) {
                Range<Integer> avcWidthRange = renderer.avcDecoder.getCapabilitiesForType("video/avc").getVideoCapabilities().getSupportedWidths();
                str += "AVC supported width range: "+avcWidthRange+DELIMITER;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        Range<Double> avcFpsRange = renderer.avcDecoder.getCapabilitiesForType("video/avc").getVideoCapabilities().getAchievableFrameRatesFor(renderer.initialWidth, renderer.initialHeight);
                        str += "AVC achievable FPS range: "+avcFpsRange+DELIMITER;
                    } catch (IllegalArgumentException e) {
                        str += "AVC achievable FPS range: UNSUPPORTED!"+DELIMITER;
                    }
                }
            }
            if (renderer.hevcDecoder != null) {
                Range<Integer> hevcWidthRange = renderer.hevcDecoder.getCapabilitiesForType("video/hevc").getVideoCapabilities().getSupportedWidths();
                str += "HEVC supported width range: "+hevcWidthRange+DELIMITER;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        Range<Double> hevcFpsRange = renderer.hevcDecoder.getCapabilitiesForType("video/hevc").getVideoCapabilities().getAchievableFrameRatesFor(renderer.initialWidth, renderer.initialHeight);
                        str += "HEVC achievable FPS range: " + hevcFpsRange + DELIMITER;
                    } catch (IllegalArgumentException e) {
                        str += "HEVC achievable FPS range: UNSUPPORTED!"+DELIMITER;
                    }
                }
            }
            if (renderer.av1Decoder != null) {
                Range<Integer> av1WidthRange = renderer.av1Decoder.getCapabilitiesForType("video/av01").getVideoCapabilities().getSupportedWidths();
                str += "AV1 supported width range: "+av1WidthRange+DELIMITER;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        Range<Double> av1FpsRange = renderer.av1Decoder.getCapabilitiesForType("video/av01").getVideoCapabilities().getAchievableFrameRatesFor(renderer.initialWidth, renderer.initialHeight);
                        str += "AV1 achievable FPS range: " + av1FpsRange + DELIMITER;
                    } catch (IllegalArgumentException e) {
                        str += "AV1 achievable FPS range: UNSUPPORTED!"+DELIMITER;
                    }
                }
            }
            str += "Configured format: "+renderer.configuredFormat+DELIMITER;
            str += "Input format: "+renderer.inputFormat+DELIMITER;
            str += "Output format: "+renderer.outputFormat+DELIMITER;
            str += "Adaptive playback: "+renderer.adaptivePlayback+DELIMITER;
            str += "GL Renderer: "+renderer.glRenderer+DELIMITER;
            //str += "Build fingerprint: "+Build.FINGERPRINT+DELIMITER;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                str += "SOC: "+Build.SOC_MANUFACTURER+" - "+Build.SOC_MODEL+DELIMITER;
                str += "Performance class: "+Build.VERSION.MEDIA_PERFORMANCE_CLASS+DELIMITER;
                /*str += "Vendor params: ";
                List<String> params = renderer.videoDecoder.getSupportedVendorParameters();
                if (params.isEmpty()) {
                    str += "NONE";
                }
                else {
                    for (String param : params) {
                        str += param + " ";
                    }
                }
                str += DELIMITER;*/
            }
            str += "Consecutive crashes: "+renderer.consecutiveCrashCount+DELIMITER;
            str += "RFI active: "+renderer.refFrameInvalidationActive+DELIMITER;
            str += "Using modern SPS patching: "+(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)+DELIMITER;
            str += "Fused IDR frames: "+renderer.fusedIdrFrame+DELIMITER;
            str += "Video dimensions: "+renderer.initialWidth+"x"+renderer.initialHeight+DELIMITER;
            str += "FPS target: "+renderer.refreshRate+DELIMITER;
            str += "Bitrate: "+renderer.prefs.bitrate+" Kbps"+DELIMITER;
            str += "CSD stats: "+renderer.numVpsIn+", "+renderer.numSpsIn+", "+renderer.numPpsIn+DELIMITER;
            str += "Frames in-out: "+renderer.numFramesIn+", "+renderer.numFramesOut+DELIMITER;
            str += "Total frames received: "+renderer.globalVideoStats.totalFramesReceived+DELIMITER;
            str += "Total frames rendered: "+renderer.globalVideoStats.totalFramesRendered+DELIMITER;
            str += "Frame losses: "+renderer.globalVideoStats.framesLost+" in "+renderer.globalVideoStats.frameLossEvents+" loss events"+DELIMITER;
            str += "Average end-to-end client latency: "+renderer.getAverageEndToEndLatency()+"ms"+DELIMITER;
            str += "Average hardware decoder latency: "+renderer.getAverageDecoderLatency()+"ms"+DELIMITER;
            str += "Frame pacing mode: "+renderer.prefs.framePacing+DELIMITER;

            if (originalException instanceof CodecException) {
                CodecException ce = (CodecException) originalException;

                str += "Diagnostic Info: " + ce.getDiagnosticInfo() + DELIMITER;
                str += "Recoverable: " + ce.isRecoverable() + DELIMITER;
                str += "Transient: " + ce.isTransient() + DELIMITER;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    str += "Codec Error Code: " + ce.getErrorCode() + DELIMITER;
                }
            }

            str += originalException.toString();

            return str;
        }
    }


    private void applySurfaceFrameRate(android.view.Surface surface, int targetFps) {
        if (surface == null) return;
        try {
            // API 30+ supports Surface.setFrameRate; for older, attempt View-based call elsewhere.
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                surface.setFrameRate((float) targetFps,
                        android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
                LimeLog.info("Applied Surface frame rate: " + targetFps + " Hz");
            }
        } catch (Throwable t) {
            // best-effort
        }
    }



    private boolean isMTKDecoderName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("c2.mtk") || n.startsWith("omx.mtk");
    }

}
