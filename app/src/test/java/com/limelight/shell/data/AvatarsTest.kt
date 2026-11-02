package com.limelight.shell.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.min

class AvatarsTest {
    /** Gradle runs unit tests in the module directory; an IDE may start them at the repository root. */
    private val assets = listOf(File("src/main/assets/avatars"), File("app/src/main/assets/avatars")).first { it.isDirectory }

    private fun hueDistance(a: Float, b: Float) = min(abs(a - b) % 360f, 360f - abs(a - b) % 360f)

    @Test fun thereAreTwelveAvatarsWithUniqueNamesAssetsAndWellSeparatedHues() {
        assertEquals(12, AVATARS.size)
        assertEquals(AVATARS.size, AVATARS.map { it.name }.toSet().size)
        assertEquals(AVATARS.size, AVATARS.map { it.asset }.toSet().size)
        for (i in AVATARS.indices) for (j in i + 1 until AVATARS.size) {
            assertTrue(
                "${AVATARS[i].name} and ${AVATARS[j].name} are too close in hue",
                hueDistance(AVATARS[i].hue, AVATARS[j].hue) >= 12f,
            )
        }
    }

    @Test fun theFirstSixKeepTheirIndexSoStoredProfilesKeepTheirAvatar() {
        assertEquals(
            listOf("Sailor" to 205f, "Voyager" to 262f, "Player" to 330f, "Fox" to 24f, "Spark" to 48f, "Calm" to 150f),
            AVATARS.take(6).map { it.name to it.hue },
        )
    }

    @Test fun everyAvatarHasItsBundledImageAndNoImageIsLeftOver() {
        val files = assets.listFiles().orEmpty().map { it.name }.toSet()
        assertEquals(AVATARS.map { it.asset }.toSet(), files)
    }

    @Test fun everyImageIsA384PixelWebpWithAlpha() {
        for (avatar in AVATARS) {
            val bytes = File(assets, avatar.asset!!).readBytes()
            assertEquals("${avatar.name} is not a WebP", "RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("${avatar.name} is not a WebP", "WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
            // An extended WebP: the character is on a transparent canvas, the profile color shows through
            assertEquals("${avatar.name} has no alpha header", "VP8X", String(bytes, 12, 4, Charsets.US_ASCII))
            assertTrue("${avatar.name} has no alpha", (bytes[20].toInt() and 0x10) != 0)
            assertEquals(384, u24(bytes, 24) + 1)
            assertEquals(384, u24(bytes, 27) + 1)
        }
    }

    @Test fun outOfRangeIndicesAreCoercedToTheNearestAvatar() {
        assertEquals(AVATARS.first(), Profile("a", "A", -3).look)
        assertEquals(AVATARS[7], Profile("a", "A", 7).look)
        assertEquals(AVATARS.last(), Profile("a", "A", 99).look)
    }

    private fun u24(bytes: ByteArray, at: Int) =
        (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8) or ((bytes[at + 2].toInt() and 0xff) shl 16)
}
