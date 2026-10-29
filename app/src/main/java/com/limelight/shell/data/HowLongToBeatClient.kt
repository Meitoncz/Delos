/*
 * Search flow/payload adapted from howlongtobeatpy 1.0.24, HTMLRequests.py:
 * https://github.com/ScrappyCocco/HowLongToBeat-PythonAPI/tree/4275a452b9ccd230b7c2a82b8d1a5d851285eb14
 * MIT License — Copyright (c) 2018 Michele
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.limelight.shell.data

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.text.Normalizer
import java.util.Locale

class HowLongToBeatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Unofficial direct website client. Blocking: the metadata store calls this off the main thread. */
class HowLongToBeatClient(private val http: OkHttpClient) {
    @Volatile private var searchPath: String? = null
    private val gson = Gson()

    fun lookup(title: String, steamAppId: Long?): CompletionTime? {
        val normalizedTitle = normalize(title)
        if (normalizedTitle.isEmpty()) return null
        val steam = steamAppId?.takeIf { it > 0 }
        val path = searchEndpoint()
        val auth = searchAuth(path)
        val results = LinkedHashMap<Long, SearchGame>()
        var page = 1
        do {
            val response = json(request(path, searchPayload(title, page, auth), auth), "search")
            val games = response.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: changed("search results")
            val current = integer(response, "pageCurrent") ?: changed("search page")
            val total = integer(response, "pageTotal") ?: changed("search page count")
            if (current != page.toLong() || total < 0 || (games.size() > 0 && total < page)) changed("search pagination")
            for (value in games) {
                val game = obj(value, "search game")
                val id = integer(game, "game_id")?.takeIf { it > 0 } ?: changed("search game ID")
                val name = text(game, "game_name").takeIf { it.isNotBlank() } ?: changed("search game name")
                results[id] = SearchGame(id, name)
            }
            page++
        } while (page.toLong() <= total)

        // Search cards omit Steam IDs. Verify on detail pages before accepting a result.
        val details = results.values.asSequence()
            .filter { steam != null || normalize(it.name) == normalizedTitle }
            .mapNotNull { detail(it.id) }.toList()
        val matchingSteam = if (steam == null) emptyList() else details.filter { steam in it.steamIds }
        if (matchingSteam.size > 1) return null // Multiple editions sharing an app ID are not a safe match.
        if (matchingSteam.size == 1) return matchingSteam.single().completion
        val exact = details.filter {
            normalize(it.name) == normalizedTitle && (steam == null || it.steamIds.isEmpty())
        }
        return exact.singleOrNull()?.completion
    }

    fun byId(id: Long): CompletionTime? {
        require(id > 0) { "HLTB game ID must be positive" }
        return detail(id)?.completion
    }

    private fun detail(id: Long): DetailGame? {
        val html = request("/game/$id", allowMissing = true) ?: return null
        val script = NEXT_DATA.find(html)?.groupValues?.get(1) ?: changed("game page data")
        val root = json(script, "game page")
        val props = obj(root.get("props"), "game props")
        val page = obj(props.get("pageProps"), "game page props")
        if (page.get("notFound")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true) return null
        val game = obj(page.get("game"), "game response")
        val data = obj(game.get("data"), "game data")
        val games = data.get("game")?.takeIf { it.isJsonArray }?.asJsonArray ?: changed("game list")
        if (games.size() == 0) return null
        if (games.size() != 1) changed("game identity")
        val entry = obj(games[0], "game entry")
        val actualId = integer(entry, "game_id") ?: changed("game ID")
        if (actualId != id) changed("game identity")
        val name = text(entry, "game_name").takeIf { it.isNotBlank() } ?: changed("game name")
        val steamIds = setOfNotNull(integer(entry, "profile_steam")?.takeIf { it > 0 },
            optionalInteger(entry, "profile_steam_alt")?.takeIf { it > 0 })
        val seconds = integer(entry, "comp_all")
        val count = integer(entry, "comp_all_count")
        if ((seconds != null && seconds < 0) || (count != null && count < 0)) changed("completion time")
        val completion = if (seconds != null && seconds > 0 && count != null && count > 0) {
            val headline = HEADLINE.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                ?: changed("headline display")
            CompletionTime(id, name, seconds, "$BASE/game/$id", headline)
        } else null
        return DetailGame(name, steamIds, completion)
    }

    @Synchronized private fun searchEndpoint(): String {
        searchPath?.let { return it }
        val html = request("/") ?: changed("homepage")
        val scripts = SCRIPT_SOURCE.findAll(html).map { it.groupValues[1] }
            .filter { it.startsWith("/_next/static/chunks/") && it.endsWith(".js") }.distinct()
        for (script in scripts) {
            val source = request(script) ?: changed("search script")
            val path = SEARCH_POST.find(source)?.groupValues?.get(1)?.trimEnd('/') ?: continue
            searchPath = path
            return path
        }
        changed("search endpoint")
    }

