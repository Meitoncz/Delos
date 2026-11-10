package com.limelight.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class IssueUrlTest {
    private val draft = IssueDraft(
        title = "Crash: IllegalStateException: boom",
        whatHappened = "Delos stopped unexpectedly the last time.\nIt was reported from the app.",
        device = "AYN Odin2 Portal, Android 13",
        build = "0.1.0 (release)",
        streamSettings = "hevc, 1920x1080, 60 fps, HDR off",
        logs = "java.lang.IllegalStateException: boom\n\tat com.limelight.Foo.bar(Foo.kt:1)",
        area = "Other",
    )

    private fun params(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        k to URLDecoder.decode(v, "UTF-8")
    }

    @Test fun theAddressOpensTheBugFormOfTheRepositoryWithTheFromAppLabel() {
        val url = IssueUrl.build(draft)
        assertTrue(url, url.startsWith("https://github.com/Meitoncz/Delos/issues/new?template=bug_report.yml&labels=from-app&"))
        val p = params(url)
        assertEquals("from-app", p["labels"])
        assertEquals("bug_report.yml", p["template"])
    }

    @Test fun everyFieldUsesTheIdOfTheFormAndSurvivesTheEscaping() {
        val p = params(IssueUrl.build(draft))
        assertEquals(draft.title, p["title"])
        assertEquals(draft.whatHappened, p["what-happened"])
        assertEquals("Other", p["area"])
        assertEquals(draft.device, p["device"])
        assertEquals(draft.build, p["build"])
        assertEquals(draft.streamSettings, p["stream-settings"])
        assertEquals(draft.logs, p["logs"])
    }

    @Test fun reservedCharactersNeverEndUpRawInAValue() {
        val url = IssueUrl.build(draft.copy(whatHappened = "a&b=c#d?e f+g%h\n\"q\" <x> é"))
        val query = url.substringAfter('?')
        assertEquals("nine parameters, so eight separators and no raw & inside a value", 8, query.count { it == '&' })
        assertFalse(query.contains(' ') || query.contains('#') || query.contains('<') || query.contains('"') || query.contains('\n'))
        assertEquals("a&b=c#d?e f+g%h\n\"q\" <x> é", params(url)["what-happened"])
    }

    @Test fun aHugeStackTraceIsCutToTheLimitAndSaysSo() {
        val trace = (1..2000).joinToString("\n") { "\tat com.limelight.Frame$it.run(Frame.kt:$it)" }
        val url = IssueUrl.build(draft.copy(logs = trace))
        assertTrue("${url.length}", url.length <= IssueUrl.MAX_LENGTH)
        val logs = params(url)["logs"]!!
        assertTrue(logs.startsWith("\tat com.limelight.Frame1.run"))
        assertTrue(logs.endsWith("the full log is in the attached file)"))
        assertEquals(draft.whatHappened, params(url)["what-happened"])
    }

    @Test fun aSmallerLimitStillHoldsAndNeverCutsInsideAnEscape() {
        val url = IssueUrl.build(draft.copy(logs = "é".repeat(5000)), maxLength = 900)
        assertTrue(url.length <= 900)
        assertFalse(Regex("%[0-9A-F]?$").containsMatchIn(url))
        params(url)
    }

    @Test fun everyFreeTextFieldPassesTheScrubber() {
        val scrubber = LogScrubber(ScrubContext(pcNames = listOf("Meiton-PC")))
        val dirty = draft.copy(
            title = "Stream to Meiton-PC failed", whatHappened = "Could not reach Meiton-PC at 192.168.0.200",
            device = "Phone of Meiton-PC owner", streamSettings = "host 192.168.0.200", logs = "poll Meiton-PC (192.168.0.200:47989)",
        )
        val url = IssueUrl.build(dirty, scrub = scrubber::scrub)
        val text = params(url).values.joinToString("\n")
        assertFalse(text, text.contains("Meiton-PC") || text.contains("192.168.0.200"))
        assertTrue(text.contains("<pc-1>"))
    }

    @Test fun theAreaIsLeftOutWhenUnset() {
        assertFalse(IssueUrl.build(draft.copy(area = null)).contains("&area="))
    }

    @Test fun theTitleIsCapped() {
        assertEquals(120, params(IssueUrl.build(draft.copy(title = "x".repeat(500))))["title"]!!.length)
    }
}
