package com.limelight.binding.video;

/**
 * Names the Vulkan swapchain settings the PyroWave renderer reports in its statistics (present mode, image count,
 * pre-transform) for the DelosPyroDiag line. Values are the raw Vulkan enums; -1 means no swapchain exists yet.
 */
final class PyroWavePresentInfo {
    private PyroWavePresentInfo() { }

    static String describe(long[] stats) {
        return describe((int) stats[PyroWaveRenderer.STAT_PRESENT_MODE], (int) stats[PyroWaveRenderer.STAT_SWAPCHAIN_IMAGES],
                (int) stats[PyroWaveRenderer.STAT_PRE_TRANSFORM]);
    }

    static String describe(int presentMode, int images, int preTransform) {
        if (presentMode < 0 || images < 0) return "present=none";
        return "present=" + presentModeName(presentMode) + " images=" + images + " preTransform=" + transformName(preTransform);
    }

    /** VkPresentModeKHR. */
    static String presentModeName(int mode) {
        switch (mode) {
            case 0: return "IMMEDIATE";
            case 1: return "MAILBOX";
            case 2: return "FIFO";
            case 3: return "FIFO_RELAXED";
            default: return "mode" + mode;
        }
    }

    /** VkSurfaceTransformFlagBitsKHR. */
    static String transformName(int transform) {
        switch (transform) {
            case 0x1: return "identity";
            case 0x2: return "rotate90";
            case 0x4: return "rotate180";
            case 0x8: return "rotate270";
            case 0x10: return "mirror";
            case 0x100: return "inherit";
            default: return "other(" + transform + ")";
        }
    }
}
