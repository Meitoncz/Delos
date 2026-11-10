package com.limelight.shell.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TileArtSourceTest {
    @Test fun lookupOffShowsBoxArtAtOnce() {
        assertEquals(TileArtSource.BOX_ART, tileArtSource(lookupExpected = false, lookupResolved = false, coverFound = false))
    }

    @Test fun coverWinsWhenFound() {
        assertEquals(TileArtSource.COVER, tileArtSource(true, true, true))
        assertEquals(TileArtSource.COVER, tileArtSource(true, false, true))
        assertEquals(TileArtSource.COVER, tileArtSource(false, true, true))
    }

    @Test fun pendingLookupShowsPlaceholder() {
        assertEquals(TileArtSource.PLACEHOLDER, tileArtSource(true, false, false))
    }

    @Test fun resolvedWithoutCoverFallsBackToBoxArt() {
        assertEquals(TileArtSource.BOX_ART, tileArtSource(true, true, false))
    }
}
