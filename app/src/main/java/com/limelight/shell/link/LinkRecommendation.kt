package com.limelight.shell.link

import android.content.res.Resources
import com.limelight.R

/** What the recommended bitrate rests on. */
internal enum class LinkReason {
    /** Only PyroWave's own guide for this format; the link was never tested. */
    UNTESTED,

    /** The tested link carries what the format wants. */
    TESTED_OK,

    /** The tested link carries less than the format wants. */
    TESTED_LIMITED,

    /** The PC's own network link (`PyroWaveHostLinkMbps`) is below what the format wants. */
    HOST_LINK_LIMITED,

    /** The running stream drops frames at its bitrate. */
    DROPPING,
}

/**
 * [kbps] is the bitrate to offer; [reason] says why, and [reasonText] words it from the numbers below. [wantedKbps] is what the format would use if the
 * link allowed (PyroWave's guide, or an ordinary codec's useful maximum). [pictureKbps] is what a tested PyroWave budget
 * leaves for the image after FEC and overhead, or -1. [testedKbps] is the raw tested ceiling the reason names, or 0.
 * [hostLinkKbps] is the PC's own link when that is the limit, [droppedAtKbps] the bitrate a running stream drops frames at.
 */
internal data class LinkRecommendation(
    val kbps: Int,
    val reason: LinkReason,
    val pictureKbps: Int = -1,
    val wantedKbps: Int = 0,
    val testedKbps: Int = 0,
    val hostLinkKbps: Int = 0,
    val droppedAtKbps: Int = 0,
) {
    /** The clause that says why ("link carries up to 665 Mbps"). */
    fun reasonText(res: Resources): String = when (reason) {
        LinkReason.UNTESTED -> res.getString(R.string.link_reason_untested)
        LinkReason.TESTED_OK -> res.getString(R.string.link_reason_tested_ok, mbps(testedKbps))
        LinkReason.TESTED_LIMITED -> res.getString(R.string.link_reason_tested_limited, mbps(testedKbps))
        LinkReason.HOST_LINK_LIMITED -> res.getString(R.string.link_reason_host_limited, mbps(hostLinkKbps))
        LinkReason.DROPPING -> res.getString(R.string.link_reason_dropping, mbps(droppedAtKbps))
    }

    /** "Recommended: 225 Mbps · link carries up to 665 Mbps". */
    fun line(res: Resources): String = res.getString(R.string.link_recommended_line, mbps(kbps), reasonText(res))

    /** "225 Mbps · link carries up to 665 Mbps", the value under Use recommended bitrate. */
    fun valueText(res: Resources): String = res.getString(R.string.link_value_reason, mbps(kbps), reasonText(res))
}

/** The most an ordinary codec is worth at [defaultKbps] (the app's default for the size and rate): a multiple of it, capped. */
internal fun ordinaryWantedKbps(codec: LinkCodec, defaultKbps: Int): Int {
    val factor = when (codec) {
        LinkCodec.H264 -> LinkPolicy.H264_CAP_FACTOR
        LinkCodec.HEVC -> LinkPolicy.HEVC_CAP_FACTOR
        else -> LinkPolicy.AV1_CAP_FACTOR
    }
    val scaled = (defaultKbps * factor / LinkPolicy.STEP_KBPS).toInt() * LinkPolicy.STEP_KBPS
    return minOf(LinkPolicy.ORDINARY_MAX_KBPS, maxOf(defaultKbps, scaled))
}

/**
 * The recommended bitrate: what the format wants ([staticKbps]: PyroWave's guide, or an ordinary codec's useful maximum),
 * held to what the link carries. The bitrate setting is a total wire allowance, like the probe's rate, so they compare
 * directly, after [share] (1 for PyroWave; [LinkPolicy.ORDINARY_BUDGET_SHARE] for the codecs with FEC and bursty rate control).
 *
 * - [testedWireKbps]: the last confirmed link test, if any.
 * - [hostLinkKbps]: the PC's routed wired link speed, 0 or less when unknown. A ceiling only.
 * - [untestedKbps]: what is offered while nothing was tested (defaults to [staticKbps]; an ordinary codec offers its plain default).
 * - [live]: the running stream's drop hint ([LinkMonitor.hint]); it can only lower the offer.
 * - [fps] and [transport] are optional; with them a tested PyroWave budget also reports the picture bitrate it carries.
 *
 * Never above what is wanted (more does not improve the picture) and never under the probe's 5 Mbps floor.
 */
internal fun recommend(
    staticKbps: Int,
    testedWireKbps: Int? = null,
    hostLinkKbps: Int = 0,
    live: LinkReading? = null,
    fps: Int = 0,
    transport: WireTransport? = null,
    share: Double = 1.0,
    untestedKbps: Int = staticKbps,
): LinkRecommendation {
    val tested = testedWireKbps?.takeIf { it > 0 }
    val ceiling = hostLinkKbps.takeIf { it > 0 }
    val testedBudget = tested?.let { (it * share).toInt() }
    val ceilingBudget = ceiling?.let { (it * share).toInt() }
    val budget = listOfNotNull(testedBudget, ceilingBudget).minOrNull()
    val base = if (tested != null) staticKbps else untestedKbps
    var kbps = if (budget == null) base else minOf(base, budget)
    kbps = maxOf(LinkPolicy.MIN_KBPS, kbps / LinkPolicy.STEP_KBPS * LinkPolicy.STEP_KBPS).coerceAtMost(maxOf(base, LinkPolicy.MIN_KBPS))

    val picture = if (tested != null && fps > 0 && transport != null && share == 1.0) {
        WireBudget.imageKbps(tested.toDouble(), fps, transport).toInt()
    } else -1
    fun make(reason: LinkReason) = LinkRecommendation(kbps, reason, picture, staticKbps, tested ?: 0, ceiling ?: 0)
    var result = when {
        budget == null || (tested == null && budget >= base) -> make(LinkReason.UNTESTED)
        testedBudget != null && testedBudget >= staticKbps && (ceilingBudget == null || ceilingBudget >= staticKbps) ->
            make(LinkReason.TESTED_OK)
        testedBudget != null && (ceilingBudget == null || testedBudget <= ceilingBudget) ->
            make(LinkReason.TESTED_LIMITED)
        else -> make(LinkReason.HOST_LINK_LIMITED)
    }
    if (live != null && live.verdict == LinkVerdict.DROPPING && live.suggestedKbps < result.kbps) {
        result = result.copy(
            kbps = maxOf(LinkPolicy.MIN_KBPS, live.suggestedKbps),
            reason = LinkReason.DROPPING, droppedAtKbps = live.targetKbps,
        )
    }
    return result
}
