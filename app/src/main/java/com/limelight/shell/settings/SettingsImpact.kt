package com.limelight.shell.settings

import com.limelight.preferences.UpscalerMode

/** A cost bucket, never a measurement. [NONE] is dimmed in the UI; the other three fill one, two or three bars. */
enum class ImpactLevel { NONE, LOW, MEDIUM, HIGH }

/**
 * What a setting's current value costs, on three axes: the phone's GPU time, the end-to-end latency and the
 * network bandwidth (issue #40, prototype variant B: three chips next to the row name).
 */
data class SettingImpact(val gpu: ImpactLevel, val latency: ImpactLevel, val network: ImpactLevel) {
    val none: Boolean get() = gpu == ImpactLevel.NONE && latency == ImpactLevel.NONE && network == ImpactLevel.NONE
}

/**
 * The impact of the setting [key] for the effective [values] (the same map as [SettingsHelp.details]), or null when the
 * setting has no impact chips, or when it is disabled by the other values (PyroWave chroma without PyroWave, the
 * supersampling level with Sharpen only). The upscaler rows return an all-[ImpactLevel.NONE] impact while the upscaler
 * is off, so their chips dim. Pure, so it is unit-tested and also usable by the per-game screen
 * (`impact(spec.key, values)`).
 *
 * Levels come from numbers only where the repo has them (docs/STREAM_QUALITY.md, docs/SGSR2_VIDEO.md):
 * - SGSR2-DV costs about 6.85 ms (1080p to 1440p, the 1.33x level) or 10.23 ms (1080p to 4K, 2x) on the Odin's
 *   Adreno 740; the GPU level is that time over the frame period (below 35 % Low, below 70 % Medium, else High). A pass
 *   longer than the frame period is over budget: frames queue, so the latency is High.
 * - Sharpen only and the final sharpness pass cost about 0.31 ms.
 * - PyroWave bandwidth is the real [pyroWaveRecommendedBitrateKbps] polynomial.
 * Every other level is a qualitative estimate that follows the help texts ([SettingsHelp]) and docs/STREAM_QUALITY.md; the
 * ordinary-codec bitrate table (LinkPolicy: 20 / 40 / 80 Mbps at 1080p / 1440p / 4K 60 FPS, doubled for HEVC and AV1,
 * tripled for H.264, capped at 150) is a judgment constant, not a measurement.
 */
fun impact(key: String, values: Map<String, *>): SettingImpact? {
    val c = ImpactContext.of(values)
    return when (key) {
        "list_resolution" -> resolution(c)
        "list_fps" -> frameRate(c)
        "seekbar_bitrate_kbps" -> bitrate(c)
        "video_format" -> codec(c)
        "checkbox_enable_hdr" -> hdr(c)
        "list_pyrowave_chroma" -> if (c.pyro) chroma(c) else null
        "frame_pacing" -> pacing(c)
        "checkbox_sgsr" -> if (c.upscaler) mode(c) else QUIET
        "list_sgsr_quality" -> if (c.upscaler) mode(c) else QUIET
        "seekbar_sgsr_sharpness" -> if (!c.upscaler || c.sharpness == 0) QUIET else I(L, L, N)
        "list_sgsr_supersampling_level" -> when {
            !c.upscaler -> QUIET
            c.mode == UpscalerMode.SHARPEN_ONLY.value() -> null
            else -> supersampling(c)
        }
        else -> null
    }
}

private val N = ImpactLevel.NONE
private val L = ImpactLevel.LOW
private val M = ImpactLevel.MEDIUM
private val H = ImpactLevel.HIGH
private val QUIET = SettingImpact(N, N, N)
private fun I(gpu: ImpactLevel, latency: ImpactLevel, network: ImpactLevel) = SettingImpact(gpu, latency, network)

private const val SHARPEN_MS = 0.31
private const val SGSR2_MS_1440 = 6.85
private const val SGSR2_MS_4K = 10.23

