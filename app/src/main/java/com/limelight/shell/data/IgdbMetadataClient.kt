package com.limelight.shell.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Calendar
import java.util.TimeZone

class IgdbMetadataException(message: String) : IOException(message)

/**
 * IGDB lookups through the Delos metadata Worker, which holds the IGDB credentials and builds the IGDB query itself
 * (`GET <base>/v1/igdb/games?search=` or `?id=`, answering IGDB's raw JSON array). Blocking: callers must dispatch
 * off the UI thread. Redirects are never followed. Any non-2xx answer (including the Worker's 429 rate limit and
 * 503 backoff) or network error is a provider failure, never an empty result.
 */
class IgdbMetadataClient internal constructor(
    http: OkHttpClient,
    private val baseUrl: String,
    private val pacer: RequestPacer,
) {
    constructor(http: OkHttpClient, baseUrl: String) : this(http, baseUrl, RequestPacer(260))

    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /** PC and Steam entries first, then entries without platform data, then console-only ones (a manually added non-Steam game may be one). */
    fun search(query: String): List<MetadataCandidate> {
        val term = MetadataProxy.term(searchTerm(query))
        if (term.isEmpty()) return emptyList()
        return preferPc(games("search" to term))
    }

    fun game(id: Long): MetadataCandidate? {
        require(id > 0) { "IGDB game ID must be positive" }
        return games("id" to id.toString()).firstOrNull()
    }

    // Keep editions as independent IDs/names; do not filter version_parent or substitute its metadata.
    private fun games(parameter: Pair<String, String>): List<MetadataCandidate> {
        val url = MetadataProxy.url(baseUrl, "v1/igdb/games", parameter)
            ?: throw IgdbMetadataException("IGDB metadata service is not configured")
        pacer.await()
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build() // l10n-ignore
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IgdbMetadataException("IGDB HTTP ${response.code}")
            return try {
                parseGames(JsonParser.parseString(response.body.string()).asJsonArray)
            } catch (_: RuntimeException) {
                throw IgdbMetadataException("IGDB returned invalid game data")
            }
        }
    }

    companion object {
        /**
         * IGDB platform data is incomplete, so a missing list is not evidence against PC. PC/Steam entries come first,
         * then entries without platform data, then console-only ones: a game added by hand to the host (an emulated
         * console title) exists on IGDB only for its console, so dropping those would make it unfindable.
         */
        internal fun preferPc(candidates: List<MetadataCandidate>): List<MetadataCandidate> {
            val (preferred, rest) = candidates.partition { it.isPc || it.steamAppIds.isNotEmpty() }
            val (unknown, consoleOnly) = rest.partition { it.platformIds.isEmpty() }
            return preferred + unknown + consoleOnly
        }

        private fun JsonObject.intOrZero(name: String): Int = get(name)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

        internal fun parseGames(data: JsonArray): List<MetadataCandidate> = data.map { element ->
            val game = element.asJsonObject
            val year = game.get("first_release_date")?.takeUnless { it.isJsonNull }?.asLong?.let { seconds ->
                Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = seconds * 1000 }
                    .get(Calendar.YEAR)
            }
            val steamIds = game.getAsJsonArray("external_games")?.mapNotNull { external ->
                val entry = external.asJsonObject
                if (entry.get("external_game_source")?.asInt == 1) {
                    entry.get("uid")?.takeUnless { it.isJsonNull }?.asString?.toLongOrNull()?.takeIf { it > 0 }
                } else null
            }?.distinct().orEmpty()
            MetadataCandidate(
                id = game.get("id").asLong,
                name = game.get("name").asString,
                year = year,
                steamAppIds = steamIds,
                genres = game.getAsJsonArray("genres")?.map { genre ->
                    val entry = genre.asJsonObject
                    GameGenre(entry.get("id").asInt, entry.get("name").asString)
                }.orEmpty(),
                themes = game.getAsJsonArray("themes")?.map { theme ->
                    val entry = theme.asJsonObject
                    GameGenre(entry.get("id").asInt, entry.get("name").asString)
                }.orEmpty(),
                summary = game.get("summary")?.takeUnless { it.isJsonNull }?.asString,
                platformIds = game.getAsJsonArray("platforms")?.mapNotNull { platform ->
                    platform.takeIf { it.isJsonPrimitive }?.asInt
                }?.distinct().orEmpty(),
                ratingCount = maxOf(game.intOrZero("rating_count"), game.intOrZero("total_rating_count")),
            )
        }
    }
}
