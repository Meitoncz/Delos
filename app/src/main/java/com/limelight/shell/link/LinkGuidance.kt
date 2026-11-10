package com.limelight.shell.link

import android.content.res.Resources
import com.limelight.R
import com.limelight.nvstream.http.PyroWaveHostInfo
import java.util.TimeZone

/** Why the PC cannot run the link test, for [LinkGuidance.testUnavailable]. */
internal enum class LinkUnavailable {
    /** The PC speaks a newer probe protocol than this Delos knows. */
    NEEDS_NEWER_DELOS,

    /** An older Vibepollo, or a host without PyroWave support. */
    HOST_OUTDATED;

    fun text(res: Resources): String = res.getString(
        when (this) {
            NEEDS_NEWER_DELOS -> R.string.link_unavailable_newer_delos
            HOST_OUTDATED -> R.string.link_unavailable_host_outdated
        },
    )
}

/**
 * What the screens show about the link for one stream format: the recommendation with its reason, the last test (if
 * any) with its age, and the note when the PC cannot run the test. Built from plain values so every screen (settings,
 * per-game settings, stream menu) says the same thing. Works for every codec: the probe measures the network, not the codec.
 */
internal data class LinkGuidance(
    val format: LinkFormat,
    val recommendation: LinkRecommendation,
    val tested: LinkTest?,
    /** Set when the PC cannot run the test (older Vibepollo, a host without PyroWave support, or a newer protocol than this Delos knows). */
    val testUnavailable: LinkUnavailable?,
) {
    /** The `Recommended: N Mbps · reason` line of the bitrate row. */
    fun line(res: Resources): String = recommendation.line(res)

    /**
     * The long text of the help panel: the test with its age and the one sentence that names both numbers ("Wi-Fi carries up
     * to 665 Mbps; 225 Mbps is enough for 1080p60 PyroWave 4:2:0."), or why there is no test.
     */
    fun help(res: Resources, nowMs: Long, transport: LinkTransport, zone: TimeZone = TimeZone.getDefault()): String {
        val test = tested ?: return testUnavailable?.text(res) ?: res.getString(R.string.link_help_untested, transport.label(res))
        val r = recommendation
        val wire = mbps(test.wireKbps)
        val sentence = when {
            r.reason == LinkReason.TESTED_OK -> res.getString(R.string.link_help_tested_ok, wire, mbps(r.kbps), format.label)
            r.reason == LinkReason.HOST_LINK_LIMITED -> res.getString(R.string.link_help_host_limited, wire, format.label)
            r.pictureKbps > 0 -> res.getString(R.string.link_help_limited_picture, wire, format.label, mbps(r.wantedKbps), mbps(r.pictureKbps))
            else -> res.getString(R.string.link_help_limited, wire, format.label, mbps(r.wantedKbps))
        }
        return res.getString(R.string.link_help_joined, test.summary(res, nowMs, zone), sentence)
    }

    /**
     * The stream menu's help for the recommendation row. There is no test here (the host refuses probes during a
     * stream), so an untested link points to Settings, before connecting.
     */
    fun streamHelp(res: Resources, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val dropping = recommendation.reason == LinkReason.DROPPING
        val dropped = mbps(recommendation.droppedAtKbps)
        return when {
            dropping && tested != null -> res.getString(R.string.link_stream_help_dropping, dropped)
            dropping -> res.getString(R.string.link_stream_help_dropping_untested, dropped)
            tested != null -> res.getString(R.string.link_stream_help_tested, tested.summary(res, nowMs, zone))
            else -> res.getString(R.string.link_stream_help_untested)
        }
    }

    /** What the Test link row says under its label. */
    fun testRowText(res: Resources, nowMs: Long, transport: LinkTransport, zone: TimeZone = TimeZone.getDefault()): String =
        tested?.summary(res, nowMs, zone) ?: testUnavailable?.text(res) ?: res.getString(R.string.link_test_row_untested, transport.label(res))
}

/**
 * [format] is the stream the user asked for, [info] what the PC advertises (use [PyroWaveHostInfo.NONE] when it is not
 * known), [tested] the stored result for this PC on the current kind of network, [live] the running stream's drop hint
 * (PyroWave only).
 */
internal fun linkGuidance(
    format: LinkFormat,
    info: PyroWaveHostInfo,
    tested: LinkTest?,
    live: LinkReading? = null,
): LinkGuidance {
    val transport = tested?.let { WireTransport(it.packetSize, it.criticalFecPercent) }
    val unavailable = when {
        info.supportsLinkTest() -> null
        info.linkTestNeedsNewerClient() -> LinkUnavailable.NEEDS_NEWER_DELOS
        // NONE is also what an unpolled PC has: it only says the PC cannot test once it answered /serverinfo
        else -> LinkUnavailable.HOST_OUTDATED
    }
    val hostLink = info.hostLinkMbps * 1000
    val recommendation = if (format.codec == LinkCodec.PYROWAVE) {
        recommend(format.defaultKbps, tested?.wireKbps, hostLink, live, format.fps, transport)
    } else {
        recommend(ordinaryWantedKbps(format.codec, format.defaultKbps), tested?.wireKbps, hostLink, null,
            share = LinkPolicy.ORDINARY_BUDGET_SHARE, untestedKbps = format.defaultKbps)
    }
    return LinkGuidance(format, recommendation, tested, unavailable)
}
