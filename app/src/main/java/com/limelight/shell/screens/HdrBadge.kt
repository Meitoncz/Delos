package com.limelight.shell.screens

import com.limelight.shell.data.GameMetadata
import com.limelight.shell.data.NativeHdr

internal const val UNVERIFIED_HDR_LABEL = "HDR/SDR unverified"

/** Missing evidence is visible only after evaluation, never as a substitute for a failed lookup. */
internal fun hdrBadgeKind(metadata: GameMetadata?): NativeHdr? {
    val support = metadata?.hdr?.support ?: return null
    if (support == NativeHdr.UNKNOWN && (metadata.updatedAt <= 0 || metadata.pcgwFailedAt != 0L)) return null
    // A refresh failure does not invalidate previously confirmed HDR/SDR evidence.
    return support
}
