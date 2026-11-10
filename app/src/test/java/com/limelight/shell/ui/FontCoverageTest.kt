package com.limelight.shell.ui

import java.io.File
import java.nio.ByteBuffer
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Pins "Fonts and scripts" in docs/LOCALIZATION.md to the shipped files: which characters Figtree and Unbounded have, and that no
 * language's text uses a character that would silently fall back to another font in the middle of a Latin word. Characters the
 * bundled fonts do not have at all (Cyrillic body text, kana, Hangul, Han) are drawn by Android's system fallback on purpose.
 */
class FontCoverageTest {
    private val figtree = cmap("figtree")
    private val unbounded = cmap("unbounded")

    @Test
    fun cmapParserReadsTheFonts() {
        assertTrue('A'.code in figtree && 'z'.code in figtree && '…'.code in figtree)
        assertFalse(0x1F600 in figtree)
        assertTrue(figtree.size > 300 && unbounded.size > 900)
    }

    @Test
    fun latinLanguagesAreCoveredByBothFonts() {
        // the letters of cs, es, pt-BR, fr, de, and the punctuation the guide asks for (non-breaking space, guillemets, quotes)
        val letters = "ÁČĎÉĚÍŇÓŘŠŤÚŮÝŽáčďéěíňóřšťúůýž" + "ÁÉÍÑÓÚÜáéíñóúü¿¡" + "ÀÁÂÃÇÉÊÍÓÔÕÚàáâãçéêíóôõú" +
            "ÀÂÆÇÉÈÊËÎÏÔŒÙÛÜŸàâæçéèêëîïôœùûüÿ«»" + "ÄÖÜßäöü„“" + "…·×–—’‘”\u00A0°"
        assertEquals("Figtree lacks", "", letters.filter { it.code !in figtree })
        assertEquals("Unbounded lacks", "", letters.filter { it.code !in unbounded })
    }

    @Test
    fun ukrainianLettersAreInTheDisplayFont() {
        val letters = "АБВГҐДЕЄЖЗИІЇЙКЛМНОПРСТУФХЦЧШЩЬЮЯабвгґдеєжзиіїйклмнопрстуфхцчшщьюя’ʼ"
        assertEquals("Unbounded lacks", "", letters.filter { it.code !in unbounded })
    }

    @Test
    fun theGapsAreWhatTheDocumentationSays() {
        // Not covered by Figtree: Cyrillic, kana, Hangul, Han (system fallback). If a font update adds them, update the docs and the notes in Type.kt.
        for (sample in listOf("Ж", "ї", "あ", "カ", "한", "設", "设")) assertFalse("Figtree has $sample", sample.codePointAt(0) in figtree)
        // Not covered by Unbounded: kana, Hangul, Han
        for (sample in listOf("あ", "カ", "한", "設", "设")) assertFalse("Unbounded has $sample", sample.codePointAt(0) in unbounded)
        // The narrow no-break space French typography uses is in neither: the guide asks for U+00A0
        assertFalse(0x202F in figtree || 0x202F in unbounded)
        assertTrue(0x00A0 in figtree && 0x00A0 in unbounded)
    }

    @Test
    fun noStringUsesACharacterThatFallsBackInTheMiddleOfAWord() {
        val problems = languageFolders().flatMap { (language, folder) ->
            val allowed = allowedOutsideFigtree(language)
            strings(folder).flatMap { (key, text) ->
                text.codePoints().toArray().distinct()
                    .filter { cp -> !Character.isWhitespace(cp) && !Character.isISOControl(cp) && cp !in figtree && !allowed(cp) }
                    .map { "$language/$key: U+%04X ${String(Character.toChars(it))}".format(it) }
            }
        }
        assertTrue("\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /** The characters a language may use that Figtree lacks because Android's system font is meant to draw them. */
    private fun allowedOutsideFigtree(language: String): (Int) -> Boolean = when (language) {
        // base English and the Latin languages: nothing
        "ja", "ko", "zh-CN" -> { cp -> cp in CJK_RANGES }
        "uk" -> { cp -> cp in 0x0400..0x04FF && cp in unbounded }
        else -> { _ -> false }
    }

    // ---- reading the resources ----

    private fun res(): File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    /** The base (English) as language "en", and every `values-<lang>` folder: the Latin text of every shipped language is checked. */
    private fun languageFolders(): List<Pair<String, File>> {
        val folders = res().listFiles { f -> f.isDirectory && (f.name == "values" || LANGUAGE_FOLDER.matches(f.name)) }.orEmpty().sortedBy { it.name }
        return folders.map { (if (it.name == "values") "en" else it.name.removePrefix("values-").replace("-r", "-")) to it }
    }

    /** Every `<string>`, `<item>` text of the folder's `strings*.xml`, with the aapt escapes resolved. */
    private fun strings(folder: File): List<Pair<String, String>> =
        folder.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }.flatMap { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val out = mutableListOf<Pair<String, String>>()
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val node = nodes.item(i) as? Element ?: continue
                if (node.getAttribute("translatable") == "false") continue
                val name = node.getAttribute("name")
                val items = node.getElementsByTagName("item")
                if (items.length == 0) out += name to unescape(node.textContent)
                else for (j in 0 until items.length) out += "$name[$j]" to unescape(items.item(j).textContent)
            }
            out
        }

