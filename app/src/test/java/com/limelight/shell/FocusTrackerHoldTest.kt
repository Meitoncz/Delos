package com.limelight.shell

import androidx.compose.ui.focus.FocusRequester
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusTrackerHoldTest {
    private fun FocusTracker.focus(requester: FocusRequester) = focused(requester, null, null, adjustable = false)

    @Test
    fun theElementThatHadTheFocusWhenTheMenuOpenedIsHeld() {
        val tile = FocusRequester()
        val tracker = FocusTracker()
        tracker.focus(tile)
        assertFalse(tracker.holds(tile))
        tracker.hold()
        assertTrue(tracker.holds(tile))
        assertFalse(tracker.holds(FocusRequester()))
    }

    @Test
    fun theFocusMovingIntoTheMenuKeepsTheHold() {
        val tile = FocusRequester()
        val tracker = FocusTracker()
        tracker.focus(tile)
        tracker.hold()
        tracker.focus(FocusRequester())
        tracker.focus(FocusRequester())
        assertTrue(tracker.holds(tile))
    }

    @Test
    fun theFocusComingBackToTheElementEndsTheHoldWithoutAGap() {
        val tile = FocusRequester()
        val tracker = FocusTracker()
        tracker.focus(tile)
        tracker.hold()
        tracker.focus(FocusRequester())
        tracker.focus(tile)
        assertFalse(tracker.holds(tile))
    }

    @Test
    fun releaseEndsAHoldThatTheFocusNeverEnded() {
        val tile = FocusRequester()
        val tracker = FocusTracker()
        tracker.focus(tile)
        tracker.hold()
        tracker.focus(FocusRequester())
        tracker.release()
        assertFalse(tracker.holds(tile))
    }
}
