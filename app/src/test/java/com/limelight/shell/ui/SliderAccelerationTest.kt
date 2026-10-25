package com.limelight.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SliderAccelerationTest {
    private val bitrate = RowValue.Slider(20000, 1000, 1000000, 1000, " Mbps", 1000) {}
    private val blur = RowValue.Slider(10, 0, 40, 5, " px") {}

    @Test fun holdingAKeySpeedsUpLongSliders() {
        assertEquals(1, sliderAcceleration(bitrate, 0))
        assertEquals(5, sliderAcceleration(bitrate, 10))
        assertEquals(10, sliderAcceleration(bitrate, 25))
        assertEquals(24, sliderAcceleration(bitrate, 60))
    }

    @Test fun shortSlidersKeepSingleSteps() {
        assertEquals(1, sliderAcceleration(blur, 60))
    }
}
