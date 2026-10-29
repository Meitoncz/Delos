package com.limelight.shell.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

class PcGamingWikiException(message: String) : IOException(message)

/** Bot-password login and read-only Cargo/page requests; all calls are blocking. */
class PcGamingWikiClient(http: OkHttpClient, private val username: String, private val password: String) {
    private val http = http.newBuilder().cookieJar(SessionCookies()).build()
    private var loggedIn = false
    private data class Cached(val time: Long, val evidence: HdrEvidence)
    private val cache = LinkedHashMap<String, Cached>()

    @Synchronized fun lookup(steamAppId: Long?, title: String): HdrEvidence {
        require(steamAppId == null || steamAppId > 0) { "Steam AppID must be positive" }
        if (steamAppId == null && title.isBlank()) return HdrEvidence(NativeHdr.UNKNOWN)
        val key = steamAppId?.let { "steam:$it" } ?: "title:${title.trim()}"
        cache[key]?.takeIf { System.nanoTime() - it.time < CACHE_NANOS }?.let { return it.evidence }
        login()
        val where = if (steamAppId != null) "Game.Steam_AppID HOLDS \"$steamAppId\""
            else "Game._pageName = \"${cargoLiteral(title.trim().replace('_', ' '))}\""
        val data = api(mapOf(
            "action" to "cargoquery", "tables" to "Game,Video",
            "fields" to "Game._pageName=Page,Video.HDR=HDR",
            "join_on" to "Game._pageID=Video._pageID", "where" to where, "limit" to "10",
            "assert" to "user",
        ))
        val rows = try {
            data.getAsJsonArray("cargoquery") ?: throw IllegalArgumentException()
        } catch (_: RuntimeException) {
            throw PcGamingWikiException("PCGamingWiki returned invalid Cargo data")
        }
        // One Steam ID can point at several pages/editions. Never arbitrarily take the first.
        val pages = try {
            rows.map { it.asJsonObject.getAsJsonObject("title").get("Page").asString }.distinct()
        } catch (_: RuntimeException) {
            throw PcGamingWikiException("PCGamingWiki returned invalid Cargo rows")
        }
        val evidence = if (pages.size != 1) HdrEvidence(NativeHdr.UNKNOWN) else {
            val page = pages.single()
            val parsed = api(mapOf("action" to "parse", "page" to page, "prop" to "wikitext", "assert" to "user"))
            val source = try {
                parsed.getAsJsonObject("parse").getAsJsonObject("wikitext").get("*").asString
            } catch (_: RuntimeException) {
                throw PcGamingWikiException("PCGamingWiki returned invalid page data")
            }
            // Cargo's string HDR column alone is not evidence of native support; inspect Video notes.
            parseVideo(source, page)
        }
        if (cache.size >= 128) cache.remove(cache.keys.first())
        cache[key] = Cached(System.nanoTime(), evidence)
        return evidence
    }

    private fun login() {
        if (loggedIn) return
        if (username.isBlank() || password.isBlank()) throw PcGamingWikiException("PCGamingWiki credentials missing")
        val tokens = api(mapOf("action" to "query", "meta" to "tokens", "type" to "login"))
        val token = try {
            tokens.getAsJsonObject("query").getAsJsonObject("tokens").get("logintoken").asString
        } catch (_: RuntimeException) {
            throw PcGamingWikiException("PCGamingWiki returned invalid login data")
        }
        val result = api(mapOf("action" to "login", "lgname" to username, "lgpassword" to password, "lgtoken" to token), post = true)
        val accepted = try {
            result.getAsJsonObject("login")?.get("result")?.asString == "Success"
        } catch (_: RuntimeException) {
            false
        }
        if (!accepted) {
            throw PcGamingWikiException("PCGamingWiki authentication rejected")
        }
        loggedIn = true
    }

