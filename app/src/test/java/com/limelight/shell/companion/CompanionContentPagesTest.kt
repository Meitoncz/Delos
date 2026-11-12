package com.limelight.shell.companion

import com.limelight.shell.settings.GameSettingsStore
import com.limelight.shell.ui.StagePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** The pure parts of the artwork, filter, profile, create-profile and boot pages: what they cut, count, enable and fit. */
class CompanionContentPagesTest {
    private val metrics = object : TextMetrics {
        override fun lines(text: String, width: Float, kind: FitText): Int = if (text.length * 15f <= width) 1 else 2
        override fun width(text: String, kind: FitText): Float = text.length * 15f
    }

    private fun panel(w: Int, h: Int, dpi: Int): Panel {
        val plan = StagePlanner.plan(w / (dpi / 160f), h / (dpi / 160f))
        return Panel(plan.stageWidth, plan.stageHeight)
    }

    private val panels = listOf(
        panel(1240, 1080, 420), panel(960, 720, 300), panel(1024, 768, 320), panel(720, 720, 255), panel(960, 640, 330),
    )

    // ---- Filters ----
    private fun chips(items: List<String>, width: Float) = CompanionFit.chips(items, FILTER_CHIPS, width, metrics, more = { "+$it" })

    @Test fun chipsAreCutToThreeAndTheRestIsCounted() {
        val items = listOf("Action", "Native HDR", "10\u201330 h", "Racing", "Static")
        assertEquals(listOf("Action", "Native HDR", "10\u201330 h", "+2"), chips(items, 1000f))
        assertEquals(listOf("Action", "Native HDR"), chips(items.take(2), 1000f))
        assertEquals(emptyList<String>(), chips(emptyList(), 1000f))
    }

    @Test fun chipsOnEveryPanelFitTheirRow() {
        val items = listOf("Action", "Native HDR", "10\u201330 h", "Racing", "Static")
        for (p in panels) {
            val width = CompanionFit.budget(p).innerWidth
            val row = chips(items, width)
            assertTrue(row.size <= FILTER_CHIPS + 1)
            assertTrue(row.sumOf { (metrics.width(it) + 26f).toDouble() } + 8f * (row.size - 1) <= width || row.isEmpty())
        }
    }

    @Test fun theCountSentenceFollowsTheFiltersAndWhatIsKnown() {
        assertEquals(FilterCount.All(48), filterCount(48, 48, active = false))
        // Without a filter the count is what is shown, whatever the total says
        assertEquals(FilterCount.All(36), filterCount(36, null, active = false))
        assertEquals(FilterCount.Of(7, 48), filterCount(7, 48, active = true))
        assertEquals(FilterCount.Of(0, 48), filterCount(0, 48, active = true))
        // An artwork list whose unfiltered total was never loaded can only say how many match
        assertEquals(FilterCount.Some(12), filterCount(12, null, active = true))
    }

    @Test fun resetIsEnabledOnlyWhileFiltersAreActive() {
        var ran = 0
        val live = filterResetAction("Reset filters", active = true) { ran++ }
        assertTrue(live.enabled)
        assertEquals(ButtonStyle.PRIMARY, live.style)
        live.onClick()
        assertEquals(1, ran)
        val idle = filterResetAction("Reset filters", active = false) { ran++ }
        assertFalse(idle.enabled)
        assertFalse(idle.style == ButtonStyle.PRIMARY)
    }

    @Test fun filterPagesAreModalAndIndependent() {
        val library = FilterPage(true, emptyList(), null, FilterUnit.GAMES) {}
        val art = FilterPage(false, emptyList(), null, FilterUnit.IMAGES) {}
        assertEquals(PagePriority.MODAL, library.priority)
        assertTrue(library.priority > PagePriority.SCREEN)
        assertTrue(library.key != art.key)
    }

    // ---- Artwork ----

    @Test fun theArtworkBoxNeverExceedsWhatItGetsAndKeepsItsRatio() {
        val ratios = listOf(2f / 3f, 3f / 2f, 16f / 9f, 1f, 1f / 4f, 4f / 1f)
        for (p in panels) {
            val budget = CompanionFit.budget(p)
            // The card's height less the heading, the button and the gaps: a generous guess of the room of the picture's row
            for (height in listOf(CompanionFit.cardHeight(p) * .6f, CompanionFit.cardHeight(p) * .35f, 64f)) {
                for (ratio in ratios) {
                    val (w, h) = ArtworkFit.box(budget.innerWidth, height, ratio)
                    assertTrue("$p $ratio $w x $h", w <= budget.innerWidth * ArtworkFit.WIDTH_SHARE + .01f && h <= height + .01f)
                    assertEquals("$p $ratio ratio", ratio, w / h, ratio * 1e-4f)
                    // It is the biggest box: it touches the width share or the height
                    assertTrue(w >= budget.innerWidth * ArtworkFit.WIDTH_SHARE - .01f || h >= height - .01f)
                }
            }
        }
    }

