package com.limelight.diag

import java.util.Locale

/**
 * What only this install knows and no pattern can recognise; the caller reads it from the stored PC list, the
 * profiles and the preferences at the moment of the export, so a renamed PC is covered too.
 */
data class ScrubContext(
    /** Names of the PCs (their own and the ones given in Delos). They become `<pc-1>`, `<pc-2>`, ... */
    val pcNames: List<String> = emptyList(),
    /** Host names and addresses typed in or learned for the PCs. They become `<host>`. */
    val hostAddresses: List<String> = emptyList(),
    /** Names of people or devices: profile names, the device's own name. They become `<name>`. */
    val names: List<String> = emptyList(),
    /** Identifiers of this install: the unique client id, the Android id, certificate ids. They become `<id>`. */
    val identifiers: List<String> = emptyList(),
    /** Keys and tokens, such as the SteamGridDB API key. They become `<secret>`. */
    val secrets: List<String> = emptyList(),
)

/**
 * Removes what must not leave the device from diagnostic text (docs/DIAGNOSTICS.md): IPv4/IPv6 and MAC addresses,
 * PC and host names, the app's client id and other identifiers, URL queries, tokens and API keys, user names in file
 * paths, e-mail addresses, Wi-Fi names and serial numbers. It errs on the side of removing: a harmless version
 * string that looks like an address is replaced too. Addresses and names get a number (`<ip-1>`, `<pc-2>`), the same
 * for the same value within one [LogScrubber], so the log stays readable ("the stream went to ip-1, the poll to ip-2").
 */
class LogScrubber(private val context: ScrubContext = ScrubContext()) {
    private val ips = LinkedHashMap<String, Int>()
    private val macs = LinkedHashMap<String, Int>()

    private val pcNames = context.pcNames.map { it.trim() }.filter { it.length >= MIN_NAME }.distinctBy { it.lowercase(Locale.ROOT) }
    private val literals: List<Pair<Regex, (String) -> String>> = listOf<Pair<Regex, (String) -> String>>(
        literalPattern(context.secrets, MIN_SECRET) to { _: String -> "<secret>" },
        literalPattern(context.identifiers, MIN_SECRET) to { _: String -> "<id>" },
        literalPattern(pcNames, MIN_NAME) to { found: String -> "<pc-${pcNames.indexOfFirst { it.equals(found, ignoreCase = true) } + 1}>" },
        literalPattern(context.hostAddresses, MIN_NAME) to { _: String -> "<host>" },
        literalPattern(context.names, MIN_NAME) to { _: String -> "<name>" },
    ).filterNot { it.first === NO_LITERALS }

