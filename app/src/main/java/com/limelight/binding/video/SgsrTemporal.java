package com.limelight.binding.video;

import android.content.Context;
import android.opengl.GLES31;
import com.limelight.LimeLog;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** SGSR 2-derived video reconstruction. Every resource is owned by the SGSR GL thread. */
final class SgsrTemporal implements AutoCloseable {
    private static final int LEVELS=4;
    private static final String VERTEX="#version 310 es\nlayout(location=0) out highp vec2 texCoord;"+
            "void main(){vec2 p=vec2(float(gl_VertexID&1),float(gl_VertexID>>1));"+
            "gl_Position=vec4(p*2.0-1.0,0,1);texCoord=p;}";
    private static final String LUMA="#version 310 es\nprecision highp float;"+
            "layout(location=0) in highp vec2 texCoord;uniform sampler2D source;out vec4 color;"+
            "void main(){vec3 c=textureLod(source,texCoord,0.0).rgb;"+
            "color=vec4(dot(c,vec3(0.2126,0.7152,0.0722)),0,0,1);}";
    private final int width,height;
    private final int[] luma=new int[2], history=new int[2], backward=new int[LEVELS],forward=new int[LEVELS];
    private int lumaProgram,flowProgram,sceneProgram,activateProgram,upscaleProgram,framebuffer,sceneTexture,reactiveTexture;
    private int outputWidth,outputHeight,index;
    private long previousTimestamp;
    private boolean valid,hdr,flowComputed;

    SgsrTemporal(Context app,int width,int height,int outputWidth,int outputHeight) throws Exception {
        this.width=width;this.height=height;
        try {
            lumaProgram=SgsrRenderer.program(VERTEX,LUMA);
            flowProgram=compute(asset(app,"sgsr/video_flow.comp"));
            sceneProgram=compute(asset(app,"sgsr/scene_change.comp"));
            activateProgram=compute(asset(app,"sgsr/video_activate.comp"));
            upscaleProgram=SgsrRenderer.program(VERTEX,asset(app,"sgsr/sgsr2_video.frag"));
            int[] id=new int[1];GLES31.glGenFramebuffers(1,id,0);framebuffer=id[0];
            GLES31.glGenTextures(2,luma,0);
            for(int texture:luma) allocate(texture,GLES31.GL_R8,width,height,LEVELS);
            GLES31.glGenTextures(LEVELS,backward,0);GLES31.glGenTextures(LEVELS,forward,0);
            for(int level=0;level<LEVELS;level++) {
                int w=((width>>level)+7)/8,h=((height>>level)+7)/8;
                allocate(backward[level],GLES31.GL_RGBA16F,w,h,1);
                allocate(forward[level],GLES31.GL_RGBA16F,w,h,1);
            }
            GLES31.glGenTextures(1,id,0);sceneTexture=id[0];allocate(sceneTexture,GLES31.GL_RGBA16F,1,1,1);
            GLES31.glGenTextures(1,id,0);reactiveTexture=id[0];allocate(reactiveTexture,GLES31.GL_RGBA16F,width,height,1);
            resize(outputWidth,outputHeight);
            require(GLES31.glGetError()==GLES31.GL_NO_ERROR,"SGSR 2 allocation");
            LimeLog.info("SGSR 2 video initialized: "+width+"x"+height+" -> "+outputWidth+"x"+outputHeight);
        } catch(Exception e) {close();throw e;}
    }

    void reset() {valid=false;}

