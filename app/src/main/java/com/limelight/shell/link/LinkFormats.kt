package com.limelight.shell.link

import com.limelight.preferences.PreferenceConfiguration
import com.limelight.preferences.PyroWaveBitrate

/** The codec a stream is asked for; the link guidance treats PyroWave and the ordinary codecs differently. */
internal enum class LinkCodec(val label: String) {
    PYROWAVE("PyroWave"), H264("H.264"), HEVC("HEVC"), AV1("AV1"),

    /** "Automatic": the codec is chosen when the stream starts, so the cautious middle of the ordinary codecs is assumed. */
    AUTO("");

    companion object {
        /** From the `video_format` preference value (`auto`, `forceav1`, `forceh265`, `neverh265` = H.264 always, `pyrowave`). */
        fun fromPreference(value: String?): LinkCodec = when (value) {
            "pyrowave" -> PYROWAVE
            "forceav1" -> AV1
            "forceh265" -> HEVC
            "neverh265" -> H264
            else -> AUTO
        }
    }
}

/**
 * The stream format the guidance talks about. [defaultKbps] is the bitrate Delos suggests for it without any test: PyroWave's
 * quality guide, or the app's own default (`PreferenceConfiguration.getDefaultBitrate`, Moonlight's 20 Mbps at 1080p60) for
 * the others. [label] names it for the one-sentence help ("1080p60 PyroWave 4:2:0").
 */
internal data class LinkFormat(val codec: LinkCodec, val label: String, val fps: Int, val defaultKbps: Int)

/** "1080p", "1440p", "4K" from the height. */
internal fun resolutionLabel(height: Int): String = if (height >= 2160) "4K" else "${height}p"

/**
 * The format for [width] x [height] at [fps] (Hz, rounded). PyroWave in HDR uses the 4:2:0 guide, as everywhere else in the app.
 * Null for a size that makes no sense.
 */
internal fun linkFormat(codec: LinkCodec, width: Int, height: Int, fps: Int, hdr: Boolean, chroma444: Boolean): LinkFormat? {
    if (width <= 0 || height <= 0 || fps <= 0) return null
    val base = "${resolutionLabel(height)}$fps${if (hdr) " HDR" else ""}"
    return if (codec == LinkCodec.PYROWAVE) {
        val full = chroma444 && !hdr
        LinkFormat(codec, "$base PyroWave ${if (full) "4:4:4" else "4:2:0"}", fps,
            minOf(PyroWaveBitrate.recommendedKbps(width, height, fps, full, hdr), PreferenceConfiguration.MAX_BITRATE_KBPS))
    } else {
        LinkFormat(codec, if (codec == LinkCodec.AUTO) base else "$base ${codec.label}", fps,
            PreferenceConfiguration.getDefaultBitrate("${width}x$height", fps.toString()))
    }
}

/** The format from the preference values (`video_format`, `list_resolution` "1920x1080", `list_fps`, HDR, PyroWave chroma "444"). */
internal fun linkFormat(videoFormat: String?, resolution: String?, fps: String?, hdr: Boolean, chroma: String?): LinkFormat? {
    val size = resolution?.split('x')?.mapNotNull { it.trim().toIntOrNull() } ?: return null
    if (size.size != 2) return null
    val rate = fps?.toFloatOrNull()?.let { Math.round(it) } ?: return null
    return linkFormat(LinkCodec.fromPreference(videoFormat), size[0], size[1], rate, hdr, chroma == "444")
}
