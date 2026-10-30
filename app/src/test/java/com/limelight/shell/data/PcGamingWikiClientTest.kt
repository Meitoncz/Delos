package com.limelight.shell.data

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PcGamingWikiClientTest {
    @Test fun actualVideoFieldsDistinguishNativeHdrFromUnrelatedModifications() {
        val result = PcGamingWikiClient.parseVideo("""
            {{Video
            |ultrawidescreen = hackable
            |ultrawidescreen notes = See [[#Modifications|Modifications]].
            |hdr = true
            |hdr notes =
            |ray tracing = true
            }}
            ==Modifications==
            Use ReShade or a mod to change other video features.
        """.trimIndent(), "Elden Ring")
        assertEquals(HdrEvidence(NativeHdr.HDR, "Elden Ring"), result)
    }

    @Test fun nestedReferencesAndLinksDoNotTruncateHdrNotes() {
        val notes = "Enable Windows HDR.<ref>{{Refurl|url=https://example.test/?a=b|title=Test}}</ref> Use [[Settings|the in-game toggle]]."
        assertEquals(HdrEvidence(NativeHdr.HDR, "Game", notes),
            PcGamingWikiClient.parseVideo("{{Video|hdr=always on|hdr notes=$notes|vsync=true}}", "Game"))
    }

    @Test fun externallyEnabledHdrIsNeverNativeHdr() {
        for (notes in listOf("Requires Special K.", "Use ReShade.", "Enable Auto HDR.",
            "Requires RTX HDR.", "Edit the configuration file.", "Change the registry.",
            "Edit Game.ini.", "Enable HDR by editing settings.json.", "Change display.cfg.",
            "Copy hdr.dll into the game folder.", "See [[#Modifications|Modifications]].")) {
            assertEquals(notes, NativeHdr.UNKNOWN,
                PcGamingWikiClient.parseVideo("{{Video|hdr=true|hdr notes=$notes}}", "Game").support)
        }
        assertEquals(NativeHdr.SDR,
            PcGamingWikiClient.parseVideo("{{Video|hdr=hackable|hdr notes=Only available using Special K.}}", "Game").support)
    }

    @Test fun platformEditionRestrictionsAndConflictingClaimsRemainUnknown() {
        for (notes in listOf("Supported on consoles only.", "Only available on Xbox Series X.",
            "Requires the enhanced edition.", "Supported in specific versions.", "Not supported natively.",
            "No native HDR.", "HDR output is broken.")) {
            assertEquals(notes, NativeHdr.UNKNOWN,
                PcGamingWikiClient.parseVideo("{{Video|hdr=true|hdr notes=$notes}}", "Game").support)
        }
    }

    @Test fun falseIsSdrButLimitedMissingAndAmbiguousAreNotGuessed() {
        assertEquals(NativeHdr.SDR, PcGamingWikiClient.parseVideo("{{Video|hdr=false}}", "Game").support)
        for (source in listOf("{{Video|hdr=limited}}", "{{Video|hdr=unknown}}", "{{Video|hdr=hackable}}",
            "{{Video|hdr=}}", "{{Video|hdr=true", "{{Other|hdr=true}}", "<!-- {{Video|hdr=true}} -->")) {
            assertEquals(source, NativeHdr.UNKNOWN, PcGamingWikiClient.parseVideo(source, "Game").support)
        }
    }

    @Test fun markupAndOrdinaryProseInHdrNotesDoNotHideNativeHdr() {
        for (notes in listOf(
            "Enable HDR in the video settings.<ref>{{Refurl|url=https://example.test/hdr-settings.txt|title=Download the HDR file|date=2024}}</ref>",
            "Toggle HDR.<ref name=a>[https://example.test/page.xml Version 2 download]</ref>",
            "HDR is only supported on HDR10 displays.",
            "Version 1.2 added HDR support.",
            "Use the HDR switch in the display options.",
            "See [https://example.test/readme.txt the manual].",
            "Works with [[HDR10]] displays.<ref name=\"x\" />",
            "Enable HDR.<ref>Reference | with a pipe, only for the file</ref> Use the in-game toggle.",
        )) {
            assertEquals(notes, NativeHdr.HDR,
                PcGamingWikiClient.parseVideo("{{Video|hdr=true|hdr notes=$notes|vsync=true}}", "Game").support)
        }
    }

    @Test fun linkTargetsAndTemplateArgumentsStillCountAsRetrofitEvidence() {
        for (notes in listOf("Use [[Special K|this tool]].", "Enable HDR in {{file|settings.ini}}.",
            "Set it in the [[Glossary:Registry|settings]].")) {
            assertEquals(notes, NativeHdr.UNKNOWN,
                PcGamingWikiClient.parseVideo("{{Video|hdr=true|hdr notes=$notes}}", "Game").support)
        }
    }

    @Test fun apiFailureIsNotAbsenceAndNeverPropagatesProviderEchoes() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("fixture").body("""{"error":{"info":"test-password must never escape"}}""".toResponseBody()).build()
        }.build()
        try {
            PcGamingWikiClient(http, "test-user", "test-password", RequestPacer(0)).lookup(listOf(1245620), "Elden Ring")
            throw AssertionError("API failure was swallowed")
        } catch (error: PcGamingWikiException) {
            assertEquals("PCGamingWiki API request rejected", error.message)
            assertNull(error.cause)
        }
    }

    @Test fun steamLookupUsesExactCargoJoinAndCachesGenuineAbsenceWithoutTitleFallback() {
        val wiki = Wiki()
        assertEquals(HdrEvidence(NativeHdr.UNKNOWN), wiki.client.lookup(listOf(1245620), "Elden Ring"))
        assertEquals(HdrEvidence(NativeHdr.UNKNOWN), wiki.client.lookup(listOf(1245620), "Different title"))
        assertEquals(3, wiki.requests.size)
        val cargo = wiki.requests.single { it.url.queryParameter("action") == "cargoquery" }
        assertEquals("Game,Video", cargo.url.queryParameter("tables"))
        assertEquals("Game._pageID=Video._pageID", cargo.url.queryParameter("join_on"))
        assertEquals("Game.Steam_AppID HOLDS \"1245620\"", cargo.url.queryParameter("where"))
    }

    @Test fun everySteamIdOfTheGameIsQueriedSoOneAmbiguousIdDoesNotForceATitleLookup() {
        val wiki = Wiki()
        wiki.client.lookup(listOf(20L, 10L, 10L), "Game")
        val cargo = wiki.requests.single { it.url.queryParameter("action") == "cargoquery" }
        assertEquals("Game.Steam_AppID HOLDS \"10\" OR Game.Steam_AppID HOLDS \"20\"", cargo.url.queryParameter("where"))
    }

    @Test fun editionPagesCountOnlyWhenTheyAgreeOnADefiniteAnswer() {
        fun video(hdr: String) = "{{Video|hdr=$hdr}}"
        val agreeing = Wiki(rows = listOf("Game", "Game (Deluxe)"),
            pages = mapOf("Game" to video("true"), "Game (Deluxe)" to video("true")))
        assertEquals(HdrEvidence(NativeHdr.HDR, "Game"), agreeing.client.lookup(listOf(1), "Game"))
        val conflicting = Wiki(rows = listOf("Game", "Game (Deluxe)"),
            pages = mapOf("Game" to video("true"), "Game (Deluxe)" to video("false")))
        assertEquals(HdrEvidence(NativeHdr.UNKNOWN), conflicting.client.lookup(listOf(1), "Game"))
        val unknown = Wiki(rows = listOf("Game", "Game (Deluxe)"),
            pages = mapOf("Game" to video("true"), "Game (Deluxe)" to video("unknown")))
        assertEquals(HdrEvidence(NativeHdr.UNKNOWN), unknown.client.lookup(listOf(1), "Game"))
    }

    @Test fun aLongListOfMatchingPagesIsAmbiguousAndIsNotFetchedPageByPage() {
        val names = (1..8).map { "Game $it" }
        val wiki = Wiki(rows = names, pages = names.associateWith { "{{Video|hdr=true}}" })
        assertEquals(HdrEvidence(NativeHdr.UNKNOWN), wiki.client.lookup(listOf(1), "Game"))
        assertEquals(0, wiki.requests.count { it.url.queryParameter("action") == "parse" })
    }

    @Test fun refusedCredentialsAreLatchedButThrottlingIsRetried() {
        val refused = Wiki(loginResult = "Failed")
        assertThrows(PcGamingWikiException::class.java) { refused.client.lookup(listOf(1), "Game") }
        val afterFirst = refused.requests.size
        assertThrows(PcGamingWikiException::class.java) { refused.client.lookup(listOf(2), "Other") }
        assertEquals(afterFirst, refused.requests.size)

        val throttled = Wiki(loginResult = "Throttled")
        assertThrows(PcGamingWikiException::class.java) { throttled.client.lookup(listOf(1), "Game") }
        val throttledAfterFirst = throttled.requests.size
        assertThrows(PcGamingWikiException::class.java) { throttled.client.lookup(listOf(2), "Other") }
        assertEquals(throttledAfterFirst * 2, throttled.requests.size)
    }

    /** A fake wiki behind an interceptor; nothing here touches the network or sleeps. */
    private class Wiki(
        private val rows: List<String> = emptyList(),
        private val pages: Map<String, String> = emptyMap(),
        private val loginResult: String = "Success",
    ) {
        val requests = mutableListOf<Request>()
        private val gson = Gson()
        val client = PcGamingWikiClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val body = when (request.url.queryParameter("action")) {
                "query" -> "{\"query\":{\"tokens\":{\"logintoken\":\"test-login-token\"}}}"
                "cargoquery" -> "{\"cargoquery\":[" + rows.joinToString(",") { "{\"title\":{\"Page\":" + gson.toJson(it) + "}}" } + "]}"
                "parse" -> "{\"parse\":{\"wikitext\":{\"*\":" + gson.toJson(pages.getValue(request.url.queryParameter("page")!!)) + "}}}"
                else -> {
                    assertEquals("POST", request.method)
                    "{\"login\":{\"result\":\"$loginResult\"}}"
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("fixture").body(body.toResponseBody()).build()
        }.build(), "test-user", "test-password", RequestPacer(0))
    }
}
