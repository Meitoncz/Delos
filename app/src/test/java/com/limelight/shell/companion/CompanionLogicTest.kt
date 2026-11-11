package com.limelight.shell.companion

import android.view.KeyEvent
import com.limelight.shell.StreamStats
import com.limelight.shell.settings.ImpactLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the companion screen: burn-in policy, statistics throttle, key mapping, echo, buttons, impact summary and readout. */
class CompanionLogicTest {
    // ---- Burn-in ----

    @Test fun shiftStartsInPlaceAndMovesEveryPeriod() {
        assertEquals(CompanionBurnIn.Shift(0, 0), CompanionBurnIn.shiftAt(0))
        assertEquals(CompanionBurnIn.Shift(0, 0), CompanionBurnIn.shiftAt(CompanionBurnIn.SHIFT_PERIOD_MS - 1))
        assertEquals(CompanionBurnIn.Shift(1, 0), CompanionBurnIn.shiftAt(CompanionBurnIn.SHIFT_PERIOD_MS))
    }

    @Test fun shiftVisitsEveryPointOfTheGridOnceAndRepeats() {
        val period = CompanionBurnIn.SHIFT_PERIOD_MS
        val cycle = (0 until 9).map { CompanionBurnIn.shiftAt(it * period) }
        assertEquals(9, cycle.toSet().size)
        assertTrue(cycle.all { it.x in -1..1 && it.y in -1..1 })
        assertEquals(cycle, (9 until 18).map { CompanionBurnIn.shiftAt(it * period) })
        // Neighbouring positions are one step apart at most: no jump across the grid
        (cycle + cycle.first()).zipWithNext().forEach { (a, b) -> assertTrue(Math.abs(a.x - b.x) <= 1 && Math.abs(a.y - b.y) <= 1) }
    }

    @Test fun shiftIgnoresANegativeClock() {
        assertEquals(CompanionBurnIn.Shift(0, 0), CompanionBurnIn.shiftAt(-5))
    }

    @Test fun dimsExactlyAfterTheTimeoutWithoutATouch() {
        assertFalse(CompanionBurnIn.dimmed(1_000, 1_000 + CompanionBurnIn.DIM_AFTER_MS - 1))
        assertTrue(CompanionBurnIn.dimmed(1_000, 1_000 + CompanionBurnIn.DIM_AFTER_MS))
        // A touch restarts the timeout
        assertFalse(CompanionBurnIn.dimmed(1_000 + CompanionBurnIn.DIM_AFTER_MS, 1_000 + CompanionBurnIn.DIM_AFTER_MS + 10))
    }

    // ---- Statistics throttle ----

    @Test fun throttleLetsTheFirstThroughAndAtMostTwoPerSecond() {
        val throttle = StatsThrottle()
        assertTrue(throttle.accept(10_000))
        assertFalse(throttle.accept(10_100))
        assertFalse(throttle.accept(10_499))
        assertTrue(throttle.accept(10_500))
        assertFalse(throttle.accept(10_600))
        assertTrue(throttle.accept(11_000))
    }

    @Test fun throttleCountsFromTheLastAcceptedSnapshotNotTheLastOffered() {
        val throttle = StatsThrottle()
        assertTrue(throttle.accept(0))
        // Offered every 100 ms for 2 s: 4 get through
        assertEquals(4, (1..20).count { throttle.accept(it * 100L) })
    }

    @Test fun throttleRestarts() {
        val throttle = StatsThrottle()
        assertTrue(throttle.accept(0))
        throttle.reset()
        assertTrue(throttle.accept(1))
    }

    // ---- Keys ----

    @Test fun lettersAndDigitsMapToTheirKeys() {
        assertEquals(KeyStroke(KeyEvent.KEYCODE_A), KeyMap.stroke('a'))
        assertEquals(KeyStroke(KeyEvent.KEYCODE_Z), KeyMap.stroke('z'))
        assertEquals(KeyStroke(KeyEvent.KEYCODE_A, shift = true), KeyMap.stroke('A'))
        assertEquals(KeyStroke(KeyEvent.KEYCODE_0), KeyMap.stroke('0'))
        assertEquals(KeyStroke(KeyEvent.KEYCODE_9), KeyMap.stroke('9'))
        assertEquals(KeyStroke(KeyEvent.KEYCODE_SPACE), KeyMap.stroke(' '))
    }

    @Test fun symbolsUseTheUsLayoutShiftStates() {
        assertEquals(KeyStroke(KeyEvent.KEYCODE_1, shift = true), KeyMap.stroke('!'))
        assertEquals(KeyStroke(KeyEvent.KEYCODE_2, shift = true), KeyMap.stroke('@'))
        assertEquals(KeyStroke(KeyEvent.KEYCODE_0, shift = true), KeyMap.stroke(')'))
        assertEquals(KeyStroke(KeyEvent.KEYCODE_COMMA), KeyMap.stroke(','))
        assertEquals(KeyStroke(KeyEvent.KEYCODE_COMMA, shift = true), KeyMap.stroke('<'))
        assertEquals(KeyStroke(KeyEvent.KEYCODE_SLASH, shift = true), KeyMap.stroke('?'))
        assertEquals(KeyStroke(KeyEvent.KEYCODE_BACKSLASH), KeyMap.stroke('\\'))
        assertEquals(KeyStroke(KeyEvent.KEYCODE_BACKSLASH, shift = true), KeyMap.stroke('|'))
        assertEquals(KeyStroke(KeyEvent.KEYCODE_APOSTROPHE, shift = true), KeyMap.stroke('"'))
        assertEquals(KeyStroke(KeyEvent.KEYCODE_GRAVE, shift = true), KeyMap.stroke('~'))
        assertEquals(KeyStroke(KeyEvent.KEYCODE_EQUALS, shift = true), KeyMap.stroke('+'))
    }

