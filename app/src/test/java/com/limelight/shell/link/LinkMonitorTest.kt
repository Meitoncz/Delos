package com.limelight.shell.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LinkMonitorTest {
    private fun sample(
        target: Int = 225_000, total: Long = 60, partial: Long = 0, lost: Long = 0, skipped: Long = 0,
        rx: Float = 28_000f, seconds: Float = 1f,
    ) = LinkSample(seconds, target, total, partial, lost, skipped, rx)

    private fun monitor(vararg samples: LinkSample) = LinkMonitor().also { m -> samples.forEach(m::add) }

    @Test
    fun nothingIsSaidBeforeTheFirstSampleAndForTheFirstThreeSeconds() {
        assertNull(LinkMonitor().reading())
        val early = monitor(sample(), sample())
        assertEquals(LinkVerdict.MEASURING, early.reading()!!.verdict)
        assertNull(early.hint())
        assertEquals(LinkVerdict.KEEPING_UP, monitor(sample(), sample(), sample()).reading()!!.verdict)
    }

    @Test
    fun framesThatArriveWholeKeepUp() {
        val reading = monitor(*Array(5) { sample() }).reading()!!
        assertEquals(LinkVerdict.KEEPING_UP, reading.verdict)
        assertEquals("Link keeps up at 225 Mbps", reading.text)
        assertEquals(0.0, reading.dropShare, 0.0)
    }

    @Test
    fun partialAndLostFramesAboveTwoPercentMeanTheLinkDropsFrames() {
        // 4 partial + 2 lost of 300 frames over 5 s = 2 %
        val m = monitor(sample(partial = 2), sample(partial = 2), sample(lost = 1), sample(lost = 1), sample())
        val reading = m.reading()!!
        assertEquals(LinkVerdict.DROPPING, reading.verdict)
        assertEquals(0.8f, reading.partialPerSecond, 1e-6f)
        assertEquals(0.4f, reading.lostPerSecond, 1e-6f)
        assertNotNull(m.hint())
        // 1.7 % does not
        val below = monitor(sample(partial = 2), sample(partial = 2), sample(partial = 1), sample(), sample())
        assertEquals(LinkVerdict.KEEPING_UP, below.reading()!!.verdict)
    }

    @Test
    fun theHintNamesTheBitrateAndASuggestedLowerOne() {
        // 6 partial a second: 10 % -> cut 40 % (capped), 225 -> 135 Mbps
        val reading = monitor(*Array(4) { sample(partial = 6) }).reading()!!
        assertEquals("Link drops frames at 225 Mbps, try 135 Mbps", reading.text)
        assertEquals(135_000, reading.suggestedKbps)
        // A mild 3 %: 4 x 3 % = 12 % -> 198 Mbps rounded down to 195
        assertEquals(195_000, monitor(*Array(4) { sample(total = 100, partial = 3) }).reading()!!.suggestedKbps)
    }

    @Test
    fun theSuggestionIsAPureFunctionOfTheDegradation() {
        assertEquals(200_000, suggestedBitrate(225_000, 0.02))   // 10 % minimum cut: 202.5 -> 200
        assertEquals(135_000, suggestedBitrate(225_000, 0.5))    // 40 % maximum cut
        assertEquals(5_000, suggestedBitrate(6_000, 0.5))        // never under the probe floor
        assertEquals(suggestedBitrate(225_000, 0.05), suggestedBitrate(225_000, 0.05))
    }

    @Test
    fun theWindowIsAboutFiveSecondsSoTheHintClearsAfterTheDropsStop() {
        val m = LinkMonitor()
        repeat(4) { m.add(sample(partial = 6)) }
        assertEquals(LinkVerdict.DROPPING, m.reading()!!.verdict)
        repeat(5) { m.add(sample()) }
        assertEquals(LinkVerdict.KEEPING_UP, m.reading()!!.verdict)
        assertNull(m.hint())
    }

    @Test
    fun aBitrateChangeStartsAFreshReadingAtTheNewBitrate() {
        val m = LinkMonitor()
        repeat(5) { m.add(sample(partial = 6)) }
        m.add(sample(target = 135_000))
        assertEquals(LinkVerdict.MEASURING, m.reading()!!.verdict)
        repeat(3) { m.add(sample(target = 135_000)) }
        val reading = m.reading()!!
        assertEquals(LinkVerdict.KEEPING_UP, reading.verdict)
        assertEquals(135_000, reading.targetKbps)
    }

    @Test
    fun skippedFramesWithoutNetworkDropsPointAtTheDeviceNotTheLink() {
        val reading = monitor(*Array(5) { sample(skipped = 3) }).reading()!!
        assertEquals(LinkVerdict.DEVICE_BEHIND, reading.verdict)
        assertEquals(3f, reading.skippedPerSecond, 1e-6f)
        assertNull(monitor(*Array(5) { sample(skipped = 3) }).hint())
        // When the network drops as well, the link verdict wins
        assertEquals(LinkVerdict.DROPPING, monitor(*Array(5) { sample(partial = 3, skipped = 3) }).reading()!!.verdict)
    }

    @Test
    fun receivedRateIsAveragedOverTheKnownSeconds() {
        val reading = monitor(sample(rx = 20_000f), sample(rx = -1f), sample(rx = 30_000f), sample(rx = 30_000f)).reading()!!
        assertEquals(26_666.666f, reading.rxKbps, 0.01f)
        assertEquals(-1f, monitor(*Array(4) { sample(rx = -1f) }).reading()!!.rxKbps, 0f)
    }

    @Test
    fun emptySamplesAreIgnoredAndFramelessWindowsStayMeasuring() {
        val m = LinkMonitor()
        m.add(sample(seconds = 0f))
        assertNull(m.reading())
        repeat(4) { m.add(sample(total = 0)) }
        assertEquals(LinkVerdict.MEASURING, m.reading()!!.verdict)
    }
}
