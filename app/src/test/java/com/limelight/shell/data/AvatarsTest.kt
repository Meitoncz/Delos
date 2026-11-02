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

    private val gradients get() = AvatarGradients.parse(File(assets, "avatars.json").readText(Charsets.UTF_8))

    private fun slug(asset: String?) = asset!!.removeSuffix(".webp")

    @Test fun thereAreTwelveAvatarsWithUniqueNamesAndAssets() {
        assertEquals(12, AVATARS.size)
        assertEquals(AVATARS.size, AVATARS.map { it.name }.toSet().size)
        assertEquals(AVATARS.size, AVATARS.map { it.asset }.toSet().size)
    }

    /** An avatar with colors of its own (a warm imported character) is told apart by them; the others by their hue. */
    @Test fun avatarsWithoutImportedColorsHaveWellSeparatedHues() {
        val hued = AVATARS.filter { slug(it.asset) !in gradients }
        for (i in hued.indices) for (j in i + 1 until hued.size) {
            assertTrue(
                "${hued[i].name} and ${hued[j].name} are too close in hue",
                hueDistance(hued[i].hue, hued[j].hue) >= 12f,
            )
        }
    }

    @Test fun everyColorEntryIsValidAndBelongsToAnAvatar() {
        val slugs = AVATARS.map { slug(it.asset) }.toSet()
        // parse() rejects anything but "#rrggbb" for c0 and c1
        for ((name, gradient) in gradients) {
            assertTrue("$name in avatars.json is not an avatar", name in slugs)
            assertTrue("$name has transparent colors", gradient.c0.alpha == 1f && gradient.c1.alpha == 1f)
        }
    }

    @Test fun brokenColorEntriesAreRejected() {
        assertEquals(1, AvatarGradients.parse("""{"fox":{"c0":"#ff8800","c1":"#0033aa"}}""").size)
        for (bad in listOf("""{"fox":{"c0":"ff8800","c1":"#0033aa"}}""", """{"fox":{"c0":"#ff8800"}}""", """{"fox":1}""", "[]", "nope")) {
            assertTrue("accepted $bad", runCatching { AvatarGradients.parse(bad) }.isFailure)
        }
    }

    @Test fun theFirstSixKeepTheirIndexSoStoredProfilesKeepTheirAvatar() {
        assertEquals(
            listOf("Sailor" to 205f, "Voyager" to 262f, "Player" to 330f, "Fox" to 24f, "Spark" to 48f, "Calm" to 150f),
            AVATARS.take(6).map { it.name to it.hue },
        )
    }

    @Test fun everyAvatarHasItsBundledImageAndNoImageIsLeftOver() {
        val files = assets.listFiles().orEmpty().map { it.name }.toSet() - "avatars.json"
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
