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
import org.robolectric.annotation.Config
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LinkGuidanceTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = 1_794_052_800_000L
    private val day = 24 * 60 * 60_000L
    private val none = com.limelight.nvstream.http.PyroWaveHostInfo.NONE
    private val tested = LinkTest(180_000, now - day, LinkTransport.WIFI, 50, 1392)

    @Test
    fun anUntestedLinkSaysSoAndAnOldHostExplainsWhy() {
        val g = linkGuidance(225_000, 60, none, null)
        assertEquals("Recommended: 225 Mbps · not tested on this link", g.line)
        assertEquals("This PC's Vibepollo cannot test the link yet. Update it.", g.testUnavailable)
        assertEquals("This PC's Vibepollo cannot test the link yet. Update it.", g.testRowText(now, LinkTransport.WIFI, utc))
    }

    @Test
    fun aTestedLinkShowsItsAgeAndThePictureItCarries() {
        val g = linkGuidance(225_000, 60, none, tested)
        assertEquals("Recommended: 180 Mbps · link tested at 180 Mbps", g.line)
        assertEquals("Tested yesterday on Wi-Fi: 180 Mbps, about 102 Mbps of picture after FEC and overhead.", g.help(now, LinkTransport.WIFI, utc))
        assertEquals("Tested yesterday on Wi-Fi", g.testRowText(now, LinkTransport.WIFI, utc))
    }

    @Test
    fun withoutAResultTheHelpOffersTheTest() {
        val supported = com.limelight.nvstream.http.PyroWaveHostInfo.parse(supportedInfo)
        val g = linkGuidance(225_000, 60, supported, null)
        assertNull(g.testUnavailable)
        assertEquals("Not tested on Wi-Fi. Test link measures what it carries.", g.help(now, LinkTransport.WIFI, utc))
        assertEquals("Not tested on Wi-Fi yet", g.testRowText(now, LinkTransport.WIFI, utc))
    }

    @Test
    fun theStreamMenuHelpSaysWhatTheRowIsAndFitsItsLines() {
        val dropping = linkGuidance(225_000, 60, none, tested, LinkReading(LinkVerdict.DROPPING, 225_000, 1f, 6f, 0f, 0f, 0.1, 135_000))
        val old = linkGuidance(225_000, 60, none, tested.copy(testedAtMs = now - 400 * day, transport = LinkTransport.ETHERNET))
        assertEquals("Link drops frames at 225 Mbps. A fills the slider, Apply sends it.", dropping.streamHelp(now, utc))
        assertEquals("Tested yesterday on Wi-Fi. A fills the slider, Apply sends it.", linkGuidance(225_000, 60, none, tested).streamHelp(now, utc))
        assertEquals("Not tested yet. A fills the slider, Apply sends it. Test the link in Settings before connecting.",
            linkGuidance(225_000, 60, none, null).streamHelp(now, utc))
        for (scale in listOf(1f, 1.15f, StreamMenuLayout.MAX_TEXT_SCALE)) {
            val chars = popoverHelpLineChars(MenuPage.BITRATE, scale)
            for (g in listOf(dropping, old, linkGuidance(225_000, 60, none, tested), linkGuidance(225_000, 60, none, null))) {
                val text = helpLineText(g.streamHelp(now, utc))
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

    private val supportedInfo = """<root status_code="200"><PyroWaveHostLinkMbps>1000</PyroWaveHostLinkMbps>
        <PyroWaveWireBudgetVersion>1</PyroWaveWireBudgetVersion><PyroWaveUdpProbeVersion>1</PyroWaveUdpProbeVersion>
        <PyroWaveUdpHandshakeVersion>1</PyroWaveUdpHandshakeVersion><PyroWaveCriticalFecPercentage>50</PyroWaveCriticalFecPercentage>
        <PyroWaveMinParityShards>2</PyroWaveMinParityShards></root>"""
}
