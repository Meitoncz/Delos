package com.limelight.shell.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRecommendationTest {
    private val drops = LinkReading(LinkVerdict.DROPPING, 225_000, 180_000f, 6f, 0f, 0f, 0.1, 135_000)

    @Test
    fun withoutATestTheStaticGuideStandsAndSaysSo() {
        val r = recommend(225_000)
        assertEquals(225_000, r.kbps)
        assertEquals(LinkReason.UNTESTED, r.reason)
        assertEquals("Recommended: 225 Mbps · not tested on this link", r.line)
    }

    @Test
    fun aLinkThatCarriesMoreThanTheFormatNeedsKeepsTheStaticGuide() {
        val r = recommend(225_000, testedWireKbps = 340_000)
        assertEquals(225_000, r.kbps)
        assertEquals(LinkReason.TESTED_OK, r.reason)
        assertEquals("Recommended: 225 Mbps · link tested at 340 Mbps", r.line)
        // Exactly enough is enough
        assertEquals(LinkReason.TESTED_OK, recommend(225_000, testedWireKbps = 225_000).reason)
    }

    @Test
    fun aSlowerLinkLowersTheRecommendationToWhatItCarries() {
        val r = recommend(225_000, testedWireKbps = 180_000)
        assertEquals(180_000, r.kbps)
        assertEquals(LinkReason.TESTED_LIMITED, r.reason)
        assertEquals("Recommended: 180 Mbps · link tested at 180 Mbps", r.line)
    }

    @Test
    fun theHostLinkIsOnlyACeilingNotAMeasurement() {
        // Untested: the PC's own 100 Mbps link caps the offer
        val capped = recommend(225_000, hostLinkKbps = 100_000)
        assertEquals(100_000, capped.kbps)
        assertEquals(LinkReason.HOST_LINK_LIMITED, capped.reason)
        assertEquals("Recommended: 100 Mbps · the PC's link is 100 Mbps", capped.line)
        // A faster host link than needed changes nothing
        assertEquals(LinkReason.UNTESTED, recommend(225_000, hostLinkKbps = 1_000_000).reason)
        // Tested above need, but the host link is lower: the lower budget binds
        assertEquals(LinkReason.HOST_LINK_LIMITED, recommend(225_000, testedWireKbps = 340_000, hostLinkKbps = 100_000).reason)
        // Tested below the host link: the test binds
        val tested = recommend(225_000, testedWireKbps = 150_000, hostLinkKbps = 1_000_000)
        assertEquals(LinkReason.TESTED_LIMITED, tested.reason)
        assertEquals(150_000, tested.kbps)
        // Unknown host link (0) is ignored
        assertEquals(LinkReason.TESTED_OK, recommend(225_000, testedWireKbps = 340_000, hostLinkKbps = 0).reason)
    }

    @Test
    fun theRecommendationIsRoundedDownToTheSliderStepAndNeverBelowTheProbeFloor() {
        assertEquals(175_000, recommend(225_000, testedWireKbps = 178_000).kbps)
        assertEquals(5_000, recommend(225_000, testedWireKbps = 5_000).kbps)
    }

    @Test
    fun aTestedBudgetAlsoReportsThePictureItCarries() {
        val transport = WireTransport(1392, 50)
        val r = recommend(225_000, testedWireKbps = 180_000, fps = 60, transport = transport)
        assertEquals(102_370, r.pictureKbps)
        assertEquals(-1, recommend(225_000, fps = 60, transport = transport).pictureKbps)
        assertEquals(-1, recommend(225_000, testedWireKbps = 180_000).pictureKbps)
    }

    @Test
    fun aLiveDropHintOnlyEverLowersTheOffer() {
        val lowered = recommend(225_000, testedWireKbps = 340_000, live = drops)
        assertEquals(135_000, lowered.kbps)
        assertEquals(LinkReason.DROPPING, lowered.reason)
        assertEquals("Recommended: 135 Mbps · drops frames at 225 Mbps", lowered.line)
        // The tested budget is already lower than the live suggestion: it stays
        val tested = recommend(225_000, testedWireKbps = 100_000, live = drops)
        assertEquals(100_000, tested.kbps)
        assertEquals(LinkReason.TESTED_LIMITED, tested.reason)
        // A healthy reading changes nothing
        val keeping = drops.copy(verdict = LinkVerdict.KEEPING_UP, suggestedKbps = 225_000)
        assertEquals(LinkReason.TESTED_OK, recommend(225_000, testedWireKbps = 340_000, live = keeping).reason)
        assertTrue(recommend(225_000, live = drops.copy(suggestedKbps = 2_000)).kbps >= LinkPolicy.MIN_KBPS)
    }
}
