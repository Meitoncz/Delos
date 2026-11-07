package com.limelight.shell

import com.limelight.binding.video.FrameQueueStats
import com.limelight.binding.video.StageSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameQueueTextTest {
    @Test fun theRowNamesWaitingFramesTheirAgeAndTheSkippedRate() {
        val queue = FrameQueueStats(120, 30, 0, 1.4f, 3, 0.8f, 4.25f, -1f, -1f, 90, 0, 120, 1f, 0)
        assertEquals("1.4 avg / 3 max waiting · age 0.8 / 4.3 ms · 30.0 skipped /s", frameQueueText(queue))
    }

    @Test fun withoutAgeSamplesTheAgeIsLeftOut() {
        val queue = FrameQueueStats(60, 0, 0, 1f, 1, -1f, -1f, -1f, -1f, 0, 0, 60, 1f, 0)
        assertEquals("1.0 avg / 1 max waiting · 0.0 skipped /s", frameQueueText(queue))
    }

    @Test fun theRenderLatencyRowShowsAverageP95AndMaximum() {
        assertEquals("6.1 / 7.9 / 9.3 ms avg/p95/max", renderLatencyText(StageSummary(120, 6.14f, 7.88f, 9.3f)))
    }

    @Test fun frameDroppedForABusyGpuAreCountedSeparately() {
        val queue = FrameQueueStats(90, 0, 30, 1f, 1, 1f, 9f, 10f, 12f, 0, 30, 120, 1f, 0)
        assertEquals("1.0 avg / 1 max waiting · age 1.0 / 9.0 ms · 0.0 skipped /s · 30.0 GPU busy /s", frameQueueText(queue))
    }
}