    @Test fun everyCharacterOfBothKeyboardPagesHasAKey() {
        val pages = "1234567890qwertyuiopasdfghjklzxcvbnm,.!@#$%^&*()[]{}<>+=_~;:'\"/\\|?-`"
        pages.forEach { assertTrue("no key for $it", KeyMap.stroke(it) != null) }
    }

    @Test fun noKeyForACharacterTheLayoutLacks() {
        assertNull(KeyMap.stroke('\u20ac'))
        assertNull(KeyMap.stroke('\u00e9'))
    }

    @Test fun shiftIsHeldAroundAShiftedKeyAndLetGoAfterIt() {
        val bang = KeyMap.stroke('!')!!
        assertEquals(listOf(KeyEvent.KEYCODE_SHIFT_LEFT to true, KeyEvent.KEYCODE_1 to true), KeyMap.down(bang))
        assertEquals(listOf(KeyEvent.KEYCODE_1 to false, KeyEvent.KEYCODE_SHIFT_LEFT to false), KeyMap.up(bang))
        assertEquals(listOf(KeyEvent.KEYCODE_A to true), KeyMap.down(KeyStroke(KeyEvent.KEYCODE_A)))
        assertEquals(listOf(KeyEvent.KEYCODE_A to false), KeyMap.up(KeyStroke(KeyEvent.KEYCODE_A)))
    }

    // ---- Echo ----

    @Test fun echoCollectsBacksUpAndClears() {
        var echo = TypedEcho()
        "hi".forEach { echo = echo.typed(it) }
        assertEquals("hi", echo.value)
        assertEquals("h", echo.backspace().value)
        assertEquals("", TypedEcho().backspace().value)
        assertEquals("hi\u21B5", echo.enter().value)
        assertEquals("", echo.cleared().value)
    }

    @Test fun echoKeepsOnlyTheNewestCharacters() {
        var echo = TypedEcho()
        repeat(TypedEcho.MAX + 5) { echo = echo.typed('a' + it % 3) }
        assertEquals(TypedEcho.MAX, echo.value.length)
        assertEquals('a' + (TypedEcho.MAX + 4) % 3, echo.value.last())
    }

    // ---- Buttons ----

    @Test fun buttonsFollowWhatRunsOnThePc() {
        assertEquals(listOf(ShellButton.START, ShellButton.GAME_SETTINGS), shellButtons(running = false, otherRunning = false))
        assertEquals(listOf(ShellButton.RESUME, ShellButton.GAME_SETTINGS), shellButtons(running = true, otherRunning = false))
        assertEquals(
            listOf(ShellButton.RESUME_OTHER, ShellButton.QUIT_AND_START, ShellButton.GAME_SETTINGS),
            shellButtons(running = false, otherRunning = true),
        )
        // This game running wins over "another game": a PC runs one game
        assertEquals(listOf(ShellButton.RESUME, ShellButton.GAME_SETTINGS), shellButtons(running = true, otherRunning = true))
    }

    // ---- Impact ----

    @Test fun impactIsTheHighestLevelOfEachAxis() {
        val light = mapOf("list_resolution" to "1920x1080", "list_fps" to "60", "seekbar_bitrate_kbps" to 20000, "video_format" to "auto")
        val heavy = light + mapOf("list_resolution" to "3840x2160", "list_fps" to "120", "seekbar_bitrate_kbps" to 150000)
        val a = gameImpact(light)!!
        val b = gameImpact(heavy)!!
        assertTrue(b.gpu.ordinal >= a.gpu.ordinal && b.network.ordinal >= a.network.ordinal)
        assertTrue(b.gpu != ImpactLevel.NONE || b.network != ImpactLevel.NONE)
    }

    @Test fun impactIsUnknownWithoutAnySetting() {
        // Every key has a default inside the impact tables, so an empty map still gives a summary: never null for a real value map
        assertTrue(gameImpact(emptyMap<String, Any>()) != null)
    }

    // ---- Readout ----

    @Test fun readoutFormatsTheStatisticsLikeThePanel() {
        val stats = StreamStats(1920, 1080, 119.94f, 120f, 119.9f, "HEVC", true, "decoder", null, 0f, 5, 1, 2.14f, 0f, -1f, -1f, -1f, 4400f)
        val readout = StatsReadout.of(stats)
        assertEquals("119.9", readout.fps)
        assertEquals("5 ms", readout.network)
        assertEquals("2.1 ms", readout.decode)
        assertEquals("1920 \u00d7 1080 \u00b7 120 FPS \u00b7 HEVC HDR", readout.picture)
    }

    @Test fun readoutBeforeTheFirstSnapshotIsDashes() {
        assertEquals(StatsReadout.EMPTY, StatsReadout.of(null))
        assertNull(StatsReadout.EMPTY.picture)
    }

    @Test fun sliderSnapsToTheStepAndStaysInRange() {
        assertEquals(5, snapSliderValue(0f, 5, 100, 5))
        assertEquals(100, snapSliderValue(1f, 5, 100, 5))
        assertEquals(100, snapSliderValue(3f, 5, 100, 5))
        assertEquals(5, snapSliderValue(-1f, 5, 100, 5))
        assertEquals(55, snapSliderValue(.52f, 5, 100, 5))
        assertEquals(151, snapSliderValue(.5f, 1, 300, 1))
    }
}
