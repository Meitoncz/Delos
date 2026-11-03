package com.limelight.shell.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Shared request shape of the Delos metadata Worker (tools/metadata-worker): `GET <base>/v1/...` with the lookup
 * values as query parameters. The Worker validates them again and builds the upstream IGDB/PCGamingWiki queries
 * itself, so the limits below mirror the Worker's (src/validate.js).
 */
internal object MetadataProxy {
    /** Longest search term / wiki title (in code points) the Worker accepts. */
    const val MAX_TERM_LENGTH = 128
    const val MAX_TITLE_LENGTH = 200
    const val MAX_STEAM_IDS = 20
    const val MAX_STEAM_APP_ID = 4_294_967_295L

    private val controls = "[\\p{Cc}\\u2028\\u2029]".toRegex()
    private val whitespace = "\\s+".toRegex()
    // MediaWiki forbids these in page titles, so no wiki page can be named with one.
    private val wikiForbidden = "[#<>\\[\\]{|}]".toRegex()

    /** [baseUrl] plus [path] and the query [params]; null when the build carries no usable service URL. */
    fun url(baseUrl: String, path: String, vararg params: Pair<String, String>): HttpUrl? {
        val base = baseUrl.trim().toHttpUrlOrNull() ?: return null
        if (base.scheme != "https" && base.host != "localhost" && base.host != "10.0.2.2" && base.host != "127.0.0.1") return null
        return base.newBuilder().addPathSegments(path).apply {
            params.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
    }

    /** A search term the Worker accepts: control characters become spaces, at most [MAX_TERM_LENGTH] code points. */
    fun term(value: String): String = clip(value.replace(controls, " ").replace(whitespace, " ").trim(), MAX_TERM_LENGTH)

    /** A wiki page title the Worker accepts, or null when no wiki page can carry that title. */
    fun title(value: String): String? {
        val title = value.replace(controls, " ").replace(whitespace, " ").trim()
        if (title.isEmpty() || wikiForbidden.containsMatchIn(title) || title.codePointCount(0, title.length) > MAX_TITLE_LENGTH) return null
        return title
    }

    private fun clip(value: String, max: Int): String =
        if (value.codePointCount(0, value.length) <= max) value
        else value.substring(0, value.offsetByCodePoints(0, max)).trim()
}
