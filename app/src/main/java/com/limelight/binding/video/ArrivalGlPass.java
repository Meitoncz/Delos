package com.limelight.binding.video;

import android.content.Context;
import android.opengl.GLES31;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Arrival-only reconstructed RGB mip pyramid and final stream transform. Owned by the GL thread. */
final class ArrivalGlPass implements AutoCloseable {
    private static final String VERTEX = "#version 310 es\n" +
            "layout(location=0) out highp vec2 uv;" +
            "void main(){vec2 p=vec2(float(gl_VertexID&1),float(gl_VertexID>>1));" +
            "gl_Position=vec4(p*2.0-1.0,0,1);uv=p;}";
    private static final String COPY = "#version 310 es\nprecision highp float;" +
            "layout(location=0) in highp vec2 uv;uniform sampler2D source;" +
            "layout(location=0) out vec4 color;void main(){color=textureLod(source,uv,0.0);}";
    private final boolean tenBit;
    private final int[] id = new int[1];
    private final int effectProgram, copyProgram;
    private final int sourceLocation, copySourceLocation, sizeLocation, presentationLocation, poseLocation, effectsLocation, hdrLocation;
    private int texture, framebuffer, width, height;

    ArrivalGlPass(Context app, boolean tenBit) throws Exception {
        this.tenBit = tenBit;
        String fragment;
        try (InputStream input = app.getAssets().open("sgsr/arrival.frag");
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            fragment = bytes.toString(StandardCharsets.UTF_8.name());
        }
        if (tenBit) {
            fragment = fragment.replace("#version 310 es", "#version 310 es\n#extension GL_EXT_YUV_target : require\nuniform int hdrMode;")
                    .replace("layout(location=0) out vec4 color;", "layout(yuv) out vec4 color;")
                    .replace("color=vec4(rgb,1.0);", "vec3 k=hdrMode!=0?vec3(0.2627,0.6780,0.0593):vec3(0.2126,0.7152,0.0722);" +
                            "float y=dot(rgb,k);vec2 c=vec2((rgb.b-y)/(2.0*(1.0-k.b)),(rgb.r-y)/(2.0*(1.0-k.r)));" +
                            "color=vec4((64.0+876.0*y)/1023.0,(vec2(512.0)+896.0*c)/1023.0,1.0);");
        }
        effectProgram = SgsrRenderer.program(VERTEX, fragment);
        int copy;
        try { copy = SgsrRenderer.program(VERTEX, COPY); }
        catch (RuntimeException e) { GLES31.glDeleteProgram(effectProgram); throw e; }
        copyProgram = copy;
        copySourceLocation = GLES31.glGetUniformLocation(copyProgram, "source");
        sourceLocation = GLES31.glGetUniformLocation(effectProgram, "source");
        sizeLocation = GLES31.glGetUniformLocation(effectProgram, "sourceSize");
        presentationLocation = GLES31.glGetUniformLocation(effectProgram, "presentationSize");
        poseLocation = GLES31.glGetUniformLocation(effectProgram, "pose");
        effectsLocation = GLES31.glGetUniformLocation(effectProgram, "effects");
        hdrLocation = GLES31.glGetUniformLocation(effectProgram, "hdrMode");
    }

    void beginReconstruction(int width, int height) {
        if (texture == 0 || this.width != width || this.height != height) {
            if (texture != 0) { id[0] = texture; GLES31.glDeleteTextures(1, id, 0); }
            GLES31.glGenTextures(1, id, 0);
            texture = id[0];
            this.width = width;
            this.height = height;
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, texture);
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_LINEAR_MIPMAP_LINEAR);
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_LINEAR);
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE);
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE);
            int levels = 1;
            for (int extent = Math.max(width, height); extent > 1; extent >>= 1) levels++;
            GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, levels, tenBit ? GLES31.GL_RGBA16F : GLES31.GL_RGBA8, width, height);
            if (framebuffer == 0) { GLES31.glGenFramebuffers(1, id, 0); framebuffer = id[0]; }
            GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, framebuffer);
            GLES31.glFramebufferTexture2D(GLES31.GL_FRAMEBUFFER, GLES31.GL_COLOR_ATTACHMENT0, GLES31.GL_TEXTURE_2D, texture, 0);
            if (GLES31.glCheckFramebufferStatus(GLES31.GL_FRAMEBUFFER) != GLES31.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException(tenBit ? "Arrival requires renderable FP16 RGB" : "Arrival RGB framebuffer unavailable");
        } else GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, framebuffer);
        GLES31.glViewport(0, 0, width, height);
    }

    void copySource(int source) {
        GLES31.glUseProgram(copyProgram);
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0);
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, source);
        GLES31.glUniform1i(copySourceLocation, 0);
        GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP, 0, 4);
    }

    void prefilter() {
        // Never generate from the attached render target while sampling it. Each live frame replaces level zero.
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0);
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, texture);
        GLES31.glGenerateMipmap(GLES31.GL_TEXTURE_2D);
        if (GLES31.glGetError() != GLES31.GL_NO_ERROR)
            throw new IllegalStateException(tenBit ? "Arrival requires FP16 mip filtering" : "Arrival mip generation failed");
    }

    void present(float[] pose, int sourceWidth, int sourceHeight, boolean hdr) {
        GLES31.glViewport(0, 0, width, height);
        GLES31.glUseProgram(effectProgram);
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0);
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, texture);
        GLES31.glUniform1i(sourceLocation, 0);
        GLES31.glUniform2f(sizeLocation, sourceWidth, sourceHeight);
        GLES31.glUniform2f(presentationLocation, width, height);
        GLES31.glUniform4f(poseLocation, pose[0], pose[1], pose[2], pose[3]);
        GLES31.glUniform2f(effectsLocation, pose[4], pose[5]);
        if (tenBit) GLES31.glUniform1i(hdrLocation, hdr ? 1 : 0);
        GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP, 0, 4);
    }

    @Override public void close() {
        GLES31.glDeleteProgram(effectProgram);
        GLES31.glDeleteProgram(copyProgram);
        if (texture != 0) { id[0] = texture; GLES31.glDeleteTextures(1, id, 0); texture = 0; }
        if (framebuffer != 0) { id[0] = framebuffer; GLES31.glDeleteFramebuffers(1, id, 0); framebuffer = 0; }
    }
}
