package com.limelight.shell.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSearchTest {
    private fun score(kbps: Int, ok: Boolean) = ProbeScore(
        kbps, 1000, 1000, if (ok) 1000 else 900, 2000.0, if (ok) 0.0 else 10.0, 0.0, 0.0, 0.0,
        if (ok) null else ProbeFailure.LOSS,
    )

    /** A path that carries [capacity] and fails above it; [seen] records every probe's rate. */
    private fun path(capacity: Int, seen: MutableList<Int>, flaky: (Int, Int) -> Boolean = { _, _ -> false }): (Int) -> ProbeScore = { kbps ->
        seen += kbps
        score(kbps, kbps <= capacity && !flaky(kbps, seen.count { it == kbps }))
    }

    @Test
    fun growsByAQuarterThenBisectsThenConfirmsFivePercentBelowTwice() {
        val seen = mutableListOf<Int>()
        val result = searchLink(100_000, 3_000_000, path(300_000, seen))
        assertEquals(
            listOf(
                100_000, 125_000, 155_000, 190_000, 235_000, 290_000, 360_000, // grow x1.25, rounded down to 5 Mbps, until 360 fails
                325_000, 305_000, 295_000, 300_000,                            // bisect the 290..360 bracket down to 5 Mbps
                285_000, 285_000,                                              // 95 % of 300, twice afresh
            ),
            seen,
        )
        assertTrue(result is LinkSearchResult.Confirmed)
        assertEquals(285_000, (result as LinkSearchResult.Confirmed).wireKbps)
        assertTrue(result.score.passed)
    }

    @Test
    fun aFailedConfirmationLowersTheRateByTwentyPercentAndTestsAgain() {
        val seen = mutableListOf<Int>()
        // 95 Mbps fails its second confirmation run, so a lucky pass is not inherited
        val result = searchLink(100_000, 100_000, path(1_000_000, seen) { kbps, count -> kbps == 95_000 && count == 2 })
        assertEquals(listOf(100_000, 95_000, 95_000, 75_000, 75_000), seen)
        assertEquals(75_000, (result as LinkSearchResult.Confirmed).wireKbps)
    }

    @Test
    fun theStartIsRoundedAndBoundedByTheCap() {
        val seen = mutableListOf<Int>()
        searchLink(12_345, 3_000_000, path(10_000, seen))
        assertEquals(10_000, seen.first())
        val high = mutableListOf<Int>()
        searchLink(900_000, 250_000, path(1_000_000, high))
        assertEquals(250_000, high.first())
        val low = mutableListOf<Int>()
        searchLink(1_000, 250_000, path(1_000_000, low))
        assertEquals(5_000, low.first())
    }

    @Test
    fun aFailingStartBisectsDownToFiveMbpsAndGivesNoRate() {
        val seen = mutableListOf<Int>()
        val result = searchLink(100_000, 3_000_000, path(0, seen))
        assertEquals(listOf(100_000, 50_000, 25_000, 10_000, 5_000), seen)
        assertEquals(ProbeFailure.LOSS, (result as LinkSearchResult.NoRate).last?.failure)
    }

    @Test
    fun aFailingStartThatPassesLowerFindsTheBracket() {
        val seen = mutableListOf<Int>()
        val result = searchLink(200_000, 3_000_000, path(60_000, seen))
        // 200 fails, 100 fails, 50 passes, then the 50..100 bracket is bisected to 5 Mbps and confirmed at 95 % of 60
        assertEquals(listOf(200_000, 100_000, 50_000, 75_000, 60_000, 65_000, 55_000, 55_000), seen.take(8))
        assertEquals(55_000, (result as LinkSearchResult.Confirmed).wireKbps)
    }

    @Test
    fun theCapEndsTheGrowthAtTheCap() {
        val seen = mutableListOf<Int>()
        val result = searchLink(100_000, 130_000, path(10_000_000, seen))
        assertEquals(listOf(100_000, 125_000, 130_000, 120_000, 120_000), seen)
        assertEquals(120_000, (result as LinkSearchResult.Confirmed).wireKbps)
    }

    @Test
    fun aCapBelowTheMinimumHasNothingToTest() {
        val seen = mutableListOf<Int>()
        assertEquals(LinkSearchResult.NoRate(null), searchLink(100_000, 4_000, path(1_000_000, seen)))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun persistentFailureAfterAPassEndsWithoutARate() {
        // Passes while searching, then every confirmation fails: the rate steps down to the 5 Mbps floor and stops
        var calls = 0
        val result = searchLink(100_000, 100_000, { kbps -> if (++calls == 1) score(kbps, true) else score(kbps, false) })
        assertTrue(result is LinkSearchResult.NoRate)
        assertEquals(ProbeFailure.LOSS, (result as LinkSearchResult.NoRate).last?.failure)
    }

    @Test
    fun cancellingBetweenProbesDiscardsEverything() {
        val seen = mutableListOf<Int>()
        val inner = path(300_000, seen)
        val result = searchLink(100_000, 3_000_000, inner, cancelled = { seen.size >= 3 })
        assertEquals(LinkSearchResult.Cancelled, result)
        assertEquals(3, seen.size)
        val atConfirm = mutableListOf<Int>()
        val inConfirm = searchLink(100_000, 100_000, path(1_000_000, atConfirm), cancelled = { atConfirm.size >= 2 })
        assertEquals(LinkSearchResult.Cancelled, inConfirm)
    }

    @Test
    fun aProbeFailureEndsTheSearch() {
        assertThrows(LinkTestException::class.java) {
            searchLink(100_000, 3_000_000, { throw LinkTestException(LinkFailure.NETWORK, "down") })
        }
    }

    @Test
    fun progressNamesTheRateAndWhetherItConfirms() {
        val steps = mutableListOf<LinkStep>()
        searchLink(100_000, 100_000, path(1_000_000, mutableListOf()), onStep = { steps += it })
        assertEquals(listOf(LinkStep(100_000, false, 1), LinkStep(95_000, true, 2), LinkStep(95_000, true, 3)), steps)
    }
}
