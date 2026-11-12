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

    private fun le(value: Long, bytes: Int) = ByteArray(bytes) { (value shr (8 * it)).toByte() }

    private fun chunk(tag: String, payload: ByteArray): ByteArray =
        tag.toByteArray() + le(payload.size.toLong(), 4) + payload + (if (payload.size % 2 == 1) byteArrayOf(0) else byteArrayOf())

    /** An ANMF chunk: X, Y, width, height (3 bytes each), [duration] (3 bytes), flags, then [data] bytes of frame data. */
    private fun frame(duration: Int, data: Int = 6) =
        chunk("ANMF", le(0, 3) + le(0, 3) + le(9, 3) + le(9, 3) + le(duration.toLong(), 3) + byteArrayOf(0) + ByteArray(data) { 7 })

    private fun webp(vararg chunks: ByteArray): ByteArray {
        val body = "WEBP".toByteArray() + chunks.fold(ByteArray(0)) { all, c -> all + c }
        return "RIFF".toByteArray() + le(body.size.toLong(), 4) + body
    }

    private fun loop(file: ByteArray) = AnimatedWebp.loopMillis(file.inputStream())

    @Test fun theLoopIsTheSumOfTheFrameDurations() {
        val vp8x = chunk("VP8X", header(0x12, 10, 10).copyOfRange(20, 30))
        val anim = chunk("ANIM", ByteArray(6))
        assertEquals(1000L + 250 + 65_000, loop(webp(vp8x, anim, frame(1000), frame(250), frame(65_000))))
    }

    @Test fun durationsAreTwentyFourBitLittleEndian() {
        // 0x010203 = 66051 ms: every one of the three bytes counts
        assertEquals(66_051L, loop(webp(frame(0x010203))))
    }

    @Test fun oddSizedChunksArePaddedAndFramesAfterThemAreFound() {
        // The frame data of 5 bytes makes an odd chunk (and the unknown chunk 3 bytes): both are followed by a pad byte
        val unknown = chunk("EXIF", ByteArray(3) { 1 })
        assertEquals(300L + 400, loop(webp(frame(300, data = 5), unknown, frame(400, data = 5))))
    }

    @Test fun anythingButACompleteAnimationHasNoLoop() {
        assertEquals(0L, loop(webp(chunk("VP8 ", ByteArray(10)))))
        assertEquals(0L, loop(ByteArray(0)))
        assertEquals(0L, loop("RIFF0000WAVE".toByteArray()))
        val whole = webp(frame(100), frame(100))
        // Cut inside the second frame's header, and inside its data
        assertEquals(0L, loop(whole.copyOf(whole.size - 12)))
        assertEquals(0L, loop(whole.copyOf(whole.size - 3)))
    }
}
