package com.limelight.shell.data

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Fully answered records are refreshed weekly. */
internal val METADATA_MAX_AGE = TimeUnit.DAYS.toMillis(7)

/** An unmatched game is retried sooner: a transient provider glitch must not hide it for a week. */
internal val METADATA_ABSENT_MAX_AGE = TimeUnit.HOURS.toMillis(6)

/** Minimum time before a provider that failed is asked about the same game again. */
internal val METADATA_FAILURE_BACKOFF = TimeUnit.MINUTES.toMillis(30)

/** True when [time] is a real timestamp that is not in the future and younger than [maxAge]. */
internal fun isFresh(time: Long, maxAge: Long, now: Long) = time in 1..now && now - time < maxAge

/** True while a recorded provider failure at [failedAt] still suppresses retries. A future timestamp never does. */
internal fun isBackedOff(failedAt: Long, now: Long) = failedAt in 1..now && now - failedAt < METADATA_FAILURE_BACKOFF

/**
 * Whether an automatic pass should contact providers for this record. Failures are retried per provider
 * only after their backoff, so refocusing a game whose provider keeps failing does not re-run the lookup.
 */
internal fun refreshDue(data: GameMetadata?, now: Long): Boolean {
    if (data == null) return true
    val failures = listOf(data.igdbFailedAt, data.pcgwFailedAt, data.hltbFailedAt).filter { it > 0 }
    if (data.candidate == null) {
        if (failures.isNotEmpty()) return failures.any { !isBackedOff(it, now) }
        return !isFresh(data.updatedAt, METADATA_ABSENT_MAX_AGE, now)
    }
    if (isFresh(data.updatedAt, METADATA_MAX_AGE, now)) return false
    return failures.isEmpty() || failures.any { !isBackedOff(it, now) }
}

private fun <T : Any> nullable(value: T?): T? = value

/**
 * Gson bypasses Kotlin constructors, so a missing or null field in an older/damaged cache file becomes
 * a null in a non-null property. Returns a record safe to use, or null if its identity is unusable.
 */
internal fun GameMetadata.validated(): GameMetadata? {
    val rawCandidate = nullable(candidate)
    val checkedCandidate = if (rawCandidate == null) null else {
        if (nullable(rawCandidate.name).isNullOrBlank()) return null
        rawCandidate.copy(
            steamAppIds = nullable(rawCandidate.steamAppIds)?.mapNotNull { nullable(it) }.orEmpty(),
            genres = nullable(rawCandidate.genres)?.filter { nullable(it) != null && nullable(it.name) != null }.orEmpty(),
            themes = nullable(rawCandidate.themes)?.filter { nullable(it) != null && nullable(it.name) != null }.orEmpty(),
            platformIds = nullable(rawCandidate.platformIds)?.mapNotNull { nullable(it) }.orEmpty(),
        )
    }
    val checkedHdr = nullable(hdr)?.let { evidence ->
        if (nullable(evidence.support) == null) evidence.copy(support = NativeHdr.UNKNOWN) else evidence
    } ?: HdrEvidence(NativeHdr.UNKNOWN)
    val checkedCompletion = nullable(completion)?.takeIf {
        nullable(it.name) != null && nullable(it.url) != null && nullable(it.headline) != null
    }
    return copy(candidate = checkedCandidate, hdr = checkedHdr, completion = checkedCompletion)
}

private val combiningMarks = "\\p{M}+".toRegex()
private val bracketed = "[(\\[][^)\\]]*[)\\]]".toRegex()
private val apostrophes = "['\u2018\u2019`\u00b4]".toRegex()
private val separators = "[^\\p{L}\\p{N}]+".toRegex()
private val romanNumeral = "^(?:x{1,2}(?:ix|iv|v?i{0,3})|ix|iv|v?i{1,3})$".toRegex()
private val hostYear = "[(\\[]\\s*((?:19|20)\\d{2})\\s*[)\\]]".toRegex()
private val symbols = "[\\p{So}\\p{Cf}]".toRegex()
private val whitespace = "\\s+".toRegex()

/** Trailing edition qualifiers that do not change which game it is, longest first. */
private val EDITION_SUFFIXES: List<List<String>> = listOf(
    "game of the year edition", "game of the year", "goty edition", "goty", "directors cut",
    "definitive edition", "complete edition", "deluxe edition", "ultimate edition", "enhanced edition",
    "special edition", "gold edition", "anniversary edition", "standard edition", "collectors edition",
    "legendary edition", "premium edition", "digital edition", "limited edition", "remastered",
).map { it.split(' ') }.sortedByDescending { it.size }

private fun lowerPlain(name: String): String = Normalizer.normalize(
    name.replace("\u2122", "").replace("\u00ae", "").replace("\u00a9", ""), Normalizer.Form.NFD,
).replace(combiningMarks, "").lowercase(Locale.ROOT)

