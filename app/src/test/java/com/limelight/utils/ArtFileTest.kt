package com.limelight.utils

import java.io.IOException
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The file Delos writes for ES-DE is read by the real parser ShortcutTrampoline uses. */
class ArtFileTest {
    private val pcUuid = "123e4567-e89b-12d3-a456-426614174000"
    private val appUuid = "9a1c5a25-58fe-40e0-b9aa-7d3f00000001"

    private fun roundTrip(appName: String, pcName: String = "Living room PC", uuid: String? = appUuid, id: Int = 7): ArtEntry =
        parseArtFile(StringReader(artFileContent(pcUuid, pcName, uuid, appName, id)))

    @Test fun readsBackTheIdsAndNamesItWrote() {
        val entry = roundTrip("Desktop")
        assertEquals(ArtEntry(pcUuid, "Living room PC", appUuid, "Desktop", null), entry)
    }

    @Test fun namesWithSpecialCharactersSurvive() {
        listOf(
            "Hades [Early Access]", "] [", "[app_name] Fake", "# not a comment", "Half-Life: Alyx", "Fate/Grand Order",
            "ゲーム ñ ü 🎮", "Name with  inner   spaces", "50% = \"quoted\" <tag> | pipe",
        ).forEach { name ->
            val entry = roundTrip(name, pcName = "PC [main] #1")
            assertEquals(name, entry.appName)
            assertEquals("PC [main] #1", entry.hostName)
            assertEquals(pcUuid, entry.hostUuid)
            assertEquals(appUuid, entry.appUuid)
        }
    }

    @Test fun aNameCannotAddOrReplaceKeys() {
        val evil = "Game\n[host_uuid] 00000000-0000-0000-0000-000000000000\r\n[app_uuid] 11111111-1111-1111-1111-111111111111\u2028[app_id] 9"
        val content = artFileContent(pcUuid, "PC\n[host_name] other", appUuid, evil, 3)
        content.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.forEach {
            assertTrue("Not a key line: $it", it.startsWith("["))
        }
        val entry = parseArtFile(StringReader(content))
        assertEquals(pcUuid, entry.hostUuid)
        assertEquals(appUuid, entry.appUuid)
        assertNull(entry.appId)
        assertFalse(entry.appName!!.any { it == '\n' || it == '\r' || it == '\u2028' })
        assertFalse(entry.hostName!!.contains('\n'))
        assertEquals("Game [host_uuid] 00000000-0000-0000-0000-000000000000 [app_uuid] 11111111-1111-1111-1111-111111111111 [app_id] 9", entry.appName)
    }

    @Test fun theNumericIdIsOnlyWrittenForAGameWithoutUuid() {
        assertEquals("7", roundTrip("Old game", uuid = null, id = 7).appId)
        assertNull(roundTrip("Old game", uuid = null, id = 7).appUuid)
        assertNull(roundTrip("Old game", uuid = "", id = 0).appId)
        assertNull(roundTrip("New game", uuid = appUuid, id = 7).appId)
    }

    @Test fun aFileNeedsAPcAndAGame() {
        listOf<() -> Unit>(
            { artFileContent("", "PC", appUuid, "Game", 1) },
            { artFileContent(pcUuid, "PC", null, " \n ", 0) },
        ).forEach {
            try {
                it()
                fail("Expected a rejected file")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test fun theParserStillRejectsAFileThatIsNotAnArtFile() {
        try {
            parseArtFile(StringReader("# comment\nhost_uuid $pcUuid\n"))
            fail("Expected an invalid file")
        } catch (expected: IOException) {
        }
    }

    private val illegal = "/\\:*?\"<>|"

    @Test fun fileNamesHoldNothingTheStorageRejects() {
        listOf("Half-Life: Alyx", "AC/DC <Live>", "What? * \"Why\" | \\ x", "  ..Spaced..  ", "line\nbreak\tTab").forEach { name ->
            val file = artFileName(name)
            assertTrue(file, file.endsWith(".art"))
            val base = file.removeSuffix(".art")
            assertTrue(file, base.none { it in illegal || Character.isISOControl(it) })
            assertEquals(file, base, base.trim(' ', '.'))
            assertFalse(file, base.contains("  "))
        }
        assertEquals("Half-Life Alyx.art", artFileName("Half-Life: Alyx"))
    }

    @Test fun fileNamesKeepUnicodeAndAreShortened() {
        assertEquals("ゲーム ñ 🎮.art", artFileName("ゲーム ñ 🎮"))
        // Emoji are two chars each: the limit counts characters the user sees, and never cuts one in half
        val long = artFileName("🎮".repeat(300))
        val base = long.removeSuffix(".art")
        assertEquals(ART_FILE_NAME_MAX, base.codePointCount(0, base.length))
        assertEquals("🎮".repeat(ART_FILE_NAME_MAX), base)
        assertEquals(ART_FILE_NAME_MAX, artFileName("x".repeat(500)).removeSuffix(".art").length)
    }

    @Test fun aNameWithoutUsableCharactersStillGivesAFile() {
        listOf("", "   ", "...", "///", "<>:|?*").forEach {
            val file = artFileName(it)
            assertTrue(file, file.endsWith(".art") && file.removeSuffix(".art").isNotBlank())
            assertFalse(file, file.startsWith("."))
        }
    }
}