    int render(int source,int outputWidth,int outputHeight,long timestamp,boolean hdr,boolean quality) {
        return render(source,outputWidth,outputHeight,timestamp,hdr,quality,false);
    }
    int render(int source,int outputWidth,int outputHeight,long timestamp,boolean hdr,boolean quality,boolean reuseFlow) {
        if(this.outputWidth!=outputWidth||this.outputHeight!=outputHeight) resize(outputWidth,outputHeight);
        boolean reset=!valid||timestamp<=previousTimestamp||timestamp-previousTimestamp>50_000_000L||this.hdr!=hdr;
        this.hdr=hdr;previousTimestamp=timestamp;
        target(luma[index],width,height);
        GLES31.glUseProgram(lumaProgram);bind(lumaProgram,"source",0,source);
        GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP,0,4);
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D,luma[index]);GLES31.glGenerateMipmap(GLES31.GL_TEXTURE_2D);
        if(reset) {
            // Initialize both flow inputs from this frame so there is no uninitialized texture read.
            target(luma[1-index],width,height);
            GLES31.glUseProgram(lumaProgram);bind(lumaProgram,"source",0,source);
            GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP,0,4);
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D,luma[1-index]);GLES31.glGenerateMipmap(GLES31.GL_TEXTURE_2D);
        }
        // Under overload (FlowGovernor) every second frame reuses the previous frame's motion instead of recomputing
        // it. Never after a reset, and never before the first computation (the textures would be uninitialised).
        if(!(reuseFlow&&!reset&&flowComputed)) {
            flow(luma[index],luma[1-index],backward);
            flow(luma[1-index],luma[index],forward);
            flowComputed=true;
        }
        GLES31.glUseProgram(sceneProgram);
        bind(sceneProgram,"currentLuma",0,luma[index]);bind(sceneProgram,"previousLuma",1,luma[1-index]);
        GLES31.glBindImageTexture(0,sceneTexture,0,false,0,GLES31.GL_WRITE_ONLY,GLES31.GL_RGBA16F);
        GLES31.glDispatchCompute(1,1,1);barrier();

        if(quality) {
            GLES31.glUseProgram(activateProgram);
            bind(activateProgram,"currentLuma",0,luma[index]);
            bind(activateProgram,"previousLuma",1,luma[1-index]);
            bind(activateProgram,"backwardFlow",2,backward[0]);
            bind(activateProgram,"forwardFlow",3,forward[0]);
            GLES31.glUniform2i(GLES31.glGetUniformLocation(activateProgram,"inputSize"),width,height);
            GLES31.glBindImageTexture(0,reactiveTexture,0,false,0,GLES31.GL_WRITE_ONLY,GLES31.GL_RGBA16F);
            GLES31.glDispatchCompute((width+7)/8,(height+7)/8,1);barrier();
        }

        target(history[index],outputWidth,outputHeight);
        GLES31.glUseProgram(upscaleProgram);
        bind(upscaleProgram,"InputColor",0,source);
        // On reset the shader discards history, but bind an initialized input to avoid NaNs.
        bind(upscaleProgram,"PrevOutput",1,reset?source:history[1-index]);
        bind(upscaleProgram,"MotionDepthClipAlphaBuffer",2,backward[0]);
        bind(upscaleProgram,"ForwardFlow",3,forward[0]);bind(upscaleProgram,"SceneChange",4,sceneTexture);
        bind(upscaleProgram,"ReactiveMask",5,quality?reactiveTexture:sceneTexture);
        vec2("renderSize",width,height);vec2("outputSize",outputWidth,outputHeight);
        vec2("renderSizeRcp",1f/width,1f/height);
        // Official Qualcomm SGSR2RenderPass: bias=output/input, box factor=min(20, areaRatio^3).
        float scaleX=(float)outputWidth/width,scaleY=(float)outputHeight/height;
        vec2("scaleRatio",scaleX,Math.min(20f,(float)Math.pow(scaleX*scaleY,3)));
        GLES31.glUniform1f(GLES31.glGetUniformLocation(upscaleProgram,"reset"),reset?1:0);
        GLES31.glUniform1i(GLES31.glGetUniformLocation(upscaleProgram,"qualityMode"),quality?1:0);
        GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP,0,4);
        require(GLES31.glGetError()==GLES31.GL_NO_ERROR,"SGSR 2 render");
        int result=history[index];index=1-index;valid=true;
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0);
        return result;
    }

    private void flow(int current,int previous,int[] output) {
        GLES31.glUseProgram(flowProgram);
        bind(flowProgram,"currentLuma",0,current);bind(flowProgram,"previousLuma",1,previous);
        for(int level=LEVELS-1;level>=0;level--) {
            int w=width>>level,h=height>>level;
            // The coarsest invocation does not sample coarseFlow. Bind a different image anyway.
            bind(flowProgram,"coarseFlow",2,level==LEVELS-1?sceneTexture:output[level+1]);
            GLES31.glUniform1i(GLES31.glGetUniformLocation(flowProgram,"level"),level);
            GLES31.glUniform1i(GLES31.glGetUniformLocation(flowProgram,"coarsest"),level==LEVELS-1?1:0);
            GLES31.glUniform2i(GLES31.glGetUniformLocation(flowProgram,"levelSize"),w,h);
            GLES31.glBindImageTexture(0,output[level],0,false,0,GLES31.GL_WRITE_ONLY,GLES31.GL_RGBA16F);
            GLES31.glDispatchCompute((((w+7)/8)+7)/8,(((h+7)/8)+7)/8,1);barrier();
        }
    }
    private void resize(int w,int h) {
        GLES31.glDeleteTextures(2,history,0);GLES31.glGenTextures(2,history,0);
        for(int texture:history) allocate(texture,GLES31.GL_RGBA16F,w,h,1);
        outputWidth=w;outputHeight=h;valid=false;
    }
    private void target(int texture,int w,int h) {
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER,framebuffer);
        GLES31.glFramebufferTexture2D(GLES31.GL_FRAMEBUFFER,GLES31.GL_COLOR_ATTACHMENT0,GLES31.GL_TEXTURE_2D,texture,0);
        require(GLES31.glCheckFramebufferStatus(GLES31.GL_FRAMEBUFFER)==GLES31.GL_FRAMEBUFFER_COMPLETE,"SGSR 2 framebuffer");
        GLES31.glViewport(0,0,w,h);
    }
    private static void allocate(int texture,int format,int w,int h,int levels) {
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0);GLES31.glBindTexture(GLES31.GL_TEXTURE_2D,texture);
        GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D,levels,format,w,h);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D,GLES31.GL_TEXTURE_MIN_FILTER,GLES31.GL_LINEAR);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D,GLES31.GL_TEXTURE_MAG_FILTER,GLES31.GL_LINEAR);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D,GLES31.GL_TEXTURE_WRAP_S,GLES31.GL_CLAMP_TO_EDGE);
        GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D,GLES31.GL_TEXTURE_WRAP_T,GLES31.GL_CLAMP_TO_EDGE);
    }
    private static void bind(int program,String name,int unit,int texture) {
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0+unit);GLES31.glBindTexture(GLES31.GL_TEXTURE_2D,texture);
        GLES31.glUniform1i(GLES31.glGetUniformLocation(program,name),unit);
    }
    private void vec2(String name,float x,float y) {GLES31.glUniform2f(GLES31.glGetUniformLocation(upscaleProgram,name),x,y);}
    private static void barrier() {GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GLES31.GL_TEXTURE_FETCH_BARRIER_BIT);}
    private static String asset(Context app,String path) throws Exception {
        try(InputStream input=app.getAssets().open(path);ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))>=0)bytes.write(buffer,0,n);
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
    }
    private static int compute(String source) {
        int shader=SgsrRenderer.shader(GLES31.GL_COMPUTE_SHADER,source),program=GLES31.glCreateProgram();
        try {
            GLES31.glAttachShader(program,shader);GLES31.glLinkProgram(program);
            int[] ok=new int[1];GLES31.glGetProgramiv(program,GLES31.GL_LINK_STATUS,ok,0);
            require(ok[0]!=0,GLES31.glGetProgramInfoLog(program));return program;
        } catch(RuntimeException e) {GLES31.glDeleteProgram(program);throw e;}
        finally {GLES31.glDeleteShader(shader);}
    }
    private static void require(boolean value,String message) {if(!value)throw new IllegalStateException(message);}
    @Override public void close() {
        GLES31.glDeleteTextures(2,luma,0);GLES31.glDeleteTextures(2,history,0);
        GLES31.glDeleteTextures(LEVELS,backward,0);GLES31.glDeleteTextures(LEVELS,forward,0);
        GLES31.glDeleteTextures(2,new int[]{sceneTexture,reactiveTexture},0);GLES31.glDeleteFramebuffers(1,new int[]{framebuffer},0);
        for(int program:new int[]{lumaProgram,flowProgram,sceneProgram,activateProgram,upscaleProgram}) if(program!=0)GLES31.glDeleteProgram(program);
    }
}