/** Punctuation/case/diacritics-insensitive name; every other difference stays significant. */
internal fun literalKey(name: String): String = lowerPlain(name).replace(separators, "")

private fun romanValue(token: String): Int {
    fun digit(c: Char) = when (c) { 'i' -> 1; 'v' -> 5; else -> 10 }
    var total = 0
    for (i in token.indices) {
        val current = digit(token[i])
        val next = if (i + 1 < token.length) digit(token[i + 1]) else 0
        total += if (current < next) -current else current
    }
    return total
}

/**
 * Tolerant identity of a game title: ignores bracketed notes such as "(Steam)", a leading "The", "&" versus
 * "and", trailing edition qualifiers ("Definitive Edition", "GOTY", "Director's Cut") and Roman versus Arabic
 * sequel numbers. Subtitles stay significant.
 */
internal fun matchKey(name: String): String {
    val tokens = lowerPlain(name).replace(bracketed, " ").replace("&", " and ").replace(apostrophes, "")
        .split(separators).filter { it.isNotEmpty() && it != "and" }.toMutableList()
    if (tokens.size > 1 && tokens[0] == "the") tokens.removeAt(0)
    var stripped = true
    while (stripped) {
        stripped = false
        for (suffix in EDITION_SUFFIXES) {
            if (tokens.size > suffix.size && tokens.subList(tokens.size - suffix.size, tokens.size) == suffix) {
                repeat(suffix.size) { tokens.removeAt(tokens.size - 1) }
                if (tokens.size > 1 && tokens.last() == "the") tokens.removeAt(tokens.size - 1)
                stripped = true
                break
            }
        }
    }
    return tokens.joinToString("") { token ->
        if (token.length >= 2 && romanNumeral.matches(token)) romanValue(token).toString() else token
    }
}

/**
 * The text sent to IGDB's name search. Trademark and copyright signs that Steam puts in names ("LEGO® Batman™")
 * make IGDB return nothing for the whole query ("®" and "©" do; "™" happens not to), so every "other symbol"
 * character is dropped; they carry no part of the title.
 */
internal fun searchTerm(name: String): String = name.replace(symbols, "").replace(whitespace, " ").trim()

/**
 * Picks the IGDB entry for a host app name, or null when the name is unmatched or still ambiguous.
 * Entries sharing the tolerant name (for example "Doom" 1993 and "DOOM" 2016) are narrowed by the literal name,
 * a release year written in the host name, a Steam listing, the PC platform, the newest release and finally a
 * clearly most-rated entry, each step applying only if it leaves at least one candidate. When nothing has the
 * tolerant name, a host name that is the title without its subtitle ("Planet of Lana II" for "Planet of Lana II:
 * Children of the Leaf") matches the one PC/Steam entry that extends it with a subtitle.
 */
internal fun chooseMatch(hostName: String, candidates: List<MetadataCandidate>): MetadataCandidate? {
    val key = matchKey(hostName)
    if (key.isEmpty()) return null
    var pool = candidates.filter { matchKey(it.name) == key }
    if (pool.isEmpty()) return subtitled(hostName, candidates)
    if (pool.size == 1) return pool.single()
    fun narrow(predicate: (MetadataCandidate) -> Boolean): MetadataCandidate? {
        val narrowed = pool.filter(predicate)
        if (narrowed.isNotEmpty()) pool = narrowed
        return pool.singleOrNull()
    }
    val literal = literalKey(hostName)
    narrow { literalKey(it.name) == literal }?.let { return it }
    hostYear.find(hostName)?.groupValues?.get(1)?.toInt()?.let { year ->
        narrow { it.year == year }?.let { return it }
    }
    narrow { it.steamAppIds.isNotEmpty() }?.let { return it }
    narrow { it.isPc }?.let { return it }
    pool.mapNotNull { it.year }.maxOrNull()?.let { newest -> narrow { it.year == newest }?.let { return it } }
    val ranked = pool.sortedByDescending { it.ratingCount }
    return ranked.first().takeIf { it.ratingCount > 0 && it.ratingCount >= 3L * ranked[1].ratingCount }
}

private fun subtitled(hostName: String, candidates: List<MetadataCandidate>): MetadataCandidate? {
    val host = lowerPlain(hostName).replace(bracketed, " ").replace(whitespace, " ").trim()
    if (host.isEmpty()) return null
    return candidates.filter { candidate ->
        val name = lowerPlain(candidate.name).replace(whitespace, " ")
        (candidate.isPc || candidate.steamAppIds.isNotEmpty()) && name.startsWith(host) &&
            name.substring(host.length).let { it.startsWith(":") || it.startsWith(" - ") || it.startsWith(" \u2013 ") || it.startsWith(" \u2014 ") }
    }.singleOrNull()
}
