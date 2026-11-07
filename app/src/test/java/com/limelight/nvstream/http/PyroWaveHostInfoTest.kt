package com.limelight.nvstream.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PyroWaveHostInfoTest {
    private fun info(vararg fields: Pair<String, String>) =
        PyroWaveHostInfo.parse("<root status_code=\"200\"><hostname>PC</hostname>" +
            fields.joinToString("") { "<${it.first}>${it.second}</${it.first}>" } + "</root>")

    private val full = arrayOf(
        "PyroWaveHostLinkMbps" to "2500", "PyroWaveBandwidthProbeBytes" to "33554432",
        "PyroWaveWireBudgetVersion" to "1", "PyroWaveUdpProbeVersion" to "1", "PyroWaveUdpHandshakeVersion" to "1",
        "PyroWaveCriticalFecPercentage" to "50", "PyroWaveMinParityShards" to "2",
    )

    @Test
    fun readsEveryFieldTheLinkTestNeeds() {
        val host = info(*full)
        assertEquals(2500, host.hostLinkMbps)
        assertEquals(33_554_432L, host.bandwidthProbeBytes)
        assertEquals(1, host.wireBudgetVersion)
        assertEquals(1, host.udpProbeVersion)
        assertEquals(1, host.udpHandshakeVersion)
        assertEquals(50, host.criticalFecPercent)
        assertEquals(2, host.minParityShards)
        assertTrue(host.supportsLinkTest())
        assertFalse(host.linkTestNeedsNewerClient())
    }

    @Test
    fun aDisabledCriticalFecIsAValidZero() {
        val host = info(*full.filter { it.first != "PyroWaveCriticalFecPercentage" }.toTypedArray(), "PyroWaveCriticalFecPercentage" to "0")
        assertEquals(0, host.criticalFecPercent)
        assertTrue(host.supportsLinkTest())
    }

    @Test
    fun anOldHostOrAHostWithoutPyroWaveCannotTest() {
        val plain = info()
        assertFalse(plain.supportsLinkTest())
        assertFalse(plain.linkTestNeedsNewerClient())
        assertEquals(0, plain.hostLinkMbps)
        assertEquals(-1, plain.criticalFecPercent)
        // The legacy download probe alone (no UDP probe fields)
        val legacy = info("PyroWaveHostLinkMbps" to "1000", "PyroWaveBandwidthProbeBytes" to "33554432")
        assertFalse(legacy.supportsLinkTest())
        assertEquals(1000, legacy.hostLinkMbps)
    }

    @Test
    fun aNewerProtocolIsNamedAndNotGuessedAt() {
        val newer = info(*full.filter { it.first != "PyroWaveUdpProbeVersion" }.toTypedArray(), "PyroWaveUdpProbeVersion" to "2")
        assertFalse(newer.supportsLinkTest())
        assertTrue(newer.linkTestNeedsNewerClient())
        val budget = info(*full.filter { it.first != "PyroWaveWireBudgetVersion" }.toTypedArray(), "PyroWaveWireBudgetVersion" to "2")
        assertFalse(budget.supportsLinkTest())
    }

    @Test
    fun missingOrInvalidFecNumbersMakeTheTestUnsupportedRatherThanGuessed() {
        assertFalse(info(*full.filter { it.first != "PyroWaveCriticalFecPercentage" }.toTypedArray()).supportsLinkTest())
        assertFalse(info(*full.filter { it.first != "PyroWaveMinParityShards" }.toTypedArray()).supportsLinkTest())
        assertFalse(info(*full.filter { it.first != "PyroWaveCriticalFecPercentage" }.toTypedArray(), "PyroWaveCriticalFecPercentage" to "abc").supportsLinkTest())
        assertFalse(info(*full.filter { it.first != "PyroWaveCriticalFecPercentage" }.toTypedArray(), "PyroWaveCriticalFecPercentage" to "256").supportsLinkTest())
    }

    @Test
    fun theProbeAnswerCarriesTheReliableCounts() {
        val answer = PyroWaveProbeResponse.parse(
            "<root status_code=\"200\"><expected>40956</expected><sent>40956</sent><elapsedMs>2000.4</elapsedMs>" +
                "<sendRetries>0</sendRetries><lastSendError>0</lastSendError></root>")
        assertEquals(40956L, answer.expected)
        assertEquals(40956L, answer.sent)
        assertEquals(2000.4, answer.elapsedMs, 1e-9)
    }

    @Test
    fun aHostFailureAfterTheHandshakeSurfacesItsMessage() {
        val error = assertThrows(HostHttpResponseException::class.java) {
            PyroWaveProbeResponse.parse("<root status_code=\"500\" status_message=\"The client did not complete the UDP handshake\"/>")
        }
        assertEquals("The client did not complete the UDP handshake", error.errorMessage)
    }

    @Test
    fun anInvalidProbeAnswerIsRejected() {
        for (body in listOf(
            "<root status_code=\"200\"><expected>10</expected><sent>11</sent><elapsedMs>2000</elapsedMs></root>",
            "<root status_code=\"200\"><expected>x</expected><sent>1</sent><elapsedMs>2000</elapsedMs></root>",
            "<root status_code=\"200\"><expected>10</expected><sent>10</sent><elapsedMs>nan</elapsedMs></root>",
        )) assertThrows(java.io.IOException::class.java) { PyroWaveProbeResponse.parse(body) }
        assertThrows(Exception::class.java) { PyroWaveProbeResponse.parse("<root status_code=\"200\"><expected>10</expected></root>") }
    }
}
