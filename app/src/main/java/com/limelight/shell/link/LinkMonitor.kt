package com.limelight.shell.link

import android.content.res.Resources
import com.limelight.R
import java.util.Locale

/** One second of the PyroWave renderer's counters, taken at the stream's [targetKbps] (the live bitrate setting). */
data class LinkSample(
    val seconds: Float,
    val targetKbps: Int,
    /** Frames the host sent (received, partial and lost) in the second. */
    val totalFrames: Long,
    /** Frames delivered with holes, whole frames never delivered, and frames the device did not submit in time. */
    val partialFrames: Long,
    val lostFrames: Long,
    val skippedFrames: Long,
    /** Bytes the app received per second, in kbps; -1 when unknown. Not video only (see docs/STREAM_QUALITY.md). */
    val rxKbps: Float,
)

enum class LinkVerdict {
    /** Too little data at this bitrate yet. */
    MEASURING,

    /** Frames arrive whole. */
    KEEPING_UP,

    /** The network drops frames at this bitrate. */
    DROPPING,

    /** Frames arrive whole but the device skips them; lowering the bitrate is not the cure. */
    DEVICE_BEHIND,
}

/** The rolling window's reading, at [targetKbps]. */
data class LinkReading(
    val verdict: LinkVerdict,
    val targetKbps: Int,
    /** Average over the window, -1 when unknown. */
    val rxKbps: Float,
    val partialPerSecond: Float,
    val lostPerSecond: Float,
    val skippedPerSecond: Float,
    /** Partial plus lost frames as a share of the window's frames. */
    val dropShare: Double,
    /** What to try when [verdict] is [LinkVerdict.DROPPING]. */
    val suggestedKbps: Int,
) {
    /** "Link drops frames at 220 Mbps, try 170", or the one-line state otherwise. */
    fun text(res: Resources): String = when (verdict) {
        LinkVerdict.MEASURING -> res.getString(R.string.link_reading_measuring)
        LinkVerdict.KEEPING_UP -> res.getString(R.string.link_reading_keeps_up, mbps(targetKbps))
        LinkVerdict.DROPPING -> res.getString(R.string.link_reading_drops, mbps(targetKbps), mbps(suggestedKbps))
        LinkVerdict.DEVICE_BEHIND -> res.getString(R.string.link_reading_device_behind)
    }
}

internal fun mbps(kbps: Int): String = "${kbps / 1000} Mbps" // l10n-ignore: technical readout

/** The suggested bitrate for a link that drops [dropShare] of the frames at [targetKbps]; a pure function of the observed degradation. */
internal fun suggestedBitrate(targetKbps: Int, dropShare: Double): Int {
    val cut = (dropShare * LinkPolicy.HINT_CUT_PER_SHARE).coerceIn(LinkPolicy.HINT_MIN_CUT, LinkPolicy.HINT_MAX_CUT)
    val reduced = (targetKbps * (1.0 - cut) / LinkPolicy.STEP_KBPS).toInt() * LinkPolicy.STEP_KBPS
    return maxOf(LinkPolicy.MIN_KBPS, reduced)
}

/**
 * The passive part of the guidance: a rolling window of the last ~5 s of the stream's own counters. Samples taken
 * at an earlier bitrate are ignored once the bitrate changed (after Apply), so the verdict always describes the
 * bitrate in use. Thread-safe: the stats thread adds, the UI reads.
 */
class LinkMonitor @JvmOverloads constructor(private val windowSeconds: Float = LinkPolicy.HINT_WINDOW_SECONDS) {
    private val samples = ArrayDeque<LinkSample>()

    @Synchronized
    fun add(sample: LinkSample) {
        if (sample.seconds <= 0f) return
        samples.addLast(sample)
        var total = samples.sumOf { it.seconds.toDouble() }
        while (samples.size > 1 && total - samples.first().seconds >= windowSeconds) {
            total -= samples.removeFirst().seconds
        }
    }

    @Synchronized
    fun clear() = samples.clear()

    /** The reading over the trailing samples that share the newest sample's bitrate, or null before the first sample. */
    @Synchronized
    fun reading(): LinkReading? {
        val newest = samples.lastOrNull() ?: return null
        val run = samples.takeLastWhile { it.targetKbps == newest.targetKbps }
        val seconds = run.sumOf { it.seconds.toDouble() }.toFloat()
        val total = run.sumOf { it.totalFrames }
        val partial = run.sumOf { it.partialFrames }
        val lost = run.sumOf { it.lostFrames }
        val skipped = run.sumOf { it.skippedFrames }
        val rx = run.filter { it.rxKbps >= 0f }.let { known ->
            val knownSeconds = known.sumOf { it.seconds.toDouble() }
            if (knownSeconds > 0) (known.sumOf { it.rxKbps * it.seconds.toDouble() } / knownSeconds).toFloat() else -1f
        }
        val dropShare = if (total > 0) (partial + lost).toDouble() / total else 0.0
        val skipShare = if (total > 0) skipped.toDouble() / total else 0.0
        val verdict = when {
            seconds < LinkPolicy.HINT_MIN_SECONDS || total <= 0 -> LinkVerdict.MEASURING
            dropShare >= LinkPolicy.HINT_DROP_SHARE -> LinkVerdict.DROPPING
            skipShare >= LinkPolicy.HINT_DROP_SHARE -> LinkVerdict.DEVICE_BEHIND
            else -> LinkVerdict.KEEPING_UP
        }
        return LinkReading(
            verdict, newest.targetKbps, rx, partial / seconds, lost / seconds, skipped / seconds, dropShare,
            if (verdict == LinkVerdict.DROPPING) suggestedBitrate(newest.targetKbps, dropShare) else newest.targetKbps,
        )
    }

    /** The in-stream hint, only while the link drops frames. */
    @Synchronized
    fun hint(): LinkReading? = reading()?.takeIf { it.verdict == LinkVerdict.DROPPING }
}

/** "12.3" style numbers of the Link block, shared with the tests. */
internal fun oneDecimal(value: Float): String = String.format(Locale.US, "%.1f", value)
