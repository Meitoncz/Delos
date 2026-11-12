package com.limelight.shell.data

/**
 * Reads what the app needs from the first bytes of a WebP file: whether it is animated (the one animated
 * format SteamGridDB images are asked for) and how big its canvas is. Only the extended header (VP8X) tells.
 */
object AnimatedWebp {
    /** Enough bytes for [isAnimated] and [pixels]. */
    const val HEADER_BYTES = 30

    /** The largest canvas that is played (a 3840 × 1240 hero is 4.8 million pixels). */
    const val MAX_PIXELS = 100_000_000L

    private const val FLAGS = 20
    private const val ANIMATION_FLAG = 0x02

    private fun isExtendedWebp(h: ByteArray) =
        h.size >= HEADER_BYTES &&
            h[0] == 'R'.code.toByte() && h[1] == 'I'.code.toByte() && h[2] == 'F'.code.toByte() && h[3] == 'F'.code.toByte() &&
            h[8] == 'W'.code.toByte() && h[9] == 'E'.code.toByte() && h[10] == 'B'.code.toByte() && h[11] == 'P'.code.toByte() &&
            h[12] == 'V'.code.toByte() && h[13] == 'P'.code.toByte() && h[14] == '8'.code.toByte() && h[15] == 'X'.code.toByte()

    fun isAnimated(header: ByteArray): Boolean = isExtendedWebp(header) && (header[FLAGS].toInt() and ANIMATION_FLAG) != 0

    /** The canvas size in pixels (width × height), 0 when [header] is not an extended WebP. */
    fun pixels(header: ByteArray): Long {
        if (!isExtendedWebp(header)) return 0
        fun le24(at: Int) = (header[at].toInt() and 0xFF) or ((header[at + 1].toInt() and 0xFF) shl 8) or ((header[at + 2].toInt() and 0xFF) shl 16)
        return (le24(24) + 1L) * (le24(27) + 1L)
    }

    /**
     * How long one pass of the animation in [input] (a whole WebP file) lasts: the sum of the durations of its frames
     * (the 24-bit little-endian field in the header of each `ANMF` chunk), in milliseconds. 0 when it is not an animated
     * WebP or the file is cut short. Only the chunk headers are read; the frames' data is skipped.
     */
    fun loopMillis(input: java.io.InputStream): Long {
        val riff = ByteArray(12)
        if (!readFully(input, riff) || !fourCc(riff, 0, "RIFF") || !fourCc(riff, 8, "WEBP")) return 0
        val chunk = ByteArray(8)
        val frame = ByteArray(ANMF_HEADER)
        var total = 0L
        var frames = 0
        while (true) {
            val first = input.read()
            if (first < 0) break
            chunk[0] = first.toByte()
            if (!readFully(input, chunk, 1)) return 0
            val size = (chunk[4].toLong() and 0xFF) or ((chunk[5].toLong() and 0xFF) shl 8) or
                ((chunk[6].toLong() and 0xFF) shl 16) or ((chunk[7].toLong() and 0xFF) shl 24)
            // Chunk payloads are padded to an even size
            val padded = size + (size and 1L)
            if (fourCc(chunk, 0, "ANMF")) {
                if (size < ANMF_HEADER || !readFully(input, frame)) return 0
                total += (frame[DURATION].toInt() and 0xFF) or ((frame[DURATION + 1].toInt() and 0xFF) shl 8) or ((frame[DURATION + 2].toInt() and 0xFF) shl 16)
                frames++
                if (!skipFully(input, padded - ANMF_HEADER)) return 0
            } else if (!skipFully(input, padded)) return 0
        }
        return if (frames > 0) total else 0
    }

    /** Bytes of an `ANMF` chunk's payload before the frame data: X, Y, width, height, duration (3 bytes each) and flags. */
    private const val ANMF_HEADER = 16
    private const val DURATION = 12

    private fun fourCc(b: ByteArray, at: Int, tag: String) = tag.indices.all { b[at + it] == tag[it].code.toByte() }

    private fun readFully(input: java.io.InputStream, into: ByteArray, from: Int = 0): Boolean {
        var at = from
        while (at < into.size) {
            val n = input.read(into, at, into.size - at)
            if (n < 0) return false
            at += n
        }
        return true
    }

    private fun skipFully(input: java.io.InputStream, count: Long): Boolean {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n > 0) {
                left -= n
            } else {
                // skip may do nothing without being at the end: read one byte to find out
                if (input.read() < 0) return false
                left--
            }
        }
        return true
    }

    /** Up to [limit] bytes of [input] (InputStream.readNBytes needs Android 13). */
    fun read(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var left = limit
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size, left))
            if (n < 0) break
            out.write(buffer, 0, n)
            left -= n
        }
        return out.toByteArray()
    }
}
