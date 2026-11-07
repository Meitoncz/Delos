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

    @Test fun theAnimatedHeroStartsOnlyAfterTheGameStayedFocused() {
        assertFalse(AnimatedPlayback.heroMayPlay(animate = true, dwelledMillis = 0))
        assertFalse(AnimatedPlayback.heroMayPlay(animate = true, dwelledMillis = AnimatedPlayback.HERO_DWELL_MS - 1))
        assertTrue(AnimatedPlayback.heroMayPlay(animate = true, dwelledMillis = AnimatedPlayback.HERO_DWELL_MS))
        // Dwelled long enough does not matter once playing is not allowed (the focus left, a launch began)
        assertFalse(AnimatedPlayback.heroMayPlay(animate = false, dwelledMillis = AnimatedPlayback.HERO_DWELL_MS))
    }

    @Test fun framesAreDecodedAtTileSizeNeverEnlarged() {
        assertEquals(520 to 300, AnimatedPlayback.decodeSize(1040, 600, 520))
        assertEquals(342 to 482, AnimatedPlayback.decodeSize(342, 482, 520))
        assertEquals(1050 to 339, AnimatedPlayback.decodeSize(3840, 1240, 1050))
        assertEquals(1 to 1, AnimatedPlayback.decodeSize(0, 0, 520))
    }

    @Test fun aCroppedViewIsDecodedJustBigEnoughToCoverIt() {
        // A 600 × 900 cover in a 330 × 495 tile: scaled by .55
        assertEquals(330 to 495, AnimatedPlayback.decodeSize(600, 900, 330, 495))
        // A square animation in a portrait tile: the height decides (330 × 495 needs 495 across), never above the original
        assertEquals(495 to 495, AnimatedPlayback.decodeSize(1000, 1000, 330, 495))
        assertEquals(400 to 400, AnimatedPlayback.decodeSize(400, 400, 330, 495))
        // A wide cover in the wide tile
        assertEquals(1052 to 492, AnimatedPlayback.decodeSize(2104, 984, 1052, 492))
    }

    @Test fun aFittedViewIsDecodedJustBigEnoughToFitIt() {
        // A 2000 × 500 logo in a 900 × 180 box: the height decides
        assertEquals(720 to 180, AnimatedPlayback.decodeSize(2000, 500, 900, 180, fit = true))
        // A small logo is never enlarged
        assertEquals(300 to 100, AnimatedPlayback.decodeSize(300, 100, 900, 180, fit = true))
    }
}
