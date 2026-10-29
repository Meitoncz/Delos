package com.limelight.shell.data

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun apiFailureIsNotAbsenceAndNeverPropagatesProviderEchoes() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("fixture").body("""{"error":{"info":"test-password must never escape"}}""".toResponseBody()).build()
        }.build()
        try {
            PcGamingWikiClient(http, "test-user", "test-password").lookup(1245620, "Elden Ring")
            throw AssertionError("API failure was swallowed")
        } catch (error: PcGamingWikiException) {
            assertEquals("PCGamingWiki API request rejected", error.message)
            assertNull(error.cause)
        }
    }
    @Test fun steamLookupUsesExactCargoJoinAndCachesGenuineAbsenceWithoutTitleFallback() {
        val requests = mutableListOf<okhttp3.Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val response = when (request.url.queryParameter("action")) {
                "query" -> """{"query":{"tokens":{"logintoken":"test-login-token"}}}"""
                "cargoquery" -> {
                    assertEquals("Game,Video", request.url.queryParameter("tables"))
                    assertEquals("Game._pageID=Video._pageID", request.url.queryParameter("join_on"))
                    assertEquals("Game.Steam_AppID HOLDS \"1245620\"", request.url.queryParameter("where"))
                    """{"cargoquery":[]}"""
                }
                else -> {
                    assertEquals("POST", request.method)
                    """{"login":{"result":"Success"}}"""
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("fixture").body(response.toResponseBody()).build()
        }.build()
        val client = PcGamingWikiClient(http, "test-user", "test-password")
        assertEquals(HdrEvidence(NativeHdr.UNKNOWN), client.lookup(1245620, "Elden Ring"))
        assertEquals(HdrEvidence(NativeHdr.UNKNOWN), client.lookup(1245620, "Different title"))
        assertEquals(3, requests.size)
    }

}
