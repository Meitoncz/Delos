package com.limelight.shell.data

import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val BASE = "https://meta.test"

class IgdbMetadataClientTest {
    @Test fun editionsKeepTheirOwnIdentityAndOnlySteamSourceBecomesAppId() {
        val candidates = IgdbMetadataClient.parseGames(JsonParser.parseString("""
            [{"id":1942,"name":"The Witcher 3: Wild Hunt","first_release_date":1431993600,
              "genres":[{"id":12,"name":"Role-playing (RPG)"}],"summary":"A PC game",
              "external_games":[{"external_game_source":5,"uid":"999"},{"external_game_source":1,"uid":"292030"}]},
             {"id":4000,"name":"The Witcher 3: Wild Hunt - Complete Edition",
              "external_games":[{"category":1,"uid":"1000"}]}]
        """).asJsonArray)
        assertEquals(2, candidates.size)
        assertEquals(1942L, candidates[0].id)
        assertEquals(2015, candidates[0].year)
        assertEquals(listOf(292030L), candidates[0].steamAppIds)
        assertEquals(listOf(GameGenre(12, "Role-playing (RPG)")), candidates[0].genres)
        assertEquals("A PC game", candidates[0].summary)
        assertEquals(4000L, candidates[1].id)
        assertEquals("The Witcher 3: Wild Hunt - Complete Edition", candidates[1].name)
        assertTrue(candidates[1].steamAppIds.isEmpty())
        assertNull(candidates[1].year)
    }

    @Test fun everySteamIdIsKeptSoPcgwCanTryEachOfThem() {
        val result = IgdbMetadataClient.parseGames(JsonParser.parseString("""
            [{"id":1,"name":"Edition","external_games":[
              {"external_game_source":1,"uid":"10"},{"external_game_source":1,"uid":"20"},
              {"external_game_source":1,"uid":"10"}]}]
        """).asJsonArray).single()
        assertEquals(listOf(10L, 20L), result.steamAppIds)
    }

    @Test fun platformsAreParsedAndMissingPlatformsStayUnknownRatherThanNonPc() {
        val (withPlatforms, without) = IgdbMetadataClient.parseGames(JsonParser.parseString("""
            [{"id":1,"name":"A","platforms":[6,14]},{"id":2,"name":"B"}]
        """).asJsonArray)
        assertEquals(listOf(6, 14), withPlatforms.platformIds)
        assertTrue(withPlatforms.isPc)
        assertTrue(without.platformIds.isEmpty())
        assertFalse(without.isPc)
    }

    @Test fun ratingCountIsTheLargerOfTheUserAndTotalCountsAndZeroWhenAbsent() {
        val (both, userOnly, none) = IgdbMetadataClient.parseGames(JsonParser.parseString("""
            [{"id":1,"name":"Peak","rating_count":154,"total_rating_count":155},
             {"id":2,"name":"Peak","rating_count":7},{"id":3,"name":"Peak"}]
        """).asJsonArray)
        assertEquals(155, both.ratingCount)
        assertEquals(7, userOnly.ratingCount)
        assertEquals(0, none.ratingCount)
    }

    @Test fun themesAreParsedLikeGenresAndMissingThemesAreEmpty() {
        val (with, without) = IgdbMetadataClient.parseGames(JsonParser.parseString("""
            [{"id":1,"name":"A","genres":[{"id":12,"name":"Role-playing (RPG)"}],
              "themes":[{"id":17,"name":"Fantasy"},{"id":1,"name":"Action"}]},
             {"id":2,"name":"B","genres":[{"id":5,"name":"Shooter"}]}]
        """).asJsonArray)
        assertEquals(listOf(GameGenre(17, "Fantasy"), GameGenre(1, "Action")), with.themes)
        assertEquals(listOf(GameGenre(12, "Role-playing (RPG)")), with.genres)
        assertTrue(without.themes.isEmpty())
    }

    @Test fun searchSendsOnlyTheTitleWithoutTrademarkSignsToTheProxy() {
        val requests = mutableListOf<Request>()
        val client = proxyClient { requests.add(it); respond(it, 200, "[]") }
        client.search("LEGO\u00ae Batman\u2122: Legacy of the Dark Knight")
        val request = requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://meta.test/v1/igdb/games?search=LEGO%20Batman%3A%20Legacy%20of%20the%20Dark%20Knight", request.url.toString())
        assertEquals("LEGO Batman: Legacy of the Dark Knight", request.url.queryParameter("search"))
        assertEquals(1, request.url.querySize)
        assertNull(request.header("Authorization"))
        assertNull(request.header("Client-ID"))
        assertNull(request.body)
    }

