package com.limelight.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DiagnosticReportTest {
    private val res = RuntimeEnvironment.getApplication().resources
    private val info = DeviceInfo(
        delosVersion = "0.1.0", buildType = "release", versionCode = 100, manufacturer = "AYN", model = "Odin2 Portal", device = "odin2portal",
        androidRelease = "13", sdk = 33, securityPatch = "2026-09-05", buildId = "TKQ1.230101", soc = "QTI SM8550", abi = "arm64-v8a",
        gpu = "Adreno (TM) 740", screen = "1920x1080 at 120.0 Hz, 400 dpi", network = "Wi-Fi", locale = "en-US", timeZone = "UTC+01:00",
        settings = listOf("Codec" to "hevc", "Resolution" to "1920x1080", "Frame rate" to "60", "Bitrate" to "80000 kbps", "HDR" to "off"),
    )
    private val scrubber = LogScrubber(ScrubContext(pcNames = listOf("Meiton-PC")))
    private val log = (1..300).joinToString("\n") { "2026-11-10 12:00:00.000 I/Lime [main] line $it for Meiton-PC at 192.168.0.200" } + "\n"

    @Test fun theDeviceBlockListsTheFactsOneLineEach() {
        val text = info.format()
        assertTrue(text.contains("Delos: 0.1.0 (release), version code 100"))
        assertTrue(text.contains("Device: AYN Odin2 Portal (odin2portal)"))
        assertTrue(text.contains("Android: 13 (API 33), security patch 2026-09-05, build TKQ1.230101"))
        assertTrue(text.contains("SoC: QTI SM8550 (arm64-v8a)"))
        assertTrue(text.contains("  Codec: hevc"))
        assertTrue(text.contains("  Bitrate: 80000 kbps"))
    }

    @Test fun theReportIsScrubbedAsAWholeAndCountsItsLines() {
        val report = DiagnosticReports.build(info, log, scrubber, "2026-11-10 12:00:00 (device time)")
        assertEquals(300, report.logLines)
        assertFalse(report.text.contains("Meiton-PC"))
        assertFalse(report.text.contains("192.168.0.200"))
        assertTrue(report.text.contains("line 300 for <pc-1> at <ip-1>"))
        assertTrue(report.text.contains("== Log (300 lines, oldest first) =="))
        assertEquals(report.text.toByteArray().size, report.bytes)
    }

    @Test fun theSummaryKeepsTheNewestLinesOnly() {
        val summary = DiagnosticReports.summary(info, log, scrubber, "now", tailLines = 100)
        assertEquals(100, summary.logLines)
        assertTrue(summary.text.contains("line 300 "))
        assertTrue(summary.text.contains("line 201 "))
        assertFalse(summary.text.contains("line 200 "))
    }

    @Test fun theDeviceInfoNeverHoldsWhatTheCollectorDoesNotPutThere() {
        val text = info.format()
        assertFalse(text.contains("192."))
        assertFalse(text.contains("Meiton"))
    }

    @Test fun theSizeAndLineCountShowAsOneLabel() {
        val report = DiagnosticReports.build(info, log, scrubber, "now")
        assertEquals("512 B", DiagnosticReport.sizeText(512))
        assertEquals("1.5 KB", DiagnosticReport.sizeText(1536))
        assertEquals("1.2 MB", DiagnosticReport.sizeText(1_300_000))
    }

    @Test fun thePreviewShowsTheHeaderAndTheLastLinesAndSaysWhatIsHidden() {
        val report = DiagnosticReports.build(info, log, scrubber, "now")
        val preview = report.preview(res, tailLines = 10)
        assertTrue(preview.startsWith("Delos diagnostic report"))
        assertTrue(preview.contains("== Device =="))
        assertTrue(preview.contains("… 290 earlier lines are in the file …"))
        assertTrue(preview.trimEnd().endsWith("line 300 for <pc-1> at <ip-1>"))
        assertTrue(preview.lines().size < 60)
    }

    @Test fun theHiddenLinesNoteUsesTheSingularForOneLine() {
        val report = DiagnosticReports.build(info, (1..11).joinToString("\n") { "2026-11-10 12:00:00.000 I/A [m] line $it" } + "\n", scrubber, "now")
        assertTrue(report.preview(res, tailLines = 10).contains("… 1 earlier line is in the file …"))
    }

    @Test fun aShortReportIsPreviewedWhole() {
        val report = DiagnosticReports.build(info, "2026-11-10 12:00:00.000 I/A [m] only\n", scrubber, "now")
        assertEquals(report.text.trimEnd(), report.preview(res).trimEnd())
    }

    @Test fun extraSectionsComeBeforeTheLog() {
        val report = DiagnosticReports.build(info, log, scrubber, "now", sections = listOf("Crash" to "java.lang.IllegalStateException: boom"))
        val crash = report.text.indexOf("== Crash ==")
        assertTrue(crash in 1 until report.text.indexOf("== Log"))
        assertTrue(report.text.contains("java.lang.IllegalStateException: boom"))
    }
}