    private fun api(parameters: Map<String, String>, post: Boolean = false): JsonObject {
        pace()
        val params = parameters + ("format" to "json")
        val request = Request.Builder().header("User-Agent", "Delos/1.0 (https://github.com/Meitoncz/Delos)")
        if (post) {
            request.url(ENDPOINT).post(FormBody.Builder().apply { params.forEach { (key, value) -> add(key, value) } }.build())
        } else {
            request.url(ENDPOINT.toHttpUrl().newBuilder().apply {
                params.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build())
        }
        http.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) throw PcGamingWikiException("PCGamingWiki HTTP ${response.code}")
            val json = try {
                JsonParser.parseString(response.body.string()).asJsonObject
            } catch (_: RuntimeException) {
                throw PcGamingWikiException("PCGamingWiki returned invalid JSON")
            }
            if (json.has("error") || json.has("errors")) {
                loggedIn = false
                // Provider error text may echo submitted parameters. Do not propagate it or causes.
                throw PcGamingWikiException("PCGamingWiki API request rejected")
            }
            return json
        }
    }

    private class SessionCookies : CookieJar {
        private val cookies = mutableListOf<Cookie>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (cookie in cookies) {
                this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (cookie.expiresAt > System.currentTimeMillis()) this.cookies.add(cookie)
            }
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
            cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
            return cookies.filter { it.matches(url) }
        }
    }

    companion object {
        private const val ENDPOINT = "https://www.pcgamingwiki.com/w/api.php"
        private val CACHE_NANOS = TimeUnit.HOURS.toNanos(6)
        private val rateLock = Any()
        private var nextRequest = 0L
        private val comments = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        private val videoStart = Regex("\\{\\{\\s*(?:Template:)?Video\\s*(?=[|}])", RegexOption.IGNORE_CASE)
        private val workaround = Regex("special\\s*k|reshade|auto\\s*hdr|rtx\\s*hdr|\\bini\\b|\\.(?:ini|json|cfg|xml|reg|dll|txt)\\b|registry|\\bmods?\\b|modifications|workaround|retrofit|inject|\\bedit(?:ing)?\\b|\\bfiles?\\b|\\bdownloads?\\b|\\binstall(?:ation)?\\b|command[ -]?line|launch (?:arguments?|options?)|hex[ -]?edit", RegexOption.IGNORE_CASE)
        private val retrofitOnly = Regex("(?:only|requires?|must|via|using|through|with).{0,60}(?:special\\s*k|reshade|auto\\s*hdr|rtx\\s*hdr|\\bmod\\b|retrofit)|(?:no|not|without) native|not supported natively", RegexOption.IGNORE_CASE)
        private val limitedSupport = Regex("\\bonly\\b|\\blimited\\b|\\bversions?\\b|\\beditions?\\b|\\bconsoles?\\b|\\bxbox\\b|playstation|\\bps[345]\\b|\\bswitch\\b|not supported|unsupported|not native|no native|broken", RegexOption.IGNORE_CASE)

        private fun pace() = synchronized(rateLock) {
            val wait = nextRequest - System.nanoTime()
            if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait)
            nextRequest = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1050)
        }

        private fun cargoLiteral(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

        /** Split only at Video's own pipes, not nested templates, links or reference parameters. */
        internal fun parseVideo(wikitext: String, page: String): HdrEvidence {
            val text = comments.replace(wikitext, "")
            val start = videoStart.find(text) ?: return HdrEvidence(NativeHdr.UNKNOWN, page)
            val parts = mutableListOf<String>()
            var partStart = start.range.last + 1
            var i = partStart
            var templates = 1
            var links = 0
            while (i < text.length - 1) {
                when {
                    text.startsWith("{{", i) -> { templates++; i += 2; continue }
                    text.startsWith("}}", i) -> {
                        templates--
                        if (templates == 0) { parts.add(text.substring(partStart, i)); break }
                        i += 2; continue
                    }
                    text.startsWith("[[", i) -> { links++; i += 2; continue }
                    text.startsWith("]]", i) -> { if (links > 0) links--; i += 2; continue }
                }
                if (text[i] == '|' && templates == 1 && links == 0) {
                    parts.add(text.substring(partStart, i)); partStart = i + 1
                }
                i++
            }
            if (templates != 0) return HdrEvidence(NativeHdr.UNKNOWN, page)
            val fields = parts.mapNotNull {
                val equals = it.indexOf('=')
                if (equals < 0) null else it.substring(0, equals).trim().lowercase(Locale.ROOT).replace('_', ' ') to it.substring(equals + 1).trim()
            }.toMap()
            val value = fields["hdr"]?.lowercase(Locale.ROOT)?.trim()
            val notes = fields["hdr notes"]?.takeIf { it.isNotBlank() }
            val support = when {
                value == "false" -> NativeHdr.SDR
                value == "hackable" && notes != null && retrofitOnly.containsMatchIn(notes) -> NativeHdr.SDR
                value != "true" && value != "always on" && value != "alwayson" -> NativeHdr.UNKNOWN
                notes != null && (workaround.containsMatchIn(notes) || retrofitOnly.containsMatchIn(notes) ||
                    limitedSupport.containsMatchIn(notes)) -> NativeHdr.UNKNOWN
                else -> NativeHdr.HDR
            }
            return HdrEvidence(support, page, notes)
        }
    }
}
