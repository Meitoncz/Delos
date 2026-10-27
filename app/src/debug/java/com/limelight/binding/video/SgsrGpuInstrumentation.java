package com.limelight.binding.video;

import android.app.Instrumentation;
import android.opengl.*;
import android.os.Bundle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

/** Runs the actual Java/GLES temporal renderer without an Activity, decoder or game launch. */
public final class SgsrGpuInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {super.onCreate(arguments);start();}
    @Override public void onStart() {
        Bundle result=new Bundle();StringBuilder report=new StringBuilder();
        EGLDisplay display=EGL14.EGL_NO_DISPLAY;EGLContext context=EGL14.EGL_NO_CONTEXT;EGLSurface surface=EGL14.EGL_NO_SURFACE;
        try {
            display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            check(EGL14.eglInitialize(display,new int[2],0,new int[2],0),"EGL initialize");
            EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
            check(EGL14.eglChooseConfig(display,new int[]{EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_NONE},0,configs,0,1,count,0)&&count[0]>0,"EGL config");
            context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
            surface=EGL14.eglCreatePbufferSurface(display,configs[0],new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);
            check(EGL14.eglMakeCurrent(display,surface,surface,context),"EGL current");
            report.append("GPU: ").append(GLES31.glGetString(GLES31.GL_RENDERER)).append('\n');
            constants(report);
            ramp(report);
            benchmark(report,854,480,1920,1079);
            benchmark(report,1280,720,1920,1080);
            benchmark(report,1920,1080,2560,1440);
            benchmark(report,1920,1080,3840,2160);
            result.putString("stream",report.toString());finish(-1,result);
        } catch(Throwable e) {result.putString("stream",report+"FAIL: "+android.util.Log.getStackTraceString(e));finish(0,result);}
        finally {
            if(display!=EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
                if(surface!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,surface);
                if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context);
                EGL14.eglTerminate(display);
            }
        }
    }
    private void constants(StringBuilder report) throws Exception {
        int w=128,h=96;int texture=texture(w,h);
        long timestamp=1_000_000_000L;
        try(SgsrTemporal renderer=new SgsrTemporal(getTargetContext(),w,h,288,216)) {
            for(int value:new int[]{3,102,230,70}) {
                ByteBuffer data=ByteBuffer.allocateDirect(w*h*4);
                for(int i=0;i<w*h;i++)data.put((byte)value).put((byte)value).put((byte)value).put((byte)255);
                data.flip();upload(texture,w,h,data);
                for(int frame=0;frame<3;frame++) {
                    renderer.render(texture,288,216,timestamp+=16_666_667L,false,true);
                    FloatBuffer pixels=ByteBuffer.allocateDirect(288*216*16).order(ByteOrder.nativeOrder()).asFloatBuffer();
                    GLES31.glReadPixels(0,0,288,216,GLES31.GL_RGBA,GLES31.GL_FLOAT,pixels);
                    check(GLES31.glGetError()==GLES31.GL_NO_ERROR,"Constant readback");
                    float error=0;
                    for(int i=0;i<288*216*4;i++)if(i%4!=3) {
                        float sample=pixels.get(i);check(Float.isFinite(sample),"Finite output");
                        error=Math.max(error,Math.abs(sample-value/255f));
                    }
                    check(error<.003,"Constant preservation: "+error);
                }
            }
            renderer.reset();renderer.render(texture,256,192,timestamp+200_000_000L,true,true);
            check(GLES31.glGetError()==GLES31.GL_NO_ERROR,"Resize / timestamp gap / HDR reset");
            report.append("PASS constant colors, scene transitions, history reset, resize, HDR transition\n");
        } finally {GLES31.glDeleteTextures(1,new int[]{texture},0);}
    }
    private void benchmark(StringBuilder report,int w,int h,int ow,int oh) throws Exception {
        int texture=texture(w,h);ByteBuffer data=ByteBuffer.allocateDirect(w*h*4);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            int v=(int)(127+35*Math.sin(x*.13+y*.033)+30*Math.sin(y*.21-x*.043));
            data.put((byte)v).put((byte)v).put((byte)v).put((byte)255);
        }
        data.flip();upload(texture,w,h,data);
        try(SgsrTemporal renderer=new SgsrTemporal(getTargetContext(),w,h,ow,oh)) {
            double[] times=new double[20];long timestamp=1_000_000_000L;
            for(int frame=0;frame<25;frame++) {
                GLES31.glFinish();long start=System.nanoTime();
                renderer.render(texture,ow,oh,timestamp+=8_333_333L,true,true);
                GLES31.glFinish();if(frame>=5)times[frame-5]=(System.nanoTime()-start)/1_000_000.0;
            }
            Arrays.sort(times);
            report.append(w).append('x').append(h).append(" -> ").append(ow).append('x').append(oh)
                    .append(" static whole temporal stage: median=").append(times[10]).append("ms p95=").append(times[18]).append("ms\n");
        } finally {GLES31.glDeleteTextures(1,new int[]{texture},0);}
    }
    private void ramp(StringBuilder report) throws Exception {
        int w=128,h=96,ow=288,oh=216,texture=texture(w,h);
        ByteBuffer data=ByteBuffer.allocateDirect(w*h*4);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            byte v=(byte)(20+x+y/2);data.put(v).put(v).put(v).put((byte)255);
        }
        data.flip();upload(texture,w,h,data);
        try(SgsrTemporal renderer=new SgsrTemporal(getTargetContext(),w,h,ow,oh)) {
            renderer.render(texture,ow,oh,1_000_000_000L,true,true);
            FloatBuffer pixels=ByteBuffer.allocateDirect(ow*oh*16).order(ByteOrder.nativeOrder()).asFloatBuffer();
            GLES31.glReadPixels(0,0,ow,oh,GLES31.GL_RGBA,GLES31.GL_FLOAT,pixels);
            check(GLES31.glGetError()==GLES31.GL_NO_ERROR,"Ramp readback");float max=0;
            for(int y=12;y<oh-12;y++)for(int x=12;x<ow-12;x++) {
                float expected=(20+((x+.5f)*w/ow-.5f)+((y+.5f)*h/oh-.5f)*.5f-.25f)/255f;
                max=Math.max(max,Math.abs(pixels.get((y*ow+x)*4)-expected));
            }
            check(max<.012,"Ramp phase noise: "+max);
            report.append("PASS 2.25x ramp / phase-noise regression, max error=").append(max).append('\n');
        } finally {GLES31.glDeleteTextures(1,new int[]{texture},0);}
    }
    private static int texture(int w,int h) {
        int[] id=new int[1];GLES31.glGenTextures(1,id,0);GLES31.glActiveTexture(GLES31.GL_TEXTURE0);
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D,id[0]);GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D,1,GLES31.GL_RGBA8,w,h);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D,GLES31.GL_TEXTURE_MIN_FILTER,GLES31.GL_LINEAR);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D,GLES31.GL_TEXTURE_MAG_FILTER,GLES31.GL_LINEAR);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D,GLES31.GL_TEXTURE_WRAP_S,GLES31.GL_CLAMP_TO_EDGE);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D,GLES31.GL_TEXTURE_WRAP_T,GLES31.GL_CLAMP_TO_EDGE);
        return id[0];
    }
    private static void upload(int texture,int w,int h,ByteBuffer data) {
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0);GLES31.glBindTexture(GLES31.GL_TEXTURE_2D,texture);
        GLES31.glTexSubImage2D(GLES31.GL_TEXTURE_2D,0,0,0,w,h,GLES31.GL_RGBA,GLES31.GL_UNSIGNED_BYTE,data);
    }
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
