package com.limelight.shell.settings

/**
 * The upscaler's settings. They follow the upscaler's availability (OpenGL ES 3.1 for MediaCodec codecs,
 * or PyroWave's own Vulkan decoder), and the stream menu's Save row writes exactly these for a game.
 */
val UPSCALER_KEYS = listOf("checkbox_sgsr", "list_sgsr_quality", "seekbar_sgsr_sharpness", "list_sgsr_supersampling_level")

private val UPSCALER_MODES = listOf("standard", "high", "temporal", "temporal-quality")

/**
 * The per-game overrides that save the live upscaler state: [enabled], the [mode] the stream menu shows
 * (0 = SGSR 1 Standard, 1 = High Quality, 2 = SGSR 2 Video, 3 = Video Quality), the [sharpness] in percent
 * and the supersampling [level]. Values outside the supported ranges are clamped, so what is stored is
 * always something a later launch reads back unchanged.
 */
fun upscalerSaveValues(enabled: Boolean, mode: Int, sharpness: Int, level: Int): Map<String, Any> = mapOf(
    "checkbox_sgsr" to enabled,
    "list_sgsr_quality" to UPSCALER_MODES[mode.coerceIn(0, UPSCALER_MODES.lastIndex)],
    "seekbar_sgsr_sharpness" to sharpness.coerceIn(0, 100),
    "list_sgsr_supersampling_level" to if (level >= 2) "2" else "1",
)
