package com.limelight.shell.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

class PcGamingWikiException(message: String) : IOException(message)

/**
 * Read-only PCGamingWiki Cargo/page lookups through the Delos metadata Worker, which holds the wiki bot login,
 * keeps the session and builds the Cargo/parse/redirect queries itself (`GET <base>/v1/pcgw/...`, answering the
 * wiki's raw JSON). All calls are blocking. Redirects are never followed; any non-2xx answer (including the Worker's
 * 429 rate limit and 503 backoff) or network error is a provider failure, never an absent page.
 */
class PcGamingWikiClient internal constructor(
    http: OkHttpClient,
    private val baseUrl: String,
    private val pacer: RequestPacer,
) {
    constructor(http: OkHttpClient, baseUrl: String) : this(http, baseUrl, RequestPacer(1050))

    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private data class Cached(val time: Long, val evidence: HdrEvidence)
    private val cache = LinkedHashMap<String, Cached>()

    /** [steamAppIds] are every Steam app IGDB lists for the game; any of them may identify the wiki page. */
    @Synchronized fun lookup(steamAppIds: List<Long>, title: String): HdrEvidence {
        require(steamAppIds.all { it > 0 }) { "Steam AppID must be positive" }
        val ids = steamAppIds.filter { it <= MetadataProxy.MAX_STEAM_APP_ID }.distinct().sorted().take(MetadataProxy.MAX_STEAM_IDS)
        if (ids.isEmpty() && title.isBlank()) return HdrEvidence(NativeHdr.UNKNOWN)
        val key = if (ids.isNotEmpty()) "steam:${ids.joinToString(",")}" else "title:${title.trim()}"
        cache[key]?.takeIf { System.nanoTime() - it.time < CACHE_NANOS }?.let { return it.evidence }
        var pages = if (ids.isNotEmpty()) wikiPages("steam" to ids.joinToString(","), withoutSteamId = false)
            else pagesByTitle(title, withoutSteamId = false)
        // Some wiki pages carry no Steam AppID in Cargo (Euro Truck Simulator 2 does not, although its wikitext lists
        // one). A page of exactly the game's title that names no Steam app at all is still this game; one that names
        // another Steam app is a different product.
        if (pages.isEmpty() && ids.isNotEmpty() && title.isNotBlank()) pages = pagesByTitle(title, withoutSteamId = true)
        val evidence = if (pages.isEmpty() || pages.size > MAX_PAGES) HdrEvidence(NativeHdr.UNKNOWN) else {
            val results = pages.map { page ->
                val parsed = api("v1/pcgw/page", "title" to page)
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

    /**
     * The wiki page named [title], a page a redirect of that name points to ("Sea of Stars: Sunset Edition"
     * redirects to "Sea of Stars"), or a page whose title differs only in case and punctuation ("The Witcher 3:
     * Wild Hunt: Remastered" for "The Witcher 3: Wild Hunt Remastered"); the wiki editors decide what is the same page.
     * A title no wiki page can carry (MediaWiki forbids `# < > [ ] { | }`) or that exceeds the wiki's length matches nothing.
     */
    private fun pagesByTitle(title: String, withoutSteamId: Boolean): List<String> {
        val name = MetadataProxy.title(title.trim().replace('_', ' ')) ?: return emptyList()
        fun resolve(page: String): List<String> {
            if (MetadataProxy.title(page) == null) return emptyList()
            wikiPages("page" to page, withoutSteamId).let { if (it.isNotEmpty()) return it }
            val target = redirectTarget(page)?.takeIf { MetadataProxy.title(it) != null } ?: return emptyList()
            return wikiPages("page" to target, withoutSteamId)
        }
        resolve(name).let { if (it.isNotEmpty()) return it }
        val key = literalKey(name)
        for (similar in searchTitles(name).filter { it != name && literalKey(it) == key }.take(2)) {
            resolve(similar).let { if (it.isNotEmpty()) return it }
        }
        return emptyList()
    }

    private fun redirectTarget(name: String): String? {
        val data = api("v1/pcgw/redirect", "title" to name)
        return try {
            data.getAsJsonObject("query")?.getAsJsonArray("redirects")?.lastOrNull()?.asJsonObject?.get("to")?.asString
        } catch (_: RuntimeException) {
            throw PcGamingWikiException("PCGamingWiki returned invalid redirect data")
        }
    }

    private fun searchTitles(name: String): List<String> {
        val data = api("v1/pcgw/search", "q" to name)
        return try {
            data.getAsJsonObject("query")?.getAsJsonArray("search")?.map { it.asJsonObject.get("title").asString }.orEmpty()
        } catch (_: RuntimeException) {
            throw PcGamingWikiException("PCGamingWiki returned invalid search data")
        }
    }

    /** Names of the wiki pages (with a Video template) for [selector] (`steam` ids or a `page` name); optionally only those without a Steam AppID. */
    private fun wikiPages(selector: Pair<String, String>, withoutSteamId: Boolean): List<String> {
        val data = api("v1/pcgw/cargo", selector)
        val rows = try {
            data.getAsJsonArray("cargoquery") ?: throw IllegalArgumentException()
        } catch (_: RuntimeException) {
            throw PcGamingWikiException("PCGamingWiki returned invalid Cargo data")
        }
        // One Steam ID can point at several pages/editions. Never arbitrarily take the first.
        return try {
            rows.map { it.asJsonObject.getAsJsonObject("title") }
                .filter { !withoutSteamId || it.get("Steam")?.takeIf { steam -> steam.isJsonPrimitive }?.asString.isNullOrBlank() }
                .map { it.get("Page").asString }.distinct()
        } catch (_: RuntimeException) {
            throw PcGamingWikiException("PCGamingWiki returned invalid Cargo rows")
        }
    }

    private fun api(path: String, vararg parameters: Pair<String, String>): JsonObject {
        val url = MetadataProxy.url(baseUrl, path, *parameters)
            ?: throw PcGamingWikiException("PCGamingWiki metadata service is not configured")
        pacer.await()
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw PcGamingWikiException("PCGamingWiki HTTP ${response.code}")
            val json = try {
                JsonParser.parseString(response.body.string()).asJsonObject
            } catch (_: RuntimeException) {
                throw PcGamingWikiException("PCGamingWiki returned invalid JSON")
            }
            if (json.has("error") || json.has("errors")) {
                // Provider error text may echo submitted parameters. Do not propagate it or causes.
                throw PcGamingWikiException("PCGamingWiki API request rejected")
            }
            return json
        }
    }

    companion object {
        private val CACHE_NANOS = TimeUnit.HOURS.toNanos(6)
        private const val MAX_PAGES = 3
        private val NATIVE_VALUES = setOf("true", "always on", "alwayson", "limited")
        private val comments = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        private val videoStart = Regex("\\{\\{\\s*(?:Template:)?Video\\s*(?=[|}])", RegexOption.IGNORE_CASE)
        // PCGamingWiki's own HDR value already says the game renders HDR itself ("true", "always on", or "limited" for a
        // native but flawed implementation). The notes can only withdraw that, and only in these specific ways; mentions
        // of config files, mods that improve the image, or "limited" brightness are ordinary caveats, not retractions.
        private val retrofitOnly = Regex("\\b(?:only|requires?|required|must|via|using|through)\\b[^.]{0,60}\\b(?:special\\s*k|reshade|auto\\s*hdr|rtx\\s*hdr|mod|retrofit)\\b|\\b(?:use|enable|with)\\s+(?:special\\s*k|reshade|auto\\s*hdr|rtx\\s*hdr)\\b|\\b(?:no|not|without)\\s+native\\b|\\bnot supported natively", RegexOption.IGNORE_CASE)
        // Platform/edition restrictions need their own context; a bare "only", "version" or "switch" is ordinary prose.
        private val limitedSupport = Regex("\\bconsoles?\\b|\\bxbox\\b|playstation|\\bps[345]\\b|nintendo\\s+switch|\\bswitch\\s+(?:version|edition)\\b|\\bnot supported\\b|\\bunsupported\\b" +
            "|\\b(?:specific|certain|some|particular|newer|older|later|earlier)\\s+(?:versions?|editions?|builds?)\\b" +
            "|\\b(?:enhanced|special|deluxe|ultimate|gold|definitive|complete|anniversary|remastered?)\\s+editions?\\b" +
            "|\\bonly\\b[^.]{0,40}\\b(?:versions?|editions?)\\b", RegexOption.IGNORE_CASE)
        // The HDR output exists but the note says it currently does not work or is not real HDR. A mere "fix broken
        // black level" is a quality caveat and stays out of this.
        private val notWorking = Regex("\\bhdr\\b[^.]{0,30}\\b(?:is|are|was|currently|now|still)\\s+(?:\\w+\\s+)?broken\\b|^\\s*broken\\b|currently disabled|disabled in (?:a |the )?(?:recent|latest|current|last)\\b" +
            "|\\bhdr\\b[^.]{0,30}\\b(?:does not|doesn't|do not|don't|cannot|can't|will not|won't)\\s+work\\b(?!\\s+(?:well|properly|correctly))|\\bno longer\\s+(?:works?|available|supported|present)\\b|\\bsimulated\\s+hdr\\b|\\bfake\\s+hdr\\b", RegexOption.IGNORE_CASE)

        private val refTags = Regex("<ref\\b[^>]*?/>|<ref\\b[^>]*>.*?</ref\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val htmlTags = Regex("</?[a-zA-Z][^>]*>")
        private val innermostTemplate = Regex("\\{\\{([^{}]*)\\}\\}")
        private val wikiLink = Regex("\\[\\[([^\\]|]*)(?:\\|([^\\]]*))?\\]\\]")
        private val wikiNamespaceTarget = Regex("\\s*(?:file|image|category|media):.*", RegexOption.IGNORE_CASE)
        private val externalLink = Regex("\\[(?:https?:)?//[^\\s\\]]+(?:\\s+([^\\]]*))?\\]", RegexOption.IGNORE_CASE)
        private val bareUrl = Regex("(?:https?://|www\\.)\\S+", RegexOption.IGNORE_CASE)
        private val htmlEntity = Regex("&(?:[a-zA-Z]+|#\\d+);")
        private val whitespace = Regex("\\s+")

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
                value == "false" || value == "hackable" -> NativeHdr.SDR
                value !in NATIVE_VALUES -> NativeHdr.UNKNOWN
                prose != null && (retrofitOnly.containsMatchIn(prose) || limitedSupport.containsMatchIn(prose) ||
                    notWorking.containsMatchIn(prose)) -> NativeHdr.UNKNOWN
                else -> NativeHdr.HDR
            }
            return HdrEvidence(support, page, notes)
        }
    }
}