    @Test fun gameLookupSendsOnlyTheId() {
        val requests = mutableListOf<Request>()
        val client = proxyClient { requests.add(it); respond(it, 200, """[{"id":1942,"name":"The Witcher 3"}]""") }
        assertEquals(1942L, client.game(1942)?.id)
        assertEquals("https://meta.test/v1/igdb/games?id=1942", requests.single().url.toString())
        assertThrows(IllegalArgumentException::class.java) { client.game(0) }
        assertEquals(1, requests.size)
    }

    @Test fun searchTermsAreMadeAcceptableToTheProxyAndBlankOnesAreNotSent() {
        val requests = mutableListOf<Request>()
        val client = proxyClient { requests.add(it); respond(it, 200, "[]") }
        client.search("Game\u0000 name")
        client.search("\u00ae\u2122 ")
        client.search("a".repeat(300))
        assertEquals(listOf("Game name", "a".repeat(128)), requests.map { it.url.queryParameter("search") })
    }

    @Test fun aBasePathAndTrailingSlashAreKept() {
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain -> requests.add(chain.request()); respond(chain.request(), 200, "[]") }.build()
        IgdbMetadataClient(http, "https://meta.test/delos/", RequestPacer(0)).search("Game")
        assertEquals("/delos/v1/igdb/games", requests.single().url.encodedPath)
    }

    @Test fun withoutAUsableServiceUrlNothingIsSentAndTheLookupFails() {
        val requests = mutableListOf<Request>()
        for (base in listOf("", "   ", "not a url", "http://meta.test")) {
            val http = OkHttpClient.Builder().addInterceptor { chain -> requests.add(chain.request()); respond(chain.request(), 200, "[]") }.build()
            val error = assertThrows(IgdbMetadataException::class.java) { IgdbMetadataClient(http, base, RequestPacer(0)).search("Game") }
            assertEquals("IGDB metadata service is not configured", error.message)
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun rateLimitAndBackoffAnswersAreProviderFailuresNotEmptySearches() {
        for (code in listOf(429, 503, 502, 403, 400)) {
            val error = assertThrows(IgdbMetadataException::class.java) {
                proxyClient { respond(it, code, """{"error":"rate_limited"} test-secret""") }.search("Game")
            }
            assertEquals("IGDB HTTP $code", error.message)
            assertNull(error.cause)
        }
    }

    @Test fun malformedOrNonArrayJsonIsAProviderFailure() {
        for (body in listOf("", "not json", """{"id":1}""", """[{"name":"no id"}]""", """[{"id":1}]""")) {
            val error = assertThrows(IgdbMetadataException::class.java) { proxyClient { respond(it, 200, body) }.search("Game") }
            assertEquals("IGDB returned invalid game data", error.message)
            assertNull(error.cause)
        }
    }

    @Test fun networkErrorsPropagateAndRedirectsAreNotFollowed() {
        assertThrows(IOException::class.java) { proxyClient { throw IOException("offline") }.search("Game") }
        var calls = 0
        val error = assertThrows(IgdbMetadataException::class.java) {
            proxyClient { calls++; respond(it, 307, "", "Location" to "https://evil.test/") }.search("Game")
        }
        assertEquals("IGDB HTTP 307", error.message)
        assertEquals(1, calls)
    }

    @Test fun everyRequestIsPacedFirst() {
        var now = 0L
        val sleeps = mutableListOf<Long>()
        val pacer = RequestPacer(260, nanoTime = { now }, sleep = { sleeps.add(it) })
        val http = OkHttpClient.Builder().addInterceptor { chain -> respond(chain.request(), 200, "[]") }.build()
        val client = IgdbMetadataClient(http, BASE, pacer)
        client.search("A")
        client.search("B")
        assertEquals(1, sleeps.size)
    }

    private fun proxyClient(handler: (Request) -> Response): IgdbMetadataClient {
        val http = OkHttpClient.Builder().addInterceptor { chain -> handler(chain.request()) }.build()
        return IgdbMetadataClient(http, BASE, RequestPacer(0))
    }

    private fun respond(request: Request, code: Int, body: String, vararg headers: Pair<String, String>): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun searchKeepsPcSteamAndPlatformlessEntriesButDropsConsoleOnlyOnes() {
        fun candidate(id: Long, platforms: List<Int>, steam: List<Long> = emptyList()) =
            MetadataCandidate(id, "Game", null, steam, platformIds = platforms)
        val result = IgdbMetadataClient.preferPc(listOf(
            candidate(1, emptyList()), candidate(2, listOf(48)), candidate(3, listOf(6)),
            candidate(4, listOf(14), steam = listOf(7L)), candidate(5, listOf(130)),
        ))
        assertEquals(listOf(3L, 4L, 1L), result.map { it.id })
    }

}
