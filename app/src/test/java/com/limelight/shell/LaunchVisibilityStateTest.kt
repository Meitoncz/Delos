package com.limelight.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchVisibilityStateTest {
    @Test fun stoppingBehindTheStreamRemovesLaunchAndRetainsReturnWork() {
        val state = LaunchVisibilityState().stopped(hasLaunch = true, diving = true)
        assertTrue(state.returningFromStream)
        assertTrue(state.suppressLaunchOverlay)
        // Shell has already cleared its old launch/diving fields while hidden.
        assertTrue(state.needsResumeCleanup(hasLaunch = false, diving = false))
    }

    @Test fun resumeConsumesRefreshAndFocusWorkOnceButNeverReplaysTheOldOverlay() {
        val stopped = LaunchVisibilityState().stopped(hasLaunch = true, diving = true)
        val returned = stopped.resumed(hasLaunch = false, diving = false)
        assertFalse(returned.returningFromStream)
        assertTrue(returned.suppressLaunchOverlay)
        assertFalse(returned.needsResumeCleanup(hasLaunch = false, diving = false))
        assertTrue(returned.resumed(false, false).suppressLaunchOverlay)
    }

    @Test fun onlyANewLaunchReenablesTheLaunchSubtree() {
        val returned = LaunchVisibilityState().stopped(true, true).resumed(false, false)
        val next = returned.newLaunch()
        assertFalse(next.suppressLaunchOverlay)
        assertFalse(next.returningFromStream)
    }

    @Test fun cancellingBeforeTheStreamOpensKeepsNormalDashboardCancelBehavior() {
        val state = LaunchVisibilityState().stopped(hasLaunch = true, diving = false)
        assertFalse(state.returningFromStream)
        assertFalse(state.suppressLaunchOverlay)
        assertFalse(state.needsResumeCleanup(hasLaunch = false, diving = false))
    }

    @Test fun cancellingInsideTheStreamLoaderStillPerformsTheReturnCleanup() {
        val state = LaunchVisibilityState().stopped(true, true)
        assertTrue(state.needsResumeCleanup(false, false))
        assertTrue(state.resumed(false, false).suppressLaunchOverlay)
    }

    @Test fun aFastReturnBeforeOnStopCannotKeepTheOldTunnelVisible() {
        val state = LaunchVisibilityState()
        assertTrue(state.needsResumeCleanup(hasLaunch = true, diving = true))
        val resumed = state.resumed(hasLaunch = true, diving = true)
        assertTrue(resumed.suppressLaunchOverlay)
        assertFalse(resumed.needsResumeCleanup(hasLaunch = false, diving = false))
    }

    @Test fun unrelatedBackgroundingDoesNotInventAStreamReturn() {
        val state = LaunchVisibilityState().stopped(hasLaunch = false, diving = false)
        assertFalse(state.returningFromStream)
        assertFalse(state.suppressLaunchOverlay)
        assertFalse(state.needsResumeCleanup(hasLaunch = false, diving = false))
    }
}
