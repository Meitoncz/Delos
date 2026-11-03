package com.limelight.shell.data

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val STEAM_BASE = "https://meta.test"

class SteamTagsClientTest {
    private class Proxy(private val status: Int = 200, private val body: String = """{"app":2101960,"tags":[]}""") {
        val requests = mutableListOf<Request>()
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
    }

    private fun client(proxy: Proxy, base: String = STEAM_BASE) = SteamTagsClient(proxy.client, base, RequestPacer(0))

    @Test fun theWorkerRouteIsCalledWithTheAppIdAsTheOnlyParameter() {
        val proxy = Proxy(body = """{"app":2101960,"tags":["Survival Horror","Horror"]}""")
        assertEquals(listOf("Survival Horror", "Horror"), client(proxy).tags(2101960))
        val request = proxy.requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://meta.test/v1/steam/tags?app=2101960", request.url.toString())
        assertEquals("application/json", request.header("Accept"))
    }

    @Test fun noTagsIsASuccessfulEmptyList() {
        assertEquals(emptyList<String>(), client(Proxy()).tags(2101960))
    }

    @Test fun tagsKeepTheirOrderAreTrimmedAndBounded() {
        val names = (1..30).joinToString(",") { "\"T$it\"" }
        val proxy = Proxy(body = """{"app":7,"tags":["  Spaced ","","Spaced",$names]}""")
        val tags = client(proxy).tags(7)
        assertEquals(SteamTagsClient.MAX_TAGS, tags.size)
        assertEquals(listOf("Spaced", "T1", "T2"), tags.take(3))
    }

    @Test fun everyNon2xxAnswerIsAProviderFailure() {
        for (code in listOf(429, 503, 502, 500, 404, 307)) {
            val error = assertThrows(SteamTagsException::class.java) { client(Proxy(status = code)).tags(2101960) }
            assertEquals("Steam tags HTTP $code", error.message)
        }
    }

    @Test fun malformedAnswersAreProviderFailuresAndNeverEchoTheBody() {
        for (body in listOf("", "not json", "[]", """{"app":2101960}""", """{"app":2101960,"tags":"x"}""",
            """{"app":2101960,"tags":[null]}""", """{"app":5,"tags":["Other game"]}""", """{"app":"x","tags":[]}""")) {
            val error = assertThrows(body, SteamTagsException::class.java) { client(Proxy(body = body)).tags(2101960) }
            assertEquals("Steam tags returned invalid data", error.message)
            assertTrue(error.cause == null)
        }
    }

    @Test fun networkErrorsPropagateAsIoFailures() {
        val offline = OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build()
        assertThrows(IOException::class.java) { SteamTagsClient(offline, STEAM_BASE, RequestPacer(0)).tags(1) }
    }

    @Test fun aMissingServiceUrlOrAnOutOfRangeIdSendsNothing() {
        val proxy = Proxy()
        for (base in listOf("", "http://meta.test")) {
            val error = assertThrows(SteamTagsException::class.java) { client(proxy, base).tags(1) }
            assertEquals("Steam tags service is not configured", error.message)
        }
        for (id in listOf(0L, -1L, SteamTagsClient.MAX_APP_ID + 1)) {
            assertThrows(IllegalArgumentException::class.java) { client(proxy).tags(id) }
        }
        assertTrue(proxy.requests.isEmpty())
    }
}
