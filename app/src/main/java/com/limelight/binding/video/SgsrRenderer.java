package com.limelight.binding.video;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Choreographer;
import android.view.Surface;
import com.limelight.LimeLog;
import com.limelight.preferences.UpscalerMode;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Delos SGSR 1 output stage. MediaCodec writes an external texture; one pass resolves its crop/
 * rotation into a 2D texture and the official Qualcomm spatial shader upscales it to the view size.
 * All EGL/GL ownership stays on one thread. The SurfaceView, overlays and input geometry stay intact.
 */
public final class SgsrRenderer implements AutoCloseable {
    private static final String VERTEX = "#version 310 es\n" +
            "uniform mat4 transform; out highp vec2 uv;\n" +
            "void main(){ vec2 p=vec2(float(gl_VertexID&1),float(gl_VertexID>>1));" +
            "gl_Position=vec4(p*2.0-1.0,0,1); uv=(transform*vec4(p,0,1)).xy; }";
    private static final String COPY = "#version 310 es\n" +
            "#extension GL_OES_EGL_image_external_essl3 : require\n" +
            "precision highp float; in highp vec2 uv; uniform samplerExternalOES source; out vec4 color;" +
            "void main(){color=texture(source,uv);}";
    private static final String UPSCALE_VERTEX = "#version 310 es\n" +
            "layout(location=0) out highp vec4 in_TEXCOORD0;" +
            "void main(){vec2 p=vec2(float(gl_VertexID&1),float(gl_VertexID>>1));" +
            "gl_Position=vec4(p*2.0-1.0,0,1); in_TEXCOORD0=vec4(p,0,0);}";
    private final HandlerThread thread = new HandlerThread("Video - SGSR", android.os.Process.THREAD_PRIORITY_DISPLAY);
    private final Handler handler;
    // Receives the decoder's frame-available callbacks apart from the GL thread so frames queued behind a long
    // draw are counted as they arrive
    private final HandlerThread frameThread = new HandlerThread("Video - SGSR frames", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
    private final Handler frameHandler;
    private final FrameQueue frameQueue = new FrameQueue();
    private final Runnable drawTask = this::draw;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Runnable failure;
    // About a second of frames: a replaced surface recovers within it, a broken pipeline never does
    private static final int MAX_CONSECUTIVE_DRAW_FAILURES = 60;
    // Only touched on the SGSR thread
    private int consecutiveDrawFailures;
    // Sharpen only: the mode number (UpscalerMode.index) of the pass that only sharpens, without scaling
    private static final int SHARPEN_ONLY = UpscalerMode.SHARPEN_ONLY.index();
    private final int width, height;
    private volatile float sharpness;
    private volatile int mode;
    private volatile int supersamplingLevel;
    private volatile boolean supersampling;
    private volatile int activeInternalWidth, activeInternalHeight;
    private volatile int activePresentationWidth, activePresentationHeight;
    private Integer drawnQuality;
    private volatile float gpuTimeMs;
    private volatile float frameToSwapMs;
    // Stage timestamps for measurement (null while off): what the GL thread records and the display timestamps it waits for
    private volatile LatencyStages latencyStages;
    private final DisplayTimestampTracker displayTimestamps = new DisplayTimestampTracker();
    private final DisplayTimestampTracker.Source sdrTimestampSource = SgsrRenderer::nativeFrameTimestamps;
    private final DisplayTimestampTracker.Source hdrTimestampSource =
            (frameId, out) -> this.hdrOutput != 0 ? nativeHdrFrameTimestamps(this.hdrOutput,(int) frameId,out) : -1;
    private boolean timestampsChecked, sdrTimestamps, statsFrameArmed;
    private long statsFrameArrivalNs;
    // The GPU frames in flight and how they are read; the GPU time per frame they measure (the stat and pacer input)
    private final GpuInFlight gpuInFlight = new GpuInFlight();
    private final GpuInFlight.Fences sdrFences = new GpuInFlight.Fences() {
        @Override public long poll(long handle) { return nativeGpuFenceStatus((int) handle); }
        @Override public void release(long handle) { nativeGpuFenceClose((int) handle); }
    };
    private final GpuInFlight.Fences hdrFences = new GpuInFlight.Fences() {
        @Override public long poll(long handle) { return hdrOutput != 0 ? nativeHdrGpuDone(hdrOutput,(int) handle) : 0; }
        @Override public void release(long handle) { /* the native output closes its own descriptors */ }
    };
    private volatile float gpuWorkMs;
    private final GpuInFlight.WorkSink gpuWorkSink = ns -> {
        gpuWorkMs = SgsrPacing.smooth(gpuWorkMs,ns / 1_000_000f);
        frameQueue.recordGpuWork(ns);
    };
    // The newest frame is held back while the GPU is busy; retried every millisecond until it can be drawn
    private boolean heldFrame, retryPosted;
    private final Runnable retryTask = () -> { retryPosted = false; draw(); };
    private final OverloadDetector overloadDetector = new OverloadDetector();
    private final FlowGovernor flowGovernor = new FlowGovernor();
    private volatile OverloadListener overloadListener;
    private volatile boolean firstFramePresented;
    private int highQualityProgram;
    private int temporalPresentProgram;
    private int supersamplePresentProgram;
    private int sharpenProgram;
    private SharpenMeter sharpenMeter;
    private final Supplier<String> sharpenDescription = this::describeSharpen;
    // Created for Sharpen only, whose output keeps the stream's size: the surface is not enlarged and the HDR
    // layer is stream-sized. A switch between this and the other modes needs a new renderer (see isSharpenGeometry).
    private final boolean sharpenGeometry;
    private volatile boolean sharpenActive;
    private SgsrTemporal temporal;
    private Context diagnosticContext;
    private boolean diagnosticsChecked;
    private final boolean tenBit;
    private volatile boolean hdrActive;
    private volatile byte[] hdrMetadata;
    private long hdrOutput;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture texture;
    private Surface decoderSurface;
    private int externalTexture, colorTexture, framebuffer, copyProgram, upscaleProgram;
    private int supersampleTexture, supersampleFramebuffer, supersampleWidth, supersampleHeight;
    private int maxTextureSize;
    private final float[] transform = new float[16];
    private final int[] size = new int[1];
    private int outputWidth, outputHeight;
    private final boolean arrivalOnly;
    private Surface arrivalOutputSurface;
    private int hdrTargetWidth, hdrTargetHeight;
    private EGLConfig eglConfig;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
    private boolean outputAttached;
    private volatile float arrivalProgress = -1f;
    private volatile boolean arrivalReady, arrivalComplete;
    private volatile String arrivalError;
    private final float[] arrivalPose = new float[LaunchArrival.POSE_FLOATS];
    private ArrivalGlPass arrival;
    private int arrivalTemporalProgram, arrivalPresentProgram, arrivalSharpenProgram;
    private Runnable arrivalCompletionListener;
    private Choreographer choreographer;
    private boolean arrivalFrameScheduled, arrivalReconstructionDirty, hdrFrameAcquired;
    private int hdrFramebuffer;
    private final Choreographer.FrameCallback arrivalTick = this::onArrivalTick;
    private final Runnable arrivalStateChange = this::updateArrivalState;
    private final AtomicBoolean arrivalStatePending = new AtomicBoolean();
    // These refer to the latest decoded/reconstructed frame. Display ticks never advance decoding
    // or temporal history and never rebuild its mip pyramid unless that frame changes.
    private int cachedSource, cachedProgram, cachedWidth, cachedHeight;
    private int cachedSampledWidth, cachedSampledHeight, cachedQuality;
    private boolean cachedReconstruction;
    private int copyTransformLocation, copySourceLocation;
    private final android.util.SparseArray<ProgramUniforms> programUniforms = new android.util.SparseArray<>();
    private SgsrDimensions.Plan dimensionsPlan;
    private int plannedWidth, plannedHeight, plannedSupersamplingLevel;

    private static final class ProgramUniforms {
        final int viewport, strength, enabled, source, hdr;
        ProgramUniforms(int program) {
            viewport = GLES31.glGetUniformLocation(program,"ViewportInfo[0]");
            strength = GLES31.glGetUniformLocation(program,"sgsrStrength");
            enabled = GLES31.glGetUniformLocation(program,"sgsrEnabled");
            source = GLES31.glGetUniformLocation(program,"ps0");
            hdr = GLES31.glGetUniformLocation(program,"hdrMode");
        }
    }

    public boolean hasPresentedFrame() {
        return firstFramePresented && !closed.get();
    }

    public static boolean isSupported(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        return manager != null && manager.getDeviceConfigurationInfo().reqGlEsVersion >= 0x30001;
    }

    public static SgsrRenderer create(Context app, StreamSurfaceHost surfaceHost, Surface output, int width, int height,
                                      int sharpness, int mode, int supersamplingLevel, boolean tenBit,
                                      boolean hdrActive, byte[] hdrMetadata, Runnable failure) {
        if (!isSupported(app)) return null;
        SgsrRenderer renderer = new SgsrRenderer(width, height, sharpness, mode, supersamplingLevel,
                tenBit, hdrActive, hdrMetadata, failure, false);
        FutureTask<Void> initialize = new FutureTask<>(() -> {
            renderer.initialize(app, surfaceHost, output);
            return null;
        });
        renderer.handler.post(initialize);
        try {
            initialize.get(8, TimeUnit.SECONDS);
            return renderer;
        } catch (Exception e) {
            LimeLog.warning("SGSR unavailable; retaining direct rendering: " + e);
            renderer.close();
            return null;
        }
    }

    /** Builds only a pbuffer/decoder producer. The codec must redirect before attaching the output. */
    public static SgsrRenderer createArrival(Context app, Surface output, int width, int height,
                                             boolean tenBit, boolean hdrActive, byte[] hdrMetadata,
                                             Runnable failure) {
        if (!isSupported(app)) throw new IllegalStateException("Stream arrival requires GLES 3.1");
        if (hdrActive && !tenBit) throw new IllegalStateException("HDR arrival requires ten-bit FP16/P010 output");
        SgsrRenderer renderer = new SgsrRenderer(width,height,0,0,1,tenBit,hdrActive,hdrMetadata,failure,true);
        FutureTask<Void> initialize = new FutureTask<>(() -> { renderer.initialize(app,null,output); return null; });
        renderer.handler.post(initialize);
        try {
            initialize.get(8,TimeUnit.SECONDS);
            return renderer;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            renderer.close();
            throw new IllegalStateException("Stream arrival initialization failed: " + e,e);
        }
    }

    private SgsrRenderer(int width, int height, int sharpness, int mode, int supersamplingLevel,
                         boolean tenBit, boolean hdrActive, byte[] metadata, Runnable failure, boolean arrivalOnly) {
        this.width = width;
        this.sharpenGeometry = !arrivalOnly && mode == SHARPEN_ONLY;
        this.arrivalOnly = arrivalOnly;
        this.height = height;
        this.sharpness = Math.max(0, Math.min(100, sharpness)) / 100f;
        this.tenBit = tenBit;
        this.mode = mode;
        this.supersamplingLevel = supersamplingLevel >= 2 ? 2 : 1;
        this.hdrActive = hdrActive;
        this.hdrMetadata = metadata != null ? metadata.clone() : null;
        this.failure = failure;
        thread.start();
        handler = new Handler(thread.getLooper());
        LatencyThreads.register(thread.getThreadId());
        frameThread.start();
        frameHandler = new Handler(frameThread.getLooper());
    }

    public Surface getDecoderSurface() { return decoderSurface; }
    /** The last statistics window of the output's frame queue, null before the first or when it is stale. */
    public FrameQueueStats getFrameQueueStats() {
        FrameQueueStats stats = frameQueue.snapshot();
        return stats != null && stats.isFresh(System.nanoTime()) ? stats : null;
    }
    /**
     * Switches the stage timestamps on (a collector) or off (null): the draw path then records its stages and, once
     * the platform reports them, the GPU-done, latch and display-present times of each frame. Off by default; the
     * checks and native calls cost nothing while it is off.
     */
    public void setLatencyStages(LatencyStages stages) { latencyStages = stages; }
    /** On the GL thread with the window current: turns on EGL frame timestamps for the SDR window surface. */
    private static boolean enableSdrTimestamps() {
        try {
            System.loadLibrary("moonlight-core");
            return nativeFrameTimestampsEnable();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }
    public void setSharpness(int value) { sharpness = Math.max(0, Math.min(100, value)) / 100f; }
    public void setMode(int mode) { this.mode = UpscalerMode.fromIndex(mode).index(); }
    public void setSupersamplingLevel(int level) { supersamplingLevel = level >= 2 ? 2 : 1; }
    public boolean isSupersampling() { return supersampling; }
    /** Whether this renderer was built for Sharpen only's stream-sized output, which the other modes cannot use. */
    public boolean isSharpenGeometry() { return sharpenGeometry; }
    /** What the Sharpen only pass cost in the last window, null while that mode is not running. */
    public SharpenCost getSharpenCost() {
        SharpenMeter meter = sharpenMeter;
        return meter != null ? meter.snapshot() : null;
    }
    public String getScaleDescription() {
        if (activeInternalWidth <= 0 || activePresentationWidth <= 0) return null;
        if (sharpenActive) return "Sharpen only: " + activePresentationWidth + " × " + activePresentationHeight;
        if (supersampling) return "Supersampled to: " + activeInternalWidth + " × " + activeInternalHeight;
        return "Upscaled to: " + activePresentationWidth + " × " + activePresentationHeight;
    }
    private String describeSharpen() {
        return "sharpen-only " + width + "x" + height + "->" + outputWidth + "x" + outputHeight
                + " sharpness=" + sharpness + " p010=" + tenBit + " hdr=" + hdrActive;
    }
    public void setHdrMode(boolean enabled, byte[] metadata) {
        hdrActive = enabled;
        hdrMetadata = metadata != null ? metadata.clone() : null;
    }

    public boolean isLaunchArrivalReady() { return arrivalReady && arrivalError == null && !closed.get(); }
    public boolean isLaunchArrivalComplete() { return arrivalComplete && arrivalError == null; }
    public String getLaunchArrivalError() { return arrivalError; }

    /** Warm only: the normal SGSR image remains untouched until progress zero is requested. */
    public void prepareLaunchArrival() {
        if (closed.get()) return;
        handler.post(() -> {
            if (closed.get() || arrival != null || arrivalComplete || arrivalError != null) return;
            try {
                int w = width, h = height;
                if (outputAttached) {
                    if (tenBit) { w = nativeHdrWidth(hdrOutput); h = nativeHdrHeight(hdrOutput); }
                    else {
                        EGL14.eglQuerySurface(display,window,EGL14.EGL_WIDTH,size,0); w=size[0];
                        EGL14.eglQuerySurface(display,window,EGL14.EGL_HEIGHT,size,0); h=size[0];
                    }
                }
                ensureArrival(Math.max(1,w),Math.max(1,h));
                if (tenBit && !arrivalOnly) {
                    arrivalRgbProgram(0);
                    arrivalRgbProgram(2);
                }
                GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER,0);
            } catch (Exception e) { failArrival(e); }
        });
    }

    public void setLaunchArrivalProgress(float progress) {
        if (closed.get()) return;
        if (arrivalComplete && progress >= 0) return;
        if (arrivalError != null && progress >= 0) return;
        float previous = arrivalProgress;
        arrivalProgress = Float.isNaN(progress) ? -1f : Math.min(progress,1f);
        // Coalesce lifecycle changes; ordinary progress updates are just the scalar read by vsync.
        if ((previous < 0 || arrivalProgress < 0 || arrivalProgress >= 1f)
                && arrivalStatePending.compareAndSet(false,true)) handler.post(arrivalStateChange);
    }

    public void setLaunchArrivalCompletionListener(Runnable listener) {
        handler.post(() -> {
            if (closed.get()) return;
            arrivalCompletionListener = listener;
            if (arrivalComplete && listener != null) {
                arrivalCompletionListener = null;
                listener.run();
            }
        });
    }

    public void attachArrivalOutput() {
        runOutputChange(() -> {
            if (!arrivalOnly || outputAttached) return;
            if (tenBit) {
                if (hdrOutput == 0) createHdrOutput(arrivalOutputSurface);
            }
            else {
                window = EGL14.eglCreateWindowSurface(display,eglConfig,arrivalOutputSurface,
                        new int[]{EGL14.EGL_NONE},0);
                require(window != EGL14.EGL_NO_SURFACE &&
                        EGL14.eglMakeCurrent(display,window,window,context),"Arrival EGL output attachment");
                EGL14.eglSwapInterval(display,0);
            }
            outputAttached = true;
            updateArrivalState();
        });
    }

    public void detachArrivalOutput() {
        runOutputChange(() -> {
            if (!arrivalOnly || !outputAttached) return;
            stopArrivalTicks();
            outputAttached = false;
            // Keep consuming the decoder producer and retain the HDR child's last identity picture.
            if (!tenBit) {
                require(EGL14.eglMakeCurrent(display,pbuffer,pbuffer,context),"Arrival EGL output detachment");
                require(EGL14.eglDestroySurface(display,window),"Arrival EGL output release");
                window = pbuffer;
            }
        });
    }

    private void runOutputChange(Runnable action) {
        if (closed.get()) throw new IllegalStateException("Arrival renderer is closed");
        if (Thread.currentThread() == thread) { action.run(); return; }
        FutureTask<Void> change = new FutureTask<>(() -> { action.run(); return null; });
        handler.post(change);
        try { change.get(500,TimeUnit.MILLISECONDS); }
        catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            arrivalError = "Stream arrival surface handover failed: " + e;
            // Leave the surface operation queued (especially detach), but stop animation now.
            // An EGL driver call already in progress cannot be forcibly interrupted.
            setLaunchArrivalProgress(-1f);
            throw new IllegalStateException(arrivalError,e);
        }
    }

