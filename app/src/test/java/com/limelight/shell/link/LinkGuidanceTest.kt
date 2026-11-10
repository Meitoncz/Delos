package com.limelight.shell.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.limelight.shell.MenuPage
import com.limelight.shell.StreamMenuLayout
import com.limelight.shell.helpLineText
import com.limelight.shell.helpLines
import com.limelight.shell.popoverHelpLineChars
import com.limelight.shell.wrappedLineCount
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LinkGuidanceTest {
    private val res = RuntimeEnvironment.getApplication().resources
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = 1_794_052_800_000L
    private val day = 24 * 60 * 60_000L
    private val none = com.limelight.nvstream.http.PyroWaveHostInfo.NONE
    private val pw = linkFormat(LinkCodec.PYROWAVE, 1920, 1080, 60, false, false)!!
    private val tested = LinkTest(180_000, now - day, LinkTransport.WIFI, 50, 1392)

    @Test
    fun anUntestedLinkSaysSoAndAnOldHostExplainsWhy() {
        val g = linkGuidance(pw,  none, null)
        assertEquals("Recommended: 225 Mbps · not tested on this link", g.line(res))
        assertEquals(LinkUnavailable.HOST_OUTDATED, g.testUnavailable)
        assertEquals("This PC's Vibepollo cannot test the link yet. Update it.", g.testRowText(res, now, LinkTransport.WIFI, utc))
    }

    @Test
    fun aTestedLinkShowsItsAgeAndThePictureItCarries() {
        val g = linkGuidance(pw,  none, tested)
        assertEquals("Recommended: 180 Mbps · link carries 180 Mbps", g.line(res))
        assertEquals("Tested yesterday on Wi-Fi: carries 180 Mbps; 1080p60 PyroWave 4:2:0 wants 225 Mbps, about 102 Mbps of picture after FEC and overhead.", g.help(res, now, LinkTransport.WIFI, utc))
        assertEquals("Tested yesterday on Wi-Fi", g.testRowText(res, now, LinkTransport.WIFI, utc))
    }

    @Test
    fun withoutAResultTheHelpOffersTheTest() {
        val supported = com.limelight.nvstream.http.PyroWaveHostInfo.parse(supportedInfo)
        val g = linkGuidance(pw, supported, null)
        assertNull(g.testUnavailable)
        assertEquals("Not tested on Wi-Fi. Test link measures what it carries.", g.help(res, now, LinkTransport.WIFI, utc))
        assertEquals("Not tested on Wi-Fi yet", g.testRowText(res, now, LinkTransport.WIFI, utc))
    }

    @Test
    fun theStreamMenuHelpSaysWhatTheRowIsAndFitsItsLines() {
        val dropping = linkGuidance(pw,  none, tested, LinkReading(LinkVerdict.DROPPING, 225_000, 1f, 6f, 0f, 0f, 0.1, 135_000))
        val old = linkGuidance(pw,  none, tested.copy(testedAtMs = now - 400 * day, transport = LinkTransport.ETHERNET))
        assertEquals("Link drops frames at 225 Mbps. A fills the slider, Apply sends it.", dropping.streamHelp(res, now, utc))
        assertEquals("Tested yesterday on Wi-Fi. A fills the slider, Apply sends it.", linkGuidance(pw,  none, tested).streamHelp(res, now, utc))
        assertEquals("Not tested yet. A fills the slider, Apply sends it. Test the link in Settings before connecting.",
            linkGuidance(pw,  none, null).streamHelp(res, now, utc))
        for (scale in listOf(1f, 1.15f, StreamMenuLayout.MAX_TEXT_SCALE)) {
            val chars = popoverHelpLineChars(MenuPage.BITRATE, scale)
            for (g in listOf(dropping, old, linkGuidance(pw,  none, tested), linkGuidance(pw,  none, null))) {
                val text = helpLineText(g.streamHelp(res, now, utc))
                assertTrue("$text needs more than ${helpLines(scale)} lines at $scale", wrappedLineCount(text, chars) <= helpLines(scale))
            }
        }
    }

    @Test
    fun theLinkBlockSaysWhatIsReceivedAgainstTheTarget() {
        val reading = LinkReading(LinkVerdict.KEEPING_UP, 225_000, 212_345f, 0f, 0f, 0f, 0.0, 225_000)
        assertEquals("212 of 225 Mb/s", com.limelight.shell.linkReceivingText(reading))
        assertEquals("unknown of 225 Mb/s", com.limelight.shell.linkReceivingText(reading.copy(rxKbps = -1f)))
    }

    @Test
    fun aLinkWithHeadroomSaysBothNumbersInOneSentence() {
        val roomy = linkGuidance(pw, none, tested.copy(wireKbps = 665_000))
        assertEquals("Recommended: 225 Mbps · link carries up to 665 Mbps", roomy.line(res))
        assertEquals("Tested yesterday on Wi-Fi: carries up to 665 Mbps; 225 Mbps is enough for 1080p60 PyroWave 4:2:0.",
            roomy.help(res, now, LinkTransport.WIFI, utc))
    }

    @Test
    fun formatsAreBuiltFromThePreferencesForEveryCodec() {
        val pyro = linkFormat("pyrowave", "1920x1080", "60", false, "420")!!
        assertEquals("1080p60 PyroWave 4:2:0", pyro.label)
        assertEquals(225_000, pyro.defaultKbps)
        assertEquals("1080p60 PyroWave 4:4:4", linkFormat("pyrowave", "1920x1080", "60", false, "444")!!.label)
        assertEquals("1080p60 HDR PyroWave 4:2:0", linkFormat("pyrowave", "1920x1080", "60", true, "444")!!.label)
        val hevc = linkFormat("forceh265", "1920x1080", "60", false, "420")!!
        assertEquals(LinkCodec.HEVC, hevc.codec)
        assertEquals("1080p60 HEVC", hevc.label)
        assertEquals(20_000, hevc.defaultKbps)
        assertEquals(LinkCodec.H264, linkFormat("neverh265", "1920x1080", "60", false, "420")!!.codec)
        assertEquals(LinkCodec.AV1, linkFormat("forceav1", "3840x2160", "60", false, "420")!!.codec)
        assertEquals(80_000, linkFormat("forceav1", "3840x2160", "60", false, "420")!!.defaultKbps)
        val auto = linkFormat("auto", "2560x1440", "120", false, "420")!!
        assertEquals(LinkCodec.AUTO, auto.codec)
        assertEquals("1440p120", auto.label)
        assertNull(linkFormat("auto", "Native", "60", false, "420"))
        assertNull(linkFormat("auto", "1920x1080", "x", false, "420"))
    }

    @Test
    fun anOrdinaryCodecGetsTheSameSentenceWithItsOwnUsefulMaximum() {
        val hevc = linkFormat("forceh265", "1920x1080", "60", false, "420")!!
        val g = linkGuidance(hevc, none, tested.copy(wireKbps = 665_000))
        assertEquals("Recommended: 40 Mbps · link carries up to 665 Mbps", g.line(res))
        assertEquals("Tested yesterday on Wi-Fi: carries up to 665 Mbps; 40 Mbps is enough for 1080p60 HEVC.", g.help(res, now, LinkTransport.WIFI, utc))
        val slow = linkGuidance(hevc, none, tested)
        assertEquals("Recommended: 40 Mbps · link carries up to 180 Mbps", slow.line(res))
        val untested = linkGuidance(hevc, none, null)
        assertEquals("Recommended: 20 Mbps · not tested on this link", untested.line(res))
        // The drop hint is PyroWave only: an ordinary codec ignores it
        val reading = LinkReading(LinkVerdict.DROPPING, 20_000, 1f, 6f, 0f, 0f, 0.1, 15_000)
        assertEquals(20_000, linkGuidance(hevc, none, null, reading).recommendation.kbps)
    }

    private val supportedInfo = """<root status_code="200"><PyroWaveHostLinkMbps>1000</PyroWaveHostLinkMbps>
        <PyroWaveWireBudgetVersion>1</PyroWaveWireBudgetVersion><PyroWaveUdpProbeVersion>1</PyroWaveUdpProbeVersion>
        <PyroWaveUdpHandshakeVersion>1</PyroWaveUdpHandshakeVersion><PyroWaveCriticalFecPercentage>50</PyroWaveCriticalFecPercentage>
        <PyroWaveMinParityShards>2</PyroWaveMinParityShards></root>"""
}
