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

    @Test fun steamLookupUsesExactCargoJoinAndCachesGenuineAbsence() {
        val wiki = Wiki()
        assertEquals(HdrEvidence(NativeHdr.UNKNOWN), wiki.client.lookup(listOf(1245620), "Elden Ring"))
        assertEquals(HdrEvidence(NativeHdr.UNKNOWN), wiki.client.lookup(listOf(1245620), "Different title"))
        assertEquals(6, wiki.requests.size)
        val cargo = wiki.requests.first { it.url.queryParameter("action") == "cargoquery" }
        assertEquals("Game,Video", cargo.url.queryParameter("tables"))
        assertEquals("Game._pageID=Video._pageID", cargo.url.queryParameter("join_on"))
        assertEquals("Game.Steam_AppID HOLDS \"1245620\"", cargo.url.queryParameter("where"))
    }

    @Test fun aPageOfTheExactTitleWithoutSteamIdIsUsedWhenNoPageListsTheSteamId() {
        // Euro Truck Simulator 2's Cargo row has an empty Steam_AppID although the page is its own.
        val notes = "Added in 1.57<ref>{{Refurl|url=https://blog.scssoft.com/2025/11/euro-truck-simulator-2-157-update.html|title=Euro Truck Simulator 2: 1.57 Update Release|date=2025-11-22}}</ref>"
        val wiki = Wiki(titleRows = mapOf("Euro Truck Simulator 2" to ""),
            pages = mapOf("Euro Truck Simulator 2" to "{{Video\n|hdr = true\n|hdr notes = $notes\n|ray tracing = false\n}}"))
        assertEquals(HdrEvidence(NativeHdr.HDR, "Euro Truck Simulator 2", notes), wiki.client.lookup(listOf(227300), "Euro Truck Simulator 2"))
        val wheres = wiki.requests.filter { it.url.queryParameter("action") == "cargoquery" }.map { it.url.queryParameter("where") }
        assertEquals(listOf("Game.Steam_AppID HOLDS \"227300\"", "Game._pageName = \"Euro Truck Simulator 2\""), wheres)
    }

    @Test fun aPageOfTheSameTitleThatNamesAnotherSteamAppIsADifferentGame() {
        val wiki = Wiki(titleRows = mapOf("Prey" to "3830"), pages = mapOf("Prey" to "{{Video|hdr=false}}"))
        assertEquals(HdrEvidence(NativeHdr.UNKNOWN), wiki.client.lookup(listOf(480490), "Prey"))
        assertEquals(0, wiki.requests.count { it.url.queryParameter("action") == "parse" })
    }

    @Test fun aGameWithoutSteamIdFindsItsPageThroughTheWikisRedirectOfThatName() {
        // "Sea of Stars: Sunset Edition" is a redirect to "Sea of Stars" on the wiki.
        val wiki = Wiki(redirects = mapOf("Sea of Stars: Sunset Edition" to "Sea of Stars"),
            titleRows = mapOf("Sea of Stars" to "1244090,2385060, 3457510"), pages = mapOf("Sea of Stars" to "{{Video|hdr=false}}"))
        assertEquals(HdrEvidence(NativeHdr.SDR, "Sea of Stars"), wiki.client.lookup(emptyList(), "Sea of Stars: Sunset Edition"))
        val wheres = wiki.requests.filter { it.url.queryParameter("action") == "cargoquery" }.map { it.url.queryParameter("where") }
        assertEquals(listOf("Game._pageName = \"Sea of Stars: Sunset Edition\"", "Game._pageName = \"Sea of Stars\""), wheres)
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
        /** Page title to its Cargo Steam_AppID text, answered only to exact page-name queries. */
        private val titleRows: Map<String, String> = emptyMap(),
        /** Redirect page name to its target, answered to redirect-resolving title queries. */
        private val redirects: Map<String, String> = emptyMap(),
        /** Page-name search query to the titles the wiki search lists. */
        private val searchHits: Map<String, List<String>> = emptyMap(),
    ) {
        val requests = mutableListOf<Request>()
        private val gson = Gson()
        val client = PcGamingWikiClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val body = when (request.url.queryParameter("action")) {
                "query" -> if (request.url.queryParameter("list") == "search") {
                    val hits = searchHits[request.url.queryParameter("srsearch")].orEmpty()
                    "{\"query\":{\"search\":[" + hits.joinToString(",") { "{\"title\":${gson.toJson(it)}}" } + "]}}"
                } else request.url.queryParameter("titles")?.let { name ->
                    val target = redirects[name]
                    if (target == null) "{\"query\":{\"pages\":{}}}"
                    else "{\"query\":{\"redirects\":[{\"from\":${gson.toJson(name)},\"to\":${gson.toJson(target)}}]}}"
                } ?: "{\"query\":{\"tokens\":{\"logintoken\":\"test-login-token\"}}}"
                "cargoquery" -> {
                    val where = request.url.queryParameter("where")!!
                    val found = if (where.startsWith("Game._pageName")) {
                        titleRows.filter { where == "Game._pageName = \"${it.key}\"" }.map { it.key to it.value }
                    } else rows.map { it to "" }
                    "{\"cargoquery\":[" + found.joinToString(",") { (page, steam) ->
                        "{\"title\":{\"Page\":" + gson.toJson(page) + ",\"Steam\":" + gson.toJson(steam) + "}}"
                    } + "]}"
                }
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
