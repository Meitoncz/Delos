package com.limelight.shell.ui

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListScrollMotionTest {
    @Test
    fun settleTimeGrowsWithDistanceBetweenNearAndFar() {
        assertEquals(ListScrollMotion.SETTLE_NEAR_MS, ListScrollMotion.settleMillis(0f), 0f)
        assertEquals(ListScrollMotion.SETTLE_NEAR_MS, ListScrollMotion.settleMillis(ListScrollMotion.NEAR_PX), 0f)
        assertEquals(ListScrollMotion.SETTLE_FAR_MS, ListScrollMotion.settleMillis(ListScrollMotion.FAR_PX), 0f)
        assertEquals(ListScrollMotion.SETTLE_FAR_MS, ListScrollMotion.settleMillis(5000f), 0f)
        var last = 0f
        for (d in 0..800 step 20) {
            val t = ListScrollMotion.settleMillis(d.toFloat())
            assertTrue(t >= last)
            last = t
        }
    }

    @Test
    fun springSettlesInTheSettleTimeAndNeverOvershootsFromRest() {
        for (d in listOf(60f, 200f, 700f)) {
            val w = ListScrollMotion.omega(d, 0f)
            val seconds = ListScrollMotion.settleMillis(d) / 1000f
            assertEquals(.02f * d, abs(ListScrollMotion.offset(d, 0f, w, seconds)), .02f * d * .05f)
            var t = 0f
            while (t < 2f) {
                assertTrue(ListScrollMotion.offset(d, 0f, w, t) >= -1e-3f)
                t += .01f
            }
        }
    }

    @Test
    fun retargetKeepsVelocityAndBrakesWhenFastAndClose() {
        // moving toward the target at 3000 px/s with only 60 px left: the rate rises so it cannot overshoot
        val w = ListScrollMotion.omega(60f, 3000f)
        assertEquals(50f, w, 1e-3f)
        var t = 0f
        while (t < 2f) {
            assertTrue(ListScrollMotion.offset(60f, -3000f, w, t) >= -1e-3f)
            t += .005f
        }
        // the velocity at the start of a segment is the one handed in
        assertEquals(-3000f, ListScrollMotion.velocity(60f, -3000f, w, 0f), 1e-2f)
        // the brake is capped, and moving away or resting uses the distance rate
        assertEquals(ListScrollMotion.MAX_OMEGA, ListScrollMotion.omega(10f, 5000f), 0f)
        assertEquals(ListScrollMotion.omega(60f, 0f), ListScrollMotion.omega(60f, -400f), 0f)
    }

    @Test
    fun durationIsFiniteAndCoversTheSettle() {
        val w = ListScrollMotion.omega(300f, 0f)
        val d = ListScrollMotion.durationSeconds(300f, 0f, w, .5f)
        assertTrue(d > ListScrollMotion.settleMillis(300f) / 1000f)
        assertTrue(abs(ListScrollMotion.offset(300f, 0f, w, d)) <= .5f)
        assertEquals(0f, ListScrollMotion.durationSeconds(.1f, 0f, w, .5f), 0f)
    }
}
