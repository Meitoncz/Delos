package com.limelight.shell.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class LinkResultTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val prague = TimeZone.getTimeZone("Europe/Prague")
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour

    // 2026-11-07 12:00 UTC
    private val now = 1_794_052_800_000L

    @Test
    fun recentResultsReadInMinutesAndHours() {
        assertEquals("just now", ageText(now, now, utc))
        assertEquals("just now", ageText(now, now - 59_000, utc))
        assertEquals("1 min ago", ageText(now, now - min, utc))
        assertEquals("45 min ago", ageText(now, now - 45 * min, utc))
        assertEquals("3 h ago", ageText(now, now - 3 * hour, utc))
    }

    @Test
    fun olderResultsReadInCalendarDays() {
        assertEquals("yesterday", ageText(now, now - 13 * hour, utc))
        assertEquals("yesterday", ageText(now, now - day, utc))
        assertEquals("2 days ago", ageText(now, now - 2 * day, utc))
        assertEquals("6 days ago", ageText(now, now - 6 * day, utc))
        assertEquals("1 week ago", ageText(now, now - 7 * day, utc))
        assertEquals("2 weeks ago", ageText(now, now - 15 * day, utc))
        assertEquals("1 month ago", ageText(now, now - 30 * day, utc))
        assertEquals("3 months ago", ageText(now, now - 100 * day, utc))
    }

    @Test
    fun daysAreCountedByTheLocalCalendarNotBy24HourSpans() {
        // 23:30 local the evening before is "yesterday" at 00:10 local, although only 40 minutes passed
        val midnightPrague = 1_794_092_400_000L // 2026-11-08 00:00 CET
        assertEquals("yesterday", ageText(midnightPrague + 10 * min, midnightPrague - 30 * min, prague))
        assertEquals("40 min ago", ageText(midnightPrague + 10 * min, midnightPrague - 30 * min, utc))
    }

    @Test
    fun aTimestampInTheFutureReadsJustNow() {
        assertEquals("just now", ageText(now, now + 5 * day, utc))
    }

    @Test
    fun theSummaryNamesTheAgeAndTheNetwork() {
        val test = LinkTest(340_000, now - day, LinkTransport.WIFI, 50, 1392)
        assertEquals("Tested yesterday on Wi-Fi", test.summary(now, utc))
        assertEquals("Tested 2 h ago on Ethernet", test.copy(testedAtMs = now - 2 * hour, transport = LinkTransport.ETHERNET).summary(now, utc))
    }

    @Test
    fun aStoredResultRoundTripsAndForeignTextIsIgnored() {
        val test = LinkTest(340_000, now, LinkTransport.ETHERNET, 50, 1392)
        assertEquals(test, LinkTest.decode(test.encode()))
        assertNull(LinkTest.decode(null))
        assertNull(LinkTest.decode(""))
        assertNull(LinkTest.decode("2;340000;1;WIFI;50;1392"))
        assertNull(LinkTest.decode("1;340000;x;WIFI;50;1392"))
        assertNull(LinkTest.decode("1;1000;1;WIFI;50;1392"))
        assertEquals(LinkTransport.OTHER, LinkTest.decode("1;340000;1;SATELLITE;50;1392")!!.transport)
    }
}
