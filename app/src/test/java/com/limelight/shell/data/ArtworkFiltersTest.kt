package com.limelight.shell.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class ArtworkFiltersTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources

    private fun image(id: Int, score: Int = 0, date: Long? = null, animated: Boolean = false) =
        SgdbImage(id, "u$id", "t$id", 600, 900, score = score, date = date, animated = animated)

    @Test fun defaultsAreNotActiveAndEveryChangedCriterionCounts() {
        assertFalse(ArtFilters().active)
        val f = ArtFilters(type = ArtType.STATIC, style = "blurred", dimensions = "600x900", sort = ArtSort.NEWEST)
        assertEquals(4, f.activeCount)
        assertEquals(1, ArtFilters(sort = ArtSort.OLDEST).activeCount)
    }

    @Test fun stillImagesOnlyAskForStaticAndTheKindsDimensions() {
        val q = ArtFilterRules.queries(ArtKind.COVER, ArtFilters(type = ArtType.STATIC)).single()
        assertFalse(q.animated)
        assertEquals("static", q.params["types"])
        assertEquals("false", q.params["nsfw"])
        assertEquals("false", q.params["humor"])
        assertEquals("image/png,image/jpeg,image/webp", q.params["mimes"])
        assertEquals("600x900,660x930,342x482", q.params["dimensions"])
        assertNull(q.params["styles"])
        assertNull(ArtFilterRules.queries(ArtKind.HERO, ArtFilters(type = ArtType.STATIC)).single().params["dimensions"])
    }

    @Test fun bothTypesAreTwoRequestsAndTheAnimatedOneAsksForWebpOnly() {
        val queries = ArtFilterRules.queries(ArtKind.WIDE, ArtFilters())
        assertEquals(listOf(false, true), queries.map { it.animated })
        val animated = queries[1].params
        assertEquals("animated", animated["types"])
        assertEquals("image/webp", animated["mimes"])
        assertEquals("920x430,460x215", animated["dimensions"])
    }

    @Test fun styleAndDimensionsNarrowTheRequest() {
        val q = ArtFilterRules.queries(ArtKind.COVER, ArtFilters(type = ArtType.ANIMATED, style = "material", dimensions = "660x930")).single()
        assertTrue(q.animated)
        assertEquals("material", q.params["styles"])
        assertEquals("660x930", q.params["dimensions"])
    }

    @Test fun logosAreStillOnlyAndIgnoreTheTypeAndDimensions() {
        val queries = ArtFilterRules.queries(ArtKind.LOGO, ArtFilters(type = ArtType.ANIMATED, dimensions = "600x900", style = "white"))
        val q = queries.single()
        assertFalse(q.animated)
        assertEquals("image/png,image/webp", q.params["mimes"])
        assertNull(q.params["dimensions"])
        assertEquals("white", q.params["styles"])
    }

    @Test fun valuesOfAnotherKindAreDropped() {
        val f = ArtFilterRules.normalized(ArtKind.HERO, ArtFilters(style = "no_logo", dimensions = "600x900"))
        assertNull(f.style)
        assertNull(f.dimensions)
        assertEquals("alternate", ArtFilterRules.normalized(ArtKind.HERO, ArtFilters(style = "alternate")).style)
    }

    @Test fun sortingByScoreKeepsTheOrderOfTies() {
        val sorted = ArtFilterRules.sort(listOf(image(1, 5), image(2, 9), image(3, 5), image(4, 9)), ArtSort.SCORE)
        assertEquals(listOf(2, 4, 1, 3), sorted.map { it.id })
    }

    @Test fun sortingByDateUsesTheIdWhenThereIsNone() {
        val images = listOf(image(1, date = 100), image(2, date = 300), image(3), image(4, date = 300))
        assertEquals(listOf(4, 2, 1, 3), ArtFilterRules.sort(images, ArtSort.NEWEST).map { it.id })
        assertEquals(listOf(1, 2, 4, 3), ArtFilterRules.sort(images, ArtSort.OLDEST).map { it.id })
    }

    @Test fun mergingKeepsAnImageOnceAndInterleavesTheAnswers() {
        val merged = ArtFilterRules.merge(listOf(listOf(image(1), image(2)), listOf(image(2, animated = true), image(3, animated = true))))
        assertEquals(listOf(1, 3, 2), merged.map { it.id })
        assertFalse(merged[2].animated)
    }

    @Test fun cacheKeyIgnoresTheSortBecauseItIsAppliedToWhatWasLoaded() {
        assertEquals(
            ArtFilterRules.cacheKey(ArtKind.COVER, ArtFilters(sort = ArtSort.SCORE)),
            ArtFilterRules.cacheKey(ArtKind.COVER, ArtFilters(sort = ArtSort.OLDEST)),
        )
        assertTrue(ArtFilterRules.cacheKey(ArtKind.COVER, ArtFilters()) != ArtFilterRules.cacheKey(ArtKind.COVER, ArtFilters(style = "blurred")))
    }

    @Test fun chipsListActiveFiltersAndClearOneEach() {
        val f = ArtFilters(type = ArtType.STATIC, style = "white_logo", dimensions = "660x930", sort = ArtSort.NEWEST)
        val chips = ArtFilterRules.chips(f)
        assertEquals(listOf("Static", "White logo", "660 × 930", "Sort: Newest"), chips.map { it.label(res) })
        assertEquals(f.copy(style = null), ArtFilterRules.clear(f, chips[1]))
        assertEquals(ArtFilters(), chips.fold(f) { acc, chip -> ArtFilterRules.clear(acc, chip) })
    }

    @Test fun stylesAreWordsAndAnUnknownOneIsShownAsItsIdentifier() {
        assertEquals("No logo", ArtFilterRules.styleLabel(res, "no_logo"))
        assertEquals("Official", ArtFilterRules.styleLabel(res, "official"))
        assertEquals("Future style", ArtFilterRules.styleLabel(res, "future_style"))
    }

    @Test fun parsingReadsWhatTheApiTellsAndKeepsTheRestEmpty() {
        val o = JsonParser.parseString(
            """{"id":7,"score":113,"style":"white_logo","width":600,"height":900,"mime":"image/jpeg","url":"https://x/a.jpg","thumb":"https://x/t.jpg",
               "date":1750636800,"author":{"name":"Dewdrop","steam64":"1"}}"""
        ).asJsonObject
        val image = SgdbImage.parse(o, animated = false)!!
        assertEquals(7, image.id)
        assertEquals("white_logo", image.style)
        assertEquals(113, image.score)
        assertEquals("Dewdrop", image.author)
        assertEquals(1750636800L, image.date)
        assertEquals("JPG", image.format)
        assertFalse(image.animated)
        val bare = SgdbImage.parse(JsonParser.parseString("""{"id":8,"url":"https://x/b.png"}""").asJsonObject, animated = true)!!
        assertEquals("https://x/b.png", bare.thumb)
        assertNull(bare.style)
        assertNull(bare.author)
        assertNull(bare.date)
        assertNull(bare.format)
        assertTrue(bare.animated)
    }

    @Test fun parsingAcceptsAStyleListAndIsoDates() {
        val o = JsonParser.parseString("""{"id":1,"url":"https://x/a.webp","style":["alternate","blurred"],"date":"2025-06-23T00:00:00Z","author":null}""").asJsonObject
        val image = SgdbImage.parse(o, animated = true)!!
        assertEquals("alternate", image.style)
        assertEquals(1750636800L, image.date)
        assertNull(image.author)
    }

    @Test fun webmAndEntriesWithoutAUrlAreSkipped() {
        assertNull(SgdbImage.parse(JsonParser.parseString("""{"id":1,"url":"https://x/a.webm"}""").asJsonObject, true))
        assertNull(SgdbImage.parse(JsonParser.parseString("""{"id":1,"url":"https://x/a","mime":"image/webm"}""").asJsonObject, true))
        assertNull(SgdbImage.parse(JsonParser.parseString("""{"id":1}""").asJsonObject, false))
    }

    @Test fun infoStripShowsOnlyKnownFacts() {
        val full = SgdbImage(1, "u", "t", 600, 900, style = "white_logo", score = 113, author = "Dewdrop", date = 1750636800L, mime = "image/webp")
        assertEquals("White logo", ArtworkInfo.title(res, full, "Cover"))
        assertEquals(
            listOf(ArtworkFact.Size(600, 900), ArtworkFact.File("WEBP"), ArtworkFact.Score(113, "Dewdrop"), ArtworkFact.Added(1750636800L)),
            ArtworkInfo.facts(full),
        )
        val bare = SgdbImage(2, "u", "t", 0, 0)
        assertEquals("Cover", ArtworkInfo.title(res, bare, "Cover"))
        assertTrue(ArtworkInfo.facts(bare).isEmpty())
        assertEquals(listOf(ArtworkFact.Score(null, "Ann")), ArtworkInfo.facts(bare.copy(author = "Ann")))
    }

    @Test fun theDateFollowsTheLanguageAndStaysInUtc() {
        assertEquals("Jun 23, 2025", ArtworkInfo.date(1750636800L, Locale.US))
        assertEquals("23 Jun 2025", ArtworkInfo.date(1750636800L, Locale.UK))
        assertEquals("23. 6. 2025", ArtworkInfo.date(1750636800L, Locale("cs", "CZ")))
        // Late on the 22nd in UTC is still the 22nd, whatever the device's zone
        assertEquals("Jun 22, 2025", ArtworkInfo.date(1750636800L - 1, Locale.US))
    }
}
