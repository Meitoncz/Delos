package com.limelight.shell.screens

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.limelight.shell.settings.SettingsTab
import com.limelight.shell.ui.RowValue
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element

/** The matching of the settings search for the letter pickers of Japanese, Korean and Chinese; Latin text must not change. */
class SettingsSearchTextTest {
    private fun matches(text: String, query: String, language: String, titles: String = text) =
        query.lowercase().split(" ").all { SearchText.matcher(text, titles, language).matches(it) }

    @Test fun latinTextIsOnlyLowerCasedAndMatchedAsASubstring() {
        assertEquals("frame pacing ščř", SearchText.fold("Frame Pacing ŠČŘ"))
        assertTrue(matches("Frame pacing", "pacing", "en"))
        assertTrue(matches("Frame pacing", "ame pac", "en"))
        // no initials, no cross-word matching, no diacritic folding for the Latin languages
        assertFalse(matches("Frame pacing", "fp", "en"))
        assertFalse(matches("Frame pacing", "fp", "zh"))
        assertTrue(matches("Ostrost", "ostrost", "cs"))
        assertFalse(matches("Ostrost", "ostrož", "cs"))
        assertNull(SearchText.initials("Frame pacing", pinyin = true))
        assertNull(SearchText.initials("ビットレート 解像度", pinyin = false))
    }

    @Test fun japaneseQueriesInHiraganaFindKatakanaTitlesWithoutVoicingOrSmallKana() {
        assertEquals("ひつとれと", SearchText.fold("ビットレート"))
        assertEquals("ひつとれと", SearchText.fold("ひっとれーと"))
        assertEquals("かあ", SearchText.fold("ガァ"))
        assertEquals("うあ", SearchText.fold("ヴァ"))
        assertEquals("めにゆ", SearchText.fold("メニュー"))
        assertTrue(matches("ビットレート", "ひつとれと", "ja"))
        assertTrue(matches("ビットレート", "ひつと", "ja"))
        assertTrue(matches("ビットレート", "ビット", "ja"))
        assertTrue(matches("ビットレート", "ひっと", "ja"))
        assertFalse(matches("ビットレート", "かめら", "ja"))
    }

    @Test fun japaneseKanjiTitlesAreFoundByTheirReadings() {
        assertEquals("かいぞうど", JapaneseReadings.read("解像度"))
        assertEquals("じどうがめんかいてん", JapaneseReadings.read("自動画面回転"))
        // okurigana stay: 戻す = もど + す; the longest word wins (画面中央 and not 画面 + 中 + 央)
        assertEquals("がめんちゅうおうにもどす", JapaneseReadings.read("画面中央に戻す"))
        assertEquals("abc かな ?", JapaneseReadings.read("abc かな ?"))
        assertEquals("猫", JapaneseReadings.read("猫"))
        assertEquals(setOf('猫'), JapaneseReadings.uncovered("猫と解像度"))
        assertTrue(matches("解像度", "かいぞうど", "ja"))
        assertTrue(matches("解像度の自動調整", "じどうちょうせい", "ja"))
        assertTrue(matches("表示位置", "ひょうじいち", "ja"))
        assertTrue(matches("表示位置", "ひようしいち", "ja"))
        assertTrue(matches("表示位置", "表示", "ja"))
        assertFalse(matches("表示位置", "かいぞうど", "ja"))
        // another language never reads kanji as Japanese
        assertFalse(matches("解像度", "かいぞうど", "zh"))
    }

    @Test fun koreanInitialConsonantsFindTitlesBySyllableInitials() {
        assertEquals("ㅎㅅㄷ", SearchText.initials("해상도", pinyin = false))
        assertEquals("ㅎㅅㄷㅈㄷㅈㅈ", SearchText.initials("해상도 자동 조정", pinyin = false))
        assertEquals("ㄱㄱ", SearchText.initials("각까", pinyin = false))
        assertEquals("hdrㅅㅇ", SearchText.initials("HDR 사용", pinyin = false))
        assertTrue(matches("해상도 자동 조정", "ㅎㅅㄷ", "ko"))
        assertTrue(matches("해상도 자동 조정", "ㅅㄷㅈㄷ", "ko"))
        assertTrue(matches("해상도 자동 조정", "ㅈㄷㅈㅈ", "ko"))
        assertTrue(matches("HDR 사용", "ㅅㅇ", "ko"))
        assertTrue(matches("HDR 사용", "hdrㅅ", "ko"))
        // an input method's double consonant counts as the plain one the picker has
        assertTrue(matches("쌍둥이", "ㅆㄷ", "ko"))
        assertFalse(matches("해상도", "ㅅㅎ", "ko"))
        assertFalse(matches("해상도", "ㅎㅅㅅ", "ko"))
        // real syllables still match as text
        assertTrue(matches("해상도", "상도", "ko"))
    }