    private fun unescape(raw: String): String =
        UNICODE_ESCAPE.replace(raw) { it.groupValues[1].toInt(16).toChar().toString() }.replace(Regex("""\\(.)""")) { it.groupValues[1] }

    // ---- reading a font's cmap (TrueType 'cmap' formats 4 and 12) ----

    private fun cmap(name: String): Set<Int> {
        val file = File(res(), "font/$name.ttf")
        val font = ByteBuffer.wrap(file.readBytes())
        val tables = font.getShort(4).toInt() and 0xFFFF
        var cmapOffset = -1
        for (i in 0 until tables) {
            val record = 12 + 16 * i
            val tag = String(ByteArray(4) { font.get(record + it) }, Charsets.US_ASCII)
            if (tag == "cmap") cmapOffset = font.getInt(record + 8)
        }
        check(cmapOffset >= 0) { "$name.ttf has no cmap" }
        val covered = HashSet<Int>()
        val subtables = font.getShort(cmapOffset + 2).toInt() and 0xFFFF
        for (i in 0 until subtables) {
            val sub = cmapOffset + font.getInt(cmapOffset + 4 + 8 * i + 4)
            when (font.getShort(sub).toInt() and 0xFFFF) {
                4 -> format4(font, sub, covered)
                12 -> format12(font, sub, covered)
            }
        }
        return covered
    }

    private fun format4(font: ByteBuffer, sub: Int, out: MutableSet<Int>) {
        val segments = (font.getShort(sub + 6).toInt() and 0xFFFF) / 2
        val ends = sub + 14
        val starts = ends + 2 * segments + 2
        val deltas = starts + 2 * segments
        val rangeOffsets = deltas + 2 * segments
        for (s in 0 until segments) {
            val end = font.getShort(ends + 2 * s).toInt() and 0xFFFF
            val start = font.getShort(starts + 2 * s).toInt() and 0xFFFF
            val delta = font.getShort(deltas + 2 * s).toInt()
            val rangeOffset = font.getShort(rangeOffsets + 2 * s).toInt() and 0xFFFF
            for (c in start..minOf(end, 0xFFFE)) {
                val glyph = if (rangeOffset == 0) (c + delta) and 0xFFFF
                else {
                    val at = rangeOffsets + 2 * s + rangeOffset + 2 * (c - start)
                    val g = font.getShort(at).toInt() and 0xFFFF
                    if (g == 0) 0 else (g + delta) and 0xFFFF
                }
                if (glyph != 0) out += c
            }
        }
    }

    private fun format12(font: ByteBuffer, sub: Int, out: MutableSet<Int>) {
        val groups = font.getInt(sub + 12)
        for (g in 0 until groups) {
            val at = sub + 16 + 12 * g
            val start = font.getInt(at)
            val end = font.getInt(at + 4)
            val glyph = font.getInt(at + 8)
            for (c in start..end) if (glyph + (c - start) != 0) out += c
        }
    }

    private companion object {
        val LANGUAGE_FOLDER = Regex("""values-[a-z]{2}(-r[A-Z]{2})?""")
        val UNICODE_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")

        /** Kana, Han, Hangul, CJK punctuation and full-width forms, which the system CJK font draws. */
        val CJK_RANGES: List<IntRange> = listOf(
            0x1100..0x11FF, 0x2E80..0x2FDF, 0x3000..0x30FF, 0x3130..0x318F, 0x31F0..0x31FF, 0x3400..0x4DBF,
            0x4E00..0x9FFF, 0xAC00..0xD7AF, 0xF900..0xFAFF, 0xFE30..0xFE4F, 0xFF00..0xFFEF, 0x20000..0x2FA1F,
        )
    }

    private operator fun List<IntRange>.contains(cp: Int) = any { cp in it }
}
