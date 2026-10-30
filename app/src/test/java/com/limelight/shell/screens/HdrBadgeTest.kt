package com.limelight.shell.screens

import com.limelight.shell.data.GameMetadata
import com.limelight.shell.data.HdrEvidence
import com.limelight.shell.data.NativeHdr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HdrBadgeTest {
    @Test fun missingOrUnevaluatedMetadataHasNoBadge() {
        assertNull(hdrBadgeKind(null))
        assertNull(hdrBadgeKind(GameMetadata(hdr = HdrEvidence(NativeHdr.UNKNOWN), updatedAt = 0)))
    }

    @Test fun successfullyEvaluatedUnknownEvidenceHasTheThirdBadgeState() {
        assertEquals(NativeHdr.UNKNOWN, hdrBadgeKind(GameMetadata(
            hdr = HdrEvidence(NativeHdr.UNKNOWN), updatedAt = 1,
        )))
    }

    @Test fun failedPcgwLookupDoesNotLookLikeMissingEvidence() {
        val failed = GameMetadata(hdr = HdrEvidence(NativeHdr.UNKNOWN), updatedAt = 0, pcgwFailedAt = 2)
        assertNull(hdrBadgeKind(failed))
        // A carried success timestamp must not override a recorded PCGW failure/backoff.
        assertNull(hdrBadgeKind(failed.copy(updatedAt = 1)))
    }

    @Test fun confirmedEvidenceRemainsVisibleDuringARefreshFailure() {
        for (support in listOf(NativeHdr.HDR, NativeHdr.SDR)) {
            val confirmed = GameMetadata(hdr = HdrEvidence(support), updatedAt = 1)
            assertEquals(support, hdrBadgeKind(confirmed))
            assertEquals(support, hdrBadgeKind(confirmed.copy(updatedAt = 0, pcgwFailedAt = 2)))
        }
    }
}
