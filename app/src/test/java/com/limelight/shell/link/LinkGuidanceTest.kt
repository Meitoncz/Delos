package com.limelight.shell.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private val supportedInfo = """<root status_code="200"><PyroWaveHostLinkMbps>1000</PyroWaveHostLinkMbps>
        <PyroWaveWireBudgetVersion>1</PyroWaveWireBudgetVersion><PyroWaveUdpProbeVersion>1</PyroWaveUdpProbeVersion>
        <PyroWaveUdpHandshakeVersion>1</PyroWaveUdpHandshakeVersion><PyroWaveCriticalFecPercentage>50</PyroWaveCriticalFecPercentage>
        <PyroWaveMinParityShards>2</PyroWaveMinParityShards></root>"""
}
