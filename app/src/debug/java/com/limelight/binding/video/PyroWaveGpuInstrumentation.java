package com.limelight.binding.video;

import android.app.Instrumentation;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;

/**
 * Real-device, offscreen Vulkan shader regressions; never launches a stream or an Activity.
 * Invoke with adb shell am instrument -w
 * <applicationId>/com.limelight.binding.video.PyroWaveGpuInstrumentation.
 * This class and its native entry point are included only in debug builds.
 */
public final class PyroWaveGpuInstrumentation extends Instrumentation {
    private static native String nativeRunProbe();
    private static native String nativeRunRendererProbe(Surface surface);

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        StringBuilder report = new StringBuilder();
        try {
            System.loadLibrary("moonlight-core");
            String shaderReport = nativeRunProbe();
            boolean passed = shaderReport != null && shaderReport.startsWith("PASS\n");
            report.append(shaderReport != null ? shaderReport : "FAIL: native shader probe returned no report\n");
            SurfaceTexture texture = new SurfaceTexture(false);
            try {
                texture.setDefaultBufferSize(128, 96);
                Surface surface = new Surface(texture);
                try {
                    String rendererReport = nativeRunRendererProbe(surface);
                    passed &= rendererReport != null && rendererReport.startsWith("PASS\n");
                    report.append(rendererReport != null ? rendererReport : "FAIL: native renderer probe returned no report\n");
                } finally {
                    surface.release();
                }
            } finally {
                texture.release();
            }
            result.putString("stream", report.toString());
            result.putString("pyrowaveGpuResult", passed ? "PASS" : "FAIL");
            finish(passed ? -1 : 0, result);
        } catch (Throwable error) {
            result.putString("stream", report + "FAIL: " + Log.getStackTraceString(error));
            result.putString("pyrowaveGpuResult", "FAIL");
            finish(0, result);
        }
    }
}
