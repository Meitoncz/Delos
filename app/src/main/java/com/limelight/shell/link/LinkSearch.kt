package com.limelight.shell.link

/** What the search is doing now, for the progress line. */
internal data class LinkStep(val kbps: Int, val confirming: Boolean, val probe: Int)

internal sealed interface LinkSearchResult {
    /** [wireKbps] passed twice afresh (with the 5 % margin applied); [score] is the last of those probes. */
    data class Confirmed(val wireKbps: Int, val score: ProbeScore) : LinkSearchResult

    /** No tested rate passed, down to the 5 Mbps floor; [last] is the final probe. */
    data class NoRate(val last: ProbeScore?) : LinkSearchResult

    data object Cancelled : LinkSearchResult
}

private fun roundDown(kbps: Double): Int = (kbps / LinkPolicy.STEP_KBPS).toInt() * LinkPolicy.STEP_KBPS

/**
 * The host document's calibration, as a pure function of [probe]. It starts at [startKbps] (the selected bitrate,
 * within 5 Mbps and [capKbps]), grows by 25 % while probes pass, then bisects the passing/failing bracket down to 5 Mbps.
 * The best pass is reduced by 5 % and measured twice afresh; a failed confirmation lowers the rate by 20% and tries
 * again, so a noisy path can never inherit an earlier lucky pass. A start that already fails bisects downwards.
 * [probe] may throw (network failure); the exception ends the search. [cancelled] is polled between probes, and
 * a cancelled search discards everything it measured.
 */
internal fun searchLink(
    startKbps: Int,
    capKbps: Int,
    probe: (Int) -> ProbeScore,
    cancelled: () -> Boolean = { false },
    onStep: (LinkStep) -> Unit = {},
): LinkSearchResult {
    val cap = minOf(LinkPolicy.MAX_KBPS, capKbps / LinkPolicy.STEP_KBPS * LinkPolicy.STEP_KBPS)
    if (cap < LinkPolicy.MIN_KBPS) return LinkSearchResult.NoRate(null)
    var probes = 0
    fun measure(kbps: Int, confirming: Boolean): ProbeScore {
        onStep(LinkStep(kbps, confirming, ++probes))
        return probe(kbps)
    }

    var low = 0
    var high = cap + LinkPolicy.STEP_KBPS
    var next = (startKbps / LinkPolicy.STEP_KBPS * LinkPolicy.STEP_KBPS).coerceIn(LinkPolicy.MIN_KBPS, cap)
    var best: ProbeScore? = null
    for (attempt in 0 until LinkPolicy.MAX_SEARCH_PROBES) {
        if (cancelled()) return LinkSearchResult.Cancelled
        val score = measure(next, confirming = false)
        if (cancelled()) return LinkSearchResult.Cancelled
        best = score
        if (score.passed) {
            low = next
            if (low == cap || high - low <= LinkPolicy.STEP_KBPS) break
            next = if (high <= cap) roundDown((low + high) / 2.0)
            else minOf(cap, maxOf(low + LinkPolicy.STEP_KBPS, roundDown(low * LinkPolicy.GROWTH_FACTOR)))
        } else {
            high = next
            if (high - low <= LinkPolicy.STEP_KBPS) break
            next = maxOf(LinkPolicy.MIN_KBPS, roundDown((low + high) / 2.0))
        }
    }
    if (low == 0) return LinkSearchResult.NoRate(best)

    next = maxOf(LinkPolicy.MIN_KBPS, roundDown(low * LinkPolicy.CONFIRM_MARGIN))
    for (attempt in 0 until LinkPolicy.MAX_CONFIRM_ATTEMPTS) {
        var confirmed = true
        var last: ProbeScore? = null
        for (run in 0 until LinkPolicy.CONFIRM_RUNS) {
            if (cancelled()) return LinkSearchResult.Cancelled
            val score = measure(next, confirming = true)
            if (cancelled()) return LinkSearchResult.Cancelled
            best = score
            last = score
            if (!score.passed) { confirmed = false; break }
        }
        if (confirmed && last != null) return LinkSearchResult.Confirmed(next, last)
        if (next == LinkPolicy.MIN_KBPS) break
        next = maxOf(LinkPolicy.MIN_KBPS, roundDown(next * LinkPolicy.CONFIRM_BACKOFF))
    }
    return LinkSearchResult.NoRate(best)
}