    @Test fun chineseLettersFindTitlesByPinyinInitials() {
        assertEquals("sz", SearchText.initials("设置", pinyin = true))
        assertEquals("hdrsz", SearchText.initials("HDR 设置", pinyin = true))
        assertTrue(matches("设置", "sz", "zh"))
        assertTrue(matches("HDR 设置", "hdrsz", "zh"))
        assertTrue(matches("自动调整分辨率", "zdtz", "zh"))
        assertFalse(matches("设置", "zs", "zh"))
        assertFalse(matches("设置", "sz", "ja"))
        // Latin text keeps matching as before
        assertTrue(matches("HDR 设置", "hdr", "zh"))
        // a Han character without an entry matches nothing by initials, and never joins its neighbours
        assertFalse(matches("设猫置", "sz", "zh"))
        assertEquals(setOf('猫'), PinyinInitials.uncovered("设猫置"))
    }

    @Test fun thePickerKeepsNineKeysToARowAndGrowsOnlyBeyondSixRows() {
        assertEquals(9, LetterPicker.columns(36))
        assertEquals(9, LetterPicker.columns(52))
        assertEquals(9, LetterPicker.columns(54))
        assertEquals(10, LetterPicker.columns(55))
        assertEquals(10, LetterPicker.columns(56))
        assertEquals(74, LetterPicker.keySize(9))
        assertEquals(71, LetterPicker.keySize(10))
        // never wider than the 820 px column, never more than six rows
        for (keys in 1..120) {
            val columns = LetterPicker.columns(keys)
            assertTrue(columns * LetterPicker.keySize(columns) + (columns - 1) * LetterPicker.GAP <= 820)
            assertTrue((keys + columns - 1) / columns <= LetterPicker.MAX_ROWS)
        }
    }

    // ---- the real resources ----

    private fun res(): File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun strings(folder: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        File(res(), folder).listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().forEach { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.childNodes
            for (i in 0 until nodes.length) {
                val node = nodes.item(i) as? Element ?: continue
                if (node.tagName == "string") out[node.getAttribute("name")] = unescape(node.textContent)
            }
        }
        return out
    }

    private fun unescape(raw: String) = Regex("""\\u([0-9a-fA-F]{4})""").replace(raw) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace(Regex("""\\(.)""")) { it.groupValues[1] }

