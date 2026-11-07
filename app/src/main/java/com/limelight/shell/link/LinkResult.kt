package com.limelight.shell.link

import java.util.TimeZone

/** The kind of network a result was measured on; the cheap stand-in for a network identity (an SSID needs a location permission). */
internal enum class LinkTransport(val label: String) {
    WIFI("Wi-Fi"),
    ETHERNET("Ethernet"),
    OTHER("this network");

    companion object {
        fun parse(name: String?): LinkTransport = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/**
 * The last confirmed link test of one PC on one kind of network. [wireKbps] is the total wire allowance the
 * path carried twice (5 % margin included). Never applied by itself: it only feeds the recommendation.
 */
internal data class LinkTest(
    val wireKbps: Int,
    val testedAtMs: Long,
    val transport: LinkTransport,
    val criticalFecPercent: Int,
    val packetSize: Int,
) {
    fun encode(): String = "1;$wireKbps;$testedAtMs;${transport.name};$criticalFecPercent;$packetSize"

    companion object {
        /** Null for anything this version did not write. */
        fun decode(text: String?): LinkTest? {
            val parts = text?.split(';') ?: return null
            if (parts.size != 6 || parts[0] != "1") return null
            return LinkTest(
                parts[1].toIntOrNull()?.takeIf { it >= LinkPolicy.MIN_KBPS } ?: return null,
                parts[2].toLongOrNull() ?: return null,
                LinkTransport.parse(parts[3]),
                parts[4].toIntOrNull() ?: return null,
                parts[5].toIntOrNull() ?: return null,
            )
        }
    }
}

/**
 * How long ago a result is, by calendar days in [zone]: "just now", "12 min ago", "3 h ago", "yesterday",
 * "4 days ago", "2 weeks ago", "3 months ago". A timestamp in the future (the clock was changed) reads "just now".
 */
internal fun ageText(nowMs: Long, thenMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val elapsed = nowMs - thenMs
    if (elapsed < 60_000L) return "just now"
    val minutes = elapsed / 60_000L
    val today = Math.floorDiv(nowMs + zone.getOffset(nowMs), LinkPolicy.MS_PER_DAY)
    val day = Math.floorDiv(thenMs + zone.getOffset(thenMs), LinkPolicy.MS_PER_DAY)
    val days = (today - day).toInt()
    return when {
        days <= 0 -> if (minutes < 60) "$minutes min ago" else "${minutes / 60} h ago"
        days == 1 -> "yesterday"
        days < 7 -> "$days days ago"
        days < 30 -> "${days / 7} ${if (days / 7 == 1) "week" else "weeks"} ago"
        else -> "${days / 30} ${if (days / 30 == 1) "month" else "months"} ago"
    }
}

/** "Tested yesterday on Wi-Fi", the line under the recommendation. */
internal fun LinkTest.summary(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
    "Tested ${ageText(nowMs, testedAtMs, zone)} on ${transport.label}"
