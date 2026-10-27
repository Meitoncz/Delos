package com.limelight.binding.video;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import com.limelight.LimeLog;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
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
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Runnable failure;
    private final int width, height;
    private volatile float sharpness;
    private volatile int mode;
    private Integer drawnQuality;
    private volatile float gpuTimeMs;
    private int highQualityProgram;
    private int temporalPresentProgram;
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
    private final float[] transform = new float[16];
    private final int[] size = new int[1];
    private int outputWidth, outputHeight;

    public static boolean isSupported(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        return manager != null && manager.getDeviceConfigurationInfo().reqGlEsVersion >= 0x30001;
    }

    public static SgsrRenderer create(Context app, Surface output, int width, int height,
                                      int sharpness, int mode, boolean tenBit, boolean hdrActive, byte[] hdrMetadata, Runnable failure) {
        if (!isSupported(app)) return null;
        SgsrRenderer renderer = new SgsrRenderer(width, height, sharpness, mode, tenBit, hdrActive, hdrMetadata, failure);
        FutureTask<Void> initialize = new FutureTask<>(() -> {
            renderer.initialize(app, output);
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

    private SgsrRenderer(int width, int height, int sharpness, int mode, boolean tenBit, boolean hdrActive, byte[] metadata, Runnable failure) {
        this.width = width;
        this.height = height;
        this.sharpness = Math.max(0, Math.min(100, sharpness)) / 100f;
        this.tenBit = tenBit;
        this.mode = mode;
        this.hdrActive = hdrActive;
        this.hdrMetadata = metadata != null ? metadata.clone() : null;
        this.failure = failure;
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public Surface getDecoderSurface() { return decoderSurface; }
    public void setSharpness(int value) { sharpness = Math.max(0, Math.min(100, value)) / 100f; }
    public void setMode(int mode) { this.mode = Math.max(0, Math.min(3, mode)); }
    public void setHdrMode(boolean enabled, byte[] metadata) {
        hdrActive = enabled;
        hdrMetadata = metadata != null ? metadata.clone() : null;
    }

    private void initialize(Context app, Surface output) throws Exception {
        diagnosticContext = app.getApplicationContext();
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        require(EGL14.eglInitialize(display, new int[2], 0, new int[2], 0), "EGL initialize");
        int[] attrs = {EGL14.EGL_RED_SIZE,8, EGL14.EGL_GREEN_SIZE,8, EGL14.EGL_BLUE_SIZE,8,
                EGL14.EGL_ALPHA_SIZE,8, EGL14.EGL_SURFACE_TYPE,tenBit ? EGL14.EGL_PBUFFER_BIT : EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RENDERABLE_TYPE,0x40, EGL14.EGL_NONE}; // EGL_OPENGL_ES3_BIT_KHR
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        require(EGL14.eglChooseConfig(display, attrs,0,configs,0,1,count,0) && count[0]>0, "EGL config");
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
        require(context != EGL14.EGL_NO_CONTEXT, "EGL context");
        window = tenBit ? EGL14.eglCreatePbufferSurface(display,configs[0],new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0)
                : EGL14.eglCreateWindowSurface(display, configs[0], output, new int[]{EGL14.EGL_NONE},0);
        require(window != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display,window,window,context), "EGL window");
        String extensions=GLES31.glGetString(GLES31.GL_EXTENSIONS);
        require(extensions.contains("GL_OES_EGL_image_external_essl3"), "External texture support");
        if (tenBit) require(extensions.contains("GL_EXT_YUV_target"), "P010 output support");
        EGL14.eglSwapInterval(display,0);
        copyProgram = program(VERTEX,COPY);
        upscaleProgram = createUpscaleProgram(app,"sgsr/sgsr1.frag");
        highQualityProgram = createUpscaleProgram(app,"sgsr/sgsr1_high.frag");
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
        if (tenBit) {
            android.view.View view = app instanceof com.limelight.Game ? ((com.limelight.Game)app).getStreamSurface() : null;
            hdrOutput = nativeCreateHdr(output,view != null ? view.getWidth() : 0,view != null ? view.getHeight() : 0);
            require(hdrOutput != 0,"P010 SurfaceControl output");
        }
        texture = new SurfaceTexture(externalTexture);
        texture.setDefaultBufferSize(width,height);
        decoderSurface = new Surface(texture);
        texture.setOnFrameAvailableListener(unused -> draw(),handler);
        LimeLog.info("SGSR 1 initialized: " + width + "x" + height + ", sharpness=" + sharpness + ", P010=" + tenBit);
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
        return program(UPSCALE_VERTEX,shader);
    }

    private void draw() {
        if (closed.get()) return;
        final long processingStartNs=System.nanoTime();
        try {
            texture.updateTexImage();
            texture.getTransformMatrix(transform);
            GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER,framebuffer);
            GLES31.glViewport(0,0,width,height);
            GLES31.glUseProgram(copyProgram);
            GLES31.glUniformMatrix4fv(GLES31.glGetUniformLocation(copyProgram,"transform"),1,false,transform,0);
            GLES31.glActiveTexture(GLES31.GL_TEXTURE0);
            GLES31.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,externalTexture);
            GLES31.glUniform1i(GLES31.glGetUniformLocation(copyProgram,"source"),0);
            GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP,0,4);
            int w,h;
            if (tenBit) {
                w=nativeHdrWidth(hdrOutput);h=nativeHdrHeight(hdrOutput);
            } else {
                EGL14.eglQuerySurface(display,window,EGL14.EGL_WIDTH,size,0); w=size[0];
                EGL14.eglQuerySurface(display,window,EGL14.EGL_HEIGHT,size,0); h=size[0];
            }
            if (w<=0 || h<=0) return;
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
                if (temporal != null) temporal.reset();
                LimeLog.info("SGSR quality: " + (quality == 3 ? "SGSR 2 Video Quality" : quality == 2 ? "SGSR 2 Video" : quality == 1 ? "High Quality" : "Standard"));
            }
            int source = colorTexture;
            int selectedProgram = quality == 1 ? highQualityProgram : upscaleProgram;
            if (quality >= 2) {
                try {
                    if (temporal == null) temporal = new SgsrTemporal(diagnosticContext,width,height,w,h);
                    if (temporalPresentProgram == 0)
                        temporalPresentProgram = createUpscaleProgram(diagnosticContext,"sgsr/temporal_present.frag");
                    source = temporal.render(colorTexture,w,h,texture.getTimestamp(),hdrActive,quality == 3);
                    selectedProgram = temporalPresentProgram;
                } catch (Exception e) { throw new IllegalStateException("SGSR 2 video",e); }
            }
            if (tenBit && nativeAcquireHdr(hdrOutput)<0) return;
            if (outputWidth!=w || outputHeight!=h) {
                outputWidth=w; outputHeight=h;
                LimeLog.info("SGSR output: " + w + "x" + h);
            }
            if (!tenBit) GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER,0);
            GLES31.glViewport(0,0,w,h);
            GLES31.glUseProgram(selectedProgram);
            GLES31.glUniform4f(GLES31.glGetUniformLocation(selectedProgram,"ViewportInfo[0]"),
                    quality >= 2 ? 1f/w : 1f/width,quality >= 2 ? 1f/h : 1f/height,width,height);
            GLES31.glUniform1f(GLES31.glGetUniformLocation(selectedProgram,"sgsrStrength"),sharpness);
            GLES31.glUniform1i(GLES31.glGetUniformLocation(selectedProgram,"sgsrEnabled"),w>=width && h>=height ? 1 : 0);
            GLES31.glActiveTexture(GLES31.GL_TEXTURE0);
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D,source);
            GLES31.glUniform1i(GLES31.glGetUniformLocation(selectedProgram,"ps0"),0);
            if (tenBit) GLES31.glUniform1i(GLES31.glGetUniformLocation(selectedProgram,"hdrMode"),hdrActive ? 1 : 0);
            GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP,0,4);
            require(GLES31.glGetError()==GLES31.GL_NO_ERROR,"SGSR draw");
            if (tenBit) nativePresentHdr(hdrOutput,hdrActive,hdrMetadata);
            else {
                EGLExt.eglPresentationTimeANDROID(display,window,System.nanoTime());
                require(EGL14.eglSwapBuffers(display,window),"SGSR swap");
            }
            float elapsedMs=(System.nanoTime()-processingStartNs)/1_000_000f;
            gpuTimeMs=gpuTimeMs==0f?elapsedMs:gpuTimeMs*.8f+elapsedMs*.2f;
        } catch (RuntimeException e) {
            LimeLog.warning("SGSR stopped; recovering to direct output: " + e);
            if (closed.compareAndSet(false,true)) {
                release();
                thread.quitSafely();
                failure.run();
            }
        }
    }

    /** Smoothed wall time of the completed SGSR processing path, excluding decode. */
    public float getGpuTimeMs() { return gpuTimeMs; }

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

    @Override public void close() {
        if (!closed.compareAndSet(false,true)) return;
        handler.post(() -> { try { release(); } finally { thread.quitSafely(); } });
        if (Thread.currentThread()!=thread) {
            try { thread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
    private void release() {
        if (hdrOutput!=0) { nativeDestroyHdr(hdrOutput); hdrOutput=0; }
        if (texture!=null) { texture.setOnFrameAvailableListener(null); texture.release(); texture=null; }
        if (decoderSurface!=null) { decoderSurface.release(); decoderSurface=null; }
        if (display!=EGL14.EGL_NO_DISPLAY) {
            if (context!=EGL14.EGL_NO_CONTEXT && window!=EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display,window,window,context);
                if(copyProgram!=0) GLES31.glDeleteProgram(copyProgram);
                if(upscaleProgram!=0) GLES31.glDeleteProgram(upscaleProgram);
                if(highQualityProgram!=0) GLES31.glDeleteProgram(highQualityProgram);
                if(temporalPresentProgram!=0) GLES31.glDeleteProgram(temporalPresentProgram);
                if(temporal!=null) { temporal.close(); temporal=null; }
                GLES31.glDeleteTextures(2,new int[]{externalTexture,colorTexture},0);
                GLES31.glDeleteFramebuffers(1,new int[]{framebuffer},0);
            }
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if(window!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,window);
            if(context!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context);
            EGL14.eglTerminate(display);
            EGL14.eglReleaseThread();
        }
    }
    private static native long nativeCreateHdr(Surface output,int width,int height);
    private static native int nativeAcquireHdr(long handle);
    private static native int nativeHdrWidth(long handle);
    private static native int nativeHdrHeight(long handle);
    private static native void nativePresentHdr(long handle,boolean hdr,byte[] metadata);
    private static native void nativeDestroyHdr(long handle);
}