    @Test fun anEmptyAreaOrABadRatioGivesAnEmptyBox() {
        assertEquals(0f to 0f, ArtworkFit.box(0f, 100f, 1f))
        assertEquals(0f to 0f, ArtworkFit.box(100f, -1f, 1f))
        assertEquals(0f to 0f, ArtworkFit.box(100f, 100f, 0f))
        assertEquals(0f to 0f, ArtworkFit.box(100f, 100f, Float.NaN))
    }

    @Test fun theLineColumnHoldsWholeLinesOnly() {
        assertEquals(5, ArtworkFit.lineCapacity(5 * 40f + 4 * 8f, 40f, 8f))
        assertEquals(4, ArtworkFit.lineCapacity(5 * 40f + 4 * 8f - 1f, 40f, 8f))
        assertEquals(0, ArtworkFit.lineCapacity(30f, 40f, 8f))
        assertEquals(0, ArtworkFit.lineCapacity(0f, 40f, 8f))
    }

    @Test fun theLowestPriorityLinesGoFirstAndTheOrderStays() {
        val lines = listOf(
            ArtLine("size", "size", ArtTone.NORMAL, 90), ArtLine("score", "score", ArtTone.SEA, 70), ArtLine("author", "author", ArtTone.NORMAL, 30),
            ArtLine("added", "added", ArtTone.MUTED, 50), ArtLine("use", "use", ArtTone.GOLD, 100),
        )
        assertEquals(lines, keepTop(lines, 5))
        assertEquals(lines, keepTop(lines, 9))
        assertEquals(listOf("size", "score", "added", "use"), keepTop(lines, 4).map { it.text })
        assertEquals(listOf("size", "score", "use"), keepTop(lines, 3).map { it.text })
        assertEquals(listOf("size", "use"), keepTop(lines, 2).map { it.text })
        assertEquals(listOf("use"), keepTop(lines, 1).map { it.text })
        assertEquals(emptyList<ArtLine>(), keepTop(lines, 0))
    }

    // ---- Profile, create profile, boot ----

    @Test fun profileLineSaysOnlyWhatIsKnownAndNotZero() {
        assertEquals(ProfileLine.NONE, profileLine(null, null))
        assertEquals(ProfileLine.DEFAULTS, profileLine(0, 0))
        assertEquals(ProfileLine.SETTINGS, profileLine(8, 0))
        assertEquals(ProfileLine.OVERRIDES, profileLine(0, 3))
        assertEquals(ProfileLine.BOTH, profileLine(8, 3))
    }

    @Test fun customSettingsAreThoseThatDifferFromTheirDefault() {
        val defaults = mapOf("a" to 1, "b" to "x", "c" to true, "d" to 5)
        val stored = mapOf("a" to 1, "b" to "y", "c" to false, "unrelated" to 9)
        assertEquals(2, customSettingCount(stored, defaults))
        assertEquals(0, customSettingCount(emptyMap<String, Any>(), defaults))
    }

    @Test fun gamesWithOverridesAreCountedOncePerGame() {
        val pc = UUID.randomUUID()
        val other = UUID.randomUUID()
        val all = mapOf(
            "ui_game_${pc}_11_list_resolution" to "1080",
            "ui_game_${pc}_11_list_fps" to "60",
            "ui_game_${pc}_12_checkbox_enable_hdr" to true,
            "ui_game_${other}_11_list_fps" to "30",
            // Not a per-game setting key: only a private codec bitrate slot
            "ui_game_${pc}_13_codec_bitrate_hevc" to 1000,
            "ui_theme" to "dark",
        )
        assertEquals(3, GameSettingsStore.overriddenGameCount(all))
        assertEquals(0, GameSettingsStore.overriddenGameCount(emptyMap<String, Any>()))
    }

    @Test fun bootListsBackOnlyWhenThereIsAWayBack() {
        assertEquals(listOf("A"), bootHints(false, "Begin", "Back").map { it.key })
        assertEquals(listOf("A", "B"), bootHints(true, "Begin", "Back").map { it.key })
        assertEquals("Back", bootHints(true, "Begin", "Back").last().label)
    }

    @Test fun theBootTextIsCutAtASentenceOrShortened() {
        val full = "Your main display holds the library, settings and stream. This panel adds useful details and touch controls. Choose Get started on the main display."
        val short = "Choose Get started on the main display."
        val roomy = CompanionFit.budget(panels[0])
        assertEquals(full, CompanionFit.summary(full, short, roomy.bodyLines, roomy.innerWidth, metrics, "See main."))
        // A narrow room: whole sentences or the authored short text, never a cut sentence
        for (lines in 1..3) {
            val text = CompanionFit.summary(full, short, lines, 700f, metrics, "See main.")
            assertTrue(text == short || text == "See main." || CompanionFit.sentences(full).any { text.startsWith(it) })
            assertTrue(text.endsWith("."))
        }
    }
}
