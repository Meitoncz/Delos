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

/**
 * Bot-password login and read-only Cargo/page requests; all calls are blocking. Redirects are never followed:
 * the login POST carries the bot password in its body, which a 307/308 would replay elsewhere.
 */
class PcGamingWikiClient internal constructor(
    http: OkHttpClient,
    private val username: String,
    private val password: String,
    private val pacer: RequestPacer,
) {
    constructor(http: OkHttpClient, username: String, password: String) :
        this(http, username, password, RequestPacer(1050))

    private val http = http.newBuilder().cookieJar(SessionCookies()).followRedirects(false).followSslRedirects(false).build()
    private var loggedIn = false
    // Latched when the wiki definitively refuses the bot account, so a wrong or rotated password is not retried per game.
    private var credentialsRejected = false
    private data class Cached(val time: Long, val evidence: HdrEvidence)
    private val cache = LinkedHashMap<String, Cached>()

    /** [steamAppIds] are every Steam app IGDB lists for the game; any of them may identify the wiki page. */
    @Synchronized fun lookup(steamAppIds: List<Long>, title: String): HdrEvidence {
        require(steamAppIds.all { it > 0 }) { "Steam AppID must be positive" }
        val ids = steamAppIds.distinct().sorted()
        if (ids.isEmpty() && title.isBlank()) return HdrEvidence(NativeHdr.UNKNOWN)
        val key = if (ids.isNotEmpty()) "steam:${ids.joinToString(",")}" else "title:${title.trim()}"
        cache[key]?.takeIf { System.nanoTime() - it.time < CACHE_NANOS }?.let { return it.evidence }
        login()
        val where = if (ids.isNotEmpty()) ids.joinToString(" OR ") { "Game.Steam_AppID HOLDS \"$it\"" }
            else "Game._pageName = \"${cargoLiteral(title.trim().replace('_', ' '))}\""
        val data = api(mapOf(
            "action" to "cargoquery", "tables" to "Game,Video",
            "fields" to "Game._pageName=Page",
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
        val evidence = if (pages.isEmpty() || pages.size > MAX_PAGES) HdrEvidence(NativeHdr.UNKNOWN) else {
            val results = pages.map { page ->
                val parsed = api(mapOf("action" to "parse", "page" to page, "prop" to "wikitext", "assert" to "user"))
                val source = try {
                    parsed.getAsJsonObject("parse").getAsJsonObject("wikitext").get("*").asString
                } catch (_: RuntimeException) {
                    throw PcGamingWikiException("PCGamingWiki returned invalid page data")
                }
                // Cargo's string HDR column alone is not evidence of native support; inspect Video notes.
                parseVideo(source, page)
            }
            // Several pages (editions) count only when they all state the same definite answer.
            val first = results.first()
            if (results.size == 1 || (first.support != NativeHdr.UNKNOWN && results.all { it.support == first.support })) first
            else HdrEvidence(NativeHdr.UNKNOWN)
        }
        if (cache.size >= 128) cache.remove(cache.keys.first())
        cache[key] = Cached(System.nanoTime(), evidence)
        return evidence
    }

    private fun login() {
        if (loggedIn) return
        if (username.isBlank() || password.isBlank()) throw PcGamingWikiException("PCGamingWiki credentials missing")
        if (credentialsRejected) throw PcGamingWikiException("PCGamingWiki credentials were rejected")
        val tokens = api(mapOf("action" to "query", "meta" to "tokens", "type" to "login"))
        val token = try {
            tokens.getAsJsonObject("query").getAsJsonObject("tokens").get("logintoken").asString
        } catch (_: RuntimeException) {
            throw PcGamingWikiException("PCGamingWiki returned invalid login data")
        }
        val result = api(mapOf("action" to "login", "lgname" to username, "lgpassword" to password, "lgtoken" to token), post = true)
        val outcome = try {
            result.getAsJsonObject("login")?.get("result")?.asString
        } catch (_: RuntimeException) {
            null
        }
        if (outcome != "Success") {
            // Only a definite refusal is latched; throttling or an unreadable answer may be transient.
            if (outcome != null && outcome != "Throttled" && outcome != "NeedToken") credentialsRejected = true
            throw PcGamingWikiException("PCGamingWiki authentication rejected")
        }
        loggedIn = true
    }

    private fun api(parameters: Map<String, String>, post: Boolean = false): JsonObject {
        pacer.await()
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
        private const val MAX_PAGES = 3
        private val comments = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        private val videoStart = Regex("\\{\\{\\s*(?:Template:)?Video\\s*(?=[|}])", RegexOption.IGNORE_CASE)
        private val workaround = Regex("special\\s*k|reshade|auto\\s*hdr|rtx\\s*hdr|\\bini\\b|\\.(?:ini|json|cfg|xml|reg|dll|txt)\\b|registry|\\bmods?\\b|modifications|workaround|retrofit|inject|\\bedit(?:ing)?\\b|\\bfiles?\\b|\\bdownloads?\\b|\\binstall(?:ation)?\\b|command[ -]?line|launch (?:arguments?|options?)|hex[ -]?edit", RegexOption.IGNORE_CASE)
        private val retrofitOnly = Regex("(?:only|requires?|must|via|using|through|with).{0,60}(?:special\\s*k|reshade|auto\\s*hdr|rtx\\s*hdr|\\bmod\\b|retrofit)|(?:no|not|without) native|not supported natively", RegexOption.IGNORE_CASE)
        // Platform/edition restrictions need their own context; a bare "only", "version" or "switch" is ordinary prose.
        private val limitedSupport = Regex("\\blimited\\b|\\bconsoles?\\b|\\bxbox\\b|playstation|\\bps[345]\\b|nintendo\\s+switch|\\bswitch\\s+(?:version|edition)\\b|not supported|unsupported|not native|no native|broken" +
            "|\\b(?:specific|certain|some|particular|newer|older|later|earlier)\\s+(?:versions?|editions?|builds?)\\b" +
            "|\\b(?:enhanced|special|deluxe|ultimate|gold|definitive|complete|anniversary|remastered?)\\s+editions?\\b" +
            "|\\bonly\\b[^.]{0,40}\\b(?:versions?|editions?)\\b", RegexOption.IGNORE_CASE)

        private val refTags = Regex("<ref\\b[^>]*?/>|<ref\\b[^>]*>.*?</ref\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val htmlTags = Regex("</?[a-zA-Z][^>]*>")
        private val innermostTemplate = Regex("\\{\\{([^{}]*)\\}\\}")
        private val wikiLink = Regex("\\[\\[([^\\]|]*)(?:\\|([^\\]]*))?\\]\\]")
        private val wikiNamespaceTarget = Regex("\\s*(?:file|image|category|media):.*", RegexOption.IGNORE_CASE)
        private val externalLink = Regex("\\[(?:https?:)?//[^\\s\\]]+(?:\\s+([^\\]]*))?\\]", RegexOption.IGNORE_CASE)
        private val bareUrl = Regex("(?:https?://|www\\.)\\S+", RegexOption.IGNORE_CASE)
        private val htmlEntity = Regex("&(?:[a-zA-Z]+|#\\d+);")
        private val whitespace = Regex("\\s+")

        private fun cargoLiteral(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

        /**
         * Reader-visible text of wikitext. References, tags, URLs and link markup carry file extensions and words
         * ("download", "version", "only") that say nothing about the game's HDR, so the heuristics read this instead.
         * Template arguments and link targets stay because `{{file|x.ini}}` or `[[Special K]]` are real evidence.
         */
        internal fun plainText(wikitext: String): String {
            var text = refTags.replace(wikitext, " ")
            text = htmlTags.replace(text, " ")
            do {
                val before = text
                text = innermostTemplate.replace(text) { match ->
                    // Drop the template name and named arguments ("url=...", "title=..."); keep positional ones.
                    match.groupValues[1].split('|').drop(1).filter { '=' !in it }.joinToString(" ")
                }
            } while (text != before)
            text = wikiLink.replace(text) { match ->
                val target = match.groupValues[1]
                val label = match.groupValues[2]
                val keptTarget = if (target.startsWith("#") || wikiNamespaceTarget.matches(target)) "" else target
                (keptTarget + " " + label).trim()
            }
            text = externalLink.replace(text) { it.groupValues[1] }
            text = bareUrl.replace(text, " ")
            return htmlEntity.replace(text.replace("'''", "").replace("''", ""), " ").replace(whitespace, " ").trim()
        }

        private fun startsReference(text: String, index: Int): Boolean {
            if (!text.startsWith("<ref", index, ignoreCase = true)) return false
            val next = text.getOrNull(index + 4)
            return next == null || next == '>' || next == '/' || next.isWhitespace()
        }

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
                if (startsReference(text, i)) {
                    // A reference may contain pipes that are not Video parameters.
                    val tagEnd = text.indexOf('>', i)
                    val close = if (tagEnd < 0 || text[tagEnd - 1] == '/') -1 else text.indexOf("</ref", tagEnd, ignoreCase = true)
                    i = if (close >= 0) close + 5 else i + 4
                    continue
                }
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
            val prose = notes?.let(::plainText)
            val support = when {
                value == "false" -> NativeHdr.SDR
                value == "hackable" && prose != null && retrofitOnly.containsMatchIn(prose) -> NativeHdr.SDR
                value != "true" && value != "always on" && value != "alwayson" -> NativeHdr.UNKNOWN
                prose != null && (workaround.containsMatchIn(prose) || retrofitOnly.containsMatchIn(prose) ||
                    limitedSupport.containsMatchIn(prose)) -> NativeHdr.UNKNOWN
                else -> NativeHdr.HDR
            }
            return HdrEvidence(support, page, notes)
        }
    }
}
