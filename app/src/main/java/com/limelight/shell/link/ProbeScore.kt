package com.limelight.shell.link

import kotlin.math.abs

/** Why a probe did not pass, in the order the reference client checks them. */
internal enum class ProbeFailure {
    /** The host sent fewer than 98 % of the planned packets (it is overloaded, or its send buffer overflowed). */
    NOT_ALL_SENT,

    /** Nothing authenticated arrived (blocked UDP, wrong network, firewall). */
    NO_PACKETS,

    /** The host's sending took more than 2 % longer or shorter than the requested two seconds. */
    SENDER_DURATION,
    LOSS,
    WINDOW_LOSS,
    DELAY_GROWTH,
}

/** What one probe measured, graded against [LinkPolicy]. [failure] is null when the probe passed. */
internal data class ProbeScore(
    val requestedKbps: Int,
    val expected: Int,
    val sent: Int,
    val received: Int,
    val senderMs: Double,
    val lossPercent: Double,
    val worstWindowLossPercent: Double,
    /** 99th percentile of the packets' transit time above the fastest one, in ms. */
    val delayP99Ms: Double,
    /** Mean transit of the last 100 ms window less that of the first, in ms; infinite when a window is empty. */
    val delayGrowthMs: Double,
    val failure: ProbeFailure?,
    /** What the OS granted for the receive buffer (0 = unknown) and the longest the receive loop was busy with one datagram. */
    val rcvBufBytes: Int = 0,
    val maxStallMs: Double = 0.0,
) {
    val passed: Boolean get() = failure == null

    /** p99 transit variation above 4 ms: a warning on a passing probe, never a failure (the reference's rule). */
    val jitterWarning: Boolean get() = delayP99Ms > LinkPolicy.JITTER_WARNING_P99_MS

    /** A loss failure that our own receiver may have caused: a small granted buffer or a late receive loop. */
    val receiverSuspect: Boolean
        get() = (failure == ProbeFailure.LOSS || failure == ProbeFailure.WINDOW_LOSS) &&
            (maxStallMs > LinkPolicy.RECEIVER_STALL_MS || (rcvBufBytes in 1 until LinkPolicy.MIN_GOOD_RCVBUF_BYTES))
}

/** Packets a probe of [kbps] plans: the host's `kbps * 2000 / (8 * (packetsize + 134))`, truncated. */
internal fun expectedProbePackets(kbps: Int, packetSize: Int): Long =
    kbps.toLong() * LinkPolicy.PROBE_DURATION_MS / (8L * (packetSize + LinkPolicy.WIRE_OVERHEAD_BYTES))

/**
 * Grades one probe. [arrivalsUs] has one entry per planned sequence number: the packet's arrival time in
 * microseconds on any local clock, or a negative value when it never arrived (duplicates and foreign packets
 * must already have been dropped by the receiver). [expected], [sent] and [senderMs] are the host's own
 * numbers from the HTTPS response.
 *
 * Delay is relative: transit is the arrival time less the packet's slot in the host's 1 ms schedule, so the
 * two clocks' offset never enters the grade, only the spread of the transits and their growth do.
 */
internal fun scoreProbe(
    requestedKbps: Int, expected: Int, sent: Int, senderMs: Double, arrivalsUs: LongArray,
    rcvBufBytes: Int = 0, maxStallMs: Double = 0.0,
): ProbeScore {
    val windows = LinkPolicy.WINDOW_COUNT
    var received = 0
    var worstWindow = 0.0
    var firstSum = 0.0
    var firstCount = 0
    var lastSum = 0.0
    var lastCount = 0
    val transit = DoubleArray(expected)
    var transitCount = 0
    for (window in 0 until windows) {
        val begin = (expected.toLong() * window / windows).toInt()
        val end = (expected.toLong() * (window + 1) / windows).toInt()
        var count = 0
        for (seq in begin until end) {
            val arrival = if (seq < arrivalsUs.size) arrivalsUs[seq] else -1L
            if (arrival < 0) continue
            count++
            // The sender's own 1 ms group schedule: packet seq leaves in the group ceil((seq + 1) * 2000 / expected) - 1.
            val tick = ((seq + 1L) * LinkPolicy.PROBE_DURATION_MS + expected - 1) / expected - 1
            val delta = arrival / 1000.0 - tick
            transit[transitCount++] = delta
            if (window == 0) { firstSum += delta; firstCount++ }
            if (window == windows - 1) { lastSum += delta; lastCount++ }
        }
        received += count
        if (end > begin) worstWindow = maxOf(worstWindow, 100.0 * (end - begin - count) / (end - begin))
    }
    val loss = if (expected > 0) 100.0 * (expected - received) / expected else 100.0
    var p99 = 0.0
    if (transitCount > 0) {
        transit.sort(0, transitCount)
        p99 = transit[(transitCount - 1) * 99 / 100] - transit[0]
    }
    val growth = if (firstCount > 0 && lastCount > 0) lastSum / lastCount - firstSum / firstCount else Double.POSITIVE_INFINITY
    val duration = LinkPolicy.PROBE_DURATION_MS.toDouble()
    val failure = when {
        expected <= 0 || sent > expected || sent < expected * LinkPolicy.MIN_SENT_SHARE -> ProbeFailure.NOT_ALL_SENT
        received == 0 -> ProbeFailure.NO_PACKETS
        !senderMs.isFinite() || abs(senderMs - duration) > duration * LinkPolicy.DURATION_TOLERANCE -> ProbeFailure.SENDER_DURATION
        loss >= LinkPolicy.MAX_LOSS_PERCENT -> ProbeFailure.LOSS
        worstWindow >= LinkPolicy.MAX_WINDOW_LOSS_PERCENT -> ProbeFailure.WINDOW_LOSS
        growth > LinkPolicy.MAX_DELAY_GROWTH_MS -> ProbeFailure.DELAY_GROWTH
        else -> null
    }
    return ProbeScore(requestedKbps, expected, sent, received, senderMs, loss, worstWindow, p99, growth, failure, rcvBufBytes, maxStallMs)
}
