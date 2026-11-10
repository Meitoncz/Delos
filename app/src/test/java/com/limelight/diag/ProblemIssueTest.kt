package com.limelight.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemIssueTest {
    private val info = DeviceInfo(
        "0.1.0", "release", 100, "AYN", "Odin2 Portal", "odin2portal", "13", 33, "2026-09-05", "TKQ1", "QTI SM8550", "arm64-v8a",
        "Adreno (TM) 740", "1920x1080 at 120.0 Hz, 400 dpi", "Wi-Fi", "en-US", "UTC+01:00",
        listOf("Codec" to "hevc", "Resolution" to "1920x1080", "Frame rate" to "60", "HDR" to "off"),
    )
    private val trace = "java.lang.IllegalStateException: boom\n" + (1..30).joinToString("\n") { "\tat com.limelight.Frame$it.run(Frame.kt:$it)" }
    private val crash = Problem("crash-1", ProblemKind.JAVA_CRASH, 1, "IllegalStateException: boom (at com.limelight.Frame1.run)", trace)

    @Test fun theDraftCarriesTheFactsAndTheFileName() {
        val draft = ProblemIssue.draft(crash, info, "delos-diagnostic-20261110-120000.txt")
        assertEquals("Crash: IllegalStateException: boom (at com.limelight.Frame1.run)", draft.title)
        assertEquals("AYN Odin2 Portal, Android 13 (API 33)", draft.device)
        assertEquals("Delos 0.1.0 (release)", draft.build)
        assertEquals("Codec hevc, Resolution 1920x1080, Frame rate 60, HDR off", draft.streamSettings)
        assertEquals("Other", draft.area)
        assertTrue(draft.whatHappened.contains("Delos stopped unexpectedly the last time."))
        assertTrue(draft.whatHappened.contains("`delos-diagnostic-20261110-120000.txt`"))
        assertTrue(draft.whatHappened.contains("Downloads"))
    }

    @Test fun theLogsFieldHoldsTheSummaryAndTwelveFramesOnly() {
        val logs = ProblemIssue.logsText(crash)
        assertTrue(logs.startsWith("IllegalStateException: boom"))
        assertEquals(12, logs.lines().count { it.trim().startsWith("at ") })
        assertFalse(logs.contains("Frame13"))
    }

    @Test fun aFailedStreamIsAboutTheConnection() {
        val draft = ProblemIssue.draft(Problem("s", ProblemKind.STREAM_FAILED, 1, "Connection lost", "Error 5"), info, "f.txt")
        assertEquals("Stream failed: Connection lost", draft.title)
        assertEquals("Pairing/connection", draft.area)
        assertEquals("Connection lost\n\nError 5", draft.logs)
    }

    @Test fun anAnrAndANativeCrashKeepTheirOwnWords() {
        val anr = ProblemIssue.draft(Problem("e", ProblemKind.ANR, 1, "Not responding (ANR): Input dispatching timed out", "Input dispatching timed out"), info, "f.txt")
        assertEquals("Not responding: Input dispatching timed out", anr.title)
        val native = ProblemIssue.draft(Problem("n", ProblemKind.NATIVE_CRASH, 1, "Native crash: signal 11 (SIGSEGV)", "signal 11 (SIGSEGV)"), info, "f.txt")
        assertEquals("Native crash: signal 11 (SIGSEGV)", native.title)
    }

    @Test fun theWholeFlowGivesAnAddressWithoutPersonalData() {
        val scrubber = LogScrubber(ScrubContext(pcNames = listOf("Meiton-PC")))
        val problem = Problem("s", ProblemKind.STREAM_FAILED, 1, "Could not reach Meiton-PC at 192.168.0.200", "Connection to 192.168.0.200:47984 failed")
        val url = IssueUrl.build(ProblemIssue.draft(problem, info, "f.txt"), scrub = scrubber::scrub)
        val decoded = java.net.URLDecoder.decode(url, "UTF-8")
        assertFalse(decoded, decoded.contains("Meiton-PC") || decoded.contains("192.168.0.200"))
        assertTrue(url.length <= IssueUrl.MAX_LENGTH)
    }

    @Test fun theReportSectionHasTheFullTrace() {
        val section = ProblemIssue.section(crash, "2026-11-10 12:00:00")
        assertTrue(section.startsWith("Kind: JAVA_CRASH\nDetected: 2026-11-10 12:00:00\nSummary: IllegalStateException: boom"))
        assertTrue(section.contains("Frame30"))
    }
}
