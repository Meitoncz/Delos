package com.limelight.shell.link

import android.content.res.Resources
import com.limelight.R

/** The real reason a probe failed, in plain words, with its rate ("Lost 3.0% of the test packets at 450 Mbps"). */
internal fun ProbeScore.reasonText(res: Resources): String {
    val rate = mbps(requestedKbps)
    return when {
        receiverSuspect -> res.getString(R.string.link_verdict_receiver_suspect, rate)
        failure == ProbeFailure.LOSS -> res.getString(R.string.link_verdict_loss, lossPercent, rate)
        failure == ProbeFailure.WINDOW_LOSS -> res.getString(R.string.link_verdict_window_loss, worstWindowLossPercent, rate)
        failure == ProbeFailure.DELAY_GROWTH -> res.getString(R.string.link_verdict_delay_growth, rate)
        failure == ProbeFailure.NO_PACKETS -> res.getString(R.string.link_verdict_no_packets)
        failure == ProbeFailure.NOT_ALL_SENT ->
            res.getString(R.string.link_verdict_not_all_sent, if (expected > 0) 100.0 * sent / expected else 0.0, rate)
        failure == ProbeFailure.SENDER_DURATION -> res.getString(R.string.link_verdict_sender_duration)
        else -> res.getString(R.string.link_verdict_passed, rate)
    }
}

/** The sentence for a search that found no rate: the last probe's real reason, and whether anything passed at all. */
internal fun LinkSearchResult.NoRate.noRateText(res: Resources): String {
    val reason = last?.reasonText(res) ?: res.getString(R.string.link_verdict_no_rate_tested)
    return when {
        bestPassKbps > 0 -> res.getString(R.string.link_no_rate_passed_once, reason, mbps(bestPassKbps))
        last != null -> res.getString(R.string.link_no_rate_nothing_passed, reason, mbps(last.requestedKbps))
        else -> res.getString(R.string.link_no_rate_plain, reason)
    }
}

/** The verdict of a passing probe: "Stable" or "Stable, a little jitter". */
internal fun ProbeScore.verdictText(res: Resources): String =
    if (!passed) reasonText(res) else res.getString(if (jitterWarning) R.string.link_verdict_stable_jitter else R.string.link_verdict_stable)
