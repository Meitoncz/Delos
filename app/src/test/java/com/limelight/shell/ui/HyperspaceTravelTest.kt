package com.limelight.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperspaceTravelTest {
    private val dt = 1f / 60f

    @Test
    fun aStepOfTheProgressNeverMakesTheTunnelLeap() {
        // Four seconds in, the connection reports a big step of the progress: the travel of one frame stays speed x dt
        var smoothed = .3f
        var travel = 3.6f
        var biggestJump = 0f
        repeat(240) { frame ->
            val target = if (frame < 60) .3f else .9f
            smoothed = HyperspaceTravel.follow(smoothed, target, dt)
            val next = HyperspaceTravel.advance(travel, smoothed, dt)
            biggestJump = maxOf(biggestJump, next - travel)
            travel = next
        }
        assertTrue("jump $biggestJump", biggestJump <= HyperspaceTravel.speed(1f) * dt + 1e-6f)
    }

    @Test
    fun theSpeedFollowsTheProgressGentlyAndArrivesAtIt() {
        var smoothed = 0f
        repeat(600) { smoothed = HyperspaceTravel.follow(smoothed, 1f, dt) }
        assertEquals(1f, smoothed, .01f)
        assertEquals(0f, HyperspaceTravel.follow(0f, 1f, 0f), 0f)
        // One frame moves it only a little
        assertTrue(HyperspaceTravel.follow(0f, 1f, dt) < .05f)
    }

    @Test
    fun aStalledFrameDoesNotThrowTheTunnelForward() {
        val travel = HyperspaceTravel.advance(2f, .5f, 2f)
        assertEquals(2f + HyperspaceTravel.speed(.5f) * HyperspaceTravel.MAX_STEP, travel, 1e-6f)
        assertEquals(2f, HyperspaceTravel.advance(2f, .5f, -1f), 0f)
    }
}
