package com.limelight.diag

import com.limelight.nvstream.jni.MoonBridge

/** The sentences of the stream events in the diagnostic log, in one place so they are tested. */
object StreamEvents {
    /** What the host and the client settled on: codec, chroma, HDR, size, rate and the bitrate asked for. */
    fun negotiated(format: Int, width: Int, height: Int, fps: Int, bitrateKbps: Int): String {
        val codec = when {
            format and MoonBridge.VIDEO_FORMAT_MASK_PYROWAVE != 0 -> "PyroWave"
            format and MoonBridge.VIDEO_FORMAT_MASK_AV1 != 0 -> "AV1"
            format and MoonBridge.VIDEO_FORMAT_MASK_H265 != 0 -> "HEVC"
            format and MoonBridge.VIDEO_FORMAT_MASK_H264 != 0 -> "H.264"
            else -> "unknown"
        }
        val chroma = if (format and MoonBridge.VIDEO_FORMAT_MASK_YUV444 != 0) "4:4:4" else "4:2:0"
        val hdr = if (format and MoonBridge.VIDEO_FORMAT_MASK_10BIT != 0) "10-bit (HDR)" else "8-bit (SDR)"
        return "Negotiated video: $codec $chroma $hdr ${width}x$height at $fps fps, bitrate $bitrateKbps kbps (format 0x${Integer.toHexString(format).uppercase()})"
    }
}
