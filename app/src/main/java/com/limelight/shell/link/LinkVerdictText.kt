package com.limelight.shell.link

import java.util.Locale

private fun percent(value: Double) = String.format(Locale.US, "%.1f", value)

/** The real reason a probe failed, in plain words, with its rate ("Lost 3.0% of the test packets at 450 Mbps"). */
internal fun ProbeScore.reasonText(): String {
    val rate = mbps(requestedKbps)
    return when {
        receiverSuspect -> "This device could not receive $rate that fast"
        failure == ProbeFailure.LOSS -> "Lost ${percent(lossPercent)}% of the test packets at $rate"
        failure == ProbeFailure.WINDOW_LOSS -> "Lost up to ${percent(worstWindowLossPercent)}% of the packets within 100 ms at $rate"
        failure == ProbeFailure.DELAY_GROWTH -> "The delay kept growing at $rate (the link queues up)"
        failure == ProbeFailure.NO_PACKETS -> "No test data arrived. UDP may be blocked on this network"
        failure == ProbeFailure.NOT_ALL_SENT ->
            "The PC sent only ${percent(if (expected > 0) 100.0 * sent / expected else 0.0)}% of the test data at $rate"
        failure == ProbeFailure.SENDER_DURATION -> "The PC's timing was off during the test"
        else -> "Passed at $rate"
    }
}

/** The sentence for a search that found no rate: the last probe's real reason, and whether anything passed at all. */
internal fun LinkSearchResult.NoRate.noRateText(): String {
    val reason = last?.reasonText() ?: "No rate could be tested"
    return when {
        bestPassKbps > 0 -> "$reason. ${mbps(bestPassKbps)} passed once but did not hold."
        last != null -> "$reason. Nothing passed, down to ${mbps(last.requestedKbps)}."
        else -> "$reason."
    }
}

/** The verdict of a passing probe: "Stable" or "Stable, a little jitter". */
internal fun ProbeScore.verdictText(): String =
    if (!passed) reasonText() else if (jitterWarning) "Stable, a little jitter" else "Stable"
