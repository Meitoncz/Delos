package com.limelight.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroLoopTimelineTest {
    private fun of(loopMillis: Long) = checkNotNull(HeroLoopTimeline.of(loopMillis))

    @Test fun aShortOrUnknownLoopKeepsThePlatformsPlainLoop() {
        assertNull(HeroLoopTimeline.of(0))
        assertNull(HeroLoopTimeline.of(1_000))
        assertNull(HeroLoopTimeline.of(HeroLoopTimeline.MIN_LOOP_MS - 1))
        assertNotNull(HeroLoopTimeline.of(HeroLoopTimeline.MIN_LOOP_MS))
    }

    @Test fun theFadeIsAboutSevenHundredMillisecondsAndNeverMoreThanAThirdOfTheLoop() {
        assertEquals(500L, of(1_500).fadeMillis)
        assertEquals(600L, of(1_800).fadeMillis)
        assertEquals(700L, of(2_100).fadeMillis)
        assertEquals(700L, of(3_500).fadeMillis)
        assertEquals(700L, of(60_000).fadeMillis)
        for (loop in 1_500L..20_000L step 37) assertTrue(of(loop).fadeMillis * 3 <= loop)
    }

    @Test fun eachPassStartsOneLoopLessTheFadeAfterTheOneBefore() {
        val t = of(3_500)
        assertEquals(2_800L, t.periodMillis)
        // The last fade's worth of a pass is the next pass's first
        assertEquals(t.loopMillis, t.periodMillis + t.fadeMillis)
    }

    @Test fun theNextPassIsDecodedAfterTheFadeOfTheCurrentOneAndBeforeItStarts() {
        for (loop in 1_500L..20_000L step 53) {
            val t = of(loop)
            assertTrue("loop $loop", t.decodeAtMillis >= t.fadeMillis)
            assertTrue(t.decodeAtMillis < t.periodMillis)
        }
        assertEquals(2_300L, of(3_500).decodeAtMillis)
    }

    @Test fun theIncomingPassFadesFromNothingToFullAndStaysThere() {
        val t = of(3_500)
        assertEquals(0f, t.progress(0f), 0f)
        assertEquals(.5f, t.progress(350f), 1e-6f)
        assertEquals(1f, t.progress(700f), 0f)
        assertEquals(1f, t.progress(2_000f), 0f)
        // A frame clock a hair earlier than the start never gives a negative opacity
        assertEquals(0f, t.progress(-3f), 0f)
    }

    @Test fun theEasedFadeIsMonotonicAndEndsAtFull() {
        val t = of(3_500)
        var last = 0f
        for (ms in 0..700 step 10) {
            val eased = Motion.Ease.transform(t.progress(ms.toFloat()))
            assertTrue(eased >= last)
            last = eased
        }
        assertEquals(1f, last, 1e-6f)
    }
}
