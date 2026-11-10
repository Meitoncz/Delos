package com.limelight.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of docs/DIAGNOSTICS.md, with lines as this app really logs them. */
class LogScrubberTest {
    private val context = ScrubContext(
        pcNames = listOf("Meiton-PC", "Living Room"),
        hostAddresses = listOf("meiton.example.org"),
        names = listOf("Meiton", "Odin Portal"),
        identifiers = listOf("0123456789abcdef"),
        secrets = listOf("sgdbSecretKey99"),
    )

    private fun scrub(text: String, ctx: ScrubContext = context) = LogScrubber(ctx).scrub(text)

    @Test fun theParallelPollLineLosesTheNameAndTheAddressButKeepsThePort() {
        assertEquals(
            "Starting parallel poll for <pc-1> (<ip-1>:47989, null, null, null)",
            scrub("Starting parallel poll for Meiton-PC (192.168.0.200:47989, null, null, null)"),
        )
    }

    @Test fun anHttpsRequestKeepsTheEndpointButNotTheQueryValues() {
        val line = "https://192.168.0.200:47984/applist?devicename=roth&uniqueid=0123456789ABCDEF&uuid=11111111-2222-3333-4444-555555555555"
        assertEquals("https://<ip-1>:47984/applist?devicename=<redacted>&uniqueid=<redacted>&uuid=<redacted>", scrub(line))
    }

    @Test fun smallQueryValuesLikeAnAppIdStayReadable() {
        assertEquals("http://<ip-1>:47989/launch?appid=1234&mode=1920x1080x60&rikey=<redacted>",
            scrub("http://10.0.0.5:47989/launch?appid=1234&mode=1920x1080x60&rikey=00112233445566778899aabbccddeeff"))
    }

    @Test fun aUrlWithCredentialsLosesThem() {
        val out = scrub("GET https://user:hunter2@meiton.example.org/api/v1/status?token=abc123 failed")
        assertFalse(out, out.contains("hunter2") || out.contains("abc123") || out.contains("meiton"))
        assertTrue(out, out.startsWith("GET https://<host>/api/v1/status?token=<redacted> failed"))
    }

    @Test fun aPublicServiceKeepsItsHostForTheDiagnosis() {
        assertEquals("GET https://www.steamgriddb.com/api/v2/grids/game/5254?types=<redacted>",
            scrub("GET https://www.steamgriddb.com/api/v2/grids/game/5254?types=static"))
    }

    @Test fun aKnownPcNameAsUrlHostBecomesItsAlias() {
        assertEquals("https://<pc-1>:47984/serverinfo", scrub("https://meiton-pc:47984/serverinfo"))
    }

    @Test fun theUniqueIdIsRemovedByValueAndByShape() {
        assertEquals("UID is now: <id>", scrub("UID is now: 0123456789abcdef"))
        assertEquals("UID is now: <hex>", scrub("UID is now: fedcba9876543210", ScrubContext()))
    }

    @Test fun ipv4AddressesAreNumberedAndTheSameAddressKeepsItsNumber() {
        assertEquals("<ip-1> -> <ip-2> -> <ip-1>", scrub("192.168.0.200 -> 8.8.8.8 -> 192.168.0.200"))
    }

    @Test fun anOctetOutOfRangeIsNotAnAddress() {
        assertEquals("version 999.1.2.3 built", scrub("version 999.1.2.3 built"))
    }

    @Test fun ipv6AddressesWithZoneBracketsAndMappedTails() {
        assertEquals("addr <ip-1> used", scrub("addr fe80::1c2d:3eff:fe4f:5a6b%wlan0 used"))
        assertEquals("connect [<ip-1>]:47989", scrub("connect [2001:db8::1]:47989"))
        assertEquals("mapped <ip-1>", scrub("mapped 2001:db8:0:0:0:0:0:1"))
        assertEquals("mapped <ip-1>", scrub("mapped ::ffff:192.168.1.7"))
        assertEquals("seen <ip-1>.", scrub("seen fe80::1."))
    }

    @Test fun timesAndPortsAreNotMistakenForAddresses() {
        val line = "2026-11-10 12:34:56.789 I/Lime [main] ratio 16:9 at 12:34:56 on port 47989 (Foo::bar)"
        assertEquals(line, scrub(line))
    }

    @Test fun macAddressesAreNumbered() {
        assertEquals("wake <mac-1> and <mac-2> and <mac-1>", scrub("wake 00:1A:2B:3C:4D:5E and aa-bb-cc-dd-ee-ff and 00:1a:2b:3c:4d:5e"))
    }

    @Test fun knownNamesAreReplacedAsWholeWordsIgnoringCase() {
        assertEquals("<pc-2> is online, <pc-1>'s games, homepage", scrub("living room is online, MEITON-PC's games, homepage"))
        assertEquals("hello <name> on <name>", scrub("hello meiton on Odin Portal"))
        assertEquals("Meitonic stays", scrub("Meitonic stays"))
    }

