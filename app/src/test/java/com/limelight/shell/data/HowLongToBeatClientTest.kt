package com.limelight.shell.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** All requests terminate in an interceptor; these tests never contact the real website. */
class HowLongToBeatClientTest {
    @Test fun headlineCombinedIsUsedNotMainStoryOrDetailedAverage() {
        val fixture = Fixture(mapOf(2127L to detail(2127, "Cyberpunk 2077", 1091500,
            "\"comp_all\":237341,\"comp_all_count\":10805,\"comp_main\":94177,\"comp_all_avg\":253582")),
            listOf(search(1, 1, 2127L to "Cyberpunk 2077")))
        val result = fixture.client.lookup("Cyberpunk 2077", listOf(1091500L))
        assertEquals(CompletionTime(2127, "Cyberpunk 2077", 237341, "https://howlongtobeat.com/game/2127", "66 Hours"), result)
        assertEquals(listOf("/game/2127"), fixture.requests.map { it.url.encodedPath }.filter { it.startsWith("/game/") })
    }

    @Test fun fractionalHeadlineKeepsTheWebsiteValueInsteadOfRoundingToWholeHours() {
        val fixture = Fixture(mapOf(86015L to detail(86015, "Alba: A Wildlife Adventure", 1337010,
            "\"comp_all\":12772,\"comp_all_count\":100", headline = "3½ Hours")),
            listOf(search(1, 1, 86015L to "Alba: A Wildlife Adventure")))
        val result = fixture.client.lookup("Alba: A Wildlife Adventure", listOf(1337010L))
        assertEquals(12772L, result?.seconds)
        assertEquals("3½ Hours", result?.headline)
    }

    @Test fun pageWithoutTheHeadlineBannerStillYieldsTheCombinedTimeInTheWebsitesRounding() {
        // Co-op titles such as Overcooked! All You Can Eat render only the per-style table next to a valid comp_all.
        for ((seconds, headline) in listOf(12772L to "3½ Hours", 80_713L to "22½ Hours", 469_657L to "130 Hours")) {
            val page = detail(1, "Game", 7, "\"comp_all\":$seconds,\"comp_all_count\":100").replace("<h4>HowLongToBeat</h4><h5>66 Hours</h5>", "")
            val fixture = Fixture(mapOf(1L to page), listOf(search(1, 1, 1L to "Game")))
            val result = fixture.client.lookup("Game", listOf(7L))
            assertEquals(seconds, result?.seconds)
            assertEquals(headline, result?.headline)
        }
    }

    @Test fun withoutSteamIdAnEditionTitleFindsTheOnePlainGamePageButExactNamesWinAndTiesAreRefused() {
        fun lookup(title: String, vararg entries: Pair<Long, String>) = Fixture(
            entries.associate { (id, name) -> id to detail(id, name, 1244090, "\"comp_all\":${id * 1000},\"comp_all_count\":100") },
            listOf(search(1, 1, *entries)),
        ).client.lookup(title, emptyList())?.id
        assertEquals(1L, lookup("Sea of Stars: Sunset Edition", 1L to "Sea of Stars", 3L to "Sea of Stars: Definitive Edition"))
        assertEquals(2L, lookup("Sea of Stars: Sunset Edition", 1L to "Sea of Stars", 2L to "Sea of Stars: Sunset Edition"))
        assertNull(lookup("Game: Deluxe Edition", 1L to "Game", 2L to "Game"))
        assertNull(lookup("Game: Deluxe Edition", 1L to "Other"))
        // The reverse is never safe: a plain title does not take an edition entry's time.
        assertNull(lookup("Sea of Stars", 3L to "Sea of Stars: Definitive Edition"))
    }

