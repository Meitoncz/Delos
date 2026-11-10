package com.limelight.shell.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LinkRecommendationTest {
    private val res = RuntimeEnvironment.getApplication().resources

    private val drops = LinkReading(LinkVerdict.DROPPING, 225_000, 180_000f, 6f, 0f, 0f, 0.1, 135_000)

    @Test
    fun withoutATestTheStaticGuideStandsAndSaysSo() {
        val r = recommend(225_000)
        assertEquals(225_000, r.kbps)
        assertEquals(LinkReason.UNTESTED, r.reason)
        assertEquals("Recommended: 225 Mbps · not tested on this link", r.line(res))
    }

    @Test
    fun aLinkThatCarriesMoreThanTheFormatNeedsKeepsTheStaticGuide() {
        val r = recommend(225_000, testedWireKbps = 340_000)
        assertEquals(225_000, r.kbps)
        assertEquals(LinkReason.TESTED_OK, r.reason)
        assertEquals("Recommended: 225 Mbps · link carries up to 340 Mbps", r.line(res))
        // Exactly enough is enough
        assertEquals(LinkReason.TESTED_OK, recommend(225_000, testedWireKbps = 225_000).reason)
    }

    @Test
    fun aSlowerLinkLowersTheRecommendationToWhatItCarries() {
        val r = recommend(225_000, testedWireKbps = 180_000)
        assertEquals(180_000, r.kbps)
        assertEquals(LinkReason.TESTED_LIMITED, r.reason)
        assertEquals("Recommended: 180 Mbps · link carries 180 Mbps", r.line(res))
    }

    @Test
    fun theHostLinkIsOnlyACeilingNotAMeasurement() {
        // Untested: the PC's own 100 Mbps link caps the offer
        val capped = recommend(225_000, hostLinkKbps = 100_000)
        assertEquals(100_000, capped.kbps)
        assertEquals(LinkReason.HOST_LINK_LIMITED, capped.reason)
        assertEquals("Recommended: 100 Mbps · the PC's link is 100 Mbps", capped.line(res))
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
        assertEquals("Recommended: 135 Mbps · drops frames at 225 Mbps", lowered.line(res))
        // The tested budget is already lower than the live suggestion: it stays
        val tested = recommend(225_000, testedWireKbps = 100_000, live = drops)
        assertEquals(100_000, tested.kbps)
        assertEquals(LinkReason.TESTED_LIMITED, tested.reason)
        // A healthy reading changes nothing
        val keeping = drops.copy(verdict = LinkVerdict.KEEPING_UP, suggestedKbps = 225_000)
        assertEquals(LinkReason.TESTED_OK, recommend(225_000, testedWireKbps = 340_000, live = keeping).reason)
        assertTrue(recommend(225_000, live = drops.copy(suggestedKbps = 2_000)).kbps >= LinkPolicy.MIN_KBPS)
    }

    @Test
    fun theReasonNamesTheCeilingAndTheRecommendationCarriesBothNumbers() {
        val r = recommend(225_000, testedWireKbps = 665_000)
        assertEquals(225_000, r.kbps)
        assertEquals(665_000, r.testedKbps)
        assertEquals(225_000, r.wantedKbps)
        assertEquals("Recommended: 225 Mbps · link carries up to 665 Mbps", r.line(res))
    }

    private val hevc1080 = LinkFormat(LinkCodec.HEVC, "1080p60 HEVC", 60, 20_000)

    @Test
    fun anOrdinaryCodecsUsefulMaximumIsAMultipleOfTheAppsDefaultCapped() {
        assertEquals(60_000, ordinaryWantedKbps(LinkCodec.H264, 20_000))   // 1080p60
        assertEquals(40_000, ordinaryWantedKbps(LinkCodec.HEVC, 20_000))
        assertEquals(40_000, ordinaryWantedKbps(LinkCodec.AV1, 20_000))
        assertEquals(40_000, ordinaryWantedKbps(LinkCodec.AUTO, 20_000))
        assertEquals(150_000, ordinaryWantedKbps(LinkCodec.HEVC, 80_000))  // 4K60: 160 capped
        assertEquals(150_000, ordinaryWantedKbps(LinkCodec.H264, 80_000))
    }

    @Test
    fun anOrdinaryCodecIsOfferedItsDefaultUntilTestedThenAShareOfTheBudgetUpToItsMaximum() {
        val wanted = ordinaryWantedKbps(LinkCodec.HEVC, 20_000)
        val share = LinkPolicy.ORDINARY_BUDGET_SHARE
        val untested = recommend(wanted, share = share, untestedKbps = 20_000)
        assertEquals(20_000, untested.kbps)
        assertEquals(LinkReason.UNTESTED, untested.reason)
        val plenty = recommend(wanted, testedWireKbps = 665_000, share = share, untestedKbps = 20_000)
        assertEquals(40_000, plenty.kbps)
        assertEquals(LinkReason.TESTED_OK, plenty.reason)
        assertEquals("Recommended: 40 Mbps · link carries up to 665 Mbps", plenty.line(res))
    }

    @Test
    fun aLinkThatCannotCarryTheMaximumWithMarginLimitsAnOrdinaryCodec() {
        val wanted = ordinaryWantedKbps(LinkCodec.H264, 20_000) // 60
        // 65 % of 80 = 52 < 60
        val limited = recommend(wanted, testedWireKbps = 80_000, share = 0.65, untestedKbps = 20_000)
        assertEquals(50_000, limited.kbps)
        assertEquals(LinkReason.TESTED_LIMITED, limited.reason)
        assertEquals("Recommended: 50 Mbps · link carries 80 Mbps", limited.line(res))
        // 65 % of 92 = 59.8: just under, still limited; of 93 = 60.45: enough
        assertEquals(LinkReason.TESTED_LIMITED, recommend(wanted, testedWireKbps = 92_000, share = 0.65, untestedKbps = 20_000).reason)
        assertEquals(LinkReason.TESTED_OK, recommend(wanted, testedWireKbps = 93_000, share = 0.65, untestedKbps = 20_000).reason)
        // A slow link goes under the plain default as well
        assertEquals(10_000, recommend(wanted, testedWireKbps = 16_000, share = 0.65, untestedKbps = 20_000).kbps)
    }

    @Test
    fun anOrdinaryCodecGetsNoPictureFigureAndIgnoresNothingAboutTheHostLink() {
        val transport = WireTransport(1392, 50)
        assertEquals(-1, recommend(40_000, testedWireKbps = 665_000, fps = 60, transport = transport, share = 0.65).pictureKbps)
        val host = recommend(40_000, testedWireKbps = 665_000, hostLinkKbps = 50_000, share = 0.65, untestedKbps = 20_000)
        assertEquals(LinkReason.HOST_LINK_LIMITED, host.reason)
        assertEquals(30_000, host.kbps)
    }
}
