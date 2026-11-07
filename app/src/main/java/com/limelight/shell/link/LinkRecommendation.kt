package com.limelight.shell.link

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

/** [kbps] is the bitrate to offer; [reason] is the one line that says why. [pictureKbps] is what a tested budget leaves for the image after FEC and overhead, or -1. */
internal data class LinkRecommendation(val kbps: Int, val reason: LinkReason, val reasonText: String, val pictureKbps: Int = -1) {
    /** "Recommended: 225 Mbps · link tested at 340 Mbps". */
    val line: String get() = "Recommended: ${mbps(kbps)} · $reasonText"
}

/**
 * The recommended bitrate: PyroWave's guide for the format ([staticKbps], `PyroWaveBitrate.recommendedKbps`), held
 * to what the link carries. The bitrate setting is a total wire allowance, like the probe's rate, so the budgets
 * compare with it directly.
 *
 * - [testedWireKbps]: the last confirmed link test, if any (never older than the user chose to keep).
 * - [hostLinkKbps]: the PC's routed wired link speed, 0 or less when unknown. A ceiling only; it says nothing about the path between.
 * - [live]: the running stream's drop hint ([LinkMonitor.hint]); it can only lower the offer.
 * - [fps] and [transport] are optional; with them a tested budget also reports the picture bitrate it carries.
 *
 * The recommendation never exceeds [staticKbps] (more does not improve the picture) and never goes under the probe's 5 Mbps floor.
 */
internal fun recommend(
    staticKbps: Int,
    testedWireKbps: Int? = null,
    hostLinkKbps: Int = 0,
    live: LinkReading? = null,
    fps: Int = 0,
    transport: WireTransport? = null,
): LinkRecommendation {
    val tested = testedWireKbps?.takeIf { it > 0 }
    val ceiling = hostLinkKbps.takeIf { it > 0 }
    val budget = listOfNotNull(tested, ceiling).minOrNull()
    var kbps = if (budget == null) staticKbps else minOf(staticKbps, budget)
    kbps = maxOf(LinkPolicy.MIN_KBPS, kbps / LinkPolicy.STEP_KBPS * LinkPolicy.STEP_KBPS).coerceAtMost(maxOf(staticKbps, LinkPolicy.MIN_KBPS))

    val picture = if (tested != null && fps > 0 && transport != null) {
        WireBudget.imageKbps(tested.toDouble(), fps, transport).toInt()
    } else -1
    var result = when {
        budget == null || (tested == null && budget >= staticKbps) ->
            LinkRecommendation(kbps, LinkReason.UNTESTED, "not tested on this link")
        tested != null && tested >= staticKbps && (ceiling == null || ceiling >= staticKbps) ->
            LinkRecommendation(kbps, LinkReason.TESTED_OK, "link tested at ${mbps(tested)}", picture)
        tested != null && (ceiling == null || tested <= ceiling) ->
            LinkRecommendation(kbps, LinkReason.TESTED_LIMITED, "link tested at ${mbps(tested)}", picture)
        else ->
            LinkRecommendation(kbps, LinkReason.HOST_LINK_LIMITED, "the PC's link is ${mbps(ceiling!!)}", picture)
    }
    if (live != null && live.verdict == LinkVerdict.DROPPING && live.suggestedKbps < result.kbps) {
        result = LinkRecommendation(
            maxOf(LinkPolicy.MIN_KBPS, live.suggestedKbps),
            LinkReason.DROPPING, "drops frames at ${mbps(live.targetKbps)}", result.pictureKbps,
        )
    }
    return result
}
