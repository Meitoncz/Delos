package com.limelight.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset

class DiagLogWriterTest {
    @get:Rule val folder = TemporaryFolder()

    private fun dir() = File(folder.root, "diag")

    @Test fun linesAreWrittenInOrderAndReadBackExactly() {
        val writer = DiagLogWriter(dir())
        listOf("one", "two", "three").forEach { writer.offer(it) }
        assertEquals("one\ntwo\nthree\n", writer.readAll())
    }

    @Test fun theFileRotatesAndTheOldestLinesGoFirst() {
        // 100 bytes per file; every line is 10 bytes with its newline
        val writer = DiagLogWriter(dir(), maxFileBytes = 100)
        for (i in 0 until 25) writer.offer("line-%04d".format(i))
        val all = writer.readAll()
        val lines = all.lines().filter { it.isNotEmpty() }
        assertTrue("never more than two files of 100 bytes: ${all.length}", all.length <= 200)
        assertEquals("the newest line is kept", "line-0024", lines.last())
        assertEquals("what is kept is a contiguous tail", lines.indices.map { "line-%04d".format(25 - lines.size + it) }, lines)
        assertEquals(setOf("diag-0.log", "diag-1.log"), dir().list()!!.toSet())
        dir().listFiles()!!.forEach { assertTrue(it.length() <= 100) }
    }

    @Test fun aWriterOnTheSameDirectoryAppendsToTheExistingFile() {
        DiagLogWriter(dir()).also { it.offer("before"); it.flush() }
        val next = DiagLogWriter(dir())
        next.offer("after")
        assertEquals("before\nafter\n", next.readAll())
    }

    @Test fun aFullQueueDropsLinesInsteadOfBlockingAndSaysSoLater() {
        // The writer thread is not started, so the queue (3 lines) fills up
        val writer = DiagLogWriter(dir(), capacity = 3, start = false)
        val results = (1..6).map { writer.offer("l$it") }
        assertEquals(listOf(true, true, true, false, false, false), results)
    }

    @Test fun droppedLinesAreCountedInTheLog() {
        val writer = DiagLogWriter(dir(), capacity = 2, start = false)
        (1..5).forEach { writer.offer("l$it") }
        writer.start()
        assertEquals("--- 3 log lines dropped (queue full) ---\nl1\nl2\n", writer.readAll())
    }

    @Test fun clearRemovesBothFilesAndLoggingStartsAgain() {
        val writer = DiagLogWriter(dir(), maxFileBytes = 30)
        (1..10).forEach { writer.offer("line $it") }
        writer.clear()
        assertEquals("", writer.readAll())
        assertEquals(0L, writer.sizeBytes())
        writer.offer("fresh")
        assertEquals("fresh\n", writer.readAll())
    }

    @Test fun sizeIsTheBytesOnDisk() {
        val writer = DiagLogWriter(dir())
        writer.offer("abc")
        writer.flush()
        assertEquals(4L, writer.sizeBytes())
        assertFalse(writer.readAll().isEmpty())
    }

    @Test fun aLineCarriesTimeLevelTagThreadAndMessage() {
        val line = DiagLine.format(0L, ZoneOffset.UTC, DiagLevel.WARN, "Stream", "main", "decoder failed")
        assertEquals("1970-01-01 00:00:00.000 W/Stream [main] decoder failed", line)
    }

    @Test fun aRunawayMessageIsCut() {
        val line = DiagLine.format(0L, ZoneOffset.UTC, DiagLevel.INFO, "T", "t", "x".repeat(DiagLine.MAX_MESSAGE_CHARS * 2))
        assertTrue(line.length < DiagLine.MAX_MESSAGE_CHARS + 100)
        assertTrue(line.endsWith("(cut)"))
    }
}
