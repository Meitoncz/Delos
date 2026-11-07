package com.limelight.shell

import com.limelight.binding.video.FrameQueueStats
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameQueueTextTest {
    @Test fun theRowNamesWaitingFramesTheirAgeAndTheSkippedRate() {
        val queue = FrameQueueStats(120, 30, 1.4f, 3, 0.8f, 4.25f, 90, 1f, 0)
        assertEquals("1.4 avg / 3 max waiting · age 0.8 / 4.3 ms · 30.0 skipped /s", frameQueueText(queue))
    }

    @Test fun withoutAgeSamplesTheAgeIsLeftOut() {
        val queue = FrameQueueStats(60, 0, 1f, 1, -1f, -1f, 0, 1f, 0)
        assertEquals("1.0 avg / 1 max waiting · 0.0 skipped /s", frameQueueText(queue))
    }
}
