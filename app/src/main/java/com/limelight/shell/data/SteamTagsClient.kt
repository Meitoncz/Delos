package com.limelight.shell.data

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class SteamTagsException(message: String) : IOException(message)

/**
 * A game's Steam store user tags through the Delos metadata Worker (`GET <base>/v1/steam/tags?app=<id>`), which reads
 * the public store page (unofficial, may change) and answers `{"app":<id>,"tags":[...]}` most voted first. All calls are
 * blocking. Redirects are never followed. Like [PcGamingWikiClient], any non-2xx answer (including the Worker's 429
 * rate limit and 503 backoff), malformed body or network error is a provider failure; "the store lists no tags" is a
 * normal answer: an empty list.
 */
class SteamTagsClient internal constructor(
    http: OkHttpClient,
    private val baseUrl: String,
    private val pacer: RequestPacer,
) {
    constructor(http: OkHttpClient, baseUrl: String) : this(http, baseUrl, RequestPacer(650))

    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /** The tags of Steam app [appId], most voted first (at most [MAX_TAGS]); empty when the store lists none. */
    fun tags(appId: Long): List<String> {
        require(appId in 1..MAX_APP_ID) { "Steam AppID out of range" }
        val url = MetadataProxy.url(baseUrl, "v1/steam/tags", "app" to appId.toString())
            ?: throw SteamTagsException("Steam tags service is not configured")
        pacer.await()
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SteamTagsException("Steam tags HTTP ${response.code}")
            return try {
                val json = JsonParser.parseString(response.body.string()).asJsonObject
                if (json.get("app").asLong != appId) throw IllegalArgumentException("another app")
                json.getAsJsonArray("tags").map { it.asString.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_TAGS)
            } catch (_: RuntimeException) {
                // The body is never copied into the failure: it is provider text.
                throw SteamTagsException("Steam tags returned invalid data")
            }
        }
    }

    companion object {
        /** The Worker accepts app ids up to this value (src/validate.js). */
        const val MAX_APP_ID = 2_000_000_000L
        const val MAX_TAGS = 20
    }
}
