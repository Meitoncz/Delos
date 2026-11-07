package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class UdpDropSamplerTest {
    private static final List<String> SNMP = Arrays.asList(
            "Ip: Forwarding DefaultTTL InReceives",
            "Ip: 2 64 100",
            "Udp: InDatagrams NoPorts InErrors OutDatagrams RcvbufErrors SndbufErrors InCsumErrors IgnoredMulti MemErrors",
            "Udp: 5000 12 7 4000 5 0 0 3 0",
            "UdpLite: InDatagrams NoPorts InErrors OutDatagrams RcvbufErrors SndbufErrors InCsumErrors IgnoredMulti MemErrors",
            "UdpLite: 0 0 99 0 98 0 0 0 0");

    @Test
    public void readsTheUdpRowNotUdpLite() {
        UdpDropSampler.Counters counters = UdpDropSampler.parseSnmp(SNMP);
        assertNotNull(counters);
        assertEquals(7, counters.inErrors);
        assertEquals(5, counters.rcvbufErrors);
    }

    @Test
    public void missingRowOrColumnsGiveNull() {
        assertNull(UdpDropSampler.parseSnmp(Arrays.asList("Ip: A B", "Ip: 1 2")));
        assertNull(UdpDropSampler.parseSnmp(Arrays.asList("Udp: InDatagrams NoPorts", "Udp: 1 2")));
        assertNull(UdpDropSampler.parseSnmp(Arrays.asList("Udp: InDatagrams InErrors RcvbufErrors")));
    }

    @Test
    public void aRowWithAMismatchedLengthOrANonNumberGivesNull() {
        assertNull(UdpDropSampler.parseSnmp(Arrays.asList(
                "Udp: InErrors RcvbufErrors", "Udp: 1")));
        assertNull(UdpDropSampler.parseSnmp(Arrays.asList(
                "Udp: InErrors RcvbufErrors", "Udp: 1 x")));
    }

    @Test
    public void noIncreaseMeansNothingToLog() {
        UdpDropSampler.Counters same = new UdpDropSampler.Counters(7, 5);
        assertNull(UdpDropSampler.describeIncrease(same, new UdpDropSampler.Counters(7, 5)));
        // A counter that went backwards (a reset) is not a drop
        assertNull(UdpDropSampler.describeIncrease(same, new UdpDropSampler.Counters(0, 0)));
    }

    @Test
    public void anIncreaseNamesBothCounters() {
        String text = UdpDropSampler.describeIncrease(new UdpDropSampler.Counters(7, 5),
                new UdpDropSampler.Counters(10, 5));
        assertNotNull(text);
        assertTrue(text, text.contains("receive buffer full +0"));
        assertTrue(text, text.contains("all input errors +3"));
    }
}
