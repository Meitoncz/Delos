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

    /** Steps a [ListScrollDriver] against a fake scrollable at 60 fps; returns the position: index n is the position after n - 1 steps of 1/60 s (index 0 and 1 are the start). */
    private fun simulate(driver: ListScrollDriver, frames: Int = 90, onFrame: (Int) -> Unit = {}): List<Float> {
        var position = 0f
        var time = 0L
        var frame = 0
        val positions = mutableListOf(0f)
        // The frame clock is the only suspension point; it never really suspends here.
        kotlinx.coroutines.runBlocking {
            driver.run({ position += it; it }, {
                if (frame > 0) positions += position
                if (frame >= frames) driver.stop()
                onFrame(frame)
                frame++
                time += 16_666_667L
                time
            })
        }
        return positions
    }

    @Test
    fun oneRowStepIsGradualAndLandsAfterTheSettleTime() {
        val driver = ListScrollDriver(1f)
        driver.retarget(90f)
        val p = simulate(driver)
        val at150 = p[10]
        val at300 = p[19]
        val at600 = p[37]
        println("90 px step: 150 ms ${at150}, 300 ms ${at300}, 600 ms ${at600}")
        assertTrue(at150 in 90f * .15f..90f * .85f)
        assertTrue(at300 < 90f * .9f)
        assertTrue(at600 > 90f * .975f)
        assertEquals(90f, p.last(), .6f)
        assertTrue(p.zipWithNext().all { (a, b) -> b >= a - 1e-3f })
    }

    @Test
    fun retargetWhileMovingKeepsTheVelocityAndNeverOvershoots() {
        val driver = ListScrollDriver(1f)
        driver.retarget(90f)
        var target = 90f
        val p = simulate(driver, frames = 400) { frame ->
            // a held D-pad: one more row every 8 frames while it is held
            if (frame in 1..40 && frame % 8 == 0) { driver.retarget(100f); target += 100f }
        }
        val steps = p.zipWithNext { a, b -> b - a }
        // no frame stops or reverses while the target keeps moving ahead (no restart, no stutter)
        assertTrue(steps.drop(1).take(40).all { it > 0f })
        assertTrue(p.max() <= target + .6f)
        assertEquals(target, p.last(), .6f)
    }

    @Test
    fun stopsWhenTheScrollableCannotMove() {
        val driver = ListScrollDriver(1f)
        driver.retarget(90f)
        kotlinx.coroutines.runBlocking {
            var time = 0L
            driver.run({ 0f }, { time += 16_666_667L; time })
        }
        assertEquals(0f, driver.remaining, 0f)
    }

    @Test
    fun marginDistanceScrollsOnlyWhenLeavingTheSafeArea() {
        // container 1000, margins 72 before / 110 after
        assertEquals(0f, MarginBringIntoView.distance(300f, 96f, 1000f, 72f, 110f), 0f)
        assertEquals(-42f, MarginBringIntoView.distance(30f, 96f, 1000f, 72f, 110f), 0f)
        assertEquals(106f, MarginBringIntoView.distance(900f, 96f, 1000f, 72f, 110f), 0f)
    }
}
