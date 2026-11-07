package com.limelight.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedPlaybackTest {
    @Test fun playsOnlyWhereItCanBeSeenAndMotionIsAllowed() {
        assertTrue(AnimatedPlayback.shouldPlay(visible = true, foreground = true, streaming = false, reducedMotion = false, animatorsOff = false))
    }

    @Test fun everyReasonToStopStopsIt() {
        fun play(visible: Boolean = true, foreground: Boolean = true, streaming: Boolean = false, reduced: Boolean = false, off: Boolean = false) =
            AnimatedPlayback.shouldPlay(visible, foreground, streaming, reduced, off)
        assertFalse(play(visible = false))
        assertFalse(play(foreground = false))
        assertFalse(play(streaming = true))
        assertFalse(play(reduced = true))
        assertFalse(play(off = true))
    }

    @Test fun framesAreDecodedAtTileSizeNeverEnlarged() {
        assertEquals(520 to 300, AnimatedPlayback.decodeSize(1040, 600, 520))
        assertEquals(342 to 482, AnimatedPlayback.decodeSize(342, 482, 520))
        assertEquals(1050 to 339, AnimatedPlayback.decodeSize(3840, 1240, 1050))
        assertEquals(1 to 1, AnimatedPlayback.decodeSize(0, 0, 520))
    }
}
