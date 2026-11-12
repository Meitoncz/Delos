package com.limelight.shell.companion

import android.app.Activity
import com.limelight.shell.settings.Appearance
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Who owns the companion window and when it closes (docs/DUAL_SCREEN.md): the hand-off between the shell and the stream must not close it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class CompanionSessionTest {
    private val shell = Robolectric.buildActivity(Activity::class.java).get()
    private val stream = Robolectric.buildActivity(Activity::class.java).get()
    private val shellOwner = Any()
    private val streamOwner = Any()

    @After fun release() {
        CompanionSession.detach(shellOwner)
        CompanionSession.detach(streamOwner)
        CompanionSession.forgetRefusal()
    }

    private fun content(pc: String) = CompanionSession.Content({ MutableStateFlow(Appearance(dark = true)) }, { CompanionState.Shell(pc) })

    @Test fun theOwnersDetachClosesTheWindow() {
        CompanionSession.attach(shellOwner, content("A"), shell)
        assertNotNull(CompanionSession.content.value)
        assertTrue(CompanionSession.detach(shellOwner))
        assertNull(CompanionSession.content.value)
    }

    @Test fun handOverKeepsTheWindowAndTheOldOwnersLateDetachDoesNothing() {
        CompanionSession.attach(shellOwner, content("A"), shell)
        // The stream resumes before the shell stops
        CompanionSession.attach(streamOwner, content("B"), stream)
        assertFalse(CompanionSession.detach(shellOwner))
        assertEquals(CompanionState.Shell("B"), CompanionSession.content.value?.state?.invoke())
        // and back: the shell resumes before the stream is destroyed
        CompanionSession.attach(shellOwner, content("C"), shell)
        assertFalse(CompanionSession.detach(streamOwner))
        assertTrue(CompanionSession.detach(shellOwner))
    }

    @Test fun aDetachWithoutAnOwnerDoesNothing() {
        assertFalse(CompanionSession.detach(Any()))
        assertNull(CompanionSession.content.value)
    }

    @Test fun aRefusedDisplayIsNotTriedUntilTheNextOwnerAttaches() {
        CompanionSession.refuse(7)
        assertEquals(7, CompanionSession.refused)
        CompanionSession.forgetRefusal()
        assertEquals(-1, CompanionSession.refused)
    }

    @Test fun aLaunchIsAwaitedForTwoSecondsOnly() {
        CompanionSession.launched(3, 1_000_000_000L)
        assertTrue(CompanionSession.isLaunching(3, 1_500_000_000L))
        assertFalse(CompanionSession.isLaunching(4, 1_500_000_000L))
        assertFalse(CompanionSession.isLaunching(3, 3_500_000_000L))
    }

    @Test fun theHelpIsShownExactlyWhileTheWindowThatTookItDrawsIt() {
        val window = Any()
        assertFalse(CompanionSession.showsHelp)
        CompanionSession.helpShown(window)
        assertTrue(CompanionSession.showsHelp)
        CompanionSession.helpGone(window)
        assertFalse(CompanionSession.showsHelp)
    }

    @Test fun aWindowThatWentCannotTakeTheHelpOfTheOneThatCameAfterIt() {
        val old = Any()
        val next = Any()
        CompanionSession.helpShown(old)
        // The swap: the new window takes the help before the old one is destroyed
        CompanionSession.helpShown(next)
        CompanionSession.helpGone(old)
        assertTrue(CompanionSession.showsHelp)
        CompanionSession.helpGone(next)
        assertFalse(CompanionSession.showsHelp)
        // A stranger's late gone does nothing
        CompanionSession.helpGone(Any())
        assertFalse(CompanionSession.showsHelp)
    }
}
