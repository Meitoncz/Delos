package com.limelight.l10n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Text fit (docs/LOCALIZATION.md, "Text fit"): a translation of a SHORT UI slot (tab, chip, hint bar, side menu item, button,
 * menu row, option label) that is more than [MAX_RATIO] times as long as the English text, and longer than [FLOOR] characters, is
 * flagged. Slots are found by key prefix and suffix ([isShortSlot]); only Latin and Cyrillic languages are checked because Japanese,
 * Korean and Chinese are shorter in width. A flagged label that has no shorter natural wording is listed in [ACCEPTED] with the
 * reason; the list must not go stale (an entry whose translation was shortened or changed must be removed).
 */
class TextFitTest {
    @Test
    fun shortSlotsStayWithinTheBudget() {
        val en = load(File(res(), "values"))
        val problems = mutableListOf<String>()
        val stale = mutableListOf<String>()
        for (language in LANGUAGES) {
            val folder = if (language == "pt-BR") "values-pt-rBR" else "values-$language"
            val flagged = flag(en, load(File(res(), folder))).toSet()
            problems += flagged.filter { (language to it) !in ACCEPTED }.map { "$language $it: ${en[it]} | ${load(File(res(), folder))[it]}" }
            stale += ACCEPTED.filter { it.first == language && it.second !in flagged }.map { "$language ${it.second}" }
        }
        assertTrue("Too long for a short slot (shorten the translation or accept with a reason):\n" + problems.joinToString("\n"), problems.isEmpty())
        assertTrue("Accepted but no longer flagged (remove from ACCEPTED): $stale", stale.isEmpty())
    }

    @Test
    fun theRuleFlagsOnlyLongTranslationsOfShortSlots() {
        val en = mapOf("settings_tab_about" to "About", "help_x" to "About", "shell_hint_ok" to "OK", "settings_tab_long" to "A rather long English sentence here.")
        val tr = mapOf(
            "settings_tab_about" to "Informationen über die App", // flagged: 5 -> 26
            "help_x" to "Informationen über die App", // not a slot
            "shell_hint_ok" to "OK, gut", // under the floor
            "settings_tab_long" to "Ein ziemlich langer deutscher Satz, der viel länger ist als der englische.", // English too long to be short
        )
        assertEquals(listOf("settings_tab_about"), flag(en, tr))
    }

    @Test
    fun placeholdersAndEscapesDoNotCountAsLength() {
        assertEquals(6, visibleLength("It\\'s %1\$s"))
        assertEquals(2, visibleLength("%d%%"))
    }

    private fun flag(en: Map<String, String>, translation: Map<String, String>): List<String> =
        en.filter { (key, text) -> isShortSlot(key, text) }.keys.filter { key ->
            val translated = translation[key] ?: return@filter false
            val length = visibleLength(translated)
            length > FLOOR && length > MAX_RATIO * visibleLength(en.getValue(key))
        }.sorted()

    private fun isShortSlot(key: String, english: String): Boolean =
        visibleLength(english) <= SHORT && !english.endsWith(".") && (SLOT_PREFIXES.any { key.startsWith(it) } || SLOT_SUFFIXES.any { key.endsWith(it) })

    /** Characters the user sees: a placeholder counts as one, `%%` as one, escapes as the character they stand for. */
    private fun visibleLength(text: String): Int =
        text.replace(Regex("%(\\d+\\$)?[sd]"), "#").replace("%%", "%").replace("\\'", "'").replace("\\\"", "\"").replace("\\n", " ")
            .let { it.codePointCount(0, it.length) }

    private fun res(): File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    /** Every string and the "other" item of every plural of `strings*.xml` in a folder, by key. */
    private fun load(folder: File): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (file in folder.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }) {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.childNodes
            for (i in 0 until nodes.length) {
                val node = nodes.item(i) as? Element ?: continue
                val name = node.getAttribute("name")
                when (node.tagName) {
                    "string" -> out[name] = node.textContent
                    "plurals" -> {
                        val items = node.getElementsByTagName("item")
                        out[name] = (0 until items.length).map { items.item(it) as Element }.let { list -> (list.firstOrNull { it.getAttribute("quantity") == "other" } ?: list.first()).textContent }
                    }
                }
            }
        }
        return out
    }

    private companion object {
        const val MAX_RATIO = 2.0
        const val FLOOR = 12
        const val SHORT = 28
        val LANGUAGES = listOf("cs", "es", "pt-BR", "fr", "de", "uk")
        val SLOT_PREFIXES = listOf(
            "settings_tab_", "shell_hint_", "shell_nav_", "shell_side_", "common_", "settings_option_", "home_menu_", "devices_menu_",
            "stream_menu_", "filters_art_sort", "filters_art_type", "filters_art_style", "metadata_menu_", "home_quit", "devices_wake",
            "keyboard_hint", "tags_",
        )
        val SLOT_SUFFIXES = listOf("_action", "_label", "_chip", "_tab", "_button")

        /** (language, key) pairs whose translation is long but has no shorter natural wording; the reason is next to the group. */
        val ACCEPTED = setOf(
            // "Custom" has no short equivalent that is not slang; the chips and rows wrap or scroll
            "es" to "filters_art_style_custom", "es" to "stream_menu_rumble_custom",
            "pt-BR" to "filters_art_style_custom", "pt-BR" to "stream_menu_rumble_custom",
            // two words, abbreviations without a dot are not allowed ("Never abbreviate")
            "es" to "shell_hint_next_tab",
            "pt-BR" to "stream_menu_row_get_clipboard", "fr" to "stream_menu_row_get_clipboard",
            "fr" to "stream_menu_row_send_keys", "fr" to "stream_menu_title_keys",
            "fr" to "devices_address_action", "de" to "devices_address_action",
            // the one word that the language has: marquee handles the stream menu rows
            "pt-BR" to "tags_genre_puzzle", "fr" to "shell_hint_reset", "uk" to "devices_menu_rename",
            // the setting title of the glossary ("Rumble feel" / "Про застосунок"), quoted in help texts
            "fr" to "stream_menu_row_rumble_feel", "fr" to "stream_menu_title_rumble", "uk" to "settings_tab_about",
            // "app" is one syllable in English; the Czech and Ukrainian words for it (aplikace, застосунок) are not
            "cs" to "home_menu_show", "uk" to "home_menu_hide", "uk" to "home_menu_quit", "uk" to "home_menu_show", "uk" to "home_menu_start", "uk" to "stream_menu_tile_quit",
        )
    }
}