    @Test fun aShortNameIsNeverUsedAsAPattern() {
        assertEquals("PC ended the stream", scrub("PC ended the stream", ScrubContext(pcNames = listOf("PC"))))
    }

    @Test fun theHostAddressesOfThePcsAreRemoved() {
        assertEquals("resolving <host> failed", scrub("resolving meiton.example.org failed"))
    }

    @Test fun apiKeysAreRemovedByValueByKeyAndByShape() {
        assertEquals("key <secret> rejected", scrub("key sgdbSecretKey99 rejected"))
        assertEquals("api_key=<redacted>", scrub("api_key=abcDEF123", ScrubContext()))
        assertEquals("Authorization: <redacted>", scrub("Authorization: Bearer abc.def-ghi", ScrubContext()))
        assertEquals("sent <token> done", scrub("sent 9fB3kQ7xL2mZ8vC1nR5tY0uW4pH6jD3s done", ScrubContext()))
        assertEquals("steamgriddb <hex>", scrub("steamgriddb 0123456789abcdef0123456789abcdef", ScrubContext()))
    }

    @Test fun userNamesInPathsAreRemoved() {
        assertEquals("/Users/<user>/Library/Caches/x", scrub("/Users/meiton/Library/Caches/x", ScrubContext()))
        assertEquals("C:\\Users\\<user>\\AppData\\x", scrub("C:\\Users\\Meiton\\AppData\\x", ScrubContext()))
        assertEquals("/home/<user>/.config", scrub("/home/bob/.config", ScrubContext()))
        assertEquals("/storage/<volume>/Download/log.txt", scrub("/storage/1A2B-3C4D/Download/log.txt", ScrubContext()))
        assertEquals("/storage/emulated/0/Download/log.txt", scrub("/storage/emulated/0/Download/log.txt", ScrubContext()))
    }

    @Test fun wifiNamesAndSerialsAreRemoved() {
        assertEquals("connected to SSID: <redacted>", scrub("connected to SSID: \"Home Wifi 5G\"", ScrubContext()))
        assertEquals("BSSID=<redacted>", scrub("BSSID=aa:bb:cc:dd:ee:ff", ScrubContext()))
        assertEquals("serial: <redacted>", scrub("serial: R5CT1234ABC", ScrubContext()))
    }

    @Test fun emailsAndLocalHostNamesAreRemoved() {
        assertEquals("from <email>", scrub("from bob@example.com", ScrubContext()))
        assertEquals("cannot reach <host>", scrub("cannot reach desktop-4f2k.local", ScrubContext()))
        assertEquals("java.net.UnknownHostException: <host>", scrub("java.net.UnknownHostException: gamingrig", ScrubContext()))
        assertEquals("Unable to resolve host \"<host>\": No address", scrub("Unable to resolve host \"gamingrig\": No address", ScrubContext()))
    }

    @Test fun anOrdinaryLogLineIsLeftAlone() {
        val line = "2026-11-10 12:34:56.789 I/Stream [main] Negotiated HEVC Main10 4:2:0 HDR at 1920x1080@120, bitrate 80000 kbps"
        assertEquals(line, scrub(line))
        val version = "Process started: Delos 0.1.0 (debug), pid 12345"
        assertEquals(version, scrub(version))
    }

    @Test fun scrubbingTwiceChangesNothingMore() {
        val text = "poll Meiton-PC (192.168.0.200:47989) https://192.168.0.200:47984/applist?uniqueid=0123456789abcdef mac 00:1A:2B:3C:4D:5E"
        val once = scrub(text)
        assertEquals(once, scrub(once))
    }

    @Test fun nothingOfAMixedLogSurvives() {
        val secrets = listOf(
            "192.168.0.200", "10.0.0.5", "fe80::1c2d:3eff:fe4f:5a6b", "00:1A:2B:3C:4D:5E", "Meiton-PC", "Living Room",
            "0123456789abcdef", "sgdbSecretKey99", "meiton.example.org", "hunter2", "Home Wifi", "meiton@example.com",
        )
        val log = """
            Starting parallel poll for Meiton-PC (192.168.0.200:47989, 10.0.0.5:47989, null, fe80::1c2d:3eff:fe4f:5a6b)
            https://192.168.0.200:47984/applist?devicename=x&uniqueid=0123456789abcdef&uuid=1
            Sending WoL to 00:1A:2B:3C:4D:5E for Living Room via meiton.example.org
            UID is now: 0123456789abcdef
            artwork key sgdbSecretKey99 password=hunter2
            SSID: "Home Wifi" owner meiton@example.com
        """.trimIndent()
        val out = scrub(log)
        for (secret in secrets) assertFalse("'$secret' leaked in:\n$out", out.contains(secret, ignoreCase = true))
    }
}
