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
