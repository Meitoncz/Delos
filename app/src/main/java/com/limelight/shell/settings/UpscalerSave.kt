package com.limelight.shell.settings

import com.limelight.preferences.UpscalerMode

/**
 * The upscaler's settings. They follow the upscaler's availability (OpenGL ES 3.1 for MediaCodec codecs,
 * or PyroWave's own Vulkan decoder), and the stream menu keeps exactly these for the running game as they are changed (MenuAutoSave).
 */
val UPSCALER_KEYS = listOf("checkbox_sgsr", "list_sgsr_quality", "seekbar_sgsr_sharpness", "list_sgsr_supersampling_level")

/**
 * The per-game overrides that save the live upscaler state: [enabled], the [mode] the stream menu shows
 * (its position in [UpscalerMode]: 0 = SGSR 1 (Normal), 1 = High Quality, 2 = SGSR2-DV, 3 = Video Quality,
 * 4 = Sharpen only), the [sharpness] in percent and the supersampling [level]. Values outside the supported
 * ranges are clamped, so what is stored is always something a later launch reads back unchanged.
 */
fun upscalerSaveValues(enabled: Boolean, mode: Int, sharpness: Int, level: Int): Map<String, Any> = mapOf(
    "checkbox_sgsr" to enabled,
    "list_sgsr_quality" to UpscalerMode.fromIndex(mode).value(),
    "seekbar_sgsr_sharpness" to sharpness.coerceIn(0, 100),
    "list_sgsr_supersampling_level" to if (level >= 2) "2" else "1",
)
