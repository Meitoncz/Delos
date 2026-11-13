package com.limelight.diag

/**
 * The stream settings a report lists, as label to value. A fixed list of keys, never the whole preference file, so
 * a PC name, an API key or a layout can not slip in. [values] are the profile's stored values and [defaults] the
 * catalogue's defaults for keys the profile never wrote.
 */
object StreamSettingsSnapshot {
    /** The keys in report order; also the complete list of what a report may say about the settings. */
    val KEYS = listOf(
        "video_format", "list_resolution", "list_fps", "seekbar_bitrate_kbps", "list_pyrowave_chroma", "checkbox_enable_hdr",
        "frame_pacing", "list_vrr", "checkbox_sgsr", "list_sgsr_quality", "seekbar_sgsr_sharpness", "list_sgsr_supersampling_level",
    )

    fun read(values: Map<String, *>, defaults: Map<String, Any?>): List<Pair<String, String>> {
        fun raw(key: String): Any? = values[key] ?: defaults[key]
        fun text(key: String) = raw(key)?.toString()?.takeIf { it.isNotBlank() } ?: "default"
        fun flag(key: String) = if (raw(key) == true) "on" else "off"
        val upscaler = if (raw("checkbox_sgsr") == true) {
            "on, mode ${text("list_sgsr_quality")}, sharpness ${text("seekbar_sgsr_sharpness")}, supersampling ${text("list_sgsr_supersampling_level")}"
        } else "off"
        return listOf(
            "Codec" to text("video_format"),
            "Resolution" to text("list_resolution"),
            "Frame rate" to text("list_fps"),
            "Bitrate" to (raw("seekbar_bitrate_kbps")?.let { "$it kbps" } ?: "default"),
            "PyroWave chroma" to text("list_pyrowave_chroma"),
            "HDR" to flag("checkbox_enable_hdr"),
            "Frame pacing" to text("frame_pacing"),
            "Variable refresh rate" to text("list_vrr"),
            "Upscaler" to upscaler,
        )
    }
}