    private void initialize(Context app, StreamSurfaceHost surfaceHost, Surface output) throws Exception {
        System.loadLibrary("moonlight-core");
        diagnosticContext = app.getApplicationContext();
        arrivalOutputSurface = arrivalOnly ? output : null;
        android.view.View streamView = surfaceHost != null ? surfaceHost.getStreamSurface() : null;
        hdrTargetWidth = streamView != null ? streamView.getWidth() : 0;
        hdrTargetHeight = streamView != null ? streamView.getHeight() : 0;
        if (sharpenGeometry) {
            // No enlargement: the HDR layer has the stream's size and SurfaceFlinger scales it to the view
            hdrTargetWidth = width;
            hdrTargetHeight = height;
        }
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        require(EGL14.eglInitialize(display, new int[2], 0, new int[2], 0), "EGL initialize");
        int[] attrs = {EGL14.EGL_RED_SIZE,8, EGL14.EGL_GREEN_SIZE,8, EGL14.EGL_BLUE_SIZE,8,
                EGL14.EGL_ALPHA_SIZE,8, EGL14.EGL_SURFACE_TYPE,tenBit ? EGL14.EGL_PBUFFER_BIT :
                        arrivalOnly ? EGL14.EGL_PBUFFER_BIT | EGL14.EGL_WINDOW_BIT : EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RENDERABLE_TYPE,0x40, EGL14.EGL_NONE}; // EGL_OPENGL_ES3_BIT_KHR
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        require(EGL14.eglChooseConfig(display, attrs,0,configs,0,1,count,0) && count[0]>0, "EGL config");
        eglConfig = configs[0];
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
        require(context != EGL14.EGL_NO_CONTEXT, "EGL context");
        if (tenBit || arrivalOnly) {
            pbuffer = EGL14.eglCreatePbufferSurface(display,configs[0],
                    new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);
            window = pbuffer;
        } else window = EGL14.eglCreateWindowSurface(display,configs[0],output,new int[]{EGL14.EGL_NONE},0);
        require(window != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display,window,window,context), "EGL window");
        String extensions=GLES31.glGetString(GLES31.GL_EXTENSIONS);
        require(extensions.contains("GL_OES_EGL_image_external_essl3"), "External texture support");
        if (tenBit) require(extensions.contains("GL_EXT_YUV_target"), "P010 output support");
        if (!arrivalOnly) sharpenMeter = SharpenMeter.create(extensions);
        EGL14.eglSwapInterval(display,0);
        copyProgram = program(VERTEX,COPY);
        copyTransformLocation = GLES31.glGetUniformLocation(copyProgram,"transform");
        copySourceLocation = GLES31.glGetUniformLocation(copyProgram,"source");
        choreographer = Choreographer.getInstance();
        if (!arrivalOnly) {
            upscaleProgram = createUpscaleProgram(app,"sgsr/sgsr1.frag",false);
            highQualityProgram = createUpscaleProgram(app,"sgsr/sgsr1_high.frag",false);
            supersamplePresentProgram = createUpscaleProgram(app,"sgsr/supersample_present.frag");
        }
        GLES31.glGetIntegerv(GLES31.GL_MAX_TEXTURE_SIZE,size,0);
        maxTextureSize = size[0];
        externalTexture = texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
        colorTexture = texture(GLES31.GL_TEXTURE_2D);
        GLES31.glTexImage2D(GLES31.GL_TEXTURE_2D,0,tenBit ? GLES31.GL_RGBA16F : GLES31.GL_RGBA8,
                width,height,0,GLES31.GL_RGBA,tenBit ? GLES31.GL_HALF_FLOAT : GLES31.GL_UNSIGNED_BYTE,null);
        int[] id = new int[1];
        GLES31.glGenFramebuffers(1,id,0); framebuffer = id[0];
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER,framebuffer);
        GLES31.glFramebufferTexture2D(GLES31.GL_FRAMEBUFFER,GLES31.GL_COLOR_ATTACHMENT0,GLES31.GL_TEXTURE_2D,colorTexture,0);
        require(GLES31.glCheckFramebufferStatus(GLES31.GL_FRAMEBUFFER)==GLES31.GL_FRAMEBUFFER_COMPLETE,"SGSR framebuffer");
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER,0);
        if (tenBit && !arrivalOnly) createHdrOutput(output);
        outputAttached = !arrivalOnly;
        if (arrivalOnly) ensureArrival(width,height);
        texture = new SurfaceTexture(externalTexture);
        texture.setDefaultBufferSize(width,height);
        decoderSurface = new Surface(texture);
        texture.setOnFrameAvailableListener(unused -> {
            // Counted on the callback's own thread: on the GL thread the callbacks of frames queued behind a
            // long draw would not be seen until that draw is over
            frameQueue.onFrameAvailable(System.nanoTime());
            handler.post(drawTask);
        },frameHandler);
        LimeLog.info("SGSR 1 initialized: " + width + "x" + height + ", sharpness=" + sharpness + ", P010=" + tenBit);
    }

    private void createHdrOutput(Surface output) {
        System.loadLibrary("moonlight-core");
        hdrOutput = nativeCreateHdr(output,hdrTargetWidth,hdrTargetHeight);
        require(hdrOutput != 0,"Arrival/SGSR requires P010 SurfaceControl output");
    }

    private int createUpscaleProgram(Context app, String asset) throws Exception {
        return createUpscaleProgram(app, asset, tenBit);
    }

    private int createUpscaleProgram(Context app, String asset, boolean yuvOutput) throws Exception {
        String shader;
        try (InputStream input = app.getAssets().open(asset); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n=input.read(buffer))>=0) bytes.write(buffer,0,n);
            shader = bytes.toString(StandardCharsets.UTF_8.name());
        }
        if (yuvOutput) {
            System.loadLibrary("moonlight-core");
            shader = shader.replace("#version 310 es", "#version 310 es\n#extension GL_EXT_YUV_target : require\nuniform int hdrMode;")
                    .replace("layout(location=0) out vec4 out_Target0;", "layout(yuv) out vec4 out_Target0;")
                    .replace("out_Target0.xyzw = color;",
                            "vec3 k = hdrMode != 0 ? vec3(0.2627,0.6780,0.0593) : vec3(0.2126,0.7152,0.0722);" +
                            "float y = dot(color.rgb,k); vec2 c = vec2((color.b-y)/(2.0*(1.0-k.b)),(color.r-y)/(2.0*(1.0-k.r)));" +
                            "out_Target0=vec4((64.0+876.0*y)/1023.0,(vec2(512.0)+896.0*c)/1023.0,1.0);");
        }
        int linked = program(UPSCALE_VERTEX,shader);
        programUniforms.put(linked,new ProgramUniforms(linked));
        return linked;
    }

    private void draw() {
        if (closed.get()) return;
        // The frame this draw was posted for may already have been consumed by an earlier draw that took
        // everything queued at that time: nothing new to show, unless a frame is held back for a busy GPU
        final int pending = frameQueue.pending();
        if (pending <= 0 && !heldFrame) return;
        final long processingStartNs=System.nanoTime();
        try {
            // Everything that queued up while the previous draw ran is consumed, only the newest is drawn: a
            // stale frame is never shown while a newer one is waiting. A skipped frame is simply not drawn, so
            // SGSR 2's history sees the newest frame's timestamp and resets only if the gap exceeds its limit.
            for (int i=0;i<pending;i++) {
                texture.updateTexImage();
                frameQueue.consumeOne();
            }
            if (pending > 0 && heldFrame) {
                // The frame held back for the GPU is replaced by a newer one and will never be shown
                final FrameQueueStats window=frameQueue.recordBusySkip(processingStartNs);
                if (window != null) onQueueWindow(window);
            }
            // The GPU has as many frames in flight as it may: keep the newest frame (it stays in the texture) and
            // draw it as soon as the GPU is free, or replace it by a newer one. Latency stays bounded to a frame
            // or two however slow the GPU is; the arrival animation's producer is not throttled.
            if (!arrivalOnly && gpuBusy(processingStartNs)) {
                if (pending > 1) frameQueue.addCoalesced(pending - 1);
                heldFrame = true;
                scheduleRetry();
                return;
            }
            heldFrame = false;
            final long drawStartNs=System.nanoTime();
            final long arrivalNs=frameQueue.lastConsumedArrivalNs();
            final LatencyStages stages=latencyStages;
            if (stages != null && !timestampsChecked) {
                timestampsChecked = true;
                sdrTimestamps = enableSdrTimestamps();
            }
            // A frame drawn after being held counts as one waiting frame, with the age it reached while it waited
            final FrameQueueStats queueWindow=frameQueue.recordDraw(drawStartNs,Math.max(pending,1),drawStartNs-arrivalNs);
            if (queueWindow != null) onQueueWindow(queueWindow);
            texture.getTransformMatrix(transform);
            if (!outputAttached || (arrivalError != null && arrivalOnly)) return;
            GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER,framebuffer);
            GLES31.glViewport(0,0,width,height);
            final SharpenMeter meter = sharpenMeter;
            final boolean measureCopy = meter != null && !arrivalOnly && mode == SHARPEN_ONLY;
            if (measureCopy) meter.beginCopy();
            GLES31.glUseProgram(copyProgram);
            GLES31.glUniformMatrix4fv(copyTransformLocation,1,false,transform,0);
            GLES31.glActiveTexture(GLES31.GL_TEXTURE0);
            GLES31.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,externalTexture);
            GLES31.glUniform1i(copySourceLocation,0);
            GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP,0,4);
            if (measureCopy) meter.endCopy();
            int w,h;
            if (tenBit) {
                w=nativeHdrWidth(hdrOutput);h=nativeHdrHeight(hdrOutput);
            } else {
                EGL14.eglQuerySurface(display,window,EGL14.EGL_WIDTH,size,0); w=size[0];
                EGL14.eglQuerySurface(display,window,EGL14.EGL_HEIGHT,size,0); h=size[0];
            }
            if (w<=0 || h<=0) return;
            final float progress = arrivalProgress;
            final boolean arrivalActive = progress >= 0 && !arrivalComplete;
            if (arrivalOnly) {
                cacheFrame(colorTexture,0,w,h,width,height,0,false);
                if (arrivalActive) scheduleArrivalTick();
                return;
            }
            if (com.limelight.BuildConfig.DEBUG && !diagnosticsChecked) {
                diagnosticsChecked = true;
                if (new java.io.File(diagnosticContext.getFilesDir(), "sgsr-capture-request").exists()) {
                    int standard=0, high=0;
                    try {
                        standard=createUpscaleProgram(diagnosticContext,"sgsr/sgsr1.frag",false);
                        high=createUpscaleProgram(diagnosticContext,"sgsr/sgsr1_high.frag",false);
                        SgsrDiagnostics.capture(diagnosticContext,width,height,w,h,externalTexture,colorTexture,
                                transform,copyProgram,standard,high,sharpness);
                    } catch (Exception e) { LimeLog.warning("SGSR diagnostic setup failed: "+e); }
                    finally {
                        if (standard!=0) GLES31.glDeleteProgram(standard);
                        if (high!=0) GLES31.glDeleteProgram(high);
                    }
                }
            }
            int quality = mode;
            if (drawnQuality == null || drawnQuality != quality) {
                drawnQuality = quality;
                overloadDetector.reset();
                flowGovernor.reset();
                if (temporal != null) temporal.reset();
                LimeLog.info("SGSR quality: " + UpscalerMode.fromIndex(quality).label());
            }
            final boolean sharpenOnly = quality == SHARPEN_ONLY;
            int internalWidth, internalHeight;
            if (sharpenOnly) {
                // No scaling at any supersampling level: the internal size is the stream's, only the pass runs
                internalWidth = width;
                internalHeight = height;
                supersampling = false;
            } else {
                int requestedSupersampling = supersamplingLevel;
                if (dimensionsPlan == null || plannedWidth != w || plannedHeight != h
                        || plannedSupersamplingLevel != requestedSupersampling) {
                    dimensionsPlan = SgsrDimensions.plan(width,height,w,h,maxTextureSize,requestedSupersampling);
                    plannedWidth = w;
                    plannedHeight = h;
                    plannedSupersamplingLevel = requestedSupersampling;
                }
                SgsrDimensions.Plan dimensions = dimensionsPlan;
                internalWidth = dimensions.internalWidth;
                internalHeight = dimensions.internalHeight;
                supersampling = dimensions.isSupersampling();
            }
            boolean dimensionsChanged = activeInternalWidth != internalWidth || activeInternalHeight != internalHeight ||
                    activePresentationWidth != w || activePresentationHeight != h;
            sharpenActive = sharpenOnly;
            activeInternalWidth = internalWidth;
            activeInternalHeight = internalHeight;
            activePresentationWidth = w;
            activePresentationHeight = h;
            int source = colorTexture;
            int sampledWidth = width;
            int sampledHeight = height;
            int selectedProgram = quality == 1 ? highQualityProgram : upscaleProgram;
            if (sharpenOnly) {
                try {
                    if (sharpenProgram == 0) sharpenProgram = createUpscaleProgram(diagnosticContext,"sgsr/sharpen_only.frag");
                } catch (Exception e) { throw new IllegalStateException("Sharpen only",e); }
                selectedProgram = sharpenProgram;
            } else if (UpscalerMode.fromIndex(quality).isTemporal()) {
                try {
                    if (temporal == null) temporal = new SgsrTemporal(diagnosticContext,width,height,internalWidth,internalHeight);
                    if (temporalPresentProgram == 0)
                        temporalPresentProgram = createUpscaleProgram(diagnosticContext,"sgsr/temporal_present.frag");
                    source = temporal.render(colorTexture,internalWidth,internalHeight,texture.getTimestamp(),hdrActive,quality == 3,
                            flowGovernor.reuseFlowThisFrame());
                    sampledWidth = internalWidth;
                    sampledHeight = internalHeight;
                    selectedProgram = temporalPresentProgram;
                } catch (Exception e) { throw new IllegalStateException("SGSR 2 video",e); }
            } else {
                // Reuse the supersampling target for spatial reconstruction, then sharpen once at
                // presentation. Fusing here would repeat the full SGSR kernel for every cross tap.
                ensureSupersampleTarget(internalWidth,internalHeight);
                GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER,supersampleFramebuffer);
                drawProgram(selectedProgram,colorTexture,internalWidth,internalHeight,width,height,
                        internalWidth>=width && internalHeight>=height);
                source = supersampleTexture;
                sampledWidth = internalWidth;
                sampledHeight = internalHeight;
                selectedProgram = supersamplePresentProgram;
            }
            if (outputWidth!=w || outputHeight!=h || dimensionsChanged) {
                outputWidth=w; outputHeight=h;
                LimeLog.info("SGSR output: " + width + "x" + height + " -> " + internalWidth + "x" +
                        internalHeight + " -> " + w + "x" + h + (supersampling ? " (supersampling)" : ""));
            }
            cacheFrame(source,selectedProgram,w,h,sampledWidth,sampledHeight,quality,
                    sharpenOnly || (w>=width && h>=height));
            if (arrivalActive) {
                scheduleArrivalTick();
                return;
            }
            statsFrameArrivalNs = arrivalNs;
            statsFrameArmed = true;
            presentCachedNormal();
            statsFrameArmed = false;
            final long swapEndNs=System.nanoTime();
            if (stages != null) {
                stages.record(LatencyStages.AVAILABLE_TO_DRAW,drawStartNs-arrivalNs);
                stages.record(LatencyStages.DRAW,swapEndNs-drawStartNs);
                displayTimestamps.poll(tenBit ? hdrTimestampSource : sdrTimestampSource,stages);
            }
            // GPU time from the fences once there is one; the CPU submit time until then
            final float cpuMs=(swapEndNs-drawStartNs)/1_000_000f;
            frameToSwapMs=SgsrPacing.smooth(frameToSwapMs,cpuMs+gpuWorkMs);
            if (sharpenMeter != null) sharpenMeter.tick(swapEndNs,sharpenOnly,sharpenDescription);
            final float elapsedMs=gpuWorkMs>0f ? gpuWorkMs : (swapEndNs-processingStartNs)/1_000_000f;
            gpuTimeMs=gpuTimeMs==0f?elapsedMs:gpuTimeMs*.8f+elapsedMs*.2f;
            consecutiveDrawFailures=0;
        } catch (RuntimeException e) {
            if (arrivalProgress >= 0 || arrivalOnly) {
                failArrival(e);
                return;
            }
            // A surface being replaced or lost fails single frames (texture update, swap); only a streak of
            // them means SGSR itself is broken and the stream must fall back to direct output.
            if (++consecutiveDrawFailures < MAX_CONSECUTIVE_DRAW_FAILURES) {
                LimeLog.warning("SGSR frame skipped (" + consecutiveDrawFailures + "): " + e);
                return;
            }
            LimeLog.warning("SGSR stopped; recovering to direct output: " + e);
            if (closed.compareAndSet(false,true)) {
                release();
                thread.quitSafely();
                frameThread.quitSafely();
                failure.run();
            }
        }
    }

    private void cacheFrame(int source, int program, int w, int h, int sampledWidth,
                            int sampledHeight, int quality, boolean reconstruct) {
        cachedSource = source;
        cachedProgram = program;
        cachedWidth = w;
        cachedHeight = h;
        cachedSampledWidth = sampledWidth;
        cachedSampledHeight = sampledHeight;
        cachedQuality = quality;
        cachedReconstruction = reconstruct;
        arrivalReconstructionDirty = true;
    }

    private boolean arrivalIsActive() {
        return !closed.get() && outputAttached && arrivalProgress >= 0
                && !arrivalComplete && arrivalError == null;
    }

    private void updateArrivalState() {
        arrivalStatePending.set(false);
        if (closed.get()) return;
        if (arrivalProgress < 0) {
            releaseArrival();
            arrivalCompletionListener = null;
            if (!arrivalComplete) {
                arrivalReady = false;
                restoreCachedNormal();
            }
        } else scheduleArrivalTick();
    }

    private void scheduleArrivalTick() {
        if (!arrivalFrameScheduled && arrivalIsActive() && choreographer != null) {
            arrivalFrameScheduled = true;
            choreographer.postFrameCallback(arrivalTick);
        }
    }

    private void stopArrivalTicks() {
        if (arrivalFrameScheduled && choreographer != null)
            choreographer.removeFrameCallback(arrivalTick);
        arrivalFrameScheduled = false;
    }

    private void onArrivalTick(long frameTimeNanos) {
        arrivalFrameScheduled = false;
        if (!arrivalIsActive()) return;
        try {
            if (cachedSource == 0) return;
            float progress = arrivalProgress;
            if (!arrivalOnly && progress >= 1f) {
                // The exact normal SGSR endpoint uses its last temporal/spatial result, without
                // submitting the same input to temporal history a second time.
                if (!presentCachedNormal()) return;
            } else {
                if (arrivalReconstructionDirty) {
                    ensureArrival(cachedWidth,cachedHeight);
                    if (arrivalOnly) arrival.copySource(cachedSource);
                    else {
                        int rgbProgram = tenBit
                                ? arrivalRgbProgram(cachedQuality) : cachedProgram;
                        drawProgram(rgbProgram,cachedSource,cachedWidth,cachedHeight,
                                cachedSampledWidth,cachedSampledHeight,cachedReconstruction);
                    }
                    arrival.prefilter();
                    arrivalReconstructionDirty = false;
                }
                if (!acquireOutput()) return;
                LaunchArrival.writePose(progress,arrivalPose);
                arrival.present(arrivalPose,width,height,hdrActive);
                presentOutput();
            }
            arrivalFramePresented(progress);
        } catch (RuntimeException e) {
            failArrival(e);
        } finally {
            scheduleArrivalTick();
        }
    }

    private boolean acquireOutput() {
        if (!tenBit) {
            GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER,0);
            return true;
        }
        if (hdrFrameAcquired) GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER,hdrFramebuffer);
        else {
            if (nativeAcquireHdr(hdrOutput) < 0) return false;
            hdrFrameAcquired = true;
            GLES31.glGetIntegerv(GLES31.GL_FRAMEBUFFER_BINDING,size,0);
            hdrFramebuffer = size[0];
        }
        return true;
    }

    private boolean presentCachedNormal() {
        if (cachedSource == 0 || !outputAttached || !acquireOutput()) return false;
        final SharpenMeter meter = cachedQuality == SHARPEN_ONLY && !arrivalOnly ? sharpenMeter : null;
        if (meter != null) meter.beginPass();
        try {
            drawProgram(cachedProgram,cachedSource,cachedWidth,cachedHeight,
                    cachedSampledWidth,cachedSampledHeight,cachedReconstruction);
        } finally {
            if (meter != null) meter.endPass();
        }
        presentOutput();
        return true;
    }

    private void restoreCachedNormal() {
        if (arrivalOnly || closed.get()) return;
        try { presentCachedNormal(); }
        catch (RuntimeException e) { LimeLog.warning("SGSR arrival normal-output restore failed: " + e); }
    }

    private void ensureArrival(int w, int h) {
        if (arrival == null) {
            try { arrival = new ArrivalGlPass(diagnosticContext,tenBit); }
            catch (Exception e) { throw new IllegalStateException("Arrival shader initialization failed",e); }
        }
        arrival.beginReconstruction(w,h);
    }

    private int arrivalRgbProgram(int quality) {
        if (quality == SHARPEN_ONLY) {
            if (arrivalSharpenProgram == 0)
                arrivalSharpenProgram = createArrivalRgbProgram("sgsr/sharpen_only.frag");
            return arrivalSharpenProgram;
        }
        if (UpscalerMode.fromIndex(quality).isTemporal()) {
            if (arrivalTemporalProgram == 0)
                arrivalTemporalProgram = createArrivalRgbProgram("sgsr/temporal_present.frag");
            return arrivalTemporalProgram;
        }
        if (arrivalPresentProgram == 0)
            arrivalPresentProgram = createArrivalRgbProgram("sgsr/supersample_present.frag");
        return arrivalPresentProgram;
    }

    private int createArrivalRgbProgram(String asset) {
        try { return createUpscaleProgram(diagnosticContext,asset,false); }
        catch (Exception e) { throw new IllegalStateException("Arrival RGB reconstruction shader failed",e); }
    }

    private void presentOutput() {
        require(GLES31.glGetError()==GLES31.GL_NO_ERROR,"SGSR/arrival draw");
        // Only the frames the draw path presents are timed, not the arrival animation's re-presents
        final LatencyStages stages = statsFrameArmed ? latencyStages : null;
        statsFrameArmed = false;
        if (tenBit) {
            final long submitNs = System.nanoTime();
            int sequence = nativePresentHdr(hdrOutput,hdrActive,hdrMetadata);
            hdrFrameAcquired = false;
            if (sequence > 0) {
                if (!arrivalOnly) gpuInFlight.add(sequence,submitNs,hdrFences);
                if (stages != null) displayTimestamps.add(sequence,statsFrameArrivalNs);
            }
        }
        else {
            // A native fence per frame bounds the GPU work in flight; it also flushes the commands. Without
            // native fences the GPU is waited for, which bounds the backlog at the price of idle time.
            if (!arrivalOnly) {
                final long submitNs = System.nanoTime();
                int fence = nativeGpuFenceCreate();
                if (fence >= 0) gpuInFlight.add(fence,submitNs,sdrFences);
                else if (fence == -1) GLES31.glFinish();
            }
            long frameId = stages != null && sdrTimestamps ? nativeFrameNextId() : -1;
            EGLExt.eglPresentationTimeANDROID(display,window,System.nanoTime());
            require(EGL14.eglSwapBuffers(display,window),"SGSR swap");
            if (frameId >= 0) displayTimestamps.add(frameId,statsFrameArrivalNs);
        }
        firstFramePresented = true;
    }

    private void arrivalFramePresented(float progress) {
        arrivalReady = true;
        if (progress >= 1f) {
            releaseArrival();
            arrivalComplete = true;
            arrivalProgress = -1f;
            Runnable listener = arrivalCompletionListener;
            arrivalCompletionListener = null;
            if (listener != null && !closed.get()) listener.run();
        }
    }

    private void failArrival(Exception error) {
        if (arrivalError != null) return;
        arrivalError = "Stream arrival GPU path failed: " + error;
        LimeLog.warning(arrivalError);
        arrivalReady = false;
        arrivalProgress = -1f;
        arrivalCompletionListener = null;
        releaseArrival();
        if (arrivalOnly) failure.run();
        else restoreCachedNormal();
    }

    private void releaseArrival() {
        stopArrivalTicks();
        handler.removeCallbacks(arrivalStateChange);
        arrivalStatePending.set(false);
        arrivalReconstructionDirty = true;
        if (arrival != null) { arrival.close(); arrival = null; }
        programUniforms.remove(arrivalTemporalProgram);
        programUniforms.remove(arrivalPresentProgram);
        programUniforms.remove(arrivalSharpenProgram);
        if (arrivalTemporalProgram != 0) GLES31.glDeleteProgram(arrivalTemporalProgram);
        if (arrivalPresentProgram != 0) GLES31.glDeleteProgram(arrivalPresentProgram);
        if (arrivalSharpenProgram != 0) GLES31.glDeleteProgram(arrivalSharpenProgram);
        arrivalTemporalProgram = arrivalPresentProgram = arrivalSharpenProgram = 0;
    }

    /**
     * Smoothed GPU execution time per frame, in milliseconds, read from the frame fences (the time queueing behind the
     * previous frame is not counted), a few frames after the draw. Until the first fence has been read, or when the
     * driver gives no fences, the CPU time of submitting the frame.
     */
    public float getGpuTimeMs() { return gpuTimeMs; }
    /**
     * Smoothed time a frame spends in the output stage after the pacer released it, in milliseconds: the CPU time of
     * the draw (draw start to the end of the swap call) plus the GPU execution time. Frames waiting for a busy GPU
     * are not part of it, the bound keeps that wait to about one frame. 0 before the first frame.
     */
    public float getFrameToSwapMs() { return frameToSwapMs; }

    /** Called from the GL thread when the upscaler could not keep up with the frame rate for about two seconds. */
    public interface OverloadListener {
        void onOverload(int mode, boolean supersampling);
    }

    public void setOverloadListener(OverloadListener listener) { overloadListener = listener; }

    /** A finished statistics window: log it and let the overload detector look at it. */
    private void onQueueWindow(FrameQueueStats window) {
        if (arrivalOnly) return;
        android.util.Log.d(FrameQueue.TAG,window.describe());
        if (flowGovernor.onWindow(window))
            android.util.Log.i(FrameQueue.TAG,flowGovernor.isReduced()
                    ? "SGSR 2 under load: the optical flow is recomputed every second frame"
                    : "SGSR 2 load is back to normal: the optical flow is recomputed every frame");
        if (overloadDetector.onWindow(window)) {
            android.util.Log.w(FrameQueue.TAG,"overload: GPU " + window.gpuAvgMs + " ms per frame at "
                    + window.arrivedPerSecond() + " frames/s, " + window.busySkippedPerSecond()
                    + " frames/s dropped because the GPU was busy, mode " + mode + ", supersampling " + supersampling);
            OverloadListener listener = overloadListener;
            if (listener != null) listener.onOverload(mode,supersampling);
        }
    }

    /**
     * Whether the next draw has to wait because the GPU still has the maximum number of frames in flight. Also
     * collects the finished frames' GPU times.
     */
    private boolean gpuBusy(long nowNs) {
        return gpuInFlight.shouldSkip(tenBit ? hdrFences : sdrFences,nowNs,gpuWorkSink);
    }

    private void scheduleRetry() {
        if (!retryPosted) {
            retryPosted = true;
            handler.postDelayed(retryTask,1);
        }
    }

    private void ensureSupersampleTarget(int width, int height) {
        if (supersampleTexture != 0 && supersampleWidth == width && supersampleHeight == height) return;
        if (supersampleTexture == 0) supersampleTexture = texture(GLES31.GL_TEXTURE_2D);
        if (supersampleFramebuffer == 0) {
            int[] id = new int[1];
            GLES31.glGenFramebuffers(1,id,0);
            supersampleFramebuffer = id[0];
        }
        supersampleWidth = width;
        supersampleHeight = height;
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D,supersampleTexture);
        GLES31.glTexImage2D(GLES31.GL_TEXTURE_2D,0,tenBit ? GLES31.GL_RGBA16F : GLES31.GL_RGBA8,
                width,height,0,GLES31.GL_RGBA,tenBit ? GLES31.GL_HALF_FLOAT : GLES31.GL_UNSIGNED_BYTE,null);
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER,supersampleFramebuffer);
        GLES31.glFramebufferTexture2D(GLES31.GL_FRAMEBUFFER,GLES31.GL_COLOR_ATTACHMENT0,
                GLES31.GL_TEXTURE_2D,supersampleTexture,0);
        require(GLES31.glCheckFramebufferStatus(GLES31.GL_FRAMEBUFFER)==GLES31.GL_FRAMEBUFFER_COMPLETE,
                "Supersampling framebuffer");
    }

    private void drawProgram(int program, int source, int viewportWidth, int viewportHeight,
                             int sampledWidth, int sampledHeight, boolean reconstructionEnabled) {
        GLES31.glViewport(0,0,viewportWidth,viewportHeight);
        GLES31.glUseProgram(program);
        ProgramUniforms uniforms = programUniforms.get(program);
        GLES31.glUniform4f(uniforms.viewport,1f/sampledWidth,1f/sampledHeight,width,height);
        GLES31.glUniform1f(uniforms.strength,sharpness);
        GLES31.glUniform1i(uniforms.enabled,reconstructionEnabled ? 1 : 0);
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0);
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D,source);
        GLES31.glUniform1i(uniforms.source,0);
        if (tenBit) GLES31.glUniform1i(uniforms.hdr,hdrActive ? 1 : 0);
        GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP,0,4);
    }

    private static int texture(int target) {
        int[] id = new int[1]; GLES31.glGenTextures(1,id,0);
        GLES31.glBindTexture(target,id[0]);
        GLES31.glTexParameteri(target,GLES31.GL_TEXTURE_MIN_FILTER,GLES31.GL_LINEAR);
        GLES31.glTexParameteri(target,GLES31.GL_TEXTURE_MAG_FILTER,GLES31.GL_LINEAR);
        GLES31.glTexParameteri(target,GLES31.GL_TEXTURE_WRAP_S,GLES31.GL_CLAMP_TO_EDGE);
        GLES31.glTexParameteri(target,GLES31.GL_TEXTURE_WRAP_T,GLES31.GL_CLAMP_TO_EDGE);
        return id[0];
    }
    static int shader(int kind,String source) {
        int id=GLES31.glCreateShader(kind);
        GLES31.glShaderSource(id,source); GLES31.glCompileShader(id);
        int[] ok=new int[1]; GLES31.glGetShaderiv(id,GLES31.GL_COMPILE_STATUS,ok,0);
        if (ok[0]==0) { String log=GLES31.glGetShaderInfoLog(id); GLES31.glDeleteShader(id); throw new IllegalStateException(log); }
        return id;
    }
    static int program(String vertex,String fragment) {
        int vs=shader(GLES31.GL_VERTEX_SHADER,vertex), fs=0, p=0;
        try {
            fs=shader(GLES31.GL_FRAGMENT_SHADER,fragment); p=GLES31.glCreateProgram();
            GLES31.glAttachShader(p,vs); GLES31.glAttachShader(p,fs); GLES31.glLinkProgram(p);
            int[] ok=new int[1]; GLES31.glGetProgramiv(p,GLES31.GL_LINK_STATUS,ok,0);
            require(ok[0]!=0,"SGSR link: "+GLES31.glGetProgramInfoLog(p));
            return p;
        } catch (RuntimeException e) { if(p!=0) GLES31.glDeleteProgram(p); throw e; }
        finally { GLES31.glDeleteShader(vs); if(fs!=0) GLES31.glDeleteShader(fs); }
    }
    private static void require(boolean condition,String message) { if(!condition) throw new IllegalStateException(message); }

    /** Queue GL-owned cleanup without extending the direct-output handover deadline. */
    void closeAsync() {
        if (!closed.compareAndSet(false,true)) return;
        handler.post(() -> { try { release(); } finally { thread.quitSafely(); frameThread.quitSafely(); } });
    }

    @Override public void close() {
        closeAsync();
        if (Thread.currentThread()!=thread) {
            try { thread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
    private void release() {
        // Every fence descriptor is closed exactly once: the ones still in flight are released here
        gpuInFlight.clear(tenBit ? hdrFences : sdrFences);
        handler.removeCallbacks(retryTask);
        heldFrame = false;
        stopArrivalTicks();
        handler.removeCallbacks(arrivalStateChange);
        arrivalStatePending.set(false);
        arrivalCompletionListener = null;
        arrivalReady = false;
        cachedSource = 0;
        hdrFrameAcquired = false;
        if (hdrOutput!=0) { nativeDestroyHdr(hdrOutput); hdrOutput=0; }
        if (texture!=null) { texture.setOnFrameAvailableListener(null); texture.release(); texture=null; }
        if (decoderSurface!=null) { decoderSurface.release(); decoderSurface=null; }
        if (display!=EGL14.EGL_NO_DISPLAY) {
            if (context!=EGL14.EGL_NO_CONTEXT && window!=EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display,window,window,context);
                releaseArrival();
                if(copyProgram!=0) GLES31.glDeleteProgram(copyProgram);
                if(upscaleProgram!=0) GLES31.glDeleteProgram(upscaleProgram);
                if(highQualityProgram!=0) GLES31.glDeleteProgram(highQualityProgram);
                if(temporalPresentProgram!=0) GLES31.glDeleteProgram(temporalPresentProgram);
                if(supersamplePresentProgram!=0) GLES31.glDeleteProgram(supersamplePresentProgram);
                if(sharpenProgram!=0) GLES31.glDeleteProgram(sharpenProgram);
                if(sharpenMeter!=null) { sharpenMeter.close(); sharpenMeter=null; }
                if(temporal!=null) { temporal.close(); temporal=null; }
                GLES31.glDeleteTextures(2,new int[]{externalTexture,colorTexture},0);
                if(supersampleTexture!=0) GLES31.glDeleteTextures(1,new int[]{supersampleTexture},0);
                GLES31.glDeleteFramebuffers(1,new int[]{framebuffer},0);
                if(supersampleFramebuffer!=0) GLES31.glDeleteFramebuffers(1,new int[]{supersampleFramebuffer},0);
            }
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if(window!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,window);
            if(pbuffer!=EGL14.EGL_NO_SURFACE && pbuffer!=window) EGL14.eglDestroySurface(display,pbuffer);
            if(context!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context);
            EGL14.eglTerminate(display);
            EGL14.eglReleaseThread();
        }
    }
    private static native long nativeCreateHdr(Surface output,int width,int height);
    private static native int nativeAcquireHdr(long handle);
    private static native int nativeHdrWidth(long handle);
    private static native int nativeHdrHeight(long handle);
    private static native int nativePresentHdr(long handle,boolean hdr,byte[] metadata);
    private static native long nativeHdrGpuDone(long handle,int sequence);
    private static native int nativeGpuFenceCreate();
    private static native long nativeGpuFenceStatus(int fd);
    private static native void nativeGpuFenceClose(int fd);
    private static native int nativeHdrFrameTimestamps(long handle,int sequence,long[] out);
    private static native void nativeDestroyHdr(long handle);
    private static native boolean nativeFrameTimestampsEnable();
    private static native long nativeFrameNextId();
    private static native int nativeFrameTimestamps(long frameId,long[] out);
}
