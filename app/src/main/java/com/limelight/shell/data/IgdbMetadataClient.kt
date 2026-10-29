package com.limelight.shell.data

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class IgdbMetadataException(message: String) : IOException(message)

/** Direct IGDB v4 calls. Blocking: callers must dispatch off the UI thread. */
class IgdbMetadataClient(
    private val http: OkHttpClient,
    private val clientId: String,
    private val clientSecret: String,
) {
    private var accessToken: String? = null
    private var tokenDeadline = 0L

    fun search(query: String): List<MetadataCandidate> {
        if (query.isBlank()) return emptyList()
        return games("search \"${escape(query.trim())}\"; $FIELDS where platforms = (6); limit 30;")
    }

    fun game(id: Long): MetadataCandidate? {
        require(id > 0) { "IGDB game ID must be positive" }
        return games("$FIELDS where id = $id & platforms = (6); limit 1;").firstOrNull()
    }

    // Keep editions as independent IDs/names; do not filter version_parent or substitute its metadata.
    private fun games(query: String): List<MetadataCandidate> {
        permits.acquire()
        try {
            var token = token()
            repeat(2) { attempt ->
                pace()
                val request = Request.Builder().url("https://api.igdb.com/v4/games")
                    .header("Client-ID", clientId).header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .post(query.toRequestBody("text/plain; charset=utf-8".toMediaType())).build()
                http.newCall(request).execute().use { response ->
                    // One token renewal is authentication maintenance, not an HTTP retry loop.
                    if (response.code == 401 && attempt == 0) {
                        token = token(rejected = token)
                    } else {
                        if (!response.isSuccessful) throw IgdbMetadataException("IGDB HTTP ${response.code}")
                        return try {
                            parseGames(JsonParser.parseString(response.body.string()).asJsonArray)
                        } catch (_: RuntimeException) {
                            throw IgdbMetadataException("IGDB returned invalid game data")
                        }
                    }
                }
            }
            throw IgdbMetadataException("IGDB authentication rejected")
        } finally {
            permits.release()
        }
    }

    @Synchronized private fun token(rejected: String? = null): String {
        val current = accessToken
        if (current != null && current != rejected && System.nanoTime() < tokenDeadline) return current
        if (clientId.isBlank() || clientSecret.isBlank()) throw IgdbMetadataException("IGDB credentials missing")
        val request = Request.Builder().url("https://id.twitch.tv/oauth2/token")
            .post(FormBody.Builder().add("client_id", clientId).add("client_secret", clientSecret)
                .add("grant_type", "client_credentials").build()).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IgdbMetadataException("IGDB authentication HTTP ${response.code}")
            try {
                val data = JsonParser.parseString(response.body.string()).asJsonObject
                val value = data.get("access_token").asString
                val seconds = data.get("expires_in").asLong
                if (value.isBlank() || seconds <= 0) throw IllegalArgumentException()
                accessToken = value
                tokenDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos((seconds - 60).coerceAtLeast(1))
                return value
            } catch (_: RuntimeException) {
                throw IgdbMetadataException("IGDB returned invalid authentication data")
            }
        }
    }

    companion object {
        private const val FIELDS = "fields name,first_release_date,summary,genres.id,genres.name,external_games.uid,external_games.external_game_source;"
        private val permits = Semaphore(8, true)
        private val rateLock = Any()
        private var nextRequest = 0L

        private fun pace() = synchronized(rateLock) {
            val wait = nextRequest - System.nanoTime()
            if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait)
            nextRequest = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(260)
        }

        internal fun escape(value: String): String = value.replace("\\", "\\\\")
            .replace("\"", "\\\"").replace('\n', ' ').replace('\r', ' ')

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
                steamAppId = steamIds.singleOrNull(),
                genres = game.getAsJsonArray("genres")?.map { genre ->
                    val entry = genre.asJsonObject
                    GameGenre(entry.get("id").asInt, entry.get("name").asString)
                }.orEmpty(),
                summary = game.get("summary")?.takeUnless { it.isJsonNull }?.asString,
            )
        }
    }
}
