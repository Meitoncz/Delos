package com.limelight.shell.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The expected numbers come from an independent transcription of Nonary's `pyrowavebandwidth.h`. */
class WireBudgetTest {
    private val fec50 = WireTransport(1392, criticalFecPercent = 50, minParityShards = 2)
    private val fec0 = WireTransport(1392, criticalFecPercent = 0, minParityShards = 2)

    @Test
    fun reservesTheLargestCriticalBlockThatFitsOneReedSolomonBlock() {
        assertEquals(85L, WireBudget.maxCriticalParity(fec50))           // 170 data + 85 parity = 255 shards
        assertEquals(43L, WireBudget.maxCriticalParity(WireTransport(1392, 20)))
        assertEquals(0L, WireBudget.maxCriticalParity(fec0))
        assertEquals(5L, WireBudget.maxCriticalParity(fec50, packets = 10)) // fewer packets, fewer parity shards
        // The two-shard minimum applies to tiny blocks
        assertEquals(2L, WireBudget.maxCriticalParity(WireTransport(1392, 1), packets = 1))
    }

    @Test
    fun anImageAllowanceNeedsItsWireAllowance() {
        assertEquals(310_481.12, WireBudget.totalKbps(220_000.0, 60, fec50), 0.01)
        assertEquals(248_220.32, WireBudget.totalKbps(220_000.0, 60, fec0), 0.01)
        // The audio and control reserve alone is the floor
        assertEquals(66_565.28, WireBudget.totalKbps(1.0, 60, fec50), 0.01)
    }

    @Test
    fun aWireAllowanceCarriesLessPicture() {
        assertEquals(247_015.68, WireBudget.imageKbps(340_000.0, 60, fec50), 0.01)
        assertEquals(102_370.56, WireBudget.imageKbps(180_000.0, 60, fec50), 0.01)
        // Nothing is left when the budget cannot even hold the reserve
        assertEquals(0.0, WireBudget.imageKbps(3_000.0, 60, fec50), 0.0)
        assertEquals(0.0, WireBudget.imageKbps(100_000.0, 0, fec50), 0.0)
    }

    @Test
    fun theTwoConversionsRoundTripWithinPacketRounding() {
        val wire = WireBudget.totalKbps(220_000.0, 60, fec50)
        val image = WireBudget.imageKbps(wire, 60, fec50)
        assertEquals(220_596.48, image, 0.01)
        assertTrue(image >= 220_000.0 && image < 221_000.0)
    }

    @Test
    fun aHigherFrameRateReservesMoreParityForTheSamePicture() {
        val at60 = WireBudget.totalKbps(220_000.0, 60, fec50) - LinkPolicy.AUDIO_CONTROL_KBPS
        val at120 = WireBudget.totalKbps(220_000.0, 120, fec50) - LinkPolicy.AUDIO_CONTROL_KBPS
        // One critical block of parity is reserved per frame: the same picture bitrate in twice the frames costs more wire
        assertTrue(at120 > at60 && at120 < at60 * 1.3)
    }
}