    private fun searchAuth(path: String): SearchAuth {
        val init = json(request("$path/init?t=${System.currentTimeMillis()}"), "search initialization")
        val token = text(init, "token").takeIf { it.isNotBlank() } ?: changed("search authorization")
        val keyField = init.entrySet().firstOrNull { it.key.contains("key", ignoreCase = true) }
        val valueField = init.entrySet().firstOrNull { it.key.contains("val", ignoreCase = true) }
        if ((keyField == null) != (valueField == null)) changed("search authorization fields")
        val key = keyField?.value?.let { scalarText(it, "search authorization key") }
        val value = valueField?.value?.let { scalarText(it, "search authorization value") }
        if (key != null && key.isBlank()) changed("search authorization key")
        return SearchAuth(token, key, value)
    }

    private fun searchPayload(title: String, page: Int, auth: SearchAuth): String {
        val payload = linkedMapOf<String, Any>(
            "searchType" to "games", "searchTerms" to title.trim().split(WHITESPACE),
            "searchPage" to page, "size" to 20,
            "searchOptions" to mapOf(
                "games" to mapOf(
                    "userId" to 0, "platform" to "", "sortCategory" to "popular", "rangeCategory" to "main",
                    "rangeTime" to mapOf("min" to 0, "max" to 0),
                    "gameplay" to mapOf("perspective" to "", "flow" to "", "genre" to "", "difficulty" to ""),
                    "rangeYear" to mapOf("max" to "", "min" to ""), "modifier" to "",
                ),
                "users" to mapOf("sortCategory" to "postcount"), "lists" to mapOf("sortCategory" to "follows"),
                "filter" to "", "sort" to 0, "randomizer" to 0,
            ), "useCache" to true,
        )
        if (auth.key != null && auth.value != null) payload[auth.key] = auth.value
        return gson.toJson(payload)
    }

    private fun request(path: String, payload: String? = null, auth: SearchAuth? = null, allowMissing: Boolean = false): String? {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("HowLongToBeat lookup cancelled")
        val url = "$BASE$path".toHttpUrl()
        val builder = Request.Builder().url(url).header("User-Agent", "Delos/1.0")
            .header("Referer", "$BASE/").header("Origin", BASE).header("Accept", "*/*")
        if (payload != null) builder.post(payload.toRequestBody(JSON_TYPE))
        if (auth != null) {
            builder.header("x-auth-token", auth.token)
            auth.key?.let { builder.header("x-hp-key", it) }
            auth.value?.let { builder.header("x-hp-val", it) }
        }
        http.newCall(builder.build()).execute().use { response ->
            if (allowMissing && response.code == 404) return null
            if (!response.isSuccessful) throw HowLongToBeatException("HowLongToBeat answered HTTP ${response.code}")
            if (response.request.url.host != url.host || !response.request.url.isHttps) {
                throw HowLongToBeatException("HowLongToBeat redirected outside the provider")
            }
            return response.body.string()
        }
    }

    private fun json(source: String?, context: String): JsonObject = try {
        obj(JsonParser.parseString(source ?: changed(context)), context)
    } catch (e: HowLongToBeatException) {
        throw e
    } catch (e: RuntimeException) {
        throw HowLongToBeatException("HowLongToBeat changed or blocked its $context data", e)
    }

    private fun obj(value: JsonElement?, context: String): JsonObject =
        value?.takeIf { it.isJsonObject }?.asJsonObject ?: changed(context)

    private fun text(value: JsonObject, field: String): String = scalarText(value.get(field), field)

    private fun scalarText(value: JsonElement?, context: String): String =
        value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: changed(context)

    private fun optionalInteger(value: JsonObject, field: String): Long? =
        if (value.has(field)) integer(value, field) else null

    private fun integer(value: JsonObject, field: String): Long? {
        if (!value.has(field)) changed(field)
        val element = value.get(field)
        if (element.isJsonNull) return null
        if (!element.isJsonPrimitive || element.asJsonPrimitive.isBoolean) changed(field)
        return element.asString.toLongOrNull() ?: changed(field)
    }

    private fun normalize(title: String): String = Normalizer.normalize(
        title.replace("™", "").replace("®", ""), Normalizer.Form.NFKC,
    ).lowercase(Locale.ROOT)
        .replace(PUNCTUATION, " ").trim().replace(WHITESPACE, " ")

    private fun changed(context: String): Nothing =
        throw HowLongToBeatException("HowLongToBeat changed or blocked its $context data")

    private data class SearchAuth(val token: String, val key: String?, val value: String?)
    private data class SearchGame(val id: Long, val name: String)
    private data class DetailGame(val name: String, val steamIds: Set<Long>, val completion: CompletionTime?)

    companion object {
        private const val BASE = "https://howlongtobeat.com"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val WHITESPACE = Regex("\\s+")
        private val PUNCTUATION = Regex("[^\\p{L}\\p{N}]+")
        private val SCRIPT_SOURCE = Regex("""<script\b[^>]*\bsrc\s*=\s*["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
        private val NEXT_DATA = Regex("""<script\b[^>]*\bid\s*=\s*["']__NEXT_DATA__["'][^>]*>(.*?)</script\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val HEADLINE = Regex("""<h4\b[^>]*>\s*HowLongToBeat\s*</h4>\s*<h5\b[^>]*>([^<]+)</h5>""", RegexOption.IGNORE_CASE)
        private val SEARCH_POST = Regex("""fetch\s*\(\s*["'](/api/[a-zA-Z0-9_/]+)[^"']*["']\s*,\s*\{[^}]*method:\s*["']POST["'][^}]*\}""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }
}
