package com.limelight.shell.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsImpactTest {
    private fun values(vararg pairs: Pair<String, Any>): Map<String, Any> = mapOf(
        "list_resolution" to "1920x1080", "list_fps" to "120", "seekbar_bitrate_kbps" to 60000, "video_format" to "forceh265",
        "checkbox_enable_hdr" to false, "list_pyrowave_chroma" to "420", "frame_pacing" to "balanced",
        "checkbox_sgsr" to true, "list_sgsr_quality" to "temporal", "seekbar_sgsr_sharpness" to 50, "list_sgsr_supersampling_level" to "1",
    ) + pairs

    private fun at(key: String, vararg pairs: Pair<String, Any>) = impact(key, values(*pairs))!!
    private fun SettingImpact.triple() = Triple(gpu, latency, network)
    private val N = ImpactLevel.NONE
    private val L = ImpactLevel.LOW
    private val M = ImpactLevel.MEDIUM
    private val H = ImpactLevel.HIGH

    @Test fun unknownAndCostlessKeysHaveNoChips() {
        for (key in listOf("checkbox_async_decode", "checkbox_ultra_low_latency", "checkbox_use_virtual_display", "checkbox_unlock_fps", "edit_diy_w_h", "nope"))
            assertNull(key, impact(key, values()))
    }

    @Test fun sgsr2CostFollowsTheFramePeriod() {
        // 6.85 ms of 8.33 ms = 82 %: High, still in budget so the latency is Medium
        assertEquals(Triple(H, M, N), at("list_sgsr_quality").triple())
        // at 60 FPS the same pass is 41 %: Medium
        assertEquals(Triple(M, M, N), at("list_sgsr_quality", "list_fps" to "60").triple())
        // 4K at 120 FPS: 10.23 ms is longer than the 8.33 ms period: over budget, frames queue
        assertEquals(Triple(H, H, N), at("list_sgsr_supersampling_level", "list_sgsr_supersampling_level" to "2").triple())
        // 4K at 90 FPS (11.1 ms) is 92 %: High, in budget
        assertEquals(Triple(H, M, N), at("list_sgsr_supersampling_level", "list_sgsr_supersampling_level" to "2", "list_fps" to "90").triple())
        // 4K at 60 FPS: 61 %
        assertEquals(Triple(M, M, N), at("list_sgsr_supersampling_level", "list_sgsr_supersampling_level" to "2", "list_fps" to "60").triple())
    }

    @Test fun qualityVariantIsOneLevelAboveNormalWithoutANumber() {
        // Normal is Medium at 60 FPS, Quality one above it; Normal at 120 FPS is already High
        assertEquals(Triple(M, M, N), at("list_sgsr_quality", "list_fps" to "60").triple())
        assertEquals(Triple(H, M, N), at("list_sgsr_quality", "list_sgsr_quality" to "temporal-quality", "list_fps" to "60").triple())
        assertEquals(Triple(H, M, N), at("list_sgsr_quality", "list_sgsr_quality" to "temporal-quality").triple())
    }

    @Test fun spatialModesAreQualitativeAndSharpenOnlyIsCheap() {
        assertEquals(Triple(L, L, N), at("list_sgsr_quality", "list_sgsr_quality" to "standard").triple())
        assertEquals(Triple(M, L, N), at("list_sgsr_quality", "list_sgsr_quality" to "high").triple())
        assertEquals(Triple(L, L, N), at("list_sgsr_quality", "list_sgsr_quality" to "sharpen").triple())
        assertEquals(Triple(L, L, N), at("list_sgsr_quality", "list_sgsr_quality" to "sharpen", "list_fps" to "120").triple())
        // SGSR 1 at 2x renders 2160p; the level of the temporal mode is a measured time instead
        assertEquals(Triple(L, L, N), at("list_sgsr_supersampling_level", "list_sgsr_quality" to "standard").triple())
        assertEquals(Triple(M, L, N), at("list_sgsr_supersampling_level", "list_sgsr_quality" to "standard", "list_sgsr_supersampling_level" to "2").triple())
    }

    @Test fun supersamplingIsUnavailableWithSharpenOnly() {
        assertNull(impact("list_sgsr_supersampling_level", values("list_sgsr_quality" to "sharpen")))
    }

    @Test fun sharpnessIsAboutTheSharpenPassAndZeroDisablesIt() {
        assertEquals(Triple(L, L, N), at("seekbar_sgsr_sharpness").triple())
        assertEquals(Triple(N, N, N), at("seekbar_sgsr_sharpness", "seekbar_sgsr_sharpness" to 0).triple())
    }

    @Test fun turningTheUpscalerOffDimsItsRows() {
        val off = values("checkbox_sgsr" to false)
        for (key in listOf("checkbox_sgsr", "list_sgsr_quality", "seekbar_sgsr_sharpness", "list_sgsr_supersampling_level"))
            assertTrue(key, impact(key, off)!!.none)
        assertEquals(Triple(H, M, N), impact("checkbox_sgsr", values())!!.triple())
    }

    @Test fun frameRateDependsOnTheUpscalerCodecAndRefresh() {
        // the SGSR2-DV pass share of the frame and the frame interval itself
        assertEquals(Triple(H, L, M), at("list_fps").triple())
        assertEquals(Triple(M, M, L), at("list_fps", "list_fps" to "60").triple())
        assertEquals(Triple(L, M, L), at("list_fps", "list_fps" to "60", "checkbox_sgsr" to false).triple())
        // PyroWave: the wavelet decode of every frame and the huge bitrate
        assertEquals(Triple(M, M, H), at("list_fps", "list_fps" to "60", "checkbox_sgsr" to false, "video_format" to "pyrowave").triple())
        assertEquals(Triple(H, L, H), at("list_fps", "checkbox_sgsr" to false, "video_format" to "pyrowave").triple())
    }

    @Test fun resolutionDependsOnPixelsAndTheRecommendedBitrate() {
        assertEquals(Triple(L, L, L), at("list_resolution", "list_fps" to "60", "list_resolution" to "1920x1080").triple())   // 40 Mbps
        assertEquals(Triple(L, L, M), at("list_resolution", "list_fps" to "60", "list_resolution" to "2560x1440").triple())   // 80 Mbps
        assertEquals(Triple(M, M, H), at("list_resolution", "list_fps" to "60", "list_resolution" to "3840x2160").triple())   // 150 Mbps cap
        assertEquals(Triple(L, L, L), at("list_resolution", "list_fps" to "60", "list_resolution" to "1280x720").triple())
        // off 60 FPS the repo has no table: a qualitative Medium for anything above 720p
        assertEquals(Triple(L, L, M), at("list_resolution").triple())
        // PyroWave 1080p60 recommends 225 Mbps, 720p60 140
        assertEquals(Triple(L, L, H), at("list_resolution", "video_format" to "pyrowave", "list_fps" to "60").triple())
        assertEquals(Triple(L, L, M), at("list_resolution", "video_format" to "pyrowave", "list_fps" to "60", "list_resolution" to "1280x720").triple())
    }

    @Test fun bitrateIsTheBitrateItself() {
        assertEquals(Triple(N, L, L), at("seekbar_bitrate_kbps", "seekbar_bitrate_kbps" to 20000).triple())
        assertEquals(Triple(N, L, M), at("seekbar_bitrate_kbps", "seekbar_bitrate_kbps" to 100000).triple())
        assertEquals(Triple(N, M, H), at("seekbar_bitrate_kbps", "seekbar_bitrate_kbps" to 200000).triple())
    }

    @Test fun codecLevels() {
        assertEquals(Triple(L, L, L), at("video_format", "video_format" to "auto").triple())
        assertEquals(Triple(L, L, M), at("video_format", "video_format" to "neverh265").triple())
        assertEquals(Triple(L, L, L), at("video_format", "video_format" to "forceav1").triple())
        assertEquals(Triple(H, L, H), at("video_format", "video_format" to "pyrowave").triple())
    }

    @Test fun hdrOnlyCostsWhenOn() {
        assertTrue(at("checkbox_enable_hdr").none)
        assertEquals(Triple(L, N, L), at("checkbox_enable_hdr", "checkbox_enable_hdr" to true).triple())
        assertEquals(Triple(L, N, H), at("checkbox_enable_hdr", "checkbox_enable_hdr" to true, "video_format" to "pyrowave").triple())
    }

    @Test fun pyroWaveChromaOnlyWithPyroWave() {
        assertNull(impact("list_pyrowave_chroma", values()))
        val pyro = values("video_format" to "pyrowave", "list_fps" to "60")
        assertEquals(Triple(M, L, H), impact("list_pyrowave_chroma", pyro)!!.triple())
        assertEquals(Triple(H, L, H), impact("list_pyrowave_chroma", pyro + ("list_pyrowave_chroma" to "444"))!!.triple())
        // HDR uses 4:2:0 whatever is saved
        assertEquals(Triple(M, L, H), impact("list_pyrowave_chroma", pyro + ("list_pyrowave_chroma" to "444") + ("checkbox_enable_hdr" to true))!!.triple())
    }

    @Test fun pyroWaveBandwidthUsesTheRealRecommendation() {
        // 1080p60 4:2:0 225 Mbps, 4:4:4 270, HDR 265: all High (150 Mbps and up); 720p60 is 140 Mbps: Medium
        val base = values("video_format" to "pyrowave", "list_fps" to "60")
        for (extra in listOf(emptyMap<String, Any>(), mapOf("list_pyrowave_chroma" to "444"), mapOf("checkbox_enable_hdr" to true)))
            assertEquals(H, impact("list_resolution", base + extra)!!.network)
        assertEquals(M, impact("list_resolution", base + ("list_resolution" to "1280x720"))!!.network)
    }

    @Test fun pacingFollowsTheHelpTexts() {
        assertEquals(Triple(L, L, N), at("frame_pacing", "frame_pacing" to "latency").triple())
        assertEquals(Triple(L, M, N), at("frame_pacing", "frame_pacing" to "balanced").triple())
        assertEquals(Triple(L, M, N), at("frame_pacing", "frame_pacing" to "cap-fps").triple())
        assertEquals(Triple(L, H, N), at("frame_pacing", "frame_pacing" to "smoothness").triple())
        assertEquals(Triple(M, L, M), at("frame_pacing", "frame_pacing" to "warp").triple())
        assertEquals(Triple(H, L, H), at("frame_pacing", "frame_pacing" to "warp2").triple())
        assertEquals(Triple(L, L, N), at("frame_pacing", "frame_pacing" to "fast-vsync").triple())
    }
}
