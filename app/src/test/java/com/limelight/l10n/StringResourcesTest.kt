package com.limelight.l10n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Enforces docs/LOCALIZATION.md on the resource files. The base set is `res/values/strings*.xml` (English); for every language folder
 * `res/values-<lang>/strings*.xml` that exists the keys, placeholders, plural quantities, empty values and the Delos rules are checked.
 * With no translation folder yet only the base set and the language lists are checked. The rules themselves are covered by the
 * in-memory tests at the bottom, so they stay proven when the first translation lands.
 */
class StringResourcesTest {
    @Test
    fun baseStringsAreWellFormed() {
        val problems = problemsOfBase(parse(stringFiles(File(res(), "values"))))
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun everyTranslationMatchesTheBase() {
        val base = parse(stringFiles(File(res(), "values")))
        val problems = languageFolders().flatMap { (language, folder) ->
            validate(base, language, parse(stringFiles(folder))).map { "$language: $it" }
        }
        assertTrue("\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun onlySupportedLanguagesHaveFolders() {
        val folders = languageFolders().map { it.first }
        assertTrue("Unsupported language folders: ${folders - SUPPORTED_FOLDERS}", SUPPORTED_FOLDERS.containsAll(folders))
    }

    @Test
    fun languageListsAgree() {
        val config = Regex("""android:name="([^"]+)"""").findAll(File(res(), "xml/locales_config.xml").readText()).map { it.groupValues[1] }.toList()
        assertEquals(SUPPORTED_TAGS, config)
        val arrays = parseRaw(File(res(), "values/arrays.xml"))
        assertEquals(listOf("default") + SUPPORTED_TAGS, arrays.getValue("language_values").values.values.toList())
        assertEquals(arrays.getValue("language_names").values.size, arrays.getValue("language_values").values.size)
    }

    // ---- the rules, on in-memory catalogs ----

    @Test
    fun placeholdersAreCheckedAsAMultiset() {
        val base = catalog(string("a", "%1\$s sent %2\$d files"), string("b", "%s files"))
        assertEquals(emptyList<String>(), validate(base, "de", catalog(string("a", "%2\$d Dateien von %1\$s"), string("b", "%s Dateien"))))
        assertTrue(validate(base, "de", catalog(string("a", "%1\$s sent files"), string("b", "%s Dateien"))).any { "placeholders" in it })
        assertTrue(validate(base, "de", catalog(string("a", "%1\$s %2\$s"), string("b", "%s Dateien"))).any { "placeholders" in it })
        // two or more arguments must be positional, so a translator may reorder them
        assertTrue(validate(base, "de", catalog(string("a", "%s %d"), string("b", "%s Dateien"))).any { "placeholders" in it })
        assertTrue(validate(base, "de", catalog(string("a", "%1\$s %2\$d"), string("b", "%s Dateien"))).isEmpty())
        assertTrue(validate(base, "de", catalog(string("a", "%1\$s %2\$d"), string("b", "%d Dateien"))).any { "placeholders" in it })
    }

    @Test
    fun keysMustMatchTheBase() {
        val base = catalog(string("a", "A"), string("b", "B"), string("c", "C", translatable = false))
        assertEquals(emptyList<String>(), validate(base, "cs", catalog(string("a", "A"), string("b", "B"))))
        val problems = validate(base, "cs", catalog(string("a", "A"), string("x", "X"), string("c", "C")))
        assertTrue(problems.any { "missing" in it && "b" in it })
        assertTrue(problems.any { "unknown" in it && "x" in it })
        assertTrue(problems.any { "not translatable" in it && "c" in it })
    }

    @Test
    fun emptyValuesAreRejected() {
        assertTrue(validate(catalog(string("a", "A")), "cs", catalog(string("a", "  "))).any { "empty" in it })
    }

    @Test
    fun pluralsNeedTheQuantitiesOfTheLanguage() {
        val base = catalog(plural("p", "one" to "%d file", "other" to "%d files"))
        assertEquals(emptyList<String>(), validate(base, "de", catalog(plural("p", "one" to "%d Datei", "other" to "%d Dateien"))))
        assertEquals(emptyList<String>(), validate(base, "ja", catalog(plural("p", "other" to "%d 個"))))
        assertTrue(validate(base, "cs", catalog(plural("p", "one" to "%d soubor", "other" to "%d souborů"))).any { "quantities" in it })
        assertEquals(emptyList<String>(), validate(base, "cs", catalog(plural("p", "one" to "%d soubor", "few" to "%d soubory", "many" to "%d souboru", "other" to "%d souborů"))))
        assertTrue(validate(base, "ja", catalog(plural("p", "one" to "%d", "other" to "%d"))).any { "quantities" in it })
        assertTrue(validate(base, "de", catalog(plural("p", "one" to "Datei", "other" to "%d Dateien"))).any { "placeholders" in it })
        assertTrue(validate(base, "de", catalog(string("p", "%d Dateien"))).any { "kind" in it })
    }

    @Test
    fun delosIsNeverTranslatedOrInflected() {
        val base = catalog(string("a", "Delos is ready"), string("b", "Plain"))
        assertEquals(emptyList<String>(), validate(base, "cs", catalog(string("a", "Aplikace Delos je připravena"), string("b", "Jednoduché"))))
        assertTrue(validate(base, "cs", catalog(string("a", "Aplikace je připravena"), string("b", "x"))).any { "Delos" in it })
        assertTrue(validate(base, "cs", catalog(string("a", "Spusť v Delosu"), string("b", "x"))).any { "declined" in it })
        assertTrue(validate(base, "cs", catalog(string("a", "Delos je připravena"), string("b", "Delosem"))).any { "declined" in it })
        assertTrue(validate(base, "es", catalog(string("a", "Delos's listo"), string("b", "x"))).any { "declined" in it })
        assertTrue(validate(base, "uk", catalog(string("a", "Delos готовий"), string("b", "Делос"))).any { "declined" in it })
        assertTrue(validate(base, "ja", catalog(string("a", "デロス"), string("b", "x"))).any { "Delos" in it })
        // particles and hyphens are not inflection
        assertEquals(emptyList<String>(), validate(base, "ja", catalog(string("a", "Delosの準備ができました"), string("b", "x"))))
        assertEquals(emptyList<String>(), validate(base, "de", catalog(string("a", "Delos-Server bereit"), string("b", "x"))))
    }

    // ---- implementation ----

    private enum class Kind { STRING, PLURALS, ARRAY }

    private class Entry(val kind: Kind, val translatable: Boolean, val values: Map<String, String>)

    private fun string(name: String, text: String, translatable: Boolean = true) = name to Entry(Kind.STRING, translatable, mapOf("" to text))

    private fun plural(name: String, vararg items: Pair<String, String>) = name to Entry(Kind.PLURALS, true, linkedMapOf(*items))

    private fun catalog(vararg entries: Pair<String, Entry>): Map<String, Entry> = linkedMapOf(*entries)

    private fun res(): File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun stringFiles(folder: File): List<File> =
        folder.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }

    /** `values-cs`, `values-pt-rBR`, `values-zh-rCN` are languages; `values-v21`, `values-night`, `values-sw600dp` are not. */
    private fun languageFolders(): List<Pair<String, File>> =
        res().listFiles { f -> f.isDirectory && LANGUAGE_FOLDER.matches(f.name) }.orEmpty().sortedBy { it.name }
            .map { it.name.removePrefix("values-") to it }

    private fun parse(files: List<File>): Map<String, Entry> {
        val all = linkedMapOf<String, Entry>()
        val duplicates = mutableListOf<String>()
        for (file in files) for ((name, entry) in parseRaw(file)) if (all.put(name, entry) != null) duplicates += name
        assertTrue("Duplicate keys: $duplicates", duplicates.isEmpty())
        return all
    }

    private fun parseRaw(file: File): Map<String, Entry> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = linkedMapOf<String, Entry>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            val name = node.getAttribute("name")
            val translatable = node.getAttribute("translatable") != "false"
            val items = node.getElementsByTagName("item")
            out[name] = when (node.tagName) {
                "string" -> Entry(Kind.STRING, translatable, mapOf("" to node.textContent))
                "plurals" -> Entry(Kind.PLURALS, translatable, (0 until items.length).associate {
                    (items.item(it) as Element).getAttribute("quantity") to items.item(it).textContent
                })
                "string-array" -> Entry(Kind.ARRAY, translatable, (0 until items.length).associate { it.toString() to items.item(it).textContent })
                else -> continue
            }
        }
        return out
    }

