package com.limelight.shell.link

import kotlin.math.ceil

/** What the host announces about its FEC (`PyroWaveCriticalFecPercentage`, `PyroWaveMinParityShards`) plus the stream's packet size. */
internal data class WireTransport(
    val packetSize: Int = LinkPolicy.DEFAULT_PACKET_SIZE,
    val criticalFecPercent: Int,
    val minParityShards: Int = 2,
    val envelopeBytes: Int = 0,
)

/**
 * The conversion between a total wire allowance (what the bitrate setting and the probe use) and the image
 * allowance (what the codec may spend on the picture). A port of `pyrowavebandwidth.h`, which Vibepollo
 * (`pyrowave_bandwidth.h`) and Nonary's moonlight-qt keep identical: it reserves one maximum feasible critical
 * FEC block, packet rounding, IPv6, encryption, Ethernet overhead and 3572 kbps for audio and control.
 */
internal object WireBudget {
    /** The largest critical-block parity count that still fits one 255-shard Reed-Solomon block, up to [packets] shards. */
    fun maxCriticalParity(t: WireTransport, packets: Long = LinkPolicy.MAX_FEC_BLOCK_SHARDS.toLong()): Long {
        if (t.criticalFecPercent <= 0) return 0
        var result = 0L
        val limit = minOf(packets, LinkPolicy.MAX_FEC_BLOCK_SHARDS.toLong())
        var critical = 1L
        while (critical <= limit) {
            val parity = maxOf((critical * t.criticalFecPercent + 99) / 100, t.minParityShards.toLong())
            if (critical + parity <= LinkPolicy.MAX_FEC_BLOCK_SHARDS) result = maxOf(result, parity)
            critical++
        }
        return result
    }

    /** The image bytes a [wireBytes] allowance carries; 0 when it cannot even hold the parity. */
    fun imageBytes(wireBytes: Long, t: WireTransport): Long {
        if (t.packetSize <= 16) return 0
        val packets = wireBytes / (t.packetSize + LinkPolicy.WIRE_OVERHEAD_BYTES)
        val parity = maxCriticalParity(t, packets)
        if (packets <= parity) return 0
        val data = (packets - parity) * (t.packetSize - 16)
        val headers = LinkPolicy.FRAME_HEADER_BYTES + t.envelopeBytes.toLong()
        return if (data > headers) (data - headers) and 3L.inv() else 0
    }

    /** The wire bytes an [image] allowance needs. */
    fun wireBytes(image: Long, t: WireTransport): Long {
        if (t.packetSize <= 16 || image <= 0) return 0
        val payload = (t.packetSize - 16).toLong()
        val data = (image + LinkPolicy.FRAME_HEADER_BYTES + t.envelopeBytes + payload - 1) / payload
        return (data + maxCriticalParity(t)) * (t.packetSize + LinkPolicy.WIRE_OVERHEAD_BYTES)
    }

    /** The image kbps a [totalKbps] wire allowance carries at [fps]. */
    fun imageKbps(totalKbps: Double, fps: Int, t: WireTransport): Double {
        if (fps <= 0 || totalKbps <= LinkPolicy.AUDIO_CONTROL_KBPS) return 0.0
        return imageBytes(((totalKbps - LinkPolicy.AUDIO_CONTROL_KBPS) * 125 / fps).toLong(), t) * fps / 125.0
    }

    /** The total wire kbps an [imageKbps] picture needs at [fps]. */
    fun totalKbps(imageKbps: Double, fps: Int, t: WireTransport): Double {
        if (fps <= 0 || imageKbps <= 0) return 0.0
        return wireBytes(ceil(imageKbps * 125 / fps).toLong(), t) * fps / 125.0 + LinkPolicy.AUDIO_CONTROL_KBPS
    }
}