    @Test fun interruptedSearchDoesNotFetchMoreProviderPages() {
        val paths = mutableListOf<String>()
        val client = HowLongToBeatClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            paths += request.url.encodedPath
            Thread.currentThread().interrupt()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body("<script src='/_next/static/chunks/search.js'></script>".toResponseBody()).build()
        }.build(), RequestPacer(0), System::nanoTime)
        try {
            assertThrows(InterruptedIOException::class.java) { client.lookup("Game", emptyList()) }
            assertEquals(listOf("/"), paths)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun noTimeAndMultiplayerNeverSubstituteMainOrInvestedTimes() {
        for (stats in listOf(
            "\"comp_all\":0,\"comp_all_count\":0,\"comp_main\":3600,\"comp_all_avg\":7200",
            "\"comp_all\":3600,\"comp_all_count\":0",
            "\"comp_all\":null,\"comp_all_count\":null",
            "\"comp_all\":0,\"comp_all_count\":0,\"comp_lvl_sp\":0,\"comp_lvl_mp\":1,\"invested_mp\":999999",
        )) {
            val fixture = Fixture(mapOf(1L to detail(1, "Game", 0, stats)), listOf(search(1, 1, 1L to "Game")))
            assertNull(fixture.client.lookup("Game", emptyList()))
        }
    }

    @Test fun realSearchFlowPostsTermsThenVerifiesSteamOnDetail() {
        val fixture = Fixture(
            mapOf(2127L to detail(2127, "Cyberpunk 2077", 1091500), 2L to detail(2, "Cyberpunk 2077: Ultimate Edition", 0)),
            listOf(search(1, 1, 2127L to "Cyberpunk 2077", 2L to "Cyberpunk 2077: Ultimate Edition")),
        )
        val result = fixture.client.lookup("Cyberpunk 2077", listOf(1091500L))
        assertEquals(2127L, result?.id)
        assertEquals(237341L, result?.seconds)
        val post = fixture.requests.single { it.method == "POST" }
        assertEquals("/api/search/site", post.url.encodedPath)
        assertEquals("fixture-token", post.header("x-auth-token"))
        assertEquals("https://howlongtobeat.com/", post.header("Referer"))
        val buffer = Buffer()
        post.body!!.writeTo(buffer)
        val payload = JsonParser.parseString(buffer.readUtf8()).asJsonObject
        assertEquals(listOf("Cyberpunk", "2077"), payload.getAsJsonArray("searchTerms").map { it.asString })
        assertEquals("games", payload.get("searchType").asString)
        assertFalse(fixture.requests.any { it.url.queryParameter("q") != null })
        assertTrue(fixture.requests.single { it.url.encodedPath.endsWith("/init") }.url.queryParameter("t")!!.toLong() > 0)
    }

    @Test fun exactTitleCannotOverrideConflictingSteamIdentity() {
        val fixture = Fixture(mapOf(1L to detail(1, "Game", 222)), listOf(search(1, 1, 1L to "Game")))
        assertNull(fixture.client.lookup("Game", listOf(111L)))
    }

    @Test fun steamIdentityCanResolveDifferentPunctuationOrProviderTitle() {
        val fixture = Fixture(mapOf(1L to detail(1, "Provider's Official Title", 111)),
            listOf(search(1, 1, 1L to "Provider's Official Title")))
        assertEquals(1L, fixture.client.lookup("Local title", listOf(111L))?.id)
    }

    @Test fun anyOfSeveralSteamIdsMayVerifyTheMatch() {
        val fixture = Fixture(mapOf(1L to detail(1, "Game", 222)), listOf(search(1, 1, 1L to "Game")))
        assertEquals(1L, fixture.client.lookup("Game", listOf(111L, 222L))?.id)
    }

    @Test fun alternativeSteamIdentityIsVerifiedFromDetails() {
        val page = detail(1, "Game", 222).replace("\"profile_steam\":222", "\"profile_steam\":222,\"profile_steam_alt\":111")
        val fixture = Fixture(mapOf(1L to page), listOf(search(1, 1, 1L to "Game")))
        assertEquals(1L, fixture.client.lookup("Game", listOf(111L))?.id)
    }

    @Test fun exactNormalizedTitleFallbackOnlyWhenSteamIdentityIsUnavailable() {
        val fixture = Fixture(mapOf(1L to detail(1, "The Game: Subtitle", 0)),
            listOf(search(1, 1, 1L to "The Game: Subtitle")))
        assertEquals(1L, fixture.client.lookup(" THE GAME™ — Subtitle® ", emptyList())?.id)
    }

    @Test fun fuzzyOrEditionStrippedTitleIsNotASafeFallback() {
        val fixture = Fixture(mapOf(1L to detail(1, "Game: Definitive Edition", 0)),
            listOf(search(1, 1, 1L to "Game: Definitive Edition")))
        assertNull(fixture.client.lookup("Game", emptyList()))
        assertFalse(fixture.requests.any { it.url.encodedPath == "/game/1" })
    }

    @Test fun duplicateExactTitlesAreAmbiguousEvenWithoutSteam() {
        val fixture = Fixture(mapOf(1L to detail(1, "Game", 0), 2L to detail(2, "Game", 0)),
            listOf(search(1, 1, 1L to "Game", 2L to "Game")))
        assertNull(fixture.client.lookup("Game", emptyList()))
    }

    @Test fun multipleEditionsSharingSteamRemainAmbiguousAcrossSearchPages() {
        val fixture = Fixture(mapOf(1L to detail(1, "Game", 111), 2L to detail(2, "Game: Ultimate Edition", 111)),
            listOf(search(1, 2, 1L to "Game"), search(2, 2, 2L to "Game: Ultimate Edition")))
        assertNull(fixture.client.lookup("Game", listOf(111L)))
        assertEquals(2, fixture.requests.count { it.method == "POST" })
    }

    @Test fun emptySearchAndMissingDetailAreGenuinelyUnavailable() {
        assertNull(Fixture(emptyMap(), listOf(search(1, 0))).client.lookup("No Match", emptyList()))
        assertNull(Fixture(emptyMap(), listOf(search(1, 1, 999L to "Missing"))).client.lookup("Missing", emptyList()))
        val emptyPage = "<script id='__NEXT_DATA__'>{\"props\":{\"pageProps\":{\"game\":{\"data\":{\"game\":[]}}}}}</script>"
        assertNull(Fixture(mapOf(1L to emptyPage), listOf(search(1, 1, 1L to "Game"))).client.lookup("Game", emptyList()))
    }

    @Test fun malformedChangedOrWrongIdentityDetailIsAnErrorNotMissingTime() {
        val valid = detail(1, "Game", 111)
        for (page in listOf(
            "<html>Access denied</html>",
            "<script id='__NEXT_DATA__'>{broken</script>",
            "<script id='__NEXT_DATA__'>{\"props\":{\"pageProps\":{\"indexStats\":{}}}}</script>",
            valid.replace("\"game_id\":1", "\"game_id\":2"),
            valid.replace("\"comp_all\":237341,", ""),
            valid.replace("\"comp_all_count\":10805", "\"comp_all_count\":\"not-a-count\""),
            valid.replace("\"comp_all\":237341", "\"comp_all\":-1"),
        )) {
            val fixture = Fixture(mapOf(1L to page), listOf(search(1, 1, 1L to "Game")))
            assertThrows(HowLongToBeatException::class.java) { fixture.client.lookup("Game", listOf(111L)) }
        }
    }

    @Test fun searchBlocksHttpErrorsAndChangedResponseShapesStayExplicit() {
        for (status in listOf(401, 403, 429, 503)) {
            val fixture = Fixture(emptyMap(), initStatus = status)
            val error = assertThrows(HowLongToBeatException::class.java) { fixture.client.lookup("Game", emptyList()) }
            assertEquals(status, error.httpStatus)
            assertFalse(fixture.requests.any { it.method == "POST" })
        }
        for (body in listOf("<html>CAPTCHA</html>", "{}", "{\"data\":{},\"pageCurrent\":1,\"pageTotal\":1}")) {
            val fixture = Fixture(emptyMap(), listOf(body))
            assertThrows(HowLongToBeatException::class.java) { fixture.client.lookup("Game", emptyList()) }
        }
    }

    @Test fun changedSearchScriptsNeverFallBackToHomepagePopularGames() {
        val fixture = Fixture(emptyMap(), script = "fetch('/api/unrelated', {method:'GET'})")
        assertThrows(HowLongToBeatException::class.java) { fixture.client.lookup("Game", emptyList()) }
        assertFalse(fixture.requests.any { it.method == "POST" })
    }

    @Test fun aPostEndpointThatIsNotASearchApiIsNeverTrusted() {
        val fixture = Fixture(emptyMap(), script = "fetch('/api/login', {method:'POST'})")
        assertThrows(HowLongToBeatException::class.java) { fixture.client.lookup("Game", emptyList()) }
        assertFalse(fixture.requests.any { it.method == "POST" })
    }

    @Test fun failedSearchDiscoveryIsRememberedInsteadOfRefetchingEveryChunkPerLookup() {
        val fixture = Fixture(emptyMap(), script = "fetch('/api/unrelated', {method:'GET'})")
        assertThrows(HowLongToBeatException::class.java) { fixture.client.lookup("Game", emptyList()) }
        val afterFirst = fixture.requests.size
        assertThrows(HowLongToBeatException::class.java) { fixture.client.lookup("Other", emptyList()) }
        assertEquals(afterFirst, fixture.requests.size)
        fixture.nanos += TimeUnit.HOURS.toNanos(1)
        assertThrows(HowLongToBeatException::class.java) { fixture.client.lookup("Game", emptyList()) }
        assertTrue(fixture.requests.size > afterFirst)
    }

    @Test fun oneLookupOfACommonTitleIsBoundedAndSkipsAnUnreadableDetailPage() {
        val ids = (1L..12L).toList()
        val details = ids.associateWith { id -> if (id == 1L) "<html>changed</html>" else detail(id, "Game $id", if (id == 2L) 111 else 0) }
        val fixture = Fixture(details, listOf(
            search(1, 5, *ids.take(6).map { it to "Game $it" }.toTypedArray()),
            search(2, 5, *ids.drop(6).map { it to "Game $it" }.toTypedArray()),
        ))
        assertEquals(2L, fixture.client.lookup("Game", listOf(111L))?.id)
        assertEquals(2, fixture.requests.count { it.method == "POST" })
        assertTrue(fixture.requests.count { it.url.encodedPath.startsWith("/game/") } <= 5)
    }

    @Test fun aBlockedDetailPageStopsTheLookupInsteadOfHammeringTheRemainingResults() {
        val fixture = Fixture(mapOf(2L to detail(2, "Game 2", 0), 3L to detail(3, "Game 3", 0)),
            listOf(search(1, 1, 1L to "Game 1", 2L to "Game 2", 3L to "Game 3")), blockedDetails = setOf(1L))
        val error = assertThrows(HowLongToBeatException::class.java) { fixture.client.lookup("Game", listOf(111L)) }
        assertEquals(429, error.httpStatus)
        assertEquals(1, fixture.requests.count { it.url.encodedPath.startsWith("/game/") })
    }

    @Test fun optionalReferenceAuthorizationFieldsAreSentWithoutInventingValues() {
        val fixture = Fixture(emptyMap(), listOf(search(1, 0)),
            init = "{\"token\":\"fixture-token\",\"requestKey\":\"fixture-field\",\"requestValue\":\"fixture-value\"}")
        assertNull(fixture.client.lookup("Game", emptyList()))
        val post = fixture.requests.single { it.method == "POST" }
        assertEquals("fixture-field", post.header("x-hp-key"))
        assertEquals("fixture-value", post.header("x-hp-val"))
        val buffer = Buffer()
        post.body!!.writeTo(buffer)
        assertEquals("fixture-value", JsonParser.parseString(buffer.readUtf8()).asJsonObject.get("fixture-field").asString)
        val incomplete = Fixture(emptyMap(), init = "{\"token\":\"fixture-token\",\"requestKey\":\"fixture-field\"}")
        assertThrows(HowLongToBeatException::class.java) { incomplete.client.lookup("Game", emptyList()) }
    }

    private class Fixture(
        private val details: Map<Long, String>,
        private val searches: List<String> = emptyList(),
        private val initStatus: Int = 200,
        private val init: String = "{\"token\":\"fixture-token\"}",
        private val script: String = "fetch(\"/api/search/site\",{method:\"POST\",headers:{\"Content-Type\":\"application/json\"}})",
        private val blockedDetails: Set<Long> = emptySet(),
    ) {
        val requests = mutableListOf<Request>()
        /** The injected clock; tests advance it instead of waiting. */
        var nanos = 0L
        val client = HowLongToBeatClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val path = request.url.encodedPath
            val (code, body) = when {
                path == "/" -> 200 to "<script src='/_next/static/chunks/search.js'></script>"
                path == "/_next/static/chunks/search.js" -> 200 to script
                path == "/api/search/site/init" -> initStatus to init
                path == "/api/search/site" && request.method == "POST" -> {
                    val buffer = Buffer()
                    request.body!!.writeTo(buffer)
                    val page = JsonParser.parseString(buffer.readUtf8()).asJsonObject.get("searchPage").asInt
                    200 to searches[page - 1]
                }
                path.startsWith("/game/") -> {
                    val id = path.substringAfterLast('/').toLong()
                    if (id in blockedDetails) 429 to "rate limited"
                    else details[id]?.let { 200 to it } ?: (404 to "not found")
                }
                else -> throw AssertionError("Unexpected provider request: ${request.method} $path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .body(body.toResponseBody("text/plain".toMediaType())).build()
        }.build(), RequestPacer(0), { nanos })
    }

    companion object {
        private val gson = Gson()

        private fun detail(id: Long, name: String, steam: Long, stats: String = "\"comp_all\":237341,\"comp_all_count\":10805", headline: String = "66 Hours"): String =
            "<h4>HowLongToBeat</h4><h5>$headline</h5>" +
                "<script type='application/json' id='__NEXT_DATA__'>{\"props\":{\"pageProps\":{\"game\":{\"data\":{\"game\":[" +
                "{\"game_id\":$id,\"game_name\":${gson.toJson(name)},\"profile_steam\":$steam,$stats}]}}}}}</script>"

        private fun search(page: Int, total: Int, vararg entries: Pair<Long, String>): String = gson.toJson(mapOf(
            "pageCurrent" to page, "pageTotal" to total,
            "data" to entries.map { (id, name) -> mapOf("game_id" to id, "game_name" to name) },
        ))
    }
}
