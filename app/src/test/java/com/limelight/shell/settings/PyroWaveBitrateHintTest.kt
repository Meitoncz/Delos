package com.limelight.shell.settings

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

    @Test fun otherCodecsHaveNoRecommendationHint() {
        for (codec in listOf("auto", "forceav1", "forceh265", "neverh265")) {
            assertNull(pyroWaveRecommendedBitrateKbps(mapOf("video_format" to codec)))
        }
        assertNull(pyroWaveRecommendedBitrateKbps(emptyMap<String, Any>()))
    }
}
