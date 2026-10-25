package com.limelight.preferences;

/**
 * Delos: the bitrate PyroWave needs for good quality, as the official Vibepollo client suggests it
 * (Nonary/moonlight-qt app/streaming/video/pyrowave/pyrowavebitrate.h). It uses the PyroWave author's
 * objective regression (pyrowave/eval-results/pyrowave_regression_results.h) at 35 dB PSNR-HVS-M-H,
 * viewed from twice the screen height, which he found matches "good quality"; HDR10 gets 1.2x.
 * PyroWave is intra-only, so it needs several times the bitrate of HEVC: about 220 Mbps for 1080p60.
 */
public final class PyroWaveBitrate {
    // The regression's 35 dB polynomials for a viewing distance of 2.00 screen heights,
    // for 4:2:0 and 4:4:4 chroma
    private static final double[] COEFFICIENTS_420 = {
            611.9945665176643, 87.22375978784984, -374.2744854882103, 138.525763156332,
            404.3879014230974, -64.04327593791751, -194.8035057145124, -12.904253458542499,
    };
    private static final double[] COEFFICIENTS_444 = {
            719.659360640365, 51.9223145866537, -522.3229212980714, 104.10383129754959,
            605.6153091742109, 87.26139899802612, -306.13390524230874, -110.39755862218644,
    };

    // The pixel counts the regression covers; resolutions outside use the nearest end
    private static final double MIN_PIXELS = 1280 * 720;
    private static final double MAX_PIXELS = 3840 * 2160;

    private PyroWaveBitrate() {
    }

    /** The recommended bitrate in kbps, rounded up to the slider's 5 Mbps step. */
    public static int recommendedKbps(int width, int height, int fps, boolean chroma444, boolean hdr) {
        double pixels = Math.min(Math.max((double) width * height, MIN_PIXELS), MAX_PIXELS);
        double[] coefficients = chroma444 ? COEFFICIENTS_444 : COEFFICIENTS_420;

        // The author's non-linear warp of the pixel count, then the polynomial (bytes per frame / 1000)
        double x = Math.sqrt(pixels * 1e-6) - 2.0;
        double power = 1.0;
        double estimate = 0.0;
        for (double coefficient : coefficients) {
            estimate += power * coefficient;
            power *= x;
        }
        double mbits = estimate * 8e-3 * Math.max(fps, 1) * (hdr ? 1.2 : 1.0);
        return (int) Math.ceil(mbits * 1000.0 / 5000.0) * 5000;
    }
}