    /** Returns [text] without personal data. */
    @Synchronized
    fun scrub(text: String): String {
        var out = text
        out = URL.replace(out) { scrubUrl(it.value) }
        out = SSID.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}<redacted>" }
        for ((pattern, replacement) in literals) out = pattern.replace(out) { replacement(it.value) }
        out = KEY_VALUE.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}<redacted>" }
        out = BEARER.replace(out) { "${it.groupValues[1]} <redacted>" }
        out = QUOTED_HOST.replace(out) { "${it.groupValues[1]}<host>${it.groupValues[3]}" }
        out = UNKNOWN_HOST.replace(out) { if (it.groupValues[2].equals("Unable", ignoreCase = true)) it.value else "${it.groupValues[1]}<host>" }
        out = MAC.replace(out) { "<mac-${macs.getOrPut(it.value.lowercase(Locale.ROOT).replace('-', ':')) { macs.size + 1 }}>" }
        out = scrubIpv6(out)
        out = scrubIpv4(out)
        out = UUID.replace(out, "<uuid>")
        out = LONG_HEX.replace(out, "<hex>")
        out = LONG_TOKEN.replace(out, "<token>")
        out = USER_PATH.replace(out) { "${it.groupValues[1]}<user>" }
        out = VOLUME_PATH.replace(out) { "${it.groupValues[1]}<volume>" }
        out = EMAIL.replace(out, "<email>")
        out = LOCAL_HOST.replace(out, "<host>")
        return out
    }

    private fun ipAlias(address: String) = "<ip-${ips.getOrPut(address.lowercase(Locale.ROOT)) { ips.size + 1 }}>"

    private fun scrubUrl(raw: String): String {
        // Trailing sentence punctuation is not part of the address
        val trimmed = raw.trimEnd('.', ',', ';', ':', '!')
        val tail = raw.substring(trimmed.length)
        val schemeEnd = trimmed.indexOf("://")
        val scheme = trimmed.substring(0, schemeEnd)
        val rest = trimmed.substring(schemeEnd + 3)
        val authorityEnd = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd).substringAfterLast('@')
        var remainder = rest.substring(authorityEnd)
        remainder = remainder.substringBefore('#')
        val query = remainder.substringAfter('?', "")
        val path = remainder.substringBefore('?')
        val (host, port) = splitHostPort(authority)
        val cleanQuery = if (query.isEmpty()) "" else "?" + query.split('&').joinToString("&") { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            if (!pair.contains('=')) key else "$key=${if (SAFE_QUERY_VALUE.matches(value)) value else "<redacted>"}"
        }
        return "$scheme://${urlHost(host)}${if (port.isEmpty()) "" else ":$port"}$path$cleanQuery$tail"
    }

    private fun urlHost(host: String): String {
        val bare = host.removePrefix("[").removeSuffix("]")
        return when {
            bare.isEmpty() -> ""
            isIpv4(bare) || isIpv6(bare) -> ipAlias(bare)
            pcNames.any { it.equals(bare, ignoreCase = true) } ->
                "<pc-${pcNames.indexOfFirst { it.equals(bare, ignoreCase = true) } + 1}>"
            PUBLIC_HOSTS.any { bare.equals(it, ignoreCase = true) || bare.endsWith(".$it", ignoreCase = true) } -> bare
            else -> "<host>"
        }
    }

    private fun splitHostPort(authority: String): Pair<String, String> {
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close > 0) return authority.substring(0, close + 1) to authority.substring(close + 1).removePrefix(":")
        }
        // A bare IPv6 address in a URL has no port; otherwise the last colon separates it
        if (authority.count { it == ':' } > 1) return authority to ""
        return authority.substringBeforeLast(':') to if (authority.contains(':')) authority.substringAfterLast(':') else ""
    }

    private fun scrubIpv4(text: String): String = IPV4.replace(text) { m ->
        if (isIpv4(m.value)) ipAlias(m.value) else m.value
    }

    private fun scrubIpv6(text: String): String = IPV6_CANDIDATE.replace(text) { m ->
        val whole = m.value
        // Sentence punctuation after an address ("seen fe80::1.") is not part of it
        val address = listOf(whole, whole.trimEnd('.'), whole.trimEnd('.', ':')).firstOrNull { isIpv6(it) }
        if (address == null) whole else ipAlias(address) + whole.substring(address.length)
    }

    private companion object {
        const val MIN_NAME = 3
        const val MIN_SECRET = 4

        /** Matches nothing: the pattern of an empty list of known values. */
        val NO_LITERALS = Regex("(?!)")

        /** A name is a whole word: "Home" in the PC list does not turn "homepage" into "<name>page". */
        fun literalPattern(values: Collection<String>, minLength: Int): Regex {
            val parts = values.map { it.trim() }.filter { it.length >= minLength }.distinct().sortedByDescending { it.length }
            if (parts.isEmpty()) return NO_LITERALS
            val alternatives = parts.joinToString("|") { Regex.escape(it) }
            return Regex("(?<![\\p{L}\\p{N}])(?:$alternatives)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        }

        val URL = Regex("""(?i)\b[a-z][a-z0-9+.-]{1,12}://[^\s"'<>\]\)]+""")
        val BEARER = Regex("""(?i)\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+""")
        val SSID = Regex("""(?i)\b(b?ssid)(\s*[=:]\s*)("[^"]*"|[^\r\n,;]*)""")
        val KEY_VALUE = Regex(
            """(?i)(?<![A-Za-z0-9])(unique_?id|uuid|device_?name|client_?id|device_?id|android_?id|serial(?:_?no|_?number)?|ro\.serialno|imei|hostname|host_name|pc_?name|computer_?name|user_?name|password|passwd|pwd|pin|(?:access_|refresh_|id_)?token|api[_-]?key|x-api-key|(?:client_)?secret|authorization|auth|salt|cookie|session_?id|rikey(?:id)?|client_?cert|private_?key)(\s*[=:]\s*)((?:Bearer\s+|Basic\s+)?(?:"[^"]*"|'[^']*'|[^\s,;&)}\]]+))""",
        )
        val QUOTED_HOST = Regex("""(?i)(\bhost\s+")([^"]*)(")""")
        val UNKNOWN_HOST = Regex("""(UnknownHostException:\s*)(\S+)""")
        val MAC = Regex("""(?i)(?<![0-9a-f:-])(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}(?![0-9a-f]|[:-][0-9a-f])""")
        val IPV4 = Regex("""(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?!\d|\.\d)""")
        val IPV6_CANDIDATE = Regex("""(?<![0-9A-Za-z_:.])[0-9A-Fa-f:]*:[0-9A-Fa-f:]*:[0-9A-Fa-f:.]*(?:%[A-Za-z0-9_.-]+)?""")
        val UUID = Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""")
        val LONG_HEX = Regex("""(?i)\b[0-9a-f]{16,}\b""")
        val LONG_TOKEN = Regex("""\b(?=[A-Za-z0-9_-]{32,}\b)(?=[A-Za-z0-9_-]*\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{32,}\b""")
        val USER_PATH = Regex("""(?i)(/Users/|/home/|[A-Za-z]:[\\/]+Users[\\/]+)([^/\\\s:"']+)""")
        val VOLUME_PATH = Regex("""(/storage/|/mnt/media_rw/)(?!emulated\b|self\b)([^/\s:"']+)""")
        val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+""")
        val LOCAL_HOST = Regex(
            """(?i)\b[a-z0-9][a-z0-9_-]*(?:\.[a-z0-9_-]+)*\.(?:local|lan|home|internal|localdomain|home\.arpa|ts\.net|duckdns\.org|dyndns\.[a-z]+|no-ip\.[a-z]+|myfritz\.net|ddns\.net)\b""",
        )

        /** Query values that carry no identity: small numbers, resolutions and booleans. */
        val SAFE_QUERY_VALUE = Regex("""(?i)\d{1,6}|\d{2,5}x\d{2,5}(?:x\d{1,3})?|true|false""")

        /** Public services the app talks to; their address in a URL says nothing about the user. */
        val PUBLIC_HOSTS = listOf("github.com", "steamgriddb.com", "igdb.com", "steampowered.com", "howlongtobeat.com", "pcgamingwiki.com")

        fun isIpv4(s: String): Boolean {
            val parts = s.split('.')
            return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() <= 255 }
        }

        /** A textual IPv6 address with optional zone and IPv4 tail: eight groups, or fewer around one `::`. */
        fun isIpv6(candidate: String): Boolean {
            val address = candidate.substringBefore('%')
            if (address.count { it == ':' } < 2 || address.contains(":::")) return false
            val halves = address.split("::")
            if (halves.size > 2) return false
            val groups = halves.flatMap { if (it.isEmpty()) emptyList() else it.split(':') }
            var count = 0
            groups.forEachIndexed { i, group ->
                when {
                    group.length in 1..4 && group.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } -> count++
                    i == groups.lastIndex && isIpv4(group) -> count += 2
                    else -> return false
                }
            }
            return count > 0 && if (halves.size == 2) count <= 7 else count == 8
        }
    }
}
