package com.limelight.shell.data

import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test fun searchKeepsPcSteamAndPlatformlessEntriesButDropsConsoleOnlyOnes() {
        fun candidate(id: Long, platforms: List<Int>, steam: List<Long> = emptyList()) =
            MetadataCandidate(id, "Game", null, steam, platformIds = platforms)
        val result = IgdbMetadataClient.preferPc(listOf(
            candidate(1, emptyList()), candidate(2, listOf(48)), candidate(3, listOf(6)),
            candidate(4, listOf(14), steam = listOf(7L)), candidate(5, listOf(130)),
        ))
        assertEquals(listOf(3L, 4L, 1L), result.map { it.id })
    }


    @Test fun rejectedTokenIsRenewedOnceAndSearchEscapesLiterallyWithoutDroppingPlatformlessGames() {
        var tokenCalls = 0
        var gameCalls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.host == "id.twitch.tv") {
                tokenCalls++
                """{"access_token":"test-token-$tokenCalls","expires_in":3600}"""
            } else {
                gameCalls++
                assertEquals("api.igdb.com", request.url.host)
                assertEquals("/v4/games", request.url.encodedPath)
                assertEquals("Bearer test-token-$tokenCalls", request.header("Authorization"))
                val buffer = okio.Buffer()
                request.body!!.writeTo(buffer)
                val query = buffer.readUtf8()
                assertFalse(query.contains("platforms ="))
                assertTrue(query.contains("search \"Game \\\"edition\\\"\""))
                "[]"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (gameCalls == 1 && request.url.host == "api.igdb.com") 401 else 200)
                .message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = IgdbMetadataClient(http, "test-client", "test-secret", RequestPacer(0))
        assertTrue(client.search("Game \"edition\"").isEmpty())
        assertTrue(client.search("Game \"edition\"").isEmpty())
        assertEquals(2, tokenCalls)
        assertEquals(3, gameCalls)
    }

    @Test fun providerFailureIsNotAnEmptySearchAndDoesNotExposeResponseText() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403)
                .message("fixture").body("test-secret".toResponseBody()).build()
        }.build()
        try {
            IgdbMetadataClient(http, "test-client", "test-secret", RequestPacer(0)).search("Game")
            throw AssertionError("Authentication failure was swallowed")
        } catch (error: IgdbMetadataException) {
            assertEquals("IGDB authentication HTTP 403", error.message)
            assertNull(error.cause)
        }
    }
}
