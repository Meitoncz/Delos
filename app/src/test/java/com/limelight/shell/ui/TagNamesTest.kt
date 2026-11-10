package com.limelight.shell.ui

import com.limelight.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The names of the genre and theme entries are resources keyed by IGDB's ids (`strings_tags.xml`); the mapping and the ranking keep
 * using the ids and the English names as data. Every id the mapping can produce needs a resource, every `tags_*` resource a place
 * in the lookup, and the English text must be IGDB's own name so nothing changes on screen.
 */
class TagNamesTest {
    private val file = listOf(File("src/main/res/values/strings_tags.xml"), File("app/src/main/res/values/strings_tags.xml")).first { it.isFile }

    /** key -> English text of every string in strings_tags.xml, in file order; a duplicate key fails the test. */
    private fun xml(): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        val out = linkedMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as Element
            val key = node.getAttribute("name")
            assertNull("duplicate key $key", out.put(key, node.textContent.replace("\\'", "'")))
        }
        return out
    }

    /** Resource id -> key of every `tags_*` field of R.string. */
    private fun declared(): Map<Int, String> =
        R.string::class.java.fields.filter { it.name.startsWith("tags_") }.associate { it.getInt(null) to it.name }

    @Test fun everyIdTheMappingCanProduceHasAResource() {
        for (id in IGDB_GENRE_NAMES.keys) assertNotNull("genre $id", genreNameRes(id))
        for (id in IGDB_THEME_NAMES.keys) assertNotNull("theme $id", themeNameRes(id))
        for (id in STEAM_GENRE_TAGS.values + STEAM_WEAK_GENRE_TAGS.values) assertNotNull("genre $id", genreNameRes(id))
        for (id in STEAM_THEME_TAGS.values + STEAM_WEAK_THEME_TAGS.values) assertNotNull("theme $id", themeNameRes(id))
    }

    @Test fun anIdWeHaveNoStringForFallsBackToTheEnglishName() {
        assertNull(genreNameRes(999))
        assertNull(themeNameRes(999))
        // genre and theme ids overlap (33 is Arcade and Sandbox), so the two lookups must not be mixed up
        assertTrue(genreNameRes(33) != themeNameRes(33))
    }

    @Test fun keysAreUniqueAndEachResourceIsUsedOnce() {
        val used = IGDB_GENRE_NAMES.keys.map { genreNameRes(it)!! } + IGDB_THEME_NAMES.keys.map { themeNameRes(it)!! }
        assertEquals("two entries share a string", used.size, used.toSet().size)
        val declared = declared()
        assertEquals("tags_* resources and lookup entries differ", declared.keys, used.toSet())
        val xml = xml()
        assertEquals("R.string and strings_tags.xml differ", declared.values.toSet(), xml.keys)
        assertEquals(declared.size, xml.size)
    }

    @Test fun englishTextIsIgdbsOwnName() {
        val declared = declared()
        val xml = xml()
        for ((id, name) in IGDB_GENRE_NAMES) assertEquals("genre $id", name, xml.getValue(declared.getValue(genreNameRes(id)!!)))
        for ((id, name) in IGDB_THEME_NAMES) assertEquals("theme $id", name, xml.getValue(declared.getValue(themeNameRes(id)!!)))
    }
}
