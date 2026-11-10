package com.limelight.shell.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProbeScoreTest {
    private val res = RuntimeEnvironment.getApplication().resources

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
    fun aggregateLossBelowTwoPercentPassesAndTwoPercentFails() {
        // The reference client's limit: lossPercent < 2.0
        val below = perfect().also { for (i in 0 until 380) it[i * 52] = -1 }
        assertEquals(1.9, score(below).lossPercent, 1e-9)
        assertNull(score(below).failure)
        val limit = perfect().also { for (i in 0 until 400) it[i * 50] = -1 }
        assertEquals(2.0, score(limit).lossPercent, 1e-9)
        assertEquals(ProbeFailure.LOSS, score(limit).failure)
    }

    @Test
    fun oneBadWindowFailsAtFivePercentEvenWhenTheAggregateIsFine() {
        // Window 7 holds sequences 7000..7999: 50 of 1000 lost is 5 % there but 0.25 % overall
        val arrivals = perfect().also { for (i in 0 until 50) it[7000 + i * 20] = -1 }
        val result = score(arrivals)
        assertEquals(0.25, result.lossPercent, 1e-9)
        assertEquals(5.0, result.worstWindowLossPercent, 1e-9)
        assertEquals(ProbeFailure.WINDOW_LOSS, result.failure)
        val fortyNine = perfect().also { for (i in 0 until 49) it[7000 + i * 20] = -1 }
        assertNull(score(fortyNine).failure)
    }

    @Test
    fun transitVariationIsOnlyAWarning() {
        // 2 % of the packets arrive 6 ms late: p99 sits in them, the probe still passes
        val late = perfect { seq -> if (seq % 50 == 0) 6.0 else 0.0 }
        val result = score(late)
        assertEquals(6.0, result.delayP99Ms, 1e-9)
        assertNull(result.failure)
        assertTrue(result.passed)
        assertTrue(result.jitterWarning)
        assertEquals("Stable, a little jitter", result.verdictText(res))
        // 4 ms exactly and a clean probe carry no warning
        assertEquals(false, score(perfect { seq -> if (seq % 50 == 0) 4.0 else 0.0 }).jitterWarning)
        assertEquals("Stable", score(perfect()).verdictText(res))
    }

    @Test
    fun aGrowingQueueFailsOnDelayGrowth() {
        // Transit grows from 0 to 3 ms over the two seconds: p99 3 ms passes, the last window is ~2.85 ms above the first
        val growing = perfect { seq -> 3.0 * seq / expected }
        val result = score(growing)
        assertTrue(result.delayP99Ms <= LinkPolicy.JITTER_WARNING_P99_MS)
        assertTrue(result.delayGrowthMs > LinkPolicy.MAX_DELAY_GROWTH_MS)
        assertEquals(ProbeFailure.DELAY_GROWTH, result.failure)
        // 1.5 ms of growth is fine
        assertNull(score(perfect { seq -> 1.5 * seq / expected }).failure)
    }

    @Test
    fun theHostsOwnNumbersGradeTheProbeToo() {
        // 98 % of the planned packets must have been sent; unsent ones are not in the arrivals either, so loss counts them
        assertNull(score(perfect(), sent = expected - 1).failure)
        assertNull(score(perfect(), sent = expected * 98 / 100).failure)
        assertEquals(ProbeFailure.NOT_ALL_SENT, score(perfect(), sent = expected * 97 / 100).failure)
        assertEquals(ProbeFailure.NOT_ALL_SENT, score(perfect(), sent = expected + 1).failure)
        assertEquals(ProbeFailure.NO_PACKETS, score(LongArray(expected) { -1 }).failure)
        assertEquals(ProbeFailure.SENDER_DURATION, score(perfect(), senderMs = 2041.0).failure)
        assertEquals(ProbeFailure.SENDER_DURATION, score(perfect(), senderMs = 1959.0).failure)
        assertNull(score(perfect(), senderMs = 2039.0).failure)
        assertEquals(ProbeFailure.SENDER_DURATION, score(perfect(), senderMs = Double.NaN).failure)
    }

    @Test
    fun anEmptyWindowCannotPassBecauseDelayGrowthIsUnknown() {
        // A whole window is lost: loss fails first, but growth is infinite as well
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProbeVerdictTextTest {
    private val res = RuntimeEnvironment.getApplication().resources

    private fun failed(kbps: Int, failure: ProbeFailure, loss: Double = 0.0, window: Double = 0.0, rcvBuf: Int = 0, stall: Double = 0.0) =
        ProbeScore(kbps, 1000, 1000, 900, 2000.0, loss, window, 0.0, 0.0, failure, rcvBuf, stall)

    @Test
    fun theReasonNamesTheRealCauseAndTheRate() {
        assertEquals("Lost 3.0% of the test packets at 450 Mbps", failed(450_000, ProbeFailure.LOSS, loss = 3.0).reasonText(res))
        assertEquals("Lost up to 7.5% of the packets within 100 ms at 450 Mbps",
            failed(450_000, ProbeFailure.WINDOW_LOSS, window = 7.5).reasonText(res))
        assertEquals("The delay kept growing at 225 Mbps (the link queues up)", failed(225_000, ProbeFailure.DELAY_GROWTH).reasonText(res))
        assertEquals("No test data arrived. UDP may be blocked on this network", failed(5_000, ProbeFailure.NO_PACKETS).reasonText(res))
        assertEquals("The PC sent only 90.0% of the test data at 500 Mbps",
            ProbeScore(500_000, 1000, 900, 900, 2000.0, 10.0, 0.0, 0.0, 0.0, ProbeFailure.NOT_ALL_SENT).reasonText(res))
    }

    @Test
    fun aLossOurOwnReceiverMayHaveCausedIsSaidSo() {
        val small = failed(450_000, ProbeFailure.LOSS, loss = 3.0, rcvBuf = 212_992)
        assertTrue(small.receiverSuspect)
        assertEquals("This device could not receive 450 Mbps that fast", small.reasonText(res))
        val late = failed(450_000, ProbeFailure.WINDOW_LOSS, window = 9.0, rcvBuf = 8 * 1024 * 1024, stall = 12.0)
        assertEquals("This device could not receive 450 Mbps that fast", late.reasonText(res))
        // A big buffer and a quick loop: the loss is the network's
        assertEquals(false, failed(450_000, ProbeFailure.LOSS, loss = 3.0, rcvBuf = 8 * 1024 * 1024, stall = 0.2).receiverSuspect)
        // Unknown buffer size is not a suspicion by itself
        assertEquals(false, failed(450_000, ProbeFailure.LOSS, loss = 3.0).receiverSuspect)
    }

    @Test
    fun noRateSaysWhatFailedAndWhetherAnythingPassed() {
        val last = failed(5_000, ProbeFailure.LOSS, loss = 4.0)
        assertEquals("Lost 4.0% of the test packets at 5 Mbps. Nothing passed, down to 5 Mbps.",
            LinkSearchResult.NoRate(last, 0).noRateText(res))
        assertEquals("Lost 4.0% of the test packets at 5 Mbps. 300 Mbps passed once but did not hold.",
            LinkSearchResult.NoRate(last, 300_000).noRateText(res))
        assertEquals("No rate could be tested.", LinkSearchResult.NoRate(null).noRateText(res))
    }
}
