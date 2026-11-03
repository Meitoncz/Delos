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
import java.io.IOException

private const val BASE = "https://meta.test"

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

    @Test fun toolsThatEnableHdrExternallyAreNeverNativeHdr() {
        for (notes in listOf("Requires Special K.", "Use ReShade.", "Enable Auto HDR.", "Requires RTX HDR.",
            "Only works using a mod.", "Possible through [[Special K]].")) {
            assertEquals(notes, NativeHdr.UNKNOWN,
                PcGamingWikiClient.parseVideo("{{Video|hdr=true|hdr notes=$notes}}", "Game").support)
        }
        assertEquals(NativeHdr.SDR,
            PcGamingWikiClient.parseVideo("{{Video|hdr=hackable|hdr notes=Only available using Special K.}}", "Game").support)
    }

    @Test fun configFilesAndImprovementModsAreCaveatsNotRetractionsOfNativeHdr() {
        // Real PCGamingWiki notes of pages whose hdr value is true.
        for (notes in listOf(
            "Raised blacks. See [[Cronos:_The_New_Dawn#Modifications|Modifications]].",
            "Change the value bUseHDRDisplayOutput from false to true in GameUserSettings.ini",
            "RenoDX mod for fix broken black level and white point",
            "Max, Mid, and Min luminance sliders. Brightness, UI Brightness, and UI Contrast sliders. Can be greatly improved with Black Floor Fix mod.",
            "The free 4K HDR Video DLC adapts the video files to this option.",
            "Use the -forcenohdr [[Glossary:Command line arguments|command line argument]] to force HDR off.",
            "{{Term|HDR}} results in Photo Mode screenshots being saved in PNG format (which is always {{Term|SDR}}) but with {{Term|HDR}} saturation. This can only be fixed by manually tone-mapping them back to {{Term|SDR}} in photo editing software.<ref>{{Refcheck|user=Smileybarry|date=2025-05-10}}</ref><br />[[Store:Steam#Steam_Community_Overlay|Steam]] screenshots are unaffected.",
            "Peak brightness is automatically set to the Windows HDR display calibration data. The HDR image is very limited in brightness range with the default settings.",
            "Has options for scRGB or PQ HDR10. See the [[Glossary:High_dynamic_range_(HDR)#Enable_HDR_output_in_unsupported_games|HDR glossary page]] for solutions providing better results.",
        )) {
            assertEquals(notes, NativeHdr.HDR,
                PcGamingWikiClient.parseVideo("{{Video|hdr=true|hdr notes=$notes}}", "Game").support)
        }
    }

    @Test fun limitedIsNativeHdrWithCaveatsUnlessTheNoteSaysItDoesNotWork() {
        val noMansSky = "HDR400, HDR600, or HDR1000<ref>{{Refsnip|url=https://www.nomanssky.com/beyond-update/|title=BEYOND: INTRODUCING UPDATE 2.0|date=09 April 2020}}</ref> No black level slider."
        assertEquals(HdrEvidence(NativeHdr.HDR, "No Man's Sky", noMansSky),
            PcGamingWikiClient.parseVideo("{{Video\n|hdr = limited\n|hdr notes = $noMansSky\n|ray tracing = false\n}}", "No Man's Sky"))
        for (notes in listOf(
            "No in-game HDR options.{{note|SDR brightness slider remains available in HDR mode and seems to control \"paper white\" point to a degree.}} Doesn't seem to respect display peak luminance provided by either EDID or HDR calibration color profile but seems to tone map to 1300 nits peak.{{cn|date=2026-10-07|reason=}}",
            "SDR based stretching. Luma or RenoDX can be used to improve the native HDR implementation.",
            "Windows HDR, DX12 and fullscreen mode are required.",
            "Brightness slider and preview do not work properly in HDR. Color Grading is missing (compared to SDR).",
            "",
        )) {
            assertEquals(notes, NativeHdr.HDR,
                PcGamingWikiClient.parseVideo("{{Video|hdr=limited|hdr notes=$notes}}", "Game").support)
        }
        for (notes in listOf("Simulated HDR only.", "HDR disabled in recent patch due to technical issues reported.",
            "Added in Patch v.37.2 but currently disabled.", "HDR does not work on Nvidia GPUs. A black screen will occur.",
            "Only confirmed in the Mac App Store version.", "Broken as of 1.1 game update")) {
            assertEquals(notes, NativeHdr.UNKNOWN,
                PcGamingWikiClient.parseVideo("{{Video|hdr=limited|hdr notes=$notes}}", "Game").support)
        }
    }

    @Test fun platformEditionRestrictionsAndConflictingClaimsRemainUnknown() {
        for (notes in listOf("Supported on consoles only.", "Only available on Xbox Series X.",
            "Requires the enhanced edition.", "Supported in specific versions.", "Not supported natively.",
            "No native HDR.", "HDR output is broken.")) {
            assertEquals(notes, NativeHdr.UNKNOWN,
                PcGamingWikiClient.parseVideo("{{Video|hdr=true|hdr notes=$notes}}", "Game").support)
        }
    }

    @Test fun falseIsSdrButMissingAndAmbiguousAreNotGuessed() {
        assertEquals(NativeHdr.SDR, PcGamingWikiClient.parseVideo("{{Video|hdr=false}}", "Game").support)
        for (source in listOf("{{Video|hdr=unknown}}",
            "{{Video|hdr=}}", "{{Video|hdr=true", "{{Other|hdr=true}}", "<!-- {{Video|hdr=true}} -->")) {
            assertEquals(source, NativeHdr.UNKNOWN, PcGamingWikiClient.parseVideo(source, "Game").support)
        }
    }

    @Test fun hackableIsSdrEvenWithConfigurationOrNativeOutputNotes() {
        // Include the real PCGamingWiki configuration/mod notes used by the native-value fixtures above.
        for (notes in listOf(
            "Change the value bUseHDRDisplayOutput from false to true in GameUserSettings.ini",
            "RenoDX mod for fix broken black level and white point",
            "Supports native HDR output via Engine.ini.",
            "",
        )) {
            assertEquals(notes, NativeHdr.SDR,
                PcGamingWikiClient.parseVideo("{{Video|hdr=hackable|hdr notes=$notes}}", "Game").support)
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
        for (notes in listOf("Use [[Special K|this tool]].", "Enable HDR with {{file|ReShade.dll}}.",
            "Set it up through [[Glossary:Auto HDR|the Windows feature]].")) {
            assertEquals(notes, NativeHdr.UNKNOWN,
                PcGamingWikiClient.parseVideo("{{Video|hdr=true|hdr notes=$notes}}", "Game").support)
        }
    }

    @Test fun apiFailureIsNotAbsenceAndNeverPropagatesProviderEchoes() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("fixture").body("""{"error":{"info":"test-echo must never escape"}}""".toResponseBody()).build()
        }.build()
        try {
            PcGamingWikiClient(http, BASE, RequestPacer(0)).lookup(listOf(1245620), "Elden Ring")
            throw AssertionError("API failure was swallowed")
        } catch (error: PcGamingWikiException) {
            assertEquals("PCGamingWiki API request rejected", error.message)
            assertNull(error.cause)
        }
    }

    @Test fun steamLookupSendsOnlyTheIdsToTheProxyAndCachesGenuineAbsence() {
        val wiki = Wiki()
        assertEquals(HdrEvidence(NativeHdr.UNKNOWN), wiki.client.lookup(listOf(1245620), "Elden Ring"))
        assertEquals(HdrEvidence(NativeHdr.UNKNOWN), wiki.client.lookup(listOf(1245620), "Different title"))
        // Steam cargo, then the title steps (page cargo, redirect, search); the second lookup is cached.
        assertEquals(4, wiki.requests.size)
        val cargo = wiki.requests.first()
        assertEquals("GET", cargo.method)
        assertEquals("https://meta.test/v1/pcgw/cargo?steam=1245620", cargo.url.toString())
        assertNull(cargo.header("Cookie"))
        assertNull(cargo.body)
    }

    @Test fun aPageOfTheExactTitleWithoutSteamIdIsUsedWhenNoPageListsTheSteamId() {
        // Euro Truck Simulator 2's Cargo row has an empty Steam_AppID although the page is its own.
        val notes = "Added in 1.57<ref>{{Refurl|url=https://blog.scssoft.com/2025/11/euro-truck-simulator-2-157-update.html|title=Euro Truck Simulator 2: 1.57 Update Release|date=2025-11-22}}</ref>"
        val wiki = Wiki(titleRows = mapOf("Euro Truck Simulator 2" to ""),
            pages = mapOf("Euro Truck Simulator 2" to "{{Video\n|hdr = true\n|hdr notes = $notes\n|ray tracing = false\n}}"))
        assertEquals(HdrEvidence(NativeHdr.HDR, "Euro Truck Simulator 2", notes), wiki.client.lookup(listOf(227300), "Euro Truck Simulator 2"))
        assertEquals(listOf("steam=227300", "page=Euro Truck Simulator 2"), wiki.cargoSelectors())
    }

    @Test fun aPageOfTheSameTitleThatNamesAnotherSteamAppIsADifferentGame() {
        val wiki = Wiki(titleRows = mapOf("Prey" to "3830"), pages = mapOf("Prey" to "{{Video|hdr=false}}"))
        assertEquals(HdrEvidence(NativeHdr.UNKNOWN), wiki.client.lookup(listOf(480490), "Prey"))
        assertEquals(0, wiki.count("page"))
    }

    @Test fun aGameWithoutSteamIdFindsItsPageThroughTheWikisRedirectOfThatName() {
        // "Sea of Stars: Sunset Edition" is a redirect to "Sea of Stars" on the wiki.
        val wiki = Wiki(redirects = mapOf("Sea of Stars: Sunset Edition" to "Sea of Stars"),
            titleRows = mapOf("Sea of Stars" to "1244090,2385060, 3457510"), pages = mapOf("Sea of Stars" to "{{Video|hdr=false}}"))
        assertEquals(HdrEvidence(NativeHdr.SDR, "Sea of Stars"), wiki.client.lookup(emptyList(), "Sea of Stars: Sunset Edition"))
        assertEquals(listOf("page=Sea of Stars: Sunset Edition", "page=Sea of Stars"), wiki.cargoSelectors())
        assertEquals(HdrEvidence(NativeHdr.UNKNOWN), Wiki().client.lookup(emptyList(), "Kopi"))
    }

    @Test fun aWikiTitleThatDiffersOnlyByPunctuationIsTheSamePage() {
        // The wiki names it "The Witcher 3: Wild Hunt: Remastered" and redirects it to the base game page.
        val wiki = Wiki(
            searchHits = mapOf("The Witcher 3: Wild Hunt Remastered" to listOf("The Witcher 3: Wild Hunt", "The Witcher 3: Wild Hunt: Remastered")),
            redirects = mapOf("The Witcher 3: Wild Hunt: Remastered" to "The Witcher 3: Wild Hunt"),
            titleRows = mapOf("The Witcher 3: Wild Hunt" to "292030"),
            pages = mapOf("The Witcher 3: Wild Hunt" to "{{Video|hdr=true}}"),
        )
        assertEquals(HdrEvidence(NativeHdr.HDR, "The Witcher 3: Wild Hunt"), wiki.client.lookup(emptyList(), "The Witcher 3: Wild Hunt Remastered"))
        // A merely similar page (a different title) is never taken.
        val other = Wiki(searchHits = mapOf("Sea of Stars: Sunset Edition" to listOf("Sea of Stars", "Infinite Space III: Sea of Stars")),
            titleRows = mapOf("Sea of Stars" to "1244090"), pages = mapOf("Sea of Stars" to "{{Video|hdr=false}}"))
        assertEquals(HdrEvidence(NativeHdr.UNKNOWN), other.client.lookup(emptyList(), "Sea of Stars: Sunset Edition"))
    }

    @Test fun everySteamIdOfTheGameIsQueriedSoOneAmbiguousIdDoesNotForceATitleLookup() {
        val wiki = Wiki(rows = listOf("Game"), pages = mapOf("Game" to "{{Video|hdr=true}}"))
        wiki.client.lookup(listOf(20L, 10L, 10L), "Game")
        assertEquals(listOf("steam=10,20"), wiki.cargoSelectors())
    }

    @Test fun steamIdListsAreCappedAndOutOfRangeIdsAreDropped() {
        val wiki = Wiki()
        wiki.client.lookup((1L..25L).toList() + 5_000_000_000L, "")
        assertEquals(listOf("steam=" + (1..20).joinToString(",")), wiki.cargoSelectors())
    }

    @Test fun titlesNoWikiPageCanCarryNeverReachTheProxy() {
        val wiki = Wiki()
        for (title in listOf("Game [Remastered]", "Game #1", "A|B", "{{x}}", "a".repeat(201))) {
            assertEquals(HdrEvidence(NativeHdr.UNKNOWN), wiki.client.lookup(emptyList(), title))
        }
        assertEquals(0, wiki.requests.size)
        // Control characters are only whitespace to the wiki and are cleaned instead of rejected.
        wiki.client.lookup(emptyList(), "Two\nLines")
        assertEquals(listOf("page=Two Lines"), wiki.cargoSelectors())
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
        assertEquals(0, wiki.count("page"))
    }

    @Test fun proxyFailuresAreProviderFailuresAreNeverCachedAndNeverEchoTheBody() {
        for (code in listOf(429, 503, 502, 500)) {
            val wiki = Wiki(status = code)
            repeat(2) {
                val error = assertThrows(PcGamingWikiException::class.java) { wiki.client.lookup(listOf(1), "Game") }
                assertEquals("PCGamingWiki HTTP $code", error.message)
                assertNull(error.cause)
            }
            // Each lookup reached the proxy again: a failure is neither remembered as absence nor latched.
            assertEquals(2, wiki.requests.size)
        }
    }

    @Test fun malformedJsonAndNetworkErrorsAreProviderFailures() {
        for (body in listOf("", "not json", "[]", """{"cargoquery":"x"}""")) {
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody()).build()
            }.build()
            assertThrows(PcGamingWikiException::class.java) { PcGamingWikiClient(http, BASE, RequestPacer(0)).lookup(listOf(1), "Game") }
        }
        val offline = OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build()
        assertThrows(IOException::class.java) { PcGamingWikiClient(offline, BASE, RequestPacer(0)).lookup(listOf(1), "Game") }
    }

    @Test fun redirectsAreNotFollowedAndAMissingServiceUrlSendsNothing() {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(307).message("fixture")
                .header("Location", "https://evil.test/").body("".toResponseBody()).build()
        }.build()
        val error = assertThrows(PcGamingWikiException::class.java) { PcGamingWikiClient(http, BASE, RequestPacer(0)).lookup(listOf(1), "Game") }
        assertEquals("PCGamingWiki HTTP 307", error.message)
        assertEquals(1, calls)
        for (base in listOf("", "http://meta.test")) {
            val missing = assertThrows(PcGamingWikiException::class.java) { PcGamingWikiClient(http, base, RequestPacer(0)).lookup(listOf(1), "Game") }
            assertEquals("PCGamingWiki metadata service is not configured", missing.message)
        }
        assertEquals(1, calls)
    }

    /** A fake proxy behind an interceptor, answering what the Worker's PCGW endpoints answer; nothing touches the network or sleeps. */
    private class Wiki(
        private val rows: List<String> = emptyList(),
        private val pages: Map<String, String> = emptyMap(),
        /** Page title to its Cargo Steam_AppID text, answered only to exact page-name queries. */
        private val titleRows: Map<String, String> = emptyMap(),
        /** Redirect page name to its target, answered to redirect-resolving title queries. */
        private val redirects: Map<String, String> = emptyMap(),
        /** Page-name search query to the titles the wiki search lists. */
        private val searchHits: Map<String, List<String>> = emptyMap(),
        private val status: Int = 200,
    ) {
        val requests = mutableListOf<Request>()
        private val gson = Gson()

        fun count(endpoint: String) = requests.count { it.url.encodedPath == "/v1/pcgw/$endpoint" }

        /** The Cargo requests as `steam=<ids>` or `page=<title>`. */
        fun cargoSelectors() = requests.filter { it.url.encodedPath == "/v1/pcgw/cargo" }.map {
            val name = it.url.queryParameterName(0)
            "$name=${it.url.queryParameterValue(0)}"
        }

        val client = PcGamingWikiClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            assertEquals("GET", request.method)
            assertEquals(1, request.url.querySize)
            val body = when (request.url.encodedPath) {
                "/v1/pcgw/search" -> {
                    val hits = searchHits[request.url.queryParameter("q")].orEmpty()
                    "{\"query\":{\"search\":[" + hits.joinToString(",") { "{\"title\":${gson.toJson(it)}}" } + "]}}"
                }
                "/v1/pcgw/redirect" -> {
                    val name = request.url.queryParameter("title")!!
                    val target = redirects[name]
                    if (target == null) "{\"query\":{\"pages\":{}}}"
                    else "{\"query\":{\"redirects\":[{\"from\":${gson.toJson(name)},\"to\":${gson.toJson(target)}}]}}"
                }
                "/v1/pcgw/cargo" -> {
                    val page = request.url.queryParameter("page")
                    val found = if (page != null) {
                        titleRows.filter { it.key == page }.map { it.key to it.value }
                    } else rows.map { it to "" }
                    "{\"cargoquery\":[" + found.joinToString(",") { (name, steam) ->
                        "{\"title\":{\"Page\":" + gson.toJson(name) + ",\"Steam\":" + gson.toJson(steam) + "}}"
                    } + "]}"
                }
                "/v1/pcgw/page" -> "{\"parse\":{\"wikitext\":{\"*\":" + gson.toJson(pages.getValue(request.url.queryParameter("title")!!)) + "}}}"
                else -> throw AssertionError("unexpected path ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                .message("fixture").body((if (status == 200) body else "{\"error\":\"test-echo\"}").toResponseBody()).build()
        }.build(), BASE, RequestPacer(0))
    }
}
