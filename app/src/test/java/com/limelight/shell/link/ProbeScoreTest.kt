package com.limelight.shell.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeScoreTest {
    private val expected = 20_000

    /** Every packet arrives in its sender slot plus a constant transit (the clocks' offset is irrelevant). */
    private fun perfect(transitMs: Double = 5.0, extra: (Int) -> Double = { 0.0 }) = LongArray(expected) { seq ->
        val tick = ((seq + 1L) * 2000 + expected - 1) / expected - 1
        ((tick + transitMs + extra(seq)) * 1000).toLong()
    }

    private fun score(arrivals: LongArray, sent: Int = expected, senderMs: Double = 2000.0) =
        scoreProbe(400_000, expected, sent, senderMs, arrivals)

    @Test
    fun plannedPacketsFollowTheHostsFormula() {
        assertEquals(40956L, expectedProbePackets(250_000, 1392))
        assertEquals(6553L, expectedProbePackets(40_000, 1392))
        // The packet size changes the wire charge: packetsize + 134 bytes
        assertEquals(1_000_000L * 2000 / (8 * (256 + 134)), expectedProbePackets(1_000_000, 256))
    }

    @Test
    fun aCleanProbePasses() {
        val result = score(perfect())
        assertNull(result.failure)
        assertTrue(result.passed)
        assertEquals(expected, result.received)
        assertEquals(0.0, result.lossPercent, 0.0)
        assertEquals(0.0, result.delayP99Ms, 1e-9)
        assertEquals(0.0, result.delayGrowthMs, 1e-9)
    }

    @Test
    fun aggregateLossOfOneTenthPercentStillPassesButMoreFails() {
        val tenth = perfect().also { for (i in 0 until 20) it[i * 1000] = -1 }
        assertEquals(0.1, score(tenth).lossPercent, 1e-9)
        assertNull(score(tenth).failure)
        val more = perfect().also { for (i in 0 until 21) it[i * 900] = -1 }
        assertEquals(ProbeFailure.LOSS, score(more).failure)
    }

    @Test
    fun oneBadWindowFailsEvenWhenTheAggregateIsFine() {
        // Window 7 holds sequences 7000..7999: losing 12 of 1000 is 1.2 % there but 0.06 % overall
        val arrivals = perfect().also { for (i in 0 until 12) it[7000 + i * 80] = -1 }
        val result = score(arrivals)
        assertEquals(0.06, result.lossPercent, 1e-9)
        assertEquals(1.2, result.worstWindowLossPercent, 1e-9)
        assertEquals(ProbeFailure.WINDOW_LOSS, result.failure)
        // Exactly 1 % in the window is allowed
        val ten = perfect().also { for (i in 0 until 10) it[7000 + i * 80] = -1 }
        assertNull(score(ten).failure)
    }

    @Test
    fun deliveryVariationAboveFourMillisecondsFailsAtTheNinetyNinthPercentile() {
        // 2 % of the packets arrive 6 ms late: the 99th percentile sits in them
        val late = perfect { seq -> if (seq % 50 == 0) 6.0 else 0.0 }
        val result = score(late)
        assertEquals(6.0, result.delayP99Ms, 1e-9)
        assertEquals(ProbeFailure.DELAY_VARIATION, result.failure)
        // 0.5 % late packets stay above the 99th percentile
        val few = perfect { seq -> if (seq % 200 == 0) 6.0 else 0.0 }
        assertEquals(0.0, score(few).delayP99Ms, 1e-9)
        assertNull(score(few).failure)
        // 4 ms exactly passes
        assertNull(score(perfect { seq -> if (seq % 50 == 0) 4.0 else 0.0 }).failure)
    }

    @Test
    fun aGrowingQueueFailsOnDelayGrowth() {
        // Transit grows from 0 to 3 ms over the two seconds: p99 3 ms passes, the last window is ~2.85 ms above the first
        val growing = perfect { seq -> 3.0 * seq / expected }
        val result = score(growing)
        assertTrue(result.delayP99Ms <= LinkPolicy.MAX_TRANSIT_P99_MS)
        assertTrue(result.delayGrowthMs > LinkPolicy.MAX_DELAY_GROWTH_MS)
        assertEquals(ProbeFailure.DELAY_GROWTH, result.failure)
        // 1.5 ms of growth is fine
        assertNull(score(perfect { seq -> 1.5 * seq / expected }).failure)
    }

    @Test
    fun theHostsOwnNumbersGradeTheProbeToo() {
        assertEquals(ProbeFailure.NOT_ALL_SENT, score(perfect(), sent = expected - 1).failure)
        assertEquals(ProbeFailure.NO_PACKETS, score(LongArray(expected) { -1 }).failure)
        assertEquals(ProbeFailure.SENDER_DURATION, score(perfect(), senderMs = 2041.0).failure)
        assertEquals(ProbeFailure.SENDER_DURATION, score(perfect(), senderMs = 1959.0).failure)
        assertNull(score(perfect(), senderMs = 2039.0).failure)
        assertEquals(ProbeFailure.SENDER_DURATION, score(perfect(), senderMs = Double.NaN).failure)
    }

    @Test
    fun anEmptyWindowCannotPassBecauseDelayGrowthIsUnknown() {
        // The whole last window is lost: loss fails first, but growth is infinite as well
        val arrivals = perfect().also { for (i in 19_000 until expected) it[i] = -1 }
        val result = score(arrivals)
        assertEquals(Double.POSITIVE_INFINITY, result.delayGrowthMs, 0.0)
        assertEquals(ProbeFailure.LOSS, result.failure)
    }

    @Test
    fun clockOffsetDoesNotMatter() {
        val shifted = perfect().also { for (i in it.indices) it[i] += 987_654_321L }
        assertEquals(score(perfect()), score(shifted))
    }
}
