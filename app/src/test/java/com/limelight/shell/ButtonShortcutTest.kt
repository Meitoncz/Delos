package com.limelight.shell

import android.content.Context
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.limelight.shadows.ShadowGameManager
import com.limelight.shadows.ShadowMoonBridge
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.StreamMenuSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
class ButtonShortcutTest {
    private val l3 = KeyEvent.KEYCODE_BUTTON_THUMBL
    private val r3 = KeyEvent.KEYCODE_BUTTON_THUMBR
    private lateinit var context: Context
    private var opened = 0

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        opened = 0
    }

    private fun shortcut(buttons: Set<Int>) = ButtonShortcut(buttons) { opened++ }

    private fun ButtonShortcut.down(code: Int, repeat: Int = 0) = onKey(code, KeyEvent.ACTION_DOWN, repeat)
    private fun ButtonShortcut.up(code: Int) = onKey(code, KeyEvent.ACTION_UP, 0)

    @Test fun l3AndR3OpenTheMenuWhenReleasedInEitherOrder() {
        val s = shortcut(setOf(l3, r3))
        s.down(l3); s.down(r3); s.up(l3); s.up(r3)
        assertEquals(1, opened)

        s.down(r3); s.down(l3); s.up(l3); s.up(r3)
        assertEquals(2, opened)

        s.down(l3); s.down(r3); s.up(r3); s.up(l3)
        assertEquals(3, opened)
    }

    @Test fun opensOnTheLastReleaseNotBefore() {
        val s = shortcut(setOf(l3, r3))
        s.down(l3); s.down(r3)
        assertEquals(0, opened)
        s.up(r3)
        assertEquals(0, opened)
        s.up(l3)
        assertEquals(1, opened)
    }

    @Test fun oneButtonAloneNeverOpensAndAlwaysReachesTheGame() {
        val s = shortcut(setOf(l3, r3))
        repeat(3) {
            assertFalse(s.down(l3)); assertFalse(s.up(l3))
            assertFalse(s.down(r3)); assertFalse(s.up(r3))
        }
        assertEquals(0, opened)
    }

    @Test fun aButtonHeldAndPressedAgainDoesNotCountTwice() {
        val s = shortcut(setOf(l3, r3))
        s.down(l3); s.down(l3, repeat = 1); s.down(l3, repeat = 2)
        s.up(l3)
        assertEquals(0, opened)
    }

    @Test fun otherButtonsPressedAlongDoNotOpenOrDisturbIt() {
        val s = shortcut(setOf(l3, r3))
        assertFalse(s.down(KeyEvent.KEYCODE_BUTTON_A))
        s.down(l3)
        assertFalse(s.down(KeyEvent.KEYCODE_BUTTON_X))
        s.up(KeyEvent.KEYCODE_BUTTON_A)
        s.down(r3); s.up(r3); s.up(l3)
        assertEquals(1, opened)
        assertFalse(s.up(KeyEvent.KEYCODE_BUTTON_X))
    }

    @Test fun onlyTheCompletingButtonIsKeptFromTheGameSoEveryPressedButtonGetsItsRelease() {
        val s = shortcut(setOf(l3, r3))
        assertFalse("first press reaches the game", s.down(l3))
        assertTrue("the completing press is kept back", s.down(r3))
        assertTrue("its repeats too", s.down(r3, repeat = 1))
        assertTrue("and its release", s.up(r3))
        assertFalse("the game saw L3 go down, so it must see it go up", s.up(l3))
        assertEquals(1, opened)

        // The other release order: L3's release completes it and still reaches the game.
        assertFalse(s.down(l3))
        assertTrue(s.down(r3))
        assertTrue(s.up(r3))
        assertFalse(s.up(l3))
        assertEquals(2, opened)
    }

    @Test fun theSameKeyEventSeenTwiceChangesNothing() {
        val s = shortcut(setOf(l3, r3))
        // The stream view (pre-IME) and the activity (dispatchKeyEvent) can both see a key that was not consumed
        assertFalse(s.down(l3)); assertFalse(s.down(l3))
        assertTrue(s.down(r3))
        assertFalse(s.up(l3)); assertFalse(s.up(l3))
        assertTrue(s.up(r3))
        assertEquals(1, opened)
    }

    @Test fun otherCombinationsWork() {
        val combos = listOf(
            setOf(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT),
            setOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1),
            setOf(KeyEvent.KEYCODE_BUTTON_MODE),
            setOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_START),
        )
        for (combo in combos) {
            opened = 0
            val s = shortcut(combo)
            combo.forEach { s.down(it) }
            combo.forEach { s.up(it) }
            assertEquals("$combo", 1, opened)
        }
    }

    @Test fun theDefaultIsL3AndR3() {
        assertEquals(setOf(l3, r3), StreamMenuSettings.buttons(context))
    }

    @Test fun theRecordedL3R3ChoiceIsStoredAndReadBackAsTheSameCombination() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        StreamMenuSettings.setButtons(prefs, setOf(r3, l3))
        assertEquals("$l3,$r3", prefs.getString(StreamMenuSettings.KEY_BUTTONS, null))
        assertEquals(setOf(l3, r3), StreamMenuSettings.buttons(context))

        val s = shortcut(StreamMenuSettings.buttons(context))
        s.down(l3); s.down(r3); s.up(l3); s.up(r3)
        assertEquals(1, opened)
    }

    @Test fun theChoiceWrittenBySettingsIsTheOneTheStreamReads() {
        val profiles = ProfileStore(context)
        profiles.create("Player", 0)
        StreamMenuSettings.setButtons(profiles.settings(), setOf(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals(setOf(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT), StreamMenuSettings.buttons(context))
        StreamMenuSettings.setButtons(profiles.settings(), setOf(l3, r3))
        assertEquals(setOf(l3, r3), StreamMenuSettings.buttons(context))
        profiles.streamProfiles.close()
    }
}
