package com.limelight.shell.data

import com.limelight.nvstream.http.NvApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BundledArtworkTest {
    private fun app(name: String, uuid: String = "") = App(1, name, false, false, NvApp(name, uuid, 1, false))

    @Test fun knownNamesIgnoreCaseAndOuterWhitespaceAndSupportTheSteamAlias() {
        val cases = mapOf(
            " \tDeSkToP\n" to BundledArtwork.Kind.DESKTOP,
            " REMOTE INPUT " to BundledArtwork.Kind.REMOTE_INPUT,
            "remote MONITOR" to BundledArtwork.Kind.REMOTE_MONITOR,
            " Steam Big Picture " to BundledArtwork.Kind.STEAM_BIG_PICTURE,
            "STEAM BIGPICTURE" to BundledArtwork.Kind.STEAM_BIG_PICTURE,
        )
        for ((name, expected) in cases) assertEquals(expected, BundledArtwork.kind(app(name)))
    }

    @Test fun fixedRemoteUuidsSurviveRenamesAndOverrideTheDisplayName() {
        assertEquals(BundledArtwork.Kind.REMOTE_INPUT,
            BundledArtwork.kind(app("My keyboard", "9A1C5A25-58FE-40E0-B9AA-7D3F00000006")))
        assertEquals(BundledArtwork.Kind.REMOTE_MONITOR,
            BundledArtwork.kind(app("Desktop", "9a1c5a25-58fe-40e0-b9aa-7d3f00000005")))
    }

    @Test fun namesStillWorkWithUnknownUuidsButInstallationSpecificUuidsAloneDoNot() {
        assertEquals(BundledArtwork.Kind.DESKTOP, BundledArtwork.kind(app(" desktop ", "unknown")))
        for (uuid in listOf("unknown", "B8656114-5BF4-E608-9B9B-278E876A36DC", "87362A0B-22EA-E101-2094-3C6565E09AB7")) {
            assertNull(BundledArtwork.kind(app("Another game", uuid)))
        }
        assertNull(BundledArtwork.kind(app("Desktop Simulator")))
    }

    @Test fun eachArtworkKindHasItsAssetButLogosAndOrdinaryAppsNeverDo() {
        for ((name, directory) in mapOf(
            "Desktop" to "artwork/desktop",
            "Remote Input" to "artwork/remote-input",
            "Remote Monitor" to "artwork/remote-monitor",
            "Steam Big Picture" to "artwork/steam-big-picture",
        )) {
            val builtin = app(name)
            assertEquals("$directory/cover.jpg", BundledArtwork.assetPath(builtin, ArtKind.COVER))
            assertEquals("$directory/wide.jpg", BundledArtwork.assetPath(builtin, ArtKind.WIDE))
            assertEquals("$directory/hero.jpg", BundledArtwork.assetPath(builtin, ArtKind.HERO))
            assertNull(BundledArtwork.assetPath(builtin, ArtKind.LOGO))
        }
        for (artKind in ArtKind.entries) assertNull(BundledArtwork.assetPath(app("Ordinary game"), artKind))
    }
}
