package com.limelight.shell.ui

import com.limelight.shell.HeaderTint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-cover color cache and the context menu header's color taken from it. */
class CoverColorsTest {
    private val red = GameColors(listOf(3f, 40f, 200f), listOf(1f, .8f, .6f))
    private val blue = GameColors(listOf(220f, 180f, 40f), listOf(.9f, .7f, .5f))

    @Test fun aCoverIsWorkedOutOncePerImage() {
        val cache = CoverColorCache()
        assertTrue(cache.claim("pc/1", 11))
        assertFalse("the same image again", cache.claim("pc/1", 11))
        assertTrue("another game with the same image id", cache.claim("pc/2", 11))
        assertTrue("new artwork for the game", cache.claim("pc/1", 12))
    }

    @Test fun colorsAreUnknownUntilStoredAndAGreyCoverIsKnown() {
        val cache = CoverColorCache()
        cache.claim("pc/1", 11)
        assertFalse(cache.known("pc/1"))
        assertNull(cache.get("pc/1"))
        cache.store("pc/1", 11, null)
        assertTrue(cache.known("pc/1"))
        assertNull(cache.get("pc/1"))
    }

    @Test fun anOlderImageFinishingLateDoesNotReplaceTheNewerOnes() {
        val cache = CoverColorCache()
        cache.claim("pc/1", 11)
        cache.claim("pc/1", 12)
        cache.store("pc/1", 12, blue)
        cache.store("pc/1", 11, red)
        assertEquals(blue, cache.get("pc/1"))
    }

    @Test fun thePreviousColorsStayWhileNewArtworkIsWorkedOut() {
        val cache = CoverColorCache()
        cache.claim("pc/1", 11)
        cache.store("pc/1", 11, red)
        cache.claim("pc/1", 12)
        assertTrue(cache.known("pc/1"))
        assertEquals(red, cache.get("pc/1"))
        cache.store("pc/1", 12, blue)
        assertEquals(blue, cache.get("pc/1"))
    }

    @Test fun theHeaderTakesTheCoversColorOnceKnown() {
        assertEquals(HeaderTint.Game(hsl(3f, 80f, 52f)), gameHeaderTint(matching = true, hasCover = true, known = true, cover = red, placeholderHue = 120f))
    }

    @Test fun aCoverStillBeingWorkedOutIsPendingNotTheAccent() {
        assertEquals(HeaderTint.Pending, gameHeaderTint(matching = true, hasCover = true, known = false, cover = null, placeholderHue = 120f))
    }

    @Test fun aGreyCoverAndGameColorsOffUseTheAccent() {
        assertEquals(HeaderTint.Accent, gameHeaderTint(matching = true, hasCover = true, known = true, cover = null, placeholderHue = 120f))
        assertEquals(HeaderTint.Accent, gameHeaderTint(matching = false, hasCover = true, known = true, cover = red, placeholderHue = 120f))
    }

    @Test fun aGameWithoutACoverTakesItsPlaceholdersHue() {
        assertEquals(HeaderTint.Game(hsl(120f, 70f, 52f)), gameHeaderTint(matching = true, hasCover = false, known = false, cover = null, placeholderHue = 120f))
    }
}
