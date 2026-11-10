package com.limelight.shell.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.limelight.shadows.ShadowGameManager
import com.limelight.shadows.ShadowMoonBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The icon rules of docs/ICON_AUDIT.md for the rows of the XML settings catalogue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
class SettingIconsTest {
    private lateinit var context: Context
    private lateinit var rows: List<SettingSpec>

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        rows = SettingsCatalog(context).all
    }

    /** Rows that really mean the same thing and so share one glyph (the same action on different targets). */
    private val sameMeaning = listOf(
        setOf("option_reset_osc_preference", "option_reset_osc"),
        setOf("import_keyboard_file", "import_special_button_file"),
        setOf("seekbar_keyboard_axi_opacity", "seekbar_osc_opacity", "seekbar_osc_free_analog_stick_opacity"),
    )

    @Test fun everyRowHasItsOwnIconEntry() {
        val missing = rows.filter { settingIcon(it.key) == null }.map { it.key }
        assertTrue("rows without an icon entry: $missing", missing.isEmpty())
    }

    @Test fun noTwoRowsOfATabShareAGlyphUnlessTheyMeanTheSame() {
        for ((tab, tabRows) in rows.groupBy { it.tab }) {
            for ((glyph, sharing) in tabRows.groupBy { it.icon }.filterValues { it.size > 1 }) {
                val keys = sharing.map { it.key }.toSet()
                assertTrue("$tab rows share glyph U+${glyph.first().code.toString(16)}: $keys", sameMeaning.any { it.containsAll(keys) })
            }
        }
    }

    @Test fun noRowRepeatsTheGlyphOfATabPill() {
        val tabGlyphs = SettingsTab.entries.associate { it.icon to it.label(context.resources) }
        val clashes = rows.filter { it.icon in tabGlyphs }.map { it.key }
        assertTrue("rows that reuse a tab icon: $clashes", clashes.isEmpty())
        assertEquals("the eight tabs keep eight glyphs", SettingsTab.entries.size, tabGlyphs.size)
        assertNotNull(settingIcon("list_resolution"))
    }
}