    private fun problemsOfBase(base: Map<String, Entry>): List<String> {
        val problems = mutableListOf<String>()
        for ((name, entry) in base) {
            if (!entry.translatable) continue
            if (entry.values.values.any { it.isBlank() }) problems += "$name: empty value"
            if (entry.kind == Kind.PLURALS) {
                if (entry.values.keys != setOf("one", "other")) problems += "$name: English plurals need exactly one and other, has ${entry.values.keys}"
                val sets = entry.values.values.map(::placeholders).toSet()
                if (sets.size > 1) problems += "$name: every quantity must use the same placeholders (put the number in each item)"
            }
            entry.values.values.forEach { problems += delosProblems(name, it, "en") }
        }
        return problems
    }

    private fun validate(base: Map<String, Entry>, language: String, translation: Map<String, Entry>): List<String> {
        val problems = mutableListOf<String>()
        val required = base.filterValues { it.translatable }
        if (language !in PARTIAL_LANGUAGES) (required.keys - translation.keys).sorted().forEach { problems += "$it: missing" }
        for ((name, entry) in translation) {
            val source = base[name]
            when {
                source == null -> problems += "$name: unknown key (not in the English base)"
                !source.translatable -> problems += "$name: not translatable in the English base"
                else -> problems += validateEntry(name, source, entry, language)
            }
        }
        return problems
    }

