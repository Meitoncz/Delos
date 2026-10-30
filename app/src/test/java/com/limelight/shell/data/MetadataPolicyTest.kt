package com.limelight.shell.data

import com.google.gson.Gson
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataPolicyTest {
    private fun game(
        id: Long, name: String, year: Int? = null, steam: List<Long> = emptyList(), platforms: List<Int> = emptyList(),
    ) = MetadataCandidate(id, name, year, steam, platformIds = platforms)

    @Test fun hostNamesThatDifferOnlyByEditionNoteArticleAmpersandOrNumeralStillMatch() {
        for ((host, igdb) in listOf(
            "The Witcher 3: Wild Hunt - Game of the Year Edition" to "The Witcher 3: Wild Hunt",
            "Witcher 3 Wild Hunt" to "The Witcher 3: Wild Hunt",
            "Final Fantasy VII" to "Final Fantasy 7",
            "Tom & Jerry" to "Tom and Jerry",
            "Hades (Steam)" to "Hades",
            "Dishonored: Director's Cut" to "Dishonored",
            "Half-Life 2" to "Half Life II",
            "Doom Eternal" to "DOOM Eternal",
            "Pokémon Arena" to "Pokemon Arena",
        )) {
            assertEquals(host, 1L, chooseMatch(host, listOf(game(1, igdb)))?.id)
        }
    }

    @Test fun differentSequelsSubtitlesAndGamesStayDistinct() {
        val candidates = listOf(game(1, "Game"), game(2, "Game 3"), game(3, "Game: Subtitle"))
        assertNull(chooseMatch("Game 2", candidates))
        assertEquals(2L, chooseMatch("Game III", candidates)?.id)
        assertNull(chooseMatch("Other", candidates))
        assertNull(chooseMatch("", candidates))
        assertNull(chooseMatch("(Steam)", candidates))
    }

    @Test fun editionPrefersTheLiterallyNamedEntryWhenBothExist() {
        val candidates = listOf(game(1, "Game"), game(2, "Game Remastered"))
        assertEquals(2L, chooseMatch("Game Remastered", candidates)?.id)
        assertEquals(1L, chooseMatch("Game", candidates)?.id)
    }

    @Test fun sameNamedEntriesAreDisambiguatedBySteamYearPlatformAndRecency() {
        val oldDoom = game(1, "Doom", 1993, platforms = listOf(13))
        val newDoom = game(2, "DOOM", 2016, steam = listOf(379720L), platforms = listOf(6))
        assertEquals(2L, chooseMatch("Doom", listOf(oldDoom, newDoom))?.id)
        assertEquals(1L, chooseMatch("Doom (1993)", listOf(oldDoom, newDoom))?.id)

        val oldPrey = game(3, "Prey", 2006, steam = listOf(1L))
        val newPrey = game(4, "Prey", 2017, steam = listOf(480490L))
        assertEquals(4L, chooseMatch("Prey", listOf(oldPrey, newPrey))?.id)

        val onlyOnePc = listOf(game(5, "Tomb Raider", 2013, platforms = listOf(48)), game(6, "Tomb Raider", 2013, platforms = listOf(6)))
        assertEquals(6L, chooseMatch("Tomb Raider", onlyOnePc)?.id)
    }

    @Test fun nothingSeparatesTwoEntriesSoTheGameStaysUnmatchedForTheUserToChoose() {
        assertNull(chooseMatch("Game", listOf(game(1, "Game", 2020, platforms = listOf(6)), game(2, "Game", 2020, platforms = listOf(6)))))
        assertNull(chooseMatch("Game", listOf(game(1, "Game"), game(2, "Game"))))
    }

    @Test fun recordsAreFreshOnlyWhenARealPastTimestampIsYoungEnough() {
        val now = 10_000_000_000_000L
        val record = GameMetadata(candidate = game(1, "Game"), updatedAt = now - TimeUnit.DAYS.toMillis(1))
        assertFalse(refreshDue(record, now))
        assertTrue(refreshDue(null, now))
        assertTrue(refreshDue(record.copy(updatedAt = now - TimeUnit.DAYS.toMillis(30)), now))
        assertTrue(refreshDue(record.copy(updatedAt = 0), now))
        // A wrong or rewound clock must not freeze a record forever.
        assertTrue(refreshDue(record.copy(updatedAt = now + TimeUnit.DAYS.toMillis(30)), now))
    }

    @Test fun unmatchedRecordsAreRetriedSoonerThanMatchedOnes() {
        val now = 10_000_000_000_000L
        val absentAt = now - TimeUnit.DAYS.toMillis(1)
        assertTrue(refreshDue(GameMetadata(updatedAt = absentAt), now))
        assertFalse(refreshDue(GameMetadata(candidate = game(1, "Game"), updatedAt = absentAt), now))
        assertFalse(refreshDue(GameMetadata(updatedAt = now - TimeUnit.MINUTES.toMillis(5)), now))
    }

    @Test fun aFailingProviderIsRetriedOnlyAfterItsBackoff() {
        val now = 10_000_000_000_000L
        val base = GameMetadata(candidate = game(1, "Game"))
        val justFailed = now - TimeUnit.MINUTES.toMillis(1)
        val longAgo = now - TimeUnit.HOURS.toMillis(5)
        assertFalse(refreshDue(base.copy(pcgwFailedAt = justFailed), now))
        assertFalse(refreshDue(base.copy(hltbFailedAt = justFailed, pcgwFailedAt = justFailed), now))
        assertTrue(refreshDue(base.copy(pcgwFailedAt = longAgo), now))
        // One provider still backing off does not hide another whose backoff is over.
        assertTrue(refreshDue(base.copy(pcgwFailedAt = justFailed, hltbFailedAt = longAgo), now))
        assertTrue(refreshDue(base.copy(pcgwFailedAt = now + TimeUnit.DAYS.toMillis(1)), now))
        assertFalse(refreshDue(GameMetadata(igdbFailedAt = justFailed), now))
        assertTrue(refreshDue(GameMetadata(igdbFailedAt = longAgo), now))
    }

    @Test fun cacheFieldsGsonLeftNullAreDefaultedAndAnUnusableIdentityIsRejected() {
        val gson = Gson()
        val minimal = gson.fromJson("""{"candidate":{"id":1,"name":"Game"},"updatedAt":5}""", GameMetadata::class.java).validated()
        assertNotNull(minimal)
        assertEquals(NativeHdr.UNKNOWN, minimal!!.hdr.support)
        assertTrue(minimal.candidate!!.genres.isEmpty())
        assertTrue(minimal.candidate!!.steamAppIds.isEmpty())
        assertTrue(minimal.candidate!!.platformIds.isEmpty())

        val noSupport = gson.fromJson("""{"hdr":{"page":"Game"}}""", GameMetadata::class.java).validated()
        assertEquals(HdrEvidence(NativeHdr.UNKNOWN, "Game"), noSupport!!.hdr)

        val brokenCompletion = gson.fromJson("""{"completion":{"id":1,"seconds":3600}}""", GameMetadata::class.java).validated()
        assertNull(brokenCompletion!!.completion)

        assertNull(gson.fromJson("""{"candidate":{"id":1}}""", GameMetadata::class.java).validated())
    }
}
