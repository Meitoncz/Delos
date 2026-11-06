package com.limelight.shell.ui

import com.limelight.utils.ToastKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToastLogicTest {
    @Test
    fun theKindIsReadFromTheIconWhenTheCallerSaysNothing() {
        assertEquals(ToastKind.ERROR, toastKindFor(null, Symbols.ERROR, false))
        assertEquals(ToastKind.ERROR, toastKindFor(null, Symbols.WIFI_OFF, false))
        assertEquals(ToastKind.ERROR, toastKindFor(null, Symbols.LINK_OFF, false))
        assertEquals(ToastKind.SUCCESS, toastKindFor(null, Symbols.CHECK_CIRCLE, false))
        assertEquals(ToastKind.SUCCESS, toastKindFor(null, Symbols.CHECK, false))
        assertEquals(ToastKind.WARNING, toastKindFor(null, Symbols.WARNING, false))
        assertEquals(ToastKind.INFO, toastKindFor(null, Symbols.SEARCH, false))
        assertEquals(ToastKind.INFO, toastKindFor(null, null, false))
    }

    @Test
    fun anExplicitKindBeatsTheIconAndAnActionAlwaysWins() {
        assertEquals(ToastKind.SUCCESS, toastKindFor(ToastKind.SUCCESS, Symbols.LINK_OFF, false))
        assertEquals(ToastKind.WARNING, toastKindFor(ToastKind.WARNING, Symbols.MONITOR, false))
        assertEquals(ToastKind.ACTION, toastKindFor(null, Symbols.DELETE, true))
        assertEquals(ToastKind.ACTION, toastKindFor(ToastKind.ERROR, Symbols.ERROR, true))
    }

    @Test
    fun everyKindHasItsOwnWordAndIcon() {
        assertEquals(listOf("INFO", "DONE", "WARNING", "ERROR", "UNDO"), ToastKind.entries.map { it.word })
        assertEquals(ToastKind.entries.size, ToastKind.entries.map { it.defaultIcon }.toSet().size)
    }

    @Test
    fun theTimerLineDrawsInAndThenDrainsOverTheHoldTime() {
        assertEquals(0f, ToastTiming.timerFraction(0f, 2000L), 0f)
        assertEquals(0f, ToastTiming.timerFraction(ToastTiming.LINE_AT.toFloat(), 2000L), 0f)
        // Drawing in: the line is shorter than what is left
        val drawing = ToastTiming.timerFraction(ToastTiming.LINE_AT + 100f, 2000L)
        assertTrue(drawing > 0f && drawing < .9f)
        // Draining: exactly what is left of the hold time
        assertEquals(.5f, ToastTiming.timerFraction(1000f, 2000L), 1e-4f)
        assertEquals(.25f, ToastTiming.timerFraction(7500f, 10_000L), 1e-4f)
        assertEquals(0f, ToastTiming.timerFraction(2000f, 2000L), 0f)
        assertEquals(0f, ToastTiming.timerFraction(5000f, 2000L), 0f)
    }

    @Test
    fun aToastLeavesAtItsHoldTimeAndIsGoneAfterTheExit() {
        val logic = ToastStageLogic<String>()
        val slot = logic.show("A", 2000L, 1000L)
        assertEquals(1000L, slot.startMs)
        assertEquals(3000L, slot.exitMs)
        assertEquals(3000L + Motion.EXIT, slot.goneMs)
        val exited = mutableListOf<String>()
        assertFalse(logic.advance(2999L) { exited += it.payload })
        assertTrue(exited.isEmpty())
        assertFalse(logic.advance(3000L) { exited += it.payload })
        assertEquals(listOf("A"), exited)
        assertFalse(logic.advance(3000L + Motion.EXIT - 1) { exited += it.payload })
        assertTrue(logic.advance(3000L + Motion.EXIT) { exited += it.payload })
        assertEquals(listOf("A"), exited) // told once
        assertTrue(logic.isEmpty)
    }

    @Test
    fun aNewToastSendsTheOldOneAwayAtOnceAndEntersTheRowDelayLater() {
        val logic = ToastStageLogic<String>()
        val first = logic.show("A", 2000L, 0L)
        val second = logic.show("B", 3500L, 500L)
        assertEquals(500L, first.exitMs)
        assertEquals(500L + Motion.EXIT, first.goneMs)
        assertEquals(500L + Motion.ROW_DELAY, second.startMs)
        assertEquals(500L + Motion.ROW_DELAY + 3500L, second.exitMs)
        assertSame(second, logic.latestLive(520L))
    }

    @Test
    fun aToastInTheGapReplacesTheOneThatIsStillWaitingAndNothingQueues() {
        val logic = ToastStageLogic<String>()
        logic.show("A", 2000L, 0L)
        val waiting = logic.show("B", 2000L, 500L)
        val third = logic.show("C", 2000L, 560L) // B has not started to enter (it would at 620)
        assertEquals(560L, waiting.exitMs)
        assertTrue(waiting.exitMs < waiting.startMs) // it never shows
        assertEquals(560L + Motion.ROW_DELAY, third.startMs)
        assertSame(third, logic.latestLive(570L))
        assertEquals(3, logic.slots().size)
        // Everything but the last is gone once the exits are over
        logic.advance(560L + Motion.EXIT + 1) {}
        assertEquals(listOf("C"), logic.slots().map { it.payload })
    }

    @Test
    fun aToastThatArrivesAfterTheOldOneLeftEntersAtOnce() {
        val logic = ToastStageLogic<String>()
        val first = logic.show("A", 2000L, 0L)
        val second = logic.show("B", 2000L, 2100L) // A is already leaving by itself
        assertEquals(2000L, first.exitMs)
        assertEquals(2100L, second.startMs)
    }

    @Test
    fun dismissingAndPressingActOnTheLiveToastOnly() {
        val logic = ToastStageLogic<String>()
        val slot = logic.show("A", 10_000L, 0L)
        logic.press(4000L)
        assertEquals(4000L, slot.pressMs)
        logic.dismiss(4100L)
        assertEquals(4100L, slot.exitMs)
        logic.press(4200L) // nothing live any more
        assertEquals(4000L, slot.pressMs)
        assertEquals(null, logic.latestLive(4200L))
        assertNotNull(logic.slots().firstOrNull())
    }

    @Test
    fun theChipSqueezesWhenPressedAndSpringsBack() {
        assertEquals(1f, ToastTiming.pressScale(-1f), 0f)
        assertEquals(1f, ToastTiming.pressScale(0f), 1e-4f)
        assertEquals(Motion.PRESS_SCALE, ToastTiming.pressScale(Motion.PRESS.toFloat() - 1f), .02f)
        assertEquals(1f, ToastTiming.pressScale(Motion.PRESS_HOLD + Motion.PRESS + 1f), 1e-4f)
    }

    @Test
    fun theTextIsCappedAtTwoLinesAndByTheWidthOfTheScreen() {
        assertEquals(2, TOAST_MAX_LINES)
        assertEquals(560f, toastTextMaxWidth(1920f, 1f), 0f)
        assertEquals(728f, toastTextMaxWidth(1920f, 1.3f), .01f)
        // 4:3 at 130 %: still room for the icon block and the margins
        assertEquals(728f, toastTextMaxWidth(1440f, 1.3f), .01f)
        assertEquals(440f, toastTextMaxWidth(800f, 1f), 0f)
    }
}
