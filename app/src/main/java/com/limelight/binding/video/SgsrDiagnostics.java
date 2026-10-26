package com.limelight.binding.video;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES11Ext;
import android.opengl.GLES31;
import com.limelight.LimeLog;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Opt-in debug-only comparison of identical frames; never changes the stream's output buffers. */
final class SgsrDiagnostics {
    static void capture(Context app, int width, int height, int outputWidth, int outputHeight,
                        int external, int source, float[] transform, int copy, int standard, int high,
                        float sharpness) {
        File request = new File(app.getFilesDir(), "sgsr-capture-request");
        if (!request.exists() || !request.delete()) return;
        File dir = new File(app.getFilesDir(), "sgsr-diagnostics");
        int[] ids = new int[2];
        GLES31.glGenFramebuffers(1, ids, 0);
        GLES31.glGenTextures(1, ids, 1);
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Capture directory");
            for (int pass = 0; pass < 5; pass++) {
                int w = pass < 2 ? width : outputWidth, h = pass < 2 ? height : outputHeight;
                GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, ids[1]);
                GLES31.glTexImage2D(GLES31.GL_TEXTURE_2D, 0, GLES31.GL_RGBA8, w, h, 0,
                        GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, null);
                GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, ids[0]);
                GLES31.glFramebufferTexture2D(GLES31.GL_FRAMEBUFFER, GLES31.GL_COLOR_ATTACHMENT0,
                        GLES31.GL_TEXTURE_2D, ids[1], 0);
                if (GLES31.glCheckFramebufferStatus(GLES31.GL_FRAMEBUFFER) != GLES31.GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("Capture framebuffer");
                GLES31.glViewport(0, 0, w, h);
                GLES31.glActiveTexture(GLES31.GL_TEXTURE0);
                if (pass < 2) {
                    GLES31.glUseProgram(copy);
                    GLES31.glUniformMatrix4fv(GLES31.glGetUniformLocation(copy, "transform"), 1, false, transform, 0);
                    GLES31.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, external);
                    int filter = pass == 0 ? GLES31.GL_LINEAR : GLES31.GL_NEAREST;
                    GLES31.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_MIN_FILTER, filter);
                    GLES31.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_MAG_FILTER, filter);
                    GLES31.glUniform1i(GLES31.glGetUniformLocation(copy, "source"), 0);
                } else {
                    int program = pass == 4 ? high : standard;
                    GLES31.glUseProgram(program);
                    GLES31.glUniform4f(GLES31.glGetUniformLocation(program, "ViewportInfo[0]"), 1f/width, 1f/height, width, height);
                    GLES31.glUniform1f(GLES31.glGetUniformLocation(program, "sgsrStrength"), sharpness);
                    GLES31.glUniform1i(GLES31.glGetUniformLocation(program, "sgsrEnabled"), pass == 2 ? 0 : 1);
                    GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, source);
                    GLES31.glUniform1i(GLES31.glGetUniformLocation(program, "ps0"), 0);
                }
                GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP, 0, 4);
                ByteBuffer bytes = ByteBuffer.allocateDirect(w*h*4).order(ByteOrder.nativeOrder());
                GLES31.glReadPixels(0, 0, w, h, GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, bytes);
                if (GLES31.glGetError() != GLES31.GL_NO_ERROR) throw new IllegalStateException("Capture GL error");
                int[] pixels = new int[w*h];
                for (int y=0; y<h; y++) for (int x=0; x<w; x++) {
                    int offset=((h-1-y)*w+x)*4;
                    pixels[y*w+x]=0xff000000 | ((bytes.get(offset)&255)<<16) |
                            ((bytes.get(offset+1)&255)<<8) | (bytes.get(offset+2)&255);
                }
                Bitmap bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
                String[] names={"source-linear", "source-nearest", "bilinear", "standard", "high"};
                try (FileOutputStream file = new FileOutputStream(new File(dir, names[pass]+".png"))) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, file);
                } finally { bitmap.recycle(); }
            }
            try (FileOutputStream file = new FileOutputStream(new File(dir, "geometry.txt"))) {
                String info=width+"x"+height+" -> "+outputWidth+"x"+outputHeight+"; sharpness="+sharpness+
                        "\nSurfaceTexture="+Arrays.toString(transform)+"\nDiagnostic PNGs are encoded-RGB, 8-bit inspection copies, not HDR screenshots.\n";
                file.write(info.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            LimeLog.info("SGSR diagnostic frame captured");
        } catch (Exception e) { LimeLog.warning("SGSR diagnostic capture failed: "+e); }
        finally {
            GLES31.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, external);
            GLES31.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_LINEAR);
            GLES31.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_LINEAR);
            GLES31.glDeleteFramebuffers(1, ids, 0);
            GLES31.glDeleteTextures(1, ids, 1);
            GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0);
        }
    }
}
