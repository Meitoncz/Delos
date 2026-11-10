package com.limelight.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashFileTest {
    private fun crash(): Throwable = IllegalStateException("Test crash from Debug info").also {
        it.stackTrace = arrayOf(
            StackTraceElement("androidx.compose.ui.Foo", "click", "Foo.kt", 10),
            StackTraceElement("com.limelight.shell.screens.DebugInfoScreen", "testCrash", "DebugInfoScreen.kt", 88),
            StackTraceElement("android.os.Handler", "handleCallback", "Handler.java", 942),
        )
    }

    private val log = (1..500).joinToString("\n") { "2026-11-10 12:00:00.000 I/Lime [main] line $it" } + "\n"

    @Test fun theRecordKeepsTheExceptionOneLineAndTheNewest200LogLines() {
        val record = CrashFile.record(1_700_000_000_000, 4321, "main", crash(), log)
        assertEquals("java.lang.IllegalStateException: Test crash from Debug info", record.exception)
        assertEquals(CrashFile.LOG_TAIL_LINES, record.logTail.size)
        assertTrue(record.logTail.first().endsWith("line 301"))
        assertTrue(record.logTail.last().endsWith("line 500"))
    }

    @Test fun aMultiLineMessageStaysOneExceptionLine() {
        val record = CrashFile.record(1, 1, "t", RuntimeException("first\nsecond"), "")
        assertEquals("java.lang.RuntimeException: first", record.exception)
    }

    @Test fun formatAndParseRoundTrip() {
        val record = CrashFile.record(1_700_000_000_000, 4321, "main", crash(), log)
        val parsed = CrashFile.parse(CrashFile.format(record))
        assertNotNull(parsed)
        assertEquals(record.copy(stackTrace = record.stackTrace.trimEnd()), parsed!!.copy(stackTrace = parsed.stackTrace.trimEnd()))
    }

    @Test fun aTruncatedOrForeignFileIsNotACrashRecord() {
        assertNull(CrashFile.parse(""))
        assertNull(CrashFile.parse("something else\nTime: 1"))
        val complete = CrashFile.format(CrashFile.record(1, 1, "t", crash(), log))
        assertNull("cut before the log heading", CrashFile.parse(complete.substringBefore("== Last log lines ==")))
        assertNull("cut inside the header", CrashFile.parse(complete.lines().take(3).joinToString("\n")))
    }

    @Test fun theSummaryNamesTheTypeTheMessageAndOurTopFrame() {
        val record = CrashFile.record(1, 1, "main", crash(), "")
        assertEquals("IllegalStateException: Test crash from Debug info (at com.limelight.shell.screens.DebugInfoScreen.testCrash)", CrashFile.summary(record))
    }

    @Test fun withoutOurFrameTheFirstFrameIsUsed() {
        val error = RuntimeException("x").also { it.stackTrace = arrayOf(StackTraceElement("android.os.Looper", "loop", "Looper.java", 1)) }
        assertEquals("RuntimeException: x (at android.os.Looper.loop)", CrashFile.summary(CrashFile.record(1, 1, "t", error, "")))
    }

    @Test fun theShortTraceKeepsTheExceptionLineAndTheFirstFrames() {
        val record = CrashFile.record(1, 1, "main", crash(), "")
        val short = CrashFile.shortTrace(record, frames = 2)
        assertEquals(1 + 2, short.lines().size)
        assertTrue(short.startsWith("java.lang.IllegalStateException: Test crash from Debug info"))
    }
}
