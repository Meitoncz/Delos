package com.limelight.shell.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovedThumbnailTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val transparent = 0

    private fun pixels(blackShare: Double, rest: Int, n: Int = 2304): IntArray {
        val blackCount = (n * blackShare).toInt()
        return IntArray(n) { if (it < blackCount) black else rest }
    }

    @Test fun aBlackPictureWithAFewLightPixelsOfTextIsAPlaceholder() {
        assertTrue(Thumbnails.looksRemoved(pixels(0.95, white)))
    }

    @Test fun darkArtworkWithRealContentIsKept() {
        assertFalse(Thumbnails.looksRemoved(pixels(0.6, 0xFF402010.toInt())))
        // Near-black but not pure black (a dark scene, a gradient)
        assertFalse(Thumbnails.looksRemoved(IntArray(2304) { 0xFF0C0C10.toInt() }))
    }

    @Test fun aTransparentLogoIsNeverAPlaceholder() {
        assertFalse(Thumbnails.looksRemoved(pixels(0.05, transparent)))
        assertFalse(Thumbnails.looksRemoved(IntArray(0)))
    }
}
