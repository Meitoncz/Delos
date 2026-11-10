package com.limelight.shell

import com.limelight.binding.video.SharpenCost
import com.limelight.preferences.UpscalerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.content.Context
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SharpenOnlyMenuTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private val sharpenOnly = UpscalerMode.SHARPEN_ONLY.index()

    @Test fun theModeRowCyclesThroughEveryModeInBothDirectionsAndWraps() {
        val last = UpscalerMode.values().lastIndex
        assertEquals(sharpenOnly, last)
        assertEquals(sharpenOnly, nextUpscalerMode(UpscalerMode.VIDEO_QUALITY.index(), 1))
        assertEquals(0, nextUpscalerMode(last, 1))
        assertEquals(last, nextUpscalerMode(0, -1))
        assertEquals(UpscalerMode.VIDEO_QUALITY.index(), nextUpscalerMode(last, -1))
        val visited = generateSequence(0) { nextUpscalerMode(it, 1) }.take(UpscalerMode.values().size).toSet()
        assertEquals(UpscalerMode.values().indices.toSet(), visited)
    }

    @Test fun supersamplingQualityIsOfferedOnlyWhileTheModeScalesAndTheStreamCanBeSupersampled() {
        for (mode in UpscalerMode.values()) {
            assertEquals(mode.name, mode != UpscalerMode.SHARPEN_ONLY, showsSupersamplingQuality(true, mode.index()))
            assertFalse(mode.name, showsSupersamplingQuality(false, mode.index()))
        }
        // A saved mode outside the list is clamped like everywhere else
        assertFalse(showsSupersamplingQuality(true, 99))
    }

    @Test fun theModeRowHelpNamesTheSelectedModeAndSharpenOnlyHasItsOwn() {
        val help = streamMenuHelp(res, "Mode", "On: Sharpen only", sharpenOnly, 0, false)
        assertTrue(help.contains("No scaling"))
        assertTrue(help.contains("Applies live."))
        assertFalse(streamMenuHelp(res, "Mode", "On", 0, 0, false).contains("No scaling"))
    }

    @Test fun theSharpenPassRowShowsAverageWorstAndThePreparation() {
        assertEquals("0.31 ms avg / 0.62 max · copy 0.18 ms",
            sharpenPassText(SharpenCost(true, 0.312f, 0.62f, 0.18f, 0.4f, "copy", 119)))
        assertEquals("0.50 ms avg / 0.90 max · conversion 0.25 ms",
            sharpenPassText(SharpenCost(true, 0.5f, 0.9f, 0.25f, 0.4f, "conversion", 60)))
    }

    @Test fun withoutAGpuTimerOrAPreparationTheRowSaysSo() {
        assertEquals("0.05 ms avg / 0.20 max (CPU submit)",
            sharpenPassText(SharpenCost(false, 0.05f, 0.2f, -1f, -1f, "copy", 100)))
        assertEquals("0.05 ms avg / 0.20 max · copy 0.03 ms (CPU submit)",
            sharpenPassText(SharpenCost(false, 0.05f, 0.2f, 0.03f, 0.1f, "copy", 100)))
    }
}
