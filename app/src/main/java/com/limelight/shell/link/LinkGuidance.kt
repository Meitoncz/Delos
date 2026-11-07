package com.limelight.shell.link

import com.limelight.nvstream.http.PyroWaveHostInfo
import java.util.TimeZone

/**
 * What the screens show about the link for one PyroWave format: the recommendation with its reason, the last
 * test (if any) with its age, and the note when the PC cannot test. Built from plain values so every screen
 * (settings, per-game settings, stream menu) says the same thing.
 */
internal data class LinkGuidance(
    val recommendation: LinkRecommendation,
    val tested: LinkTest?,
    /** Set when the PC cannot run the test (older Vibepollo, or a newer protocol than this Delos knows). */
    val testUnavailable: String?,
) {
    /** The `Recommended: N Mbps · reason` line of the bitrate row. */
    val line: String get() = recommendation.line

    /** The long text of the help panel: the test with its age, or why there is none. */
    fun help(nowMs: Long, transport: LinkTransport, zone: TimeZone = TimeZone.getDefault()): String {
        val test = tested ?: return testUnavailable ?: "Not tested on ${transport.label}. Test link measures what it carries."
        val picture = recommendation.pictureKbps.takeIf { it > 0 }
            ?.let { ", about ${mbps(it)} of picture after FEC and overhead" }.orEmpty()
        return "${test.summary(nowMs, zone)}: ${mbps(test.wireKbps)}$picture."
    }

    /**
     * The stream menu's help for the recommendation row. There is no test here (the host refuses probes during a
     * stream), so an untested link points to Settings, before connecting.
     */
    fun streamHelp(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val state = when {
            recommendation.reason == LinkReason.DROPPING -> "Link ${recommendation.reasonText}."
            tested != null -> "${tested.summary(nowMs, zone)}."
            else -> "Not tested yet."
        }
        val test = if (tested == null) " Test the link in Settings before connecting." else ""
        return "$state A fills the slider, Apply sends it.$test"
    }

    /** What the Test link row says under its label. */
    fun testRowText(nowMs: Long, transport: LinkTransport, zone: TimeZone = TimeZone.getDefault()): String =
        tested?.summary(nowMs, zone) ?: testUnavailable ?: "Not tested on ${transport.label} yet"
}

/**
 * [staticKbps] is PyroWave's guide for the chosen format at [fps]. [info] is what the PC advertises (use
 * [PyroWaveHostInfo.NONE] when it is not known), [tested] the stored result for this PC on the current kind of
 * network, [live] the running stream's drop hint.
 */
internal fun linkGuidance(
    staticKbps: Int,
    fps: Int,
    info: PyroWaveHostInfo,
    tested: LinkTest?,
    live: LinkReading? = null,
): LinkGuidance {
    val transport = tested?.let { WireTransport(it.packetSize, it.criticalFecPercent) }
    val unavailable = when {
        info.supportsLinkTest() -> null
        info.linkTestNeedsNewerClient() -> "This PC needs a newer Delos for the link test."
        // NONE is also what an unpolled PC has: it only says the PC cannot test once it answered /serverinfo
        else -> "This PC's Vibepollo cannot test the link yet. Update it."
    }
    return LinkGuidance(
        recommend(staticKbps, tested?.wireKbps, info.hostLinkMbps * 1000, live, fps, transport),
        tested, unavailable,
    )
}
