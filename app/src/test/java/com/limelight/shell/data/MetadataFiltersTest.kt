package com.limelight.shell.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataFiltersTest {
    private fun metadata(hdr: NativeHdr = NativeHdr.UNKNOWN, seconds: Long? = null, genre: Int = 1) = GameMetadata(
        candidate = MetadataCandidate(10, "Game", 2020, 20, listOf(GameGenre(genre, "Genre"))),
        hdr = HdrEvidence(hdr),
        completion = seconds?.let { CompletionTime(30, "Game", it, "https://howlongtobeat.com/game/30", "10 Hours") },
    )

    @Test fun confirmedSdrDoesNotIncludeUnmatchedOrUnknownGames() {
        val filter = MetadataFilters(hdr = HdrFilter.SDR)
        assertTrue(filter.matches(metadata(NativeHdr.SDR)))
        assertFalse(filter.matches(metadata(NativeHdr.HDR)))
        assertFalse(filter.matches(metadata(NativeHdr.UNKNOWN)))
        assertFalse(filter.matches(null))
        assertTrue(MetadataFilters(hdr = HdrFilter.UNKNOWN).matches(null))
    }

    @Test fun durationRangesHaveUnambiguousBoundariesAndKeepMissingValuesSeparate() {
        val times = listOf(35_999L, 36_000L, 107_999L, 108_000L, 215_999L, 216_000L)
        val expected = mapOf(
            LengthFilter.UNDER_10 to listOf(35_999L),
            LengthFilter.FROM_10_TO_30 to listOf(36_000L, 107_999L),
            LengthFilter.FROM_30_TO_60 to listOf(108_000L, 215_999L),
            LengthFilter.OVER_60 to listOf(216_000L),
        )
        for ((range, values) in expected) {
            val filter = MetadataFilters(length = range)
            assertEquals(values, times.filter { filter.matches(metadata(seconds = it)) })
            assertFalse(filter.matches(metadata(seconds = null)))
            assertFalse(filter.matches(metadata(seconds = 0)))
        }
        assertTrue(MetadataFilters(length = LengthFilter.UNKNOWN).matches(metadata(seconds = null)))
        assertTrue(MetadataFilters(length = LengthFilter.UNKNOWN).matches(metadata(seconds = 0)))
        assertFalse(MetadataFilters(length = LengthFilter.UNKNOWN).matches(metadata(seconds = 36_000)))
    }

    @Test fun genreHdrAndHeadlineLengthAreCombinedAsIndependentRequirements() {
        val filter = MetadataFilters(genreId = 12, hdr = HdrFilter.HDR, length = LengthFilter.UNDER_10)
        assertTrue(filter.matches(metadata(NativeHdr.HDR, 20_000, 12)))
        assertFalse(filter.matches(metadata(NativeHdr.HDR, 20_000, 1)))
        assertFalse(filter.matches(metadata(NativeHdr.SDR, 20_000, 12)))
        assertFalse(filter.matches(metadata(NativeHdr.HDR, 36_000, 12)))
        assertFalse(filter.matches(null))
    }
}