    private fun validateEntry(name: String, source: Entry, entry: Entry, language: String): List<String> {
        if (source.kind != entry.kind) return listOf("$name: kind differs from the English base (${source.kind} vs ${entry.kind})")
        val problems = mutableListOf<String>()
        when (source.kind) {
            Kind.PLURALS -> {
                val needed = pluralQuantities(language)
                if (entry.values.keys != needed) problems += "$name: quantities ${entry.values.keys} but $language needs $needed"
            }
            Kind.ARRAY -> if (entry.values.size != source.values.size) problems += "$name: ${entry.values.size} items but the English base has ${source.values.size}"
            Kind.STRING -> {}
        }
        val expected = if (source.kind == Kind.PLURALS) source.values.getValue("other") else null
        for ((key, text) in entry.values) {
            val label = if (key.isEmpty()) name else "$name[$key]"
            if (text.isBlank()) {
                problems += "$label: empty value"
                continue
            }
            val english = expected ?: source.values[key] ?: continue
            val want = placeholders(english)
            val got = placeholders(text)
            if (want.sorted() != got.sorted()) problems += "$label: placeholders $got differ from English $want"
            else if (got.size > 1 && !POSITIONAL_ONLY.containsMatchIn(text.replace("%%", ""))) problems += "$label: placeholders $got"
            if (english.containsWord("Delos") && !text.containsWord("Delos")) problems += "$label: the English text has Delos, the translation must keep it unchanged"
            problems += delosProblems(label, text, language)
        }
        return problems
    }

    /** The placeholders as `index$conversion`; implicit indexes follow the order of appearance. `%%` and `%n` take no argument. */
    private fun placeholders(text: String): List<String> {
        var implicit = 0
        return PLACEHOLDER.findAll(text).mapNotNull { m ->
            val conversion = m.groupValues[3]
            if (conversion == "%" || conversion == "n") null
            else "${m.groupValues[1].ifEmpty { (++implicit).toString() }}\$$conversion"
        }.toList()
    }

    private fun delosProblems(label: String, text: String, language: String): List<String> {
        val found = DECLINED_DELOS.find(text)?.value ?: TRANSLITERATIONS[language.substringBefore('-')]?.find(text)?.value
        return listOfNotNull(found?.let { "$label: declined or transliterated Delos ($it); rephrase, e.g. \"the Delos app\"" })
    }

    private fun String.containsWord(word: String) = Regex("(?<![\\p{L}\\p{N}])$word(?![A-Za-z\\u00C0-\\u024F])").containsMatchIn(this)

    /** The CLDR plural categories Android uses for each supported language. */
    private fun pluralQuantities(language: String): Set<String> = when (language.substringBefore('-')) {
        "cs", "uk" -> setOf("one", "few", "many", "other")
        "es", "fr", "pt" -> setOf("one", "many", "other")
        "ja", "ko", "zh" -> setOf("other")
        else -> setOf("one", "other")
    }

    private companion object {
        /**
         * Languages whose folder may still lack keys (the English text shows for them). Empty by default: a language folder that
         * exists must be complete. Add a tag here only while that language is being filled in, and remove it again.
         */
        val PARTIAL_LANGUAGES = emptySet<String>()
        val SUPPORTED_TAGS = listOf("en", "cs", "es", "pt-BR", "fr", "de", "ja", "ko", "zh-CN", "uk")
        val SUPPORTED_FOLDERS = listOf("cs", "es", "pt-rBR", "fr", "de", "ja", "ko", "zh-rCN", "uk")
        val LANGUAGE_FOLDER = Regex("""values-([a-z]{2,3}(-r[A-Z]{2})?|b\+.+)""")
        val PLACEHOLDER = Regex("""%(?:(\d+)\$)?([-#+ 0,(]*\d*(?:\.\d+)?)([a-zA-Z%])""")
        val POSITIONAL_ONLY = Regex("""%\d+\$""")

        /** Delos followed by a Latin letter (case ending) or a possessive 's; hyphens, spaces and CJK particles are fine. */
        val DECLINED_DELOS = Regex("""Delos(?:['’]s|[A-Za-z\u00C0-\u024F])""")

        /** The brand written in another script; extend per language when a translator slips. */
        val TRANSLITERATIONS = mapOf(
            "uk" to Regex("Делос"),
            "ru" to Regex("Делос"),
            "ja" to Regex("デロス|デロ-ス"),
            "ko" to Regex("델로스"),
            "zh" to Regex("德洛斯|德罗斯|德勒斯"),
        )
    }
}
