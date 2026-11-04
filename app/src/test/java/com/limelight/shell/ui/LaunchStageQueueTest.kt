package com.limelight.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchStageQueueTest {
    @Test
    fun anEmptyQueueHasNoLineOrDwellAndIsAlreadyDrained() {
        val queue = LaunchStageQueue()
        assertNull(queue.currentLine)
        assertEquals(0L, queue.remainingMillis(0L))
        assertTrue(queue.isDrained(0L))
        assertFalse(queue.advance(10_000_000_000L))
        assertNull(queue.currentLine)
    }

    @Test
    fun theFirstQueuedLineStartsItsDwellOnlyWhenPresented() {
        val queue = LaunchStageQueue()
        queue.enqueue("first")
        assertNull(queue.currentLine)
        assertEquals(0L, queue.remainingMillis(50_000_000L))
        assertFalse(queue.isDrained(50_000_000L))
        assertTrue(queue.advance(50_000_000L))
        assertEquals("first", queue.currentLine)
        assertEquals(1200L, queue.remainingMillis(50_000_000L))
        assertFalse(queue.isDrained(50_000_000L))
    }

    @Test
    fun theDefaultDwellIncludesTheLastNanosecondAndRoundsRemainingTimeUp() {
        val queue = LaunchStageQueue(initialLine = "first", initialShownAtNanos = 50_000_000L)
        queue.enqueue("second")
        assertEquals(1200L, queue.remainingMillis(50_000_001L))
        assertEquals(1199L, queue.remainingMillis(51_000_000L))
        assertEquals(1L, queue.remainingMillis(1_249_999_999L))
        assertFalse(queue.advance(1_249_999_999L))
        assertEquals("first", queue.currentLine)
        assertEquals(0L, queue.remainingMillis(1_250_000_000L))
        assertTrue(queue.advance(1_250_000_000L))
        assertEquals("second", queue.currentLine)
        assertEquals(1200L, queue.remainingMillis(1_250_000_000L))
    }

    @Test
    fun aHandoffKeepsTheOriginalLinesAlreadyElapsedDwell() {
        val queue = LaunchStageQueue(initialLine = "inherited", initialShownAtNanos = 2_000_000_000L)
        queue.enqueue("next")
        assertEquals(300L, queue.remainingMillis(2_900_000_000L))
        assertFalse(queue.advance(3_199_999_999L))
        assertTrue(queue.advance(3_200_000_000L))
        assertEquals("next", queue.currentLine)
        assertEquals(1200L, queue.remainingMillis(3_200_000_000L))
    }

    @Test
    fun anAlreadyMatureHandoffCanAdvanceImmediatelyWithoutRestartingItsTimer() {
        val queue = LaunchStageQueue(initialLine = "inherited", initialShownAtNanos = 100_000_000L)
        queue.enqueue("next")
        assertEquals(0L, queue.remainingMillis(9_000_000_000L))
        assertTrue(queue.advance(9_000_000_000L))
        assertEquals("next", queue.currentLine)
    }

    @Test
    fun aBurstStaysInOrderAndLateFramesNeverSkipOrShortenAnyLine() {
        val queue = LaunchStageQueue(initialLine = "first")
        listOf("second", "third", "fourth").forEach(queue::enqueue)
        for ((index, line) in listOf("second", "third", "fourth").withIndex()) {
            val now = (index + 1) * 10_000_000_000L
            assertTrue(queue.advance(now))
            assertEquals(line, queue.currentLine)
            assertEquals(1200L, queue.remainingMillis(now))
            assertFalse(queue.advance(now))
            assertFalse(queue.advance(now + 1_199_999_999L))
            assertEquals(line, queue.currentLine)
            assertFalse(queue.isDrained(now + 1_199_999_999L))
        }
        assertTrue(queue.isDrained(31_200_000_000L))
    }

    @Test
    fun theFinalLineRemainsVisibleAndMustFinishItsFullDwellBeforeDrain() {
        val queue = LaunchStageQueue(initialLine = "first")
        queue.enqueue("last")
        assertTrue(queue.advance(1_200_000_000L))
        assertFalse(queue.isDrained(2_399_999_999L))
        assertFalse(queue.advance(2_400_000_000L))
        assertTrue(queue.isDrained(2_400_000_000L))
        assertEquals("last", queue.currentLine)
        assertEquals(0L, queue.remainingMillis(Long.MAX_VALUE))
    }

    @Test
    fun aLineThatArrivesAfterDrainStartsAFreshFullDwell() {
        val queue = LaunchStageQueue(initialLine = "first")
        assertTrue(queue.isDrained(5_000_000_000L))
        queue.enqueue("late")
        assertFalse(queue.isDrained(5_000_000_000L))
        assertTrue(queue.advance(5_000_000_000L))
        assertEquals("late", queue.currentLine)
        assertEquals(1200L, queue.remainingMillis(5_000_000_000L))
    }

    @Test
    fun repeatedStageTextStillRepresentsSeparateEvents() {
        val queue = LaunchStageQueue(initialLine = "same")
        queue.enqueue("same")
        assertTrue(queue.advance(1_200_000_000L))
        assertEquals("same", queue.currentLine)
        assertEquals(1200L, queue.remainingMillis(1_200_000_000L))
        assertFalse(queue.isDrained(1_200_000_000L))
    }

    @Test
    fun aCustomDwellUsesTheSameExactBoundary() {
        val queue = LaunchStageQueue(initialLine = "first", minimumDurationMillis = 25L)
        queue.enqueue("second")
        assertEquals(1L, queue.remainingMillis(24_999_999L))
        assertFalse(queue.advance(24_999_999L))
        assertTrue(queue.advance(25_000_000L))
        assertEquals(25L, queue.remainingMillis(25_000_000L))
    }

    @Test
    fun zeroDwellStillAdvancesOnlyOneLinePerCall() {
        val queue = LaunchStageQueue(minimumDurationMillis = 0L)
        queue.enqueue("first")
        queue.enqueue("second")
        assertTrue(queue.advance(0L))
        assertEquals("first", queue.currentLine)
        assertFalse(queue.isDrained(0L))
        assertTrue(queue.advance(0L))
        assertEquals("second", queue.currentLine)
        assertTrue(queue.isDrained(0L))
    }

    @Test
    fun aTimestampBeforeTheInheritedPresentationDoesNotShortenTheDwell() {
        val queue = LaunchStageQueue(initialLine = "inherited", initialShownAtNanos = 500_000_000L)
        queue.enqueue("next")
        assertEquals(1200L, queue.remainingMillis(400_000_000L))
        assertFalse(queue.advance(400_000_000L))
    }
}
