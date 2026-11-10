package com.limelight.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StreamSettingsSnapshotTest {
    private val defaults = mapOf("video_format" to "auto", "list_resolution" to "1280x720", "list_fps" to "60", "checkbox_enable_hdr" to false,
        "frame_pacing" to "balanced", "checkbox_sgsr" to false, "list_sgsr_quality" to "standard", "seekbar_sgsr_sharpness" to 20,
        "list_sgsr_supersampling_level" to "1", "list_pyrowave_chroma" to "420")

    private fun read(values: Map<String, Any?>) = StreamSettingsSnapshot.read(values, defaults).toMap()

    @Test fun storedValuesWinOverTheDefaults() {
        val snapshot = read(mapOf("video_format" to "hevc", "list_resolution" to "1920x1080", "seekbar_bitrate_kbps" to 80000, "checkbox_enable_hdr" to true))
        assertEquals("hevc", snapshot["Codec"])
        assertEquals("1920x1080", snapshot["Resolution"])
        assertEquals("60", snapshot["Frame rate"])
        assertEquals("80000 kbps", snapshot["Bitrate"])
        assertEquals("on", snapshot["HDR"])
    }

    @Test fun theUpscalerIsDescribedByItsModeOnlyWhenOn() {
        assertEquals("off", read(emptyMap())["Upscaler"])
        assertEquals("on, mode temporal, sharpness 20, supersampling 1",
            read(mapOf("checkbox_sgsr" to true, "list_sgsr_quality" to "temporal"))["Upscaler"])
    }

    @Test fun onlyTheListedKeysCanAppearInAReport() {
        val snapshot = StreamSettingsSnapshot.read(mapOf("sgdb_api_key" to "secret", "ui_pc_name_x" to "Meiton-PC", "video_format" to "av1"), defaults)
        assertFalse(snapshot.toString().contains("secret"))
        assertFalse(snapshot.toString().contains("Meiton"))
        assertEquals(8, snapshot.size)
    }
}
