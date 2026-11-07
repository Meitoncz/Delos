package com.limelight.shell.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedWebpTest {
    /** The first 30 bytes of an extended WebP: RIFF header, VP8X chunk with [flags], a canvas of [w] × [h]. */
    private fun header(flags: Int, w: Int, h: Int, fourCc: String = "VP8X"): ByteArray {
        val b = ByteArray(30)
        "RIFF".toByteArray().copyInto(b, 0)
        "WEBP".toByteArray().copyInto(b, 8)
        fourCc.toByteArray().copyInto(b, 12)
        b[16] = 10
        b[20] = flags.toByte()
        fun put(at: Int, v: Int) { b[at] = v.toByte(); b[at + 1] = (v shr 8).toByte(); b[at + 2] = (v shr 16).toByte() }
        put(24, w - 1)
        put(27, h - 1)
        return b
    }

    @Test fun theAnimationFlagTellsAnimatedFromStill() {
        assertTrue(AnimatedWebp.isAnimated(header(0x12, 1920, 620)))
        assertFalse(AnimatedWebp.isAnimated(header(0x10, 1920, 620)))
    }

    @Test fun canvasSizeIsReadFromTheHeader() {
        assertEquals(3840L * 1240, AnimatedWebp.pixels(header(0x02, 3840, 1240)))
        assertEquals(600L * 900, AnimatedWebp.pixels(header(0x02, 600, 900)))
    }

    @Test fun otherFilesAreNeverAnimated() {
        assertFalse(AnimatedWebp.isAnimated(header(0x02, 10, 10, fourCc = "VP8 ")))
        assertEquals(0L, AnimatedWebp.pixels(header(0x02, 10, 10, fourCc = "VP8L")))
        assertFalse(AnimatedWebp.isAnimated(ByteArray(8)))
        assertFalse(AnimatedWebp.isAnimated(ByteArray(30)))
        val png = ByteArray(30).also { byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()).copyInto(it) }
        assertFalse(AnimatedWebp.isAnimated(png))
    }
}