/** The values the impact depends on, parsed once with the defaults of the preference catalog. */
private class ImpactContext(
    val height: Int,
    val fps: Double,
    val bitrateMbps: Int,
    val codec: String,
    val hdr: Boolean,
    val pacing: String,
    val upscaler: Boolean,
    val mode: String,
    val sharpness: Int,
    val twoTimes: Boolean,
    val pyroKbps: Int?,
    val values: Map<String, *>,
) {
    val pyro: Boolean get() = codec == "pyrowave"
    val periodMs: Double get() = 1000.0 / fps
    val temporal: Boolean get() = mode == UpscalerMode.VIDEO.value() || mode == UpscalerMode.VIDEO_QUALITY.value()
    val temporalMs: Double get() = if (twoTimes) SGSR2_MS_4K else SGSR2_MS_1440

    /** The upscaler pass in ms for the current mode, or null where the repo has no measurement. */
    val upscalerMs: Double?
        get() = when {
            !upscaler -> null
            mode == UpscalerMode.SHARPEN_ONLY.value() -> SHARPEN_MS
            temporal -> temporalMs
            else -> null
        }

    /** The bitrate (Mbps) the host would be asked for with this codec, resolution and frame rate, if the repo knows it. */
    val recommendedMbps: Int?
        get() = if (pyro) pyroKbps?.let { it / 1000 } else ordinaryMbps(height, fps, codec)

    companion object {
        fun of(values: Map<String, *>): ImpactContext {
            val height = (values["list_resolution"] as? String)?.substringAfter("x", "")?.toIntOrNull() ?: 1080
            val fps = ((values["list_fps"] as? String)?.toDoubleOrNull() ?: 60.0).coerceAtLeast(1.0)
            val hdr = values["checkbox_enable_hdr"] as? Boolean ?: false
            return ImpactContext(
                height = height.coerceAtLeast(1),
                fps = fps,
                bitrateMbps = ((values["seekbar_bitrate_kbps"] as? Number)?.toInt() ?: 0) / 1000,
                codec = values["video_format"] as? String ?: "auto",
                hdr = hdr,
                pacing = values["frame_pacing"] as? String ?: "latency",
                upscaler = values["checkbox_sgsr"] as? Boolean ?: false,
                mode = values["list_sgsr_quality"] as? String ?: UpscalerMode.STANDARD.value(),
                sharpness = (values["seekbar_sgsr_sharpness"] as? Number)?.toInt() ?: 50,
                twoTimes = values["list_sgsr_supersampling_level"] == "2",
                pyroKbps = pyroWaveRecommendedBitrateKbps(values),
                values = values,
            )
        }
    }
}

/** Moonlight's 60 FPS table (LinkPolicy), judgment constants. Null off 60 FPS and for a resolution the table lacks. */
private fun ordinaryMbps(height: Int, fps: Double, codec: String): Int? {
    if (fps.toInt() != 60) return null
    val base = when {
        height <= 720 -> return null
        height <= 1080 -> 20
        height <= 1440 -> 40
        else -> 80
    }
    return minOf(150, base * if (codec == "neverh265") 3 else 2)
}

/** Low below 50 Mbps, Medium below 150, else High (the 4K60 knee of the link sits at 100-160). */
private fun networkLevel(mbps: Int) = if (mbps < 50) L else if (mbps < 150) M else H

private fun gpuLevel(ms: Double, periodMs: Double): ImpactLevel {
    val share = ms / periodMs
    return if (share < 0.35) L else if (share < 0.70) M else H
}

private fun resolution(c: ImpactContext): SettingImpact {
    val big = c.height >= 2160
    val rec = c.recommendedMbps
    val network = when {
        rec != null -> networkLevel(rec)
        c.height <= 720 -> L
        else -> M
    }
    return I(if (big) M else L, if (big) M else L, network)
}

private fun frameRate(c: ImpactContext): SettingImpact {
    val ms = c.upscalerMs
    val low = c.fps <= 60.0
    val gpu = when {
        ms != null -> gpuLevel(ms, c.periodMs)
        c.pyro -> if (low) M else H
        else -> L
    }
    return I(gpu, if (low) M else L, if (c.pyro) H else if (low) L else M)
}

private fun bitrate(c: ImpactContext) =
    I(N, if (c.bitrateMbps >= 150) M else L, networkLevel(c.bitrateMbps))

private fun codec(c: ImpactContext): SettingImpact = when (c.codec) {
    "neverh265" -> I(L, L, M)
    "pyrowave" -> I(H, L, H)
    else -> I(L, L, L)
}

private fun hdr(c: ImpactContext): SettingImpact =
    if (!c.hdr) QUIET else I(L, N, if (c.pyro) H else L)

private fun chroma(c: ImpactContext): SettingImpact {
    val full = c.values["list_pyrowave_chroma"] == "444" && !c.hdr
    return I(if (full) H else M, L, H)
}

private fun pacing(c: ImpactContext): SettingImpact = when (c.pacing) {
    "latency" -> I(L, L, N)
    "balanced", "cap-fps" -> I(L, M, N)
    "smoothness" -> I(L, H, N)
    "warp" -> I(M, L, M)
    "warp2" -> I(H, L, H)
    else -> I(L, L, N)
}

/** The Upscaler mode row; also the on/off row while the upscaler is on. */
private fun mode(c: ImpactContext): SettingImpact = when (c.mode) {
    UpscalerMode.SHARPEN_ONLY.value() -> I(L, L, N)
    UpscalerMode.STANDARD.value() -> I(L, L, N)
    UpscalerMode.HIGH.value() -> I(M, L, N)
    else -> temporalImpact(c)
}

private fun temporalImpact(c: ImpactContext): SettingImpact {
    val over = c.temporalMs > c.periodMs
    val level = gpuLevel(c.temporalMs, c.periodMs)
    // SGSR2-DV (Quality) is documented as "most GPU" without a number: one level above Normal
    val gpu = if (c.mode == UpscalerMode.VIDEO_QUALITY.value() && level != H) ImpactLevel.entries[level.ordinal + 1] else level
    return I(gpu, if (over) H else M, N)
}

private fun supersampling(c: ImpactContext): SettingImpact =
    if (c.temporal) temporalImpact(c) else I(if (c.twoTimes) M else L, L, N)
