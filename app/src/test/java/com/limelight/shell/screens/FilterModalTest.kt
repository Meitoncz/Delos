package com.limelight.shell.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.limelight.R
import com.limelight.shell.data.GameGenre
import com.limelight.shell.data.GameMetadata
import com.limelight.shell.data.HdrFilter
import com.limelight.shell.data.LengthFilter
import com.limelight.shell.data.MetadataCandidate
import com.limelight.shell.data.MetadataFilters
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class FilterModalTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources

    private fun game(vararg genres: Int) = GameMetadata(
        candidate = MetadataCandidate(1, "Game", 2020, genres = genres.map { GameGenre(it, "Genre $it") }),
    )

    @Test fun genreCountsCountGamesNotListings() {
        val counts = genreCounts(listOf(game(1, 2), game(2), game(2, 2), game(), null, GameMetadata()))
        assertEquals(mapOf(1 to 1, 2 to 3), counts)
    }

    @Test fun noGamesHaveNoCounts() {
        assertEquals(emptyMap<Int, Int>(), genreCounts(emptyList()))
        assertEquals(emptyMap<Int, Int>(), themeCounts(emptyList()))
    }

    @Test fun themeCountsCountGamesNotListingsAndKeepGenresApart() {
        fun themed(vararg themes: Int) = GameMetadata(
            candidate = MetadataCandidate(1, "Game", 2020, genres = listOf(GameGenre(7, "Genre 7")), themes = themes.map { GameGenre(it, "Theme $it") }),
        )
        val counts = themeCounts(listOf(themed(19, 21), themed(19), themed(19, 19), themed(), null, GameMetadata()))
        assertEquals(mapOf(19 to 3, 21 to 1), counts)
    }

    @Test fun resultLineShowsTheMatchesOfAnActiveFilterAndTheWholeLibraryOtherwise() {
        assertEquals("27 of 55 games", filterResultLine(res, 27, 55, active = true))
        assertEquals("0 of 55 games", filterResultLine(res, 0, 55, active = true))
        assertEquals("All 55 games", filterResultLine(res, 55, 55, active = false))
        assertEquals("1 of 1 game", filterResultLine(res, 1, 1, active = true))
        assertEquals("All 1 game", filterResultLine(res, 1, 1, active = false))
    }

    @Test fun hintLabelShowsHowManyCriteriaNarrowTheLibrary() {
        assertEquals("Filter", filterHintLabel(res, MetadataFilters()))
        assertEquals("Filter • 1 active", filterHintLabel(res, MetadataFilters(hdr = HdrFilter.SDR)))
        assertEquals("Filter • 3 active", filterHintLabel(res, MetadataFilters(genreId = 2, hdr = HdrFilter.HDR, length = LengthFilter.OVER_60)))
        assertEquals("Filter • 4 active", filterHintLabel(res, MetadataFilters(2, 19, HdrFilter.HDR, LengthFilter.OVER_60)))
    }

    @Test fun theUnverifiedHdrOptionUsesTheBadgeLabelAndEveryOptionHasItsOwnLabel() {
        assertEquals("HDR/SDR unverified", res.getString(hdrFilterLabel(HdrFilter.UNKNOWN)))
        assertEquals(R.string.metadata_hdr_unverified, hdrFilterLabel(HdrFilter.UNKNOWN))
        assertEquals(HdrFilter.entries.size, HdrFilter.entries.map { res.getString(hdrFilterLabel(it)) }.toSet().size)
        assertEquals(LengthFilter.entries.size, LengthFilter.entries.map { res.getString(lengthFilterLabel(it)) }.toSet().size)
        assertEquals(listOf("Any", "<10 h", "10–30 h", "30–60 h", "60+ h", "Unknown"), LengthFilter.entries.map { res.getString(lengthFilterLabel(it)) })
    }

    @Test fun theCompletionTimeUsesTheWebsitesRounding() {
        assertEquals("45 Mins", completionText(res, 2700))
        assertEquals("3½ Hours", completionText(res, 12772))
        assertEquals("22½ Hours", completionText(res, 80_713))
        assertEquals("66 Hours", completionText(res, 237_341))
        assertEquals("130 Hours", completionText(res, 469_657))
        assertEquals("1 Hour", completionText(res, 3600))
    }
}