    /** The setting titles, section and tab names and the link rows: what the user types a title of. Their search aliases too. */
    private fun titles(folder: String): List<String> {
        val strings = strings(folder)
        val preferences = File(res(), "xml/preferences.xml").readText()
        val referenced = Regex("""(?:android|app):title="@string/(\w+)"""").findAll(preferences).map { it.groupValues[1] }.toSet()
        return strings.filterKeys {
            it in referenced || it == "link_test_action" || it == "link_use_action" ||
                (it.startsWith("settings_native_") && (it.endsWith("_title") || "_section_" in it)) ||
                (it.startsWith("settings_tab_") && !it.endsWith("_description"))
        }.values.toList()
    }

    private fun letters(folder: String) = strings(folder).getValue("settings_screen_search_letters")

    @Test fun everyPickerHasEachKeyOnceAndFitsSixRows() {
        for (folder in listOf("values", "values-cs", "values-es", "values-pt-rBR", "values-fr", "values-de", "values-ja", "values-ko", "values-zh-rCN", "values-uk")) {
            val keys = letters(folder)
            assertEquals("$folder repeats a key", keys.length, keys.toSet().size)
            assertTrue("$folder: ${keys.length} keys", (keys.length + LetterPicker.columns(keys.length) - 1) / LetterPicker.columns(keys.length) <= LetterPicker.MAX_ROWS)
        }
        assertEquals(46 + 10, letters("values-ja").length)
        assertEquals(14 + 26 + 10, letters("values-ko").length)
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", letters("values-zh-rCN"))
        assertEquals(41 + 10, letters("values-cs").length)
    }

    @Test fun everyJapaneseTitleCanBeTypedWithTheHiraganaKeys() {
        val picker = SearchText.fold(letters("values-ja")).toSet()
        for (title in titles("values-ja")) {
            assertEquals("a kanji of '$title' has no reading in JapaneseReadings", emptySet<Char>(), JapaneseReadings.uncovered(title))
            val missing = SearchText.fold(JapaneseReadings.read(title)).filter { it in '\u3041'..'\u3096' && it !in picker }
            assertEquals("'$title' needs kana that the picker lacks", "", missing)
        }
    }

    @Test fun everyKoreanTitleCanBeTypedWithTheInitialConsonantKeys() {
        val picker = letters("values-ko").toSet()
        for (title in titles("values-ko")) {
            val initials = SearchText.initials(title, pinyin = false) ?: continue
            val missing = initials.filter { it in '\u3131'..'\u314E' && it !in picker }
            assertEquals("'$title' needs consonants that the picker lacks", "", missing)
        }
    }

    @Test fun everyChineseTitleCanBeTypedWithTheLetterKeys() {
        val picker = letters("values-zh-rCN").lowercase().toSet()
        for (title in titles("values-zh-rCN")) {
            assertEquals("a character of '$title' has no initial in PinyinInitials", emptySet<Char>(), PinyinInitials.uncovered(title))
            val initials = SearchText.initials(title, pinyin = true) ?: continue
            assertEquals("'$title'", "", initials.filter { it.isLetterOrDigit() && it !in picker })
        }
    }

    @Test fun everyAliasedRowHasASynonymStringInEveryLanguage() {
        for (folder in listOf("values", "values-cs", "values-es", "values-pt-rBR", "values-fr", "values-de", "values-ja", "values-ko", "values-zh-rCN", "values-uk")) {
            val strings = strings(folder)
            for (name in listOf("motion_sensors", "gyro_smoothing", "link_test", "link_use")) {
                assertTrue("$folder: settings_search_alias_$name", strings["settings_search_alias_$name"].orEmpty().isNotBlank())
            }
        }
    }
}

/** The search on real resources of other languages: the aliases (English always, localized too) and the pickers' letters. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsSearchLanguagesTest {
    private val res: Resources get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private fun item(key: String, label: String, tab: SettingsTab = SettingsTab.CONTROLS, terms: String = "") =
        SettingsItem(key, tab, "", "", label, "", RowValue.Info(""), searchTerms = terms)

    private fun keys(query: String, vararg items: SettingsItem) = searchSettings(res, items.toList(), query).map { it.key }

    @Test @Config(qualifiers = "de")
    fun aGermanUserFindsAMotionRowByTheGermanAndTheEnglishSynonym() {
        val row = item("seekbar_gyro_smoothing", "Gyro-Glättung", terms = SearchAliases.terms(res, "seekbar_gyro_smoothing"))
        assertEquals(listOf("seekbar_gyro_smoothing"), keys("stabilisierung", row))
        assertEquals(listOf("seekbar_gyro_smoothing"), keys("gyroskop glättung", row))
        assertEquals(listOf("seekbar_gyro_smoothing"), keys("stabilisation", row))
        assertEquals(listOf("seekbar_gyro_smoothing"), keys("motion sensors", row))
        assertTrue(keys("bandbreite", row).isEmpty())
    }

    @Test @Config(qualifiers = "ja")
    fun aJapaneseUserFindsRowsByHiraganaAndBySynonyms() {
        val rows = arrayOf(
            item(LINK_USE_ROW, "推奨ビットレートを使用", SettingsTab.STREAM, SearchAliases.terms(res, LINK_USE_ROW)),
            item("list_resolution", "解像度", SettingsTab.STREAM),
        )
        assertEquals(listOf(LINK_USE_ROW), keys("ひつとれと", *rows))
        assertEquals(listOf(LINK_USE_ROW), keys("すいしょう", *rows))
        assertEquals(listOf("list_resolution"), keys("かいぞうど", *rows))
        assertEquals(listOf(LINK_USE_ROW), keys("接続", *rows))
        assertEquals(listOf(LINK_USE_ROW), keys("recommended bitrate", *rows))
    }

    @Test @Config(qualifiers = "ko")
    fun aKoreanUserFindsRowsByInitialConsonants() {
        val rows = arrayOf(item("list_resolution", "해상도", SettingsTab.STREAM), item("seekbar_bitrate_kbps", "비트레이트", SettingsTab.STREAM))
        assertEquals(listOf("list_resolution"), keys("ㅎㅅㄷ", *rows))
        assertEquals(listOf("seekbar_bitrate_kbps"), keys("ㅂㅌㄹㅇㅌ", *rows))
    }

    @Test @Config(qualifiers = "zh-rCN")
    fun aChineseUserFindsRowsByPinyinInitials() {
        val rows = arrayOf(item("list_resolution", "分辨率", SettingsTab.STREAM), item("seekbar_bitrate_kbps", "码率", SettingsTab.STREAM))
        assertEquals(listOf("list_resolution"), keys("fbl", *rows))
        assertEquals(listOf("seekbar_bitrate_kbps"), keys("ml", *rows))
    }

    @Test fun englishKeepsItsBehaviour() {
        val rows = arrayOf(item("frame_pacing", "Frame pacing"), item("list_fps", "Frame rate"))
        assertEquals(listOf("frame_pacing"), keys("pacing", *rows))
        assertEquals(listOf("frame_pacing", "list_fps"), keys("frame", *rows))
        assertEquals(emptyList<String>(), keys("fp", *rows))
    }
}
