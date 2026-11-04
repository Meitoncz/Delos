package com.limelight.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchTravelTest {
    @Test fun aProgressStepCannotRetroactivelyMoveSeveralSecondsOfTravel() {
        val motion = LaunchTravel()
        motion.advance(0f, .1f, 0f)
        var previous = motion.travel
        for (frame in 1..1000) {
            val target = if (frame < 500) .1f else 1f
            motion.advance(frame * .016f, target, 0f)
            val delta = motion.travel - previous
            assertTrue(delta >= 0f)
            assertTrue("Frame $frame jumped by $delta", delta <=
                (HyperspaceConfig.VELOCITY + HyperspaceConfig.ACCELERATION) * .016f + .0001f)
            previous = motion.travel
        }
    }

    @Test fun aStageStepChangesVelocityThroughTheFollowerNotInOneFrame() {
        val motion = LaunchTravel()
        motion.advance(0f, 0f, 0f)
        motion.advance(.016f, 1f, 0f)
        assertTrue(motion.smoothedProgress > 0f && motion.smoothedProgress < .1f)
        val first = motion.smoothedProgress
        for (frame in 2..125) motion.advance(frame * .016f, 1f, 0f)
        assertTrue(motion.smoothedProgress > first && motion.smoothedProgress > .98f)
        assertTrue(motion.smoothedProgress <= 1f)
    }

    @Test fun handoffRetainsTravelAndTheSmoothedProgressInsteadOfMultiplyingTheNewClock() {
        val motion = LaunchTravel(12f, .4f)
        motion.advance(20f, 1f, 0f)
        assertEquals(12f, motion.travel, 0f)
        assertEquals(.4f, motion.smoothedProgress, 0f)
        motion.advance(20.016f, 1f, 0f)
        assertTrue(motion.travel > 12f && motion.travel < 12.06f)
        assertTrue(motion.smoothedProgress > .4f && motion.smoothedProgress < .5f)
    }

    @Test fun twoWindowsDrawingTheSameFrameDoNotIntegrateItTwice() {
        val motion = LaunchTravel()
        motion.advance(0f, .3f, 0f)
        motion.advance(.016f, .3f, 0f)
        val travel = motion.travel
        val progress = motion.smoothedProgress
        motion.advance(.016f, .8f, 0f)
        assertEquals(travel, motion.travel, 0f)
        assertEquals(progress, motion.smoothedProgress, 0f)
        motion.advance(.015f, .8f, 0f)
        motion.advance(.032f, .8f, 0f)
        assertTrue(motion.travel - travel <= .016f *
            (HyperspaceConfig.VELOCITY + HyperspaceConfig.ACCELERATION) + .00001f)
    }

    @Test fun aStalledFrameIsClampedAndCannotCauseACatchupLeap() {
        val motion = LaunchTravel(5f, 1f)
        motion.advance(1f, 1f, 0f)
        motion.advance(30f, 1f, 0f)
        assertEquals(5f + (HyperspaceConfig.VELOCITY + HyperspaceConfig.ACCELERATION) *
            HyperspaceConfig.MAX_FRAME_SECONDS, motion.travel, .00001f)
    }

    @Test fun theExitStartsAtExactlyTheFrozenConnectionTravel() {
        val motion = LaunchTravel(17f, .8f)
        motion.advance(2f, 1f, 0f)
        motion.advance(2.016f, 1f, 0f)
        val travel = motion.travel
        val progress = motion.smoothedProgress
        assertEquals(travel, motion.travelAtOpening(0f), 0f)
        motion.advance(2.032f, .2f, .001f)
        motion.advance(3f, 1f, .5f)
        assertEquals(travel, motion.travel, 0f)
        assertEquals(progress, motion.smoothedProgress, 0f)
        val justOpened = motion.travelAtOpening(.0001f)
        assertTrue(justOpened >= travel && justOpened - travel < .001f)
        var previous = travel
        for (step in 1..1000) {
            val position = motion.travelAtOpening(step / 1000f)
            assertTrue(position >= previous)
            previous = position
        }
    }
}
