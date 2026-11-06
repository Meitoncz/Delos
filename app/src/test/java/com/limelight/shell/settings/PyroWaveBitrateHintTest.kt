package com.limelight.shell.settings

import com.limelight.preferences.PyroWaveBitrate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PyroWaveBitrateHintTest {
    @Test fun recommendationTracksQualityRatherThanTheChosenBitrate() {
        val values = mutableMapOf<String, Any>(
            "video_format" to "pyrowave",
            "list_resolution" to "1920x1080",
            "list_fps" to "60",
            "checkbox_enable_hdr" to false,
            "seekbar_bitrate_kbps" to 528000,
        )
        assertEquals(225000, pyroWaveRecommendedBitrateKbps(values))
        values["list_fps"] = "120"
        assertEquals(445000, pyroWaveRecommendedBitrateKbps(values))
        values["checkbox_enable_hdr"] = true
        assertEquals(530000, pyroWaveRecommendedBitrateKbps(values))
        values["list_resolution"] = "2560x1440"
        assertEquals(695000, pyroWaveRecommendedBitrateKbps(values))
    }

    @Test fun recommendationTracksChromaAndHdrUses420DespiteSaved444() {
        val values = mutableMapOf<String, Any>(
            "video_format" to "pyrowave",
            "list_resolution" to "1920x1080",
            "list_fps" to "60",
            "list_pyrowave_chroma" to "444",
            "seekbar_bitrate_kbps" to 528000,
        )
        assertEquals(PyroWaveBitrate.recommendedKbps(1920, 1080, 60, true, false),
            pyroWaveRecommendedBitrateKbps(values))
        values["list_pyrowave_chroma"] = "420"
        assertEquals(PyroWaveBitrate.recommendedKbps(1920, 1080, 60, false, false),
            pyroWaveRecommendedBitrateKbps(values))
        values["list_pyrowave_chroma"] = "444"
        values["checkbox_enable_hdr"] = true
        val hdr420 = PyroWaveBitrate.recommendedKbps(1920, 1080, 60, false, true)
        assertEquals(hdr420, pyroWaveRecommendedBitrateKbps(values))
        values["list_pyrowave_chroma"] = "420"
        assertEquals(hdr420, pyroWaveRecommendedBitrateKbps(values))
        values["list_pyrowave_chroma"] = "444"
        values["checkbox_enable_hdr"] = false
        assertEquals(PyroWaveBitrate.recommendedKbps(1920, 1080, 60, true, false),
            pyroWaveRecommendedBitrateKbps(values))
    }

    @Test fun otherCodecsHaveNoRecommendationHint() {
        for (codec in listOf("auto", "forceav1", "forceh265", "neverh265")) {
            assertNull(pyroWaveRecommendedBitrateKbps(mapOf("video_format" to codec)))
        }
        assertNull(pyroWaveRecommendedBitrateKbps(emptyMap<String, Any>()))
    }
}
