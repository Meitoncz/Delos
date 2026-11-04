package com.limelight.shell

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchParameterPreferencesTest {
    @Test fun gameWithoutOverridesReadsTheProfileAndNeverEditsEitherSource() {
        val profile = StrictPreferences(mapOf("list_resolution" to "1280x720", "list_fps" to "60", "seekbar_metered_bitrate_kbps" to 45000))
        val unusedGame = StrictPreferences(emptyMap<String, Any>(), forbidReads = true)
        val view = launchParameterPreferences(profile, unusedGame, emptyMap<String, Any>())

        assertEquals("1280x720", view.getString("list_resolution", null))
        assertEquals("60", view.getString("list_fps", null))
        // The legacy reader's normalization must not reach either strict backing editor.
        view.edit().putInt("seekbar_metered_bitrate_kbps", 0).apply()
        assertEquals(45000, view.getInt("seekbar_metered_bitrate_kbps", -1))
        assertEquals(0, profile.edits)
        assertEquals(0, unusedGame.edits)
    }

    @Test fun gameWithOverridesReadsTheEffectiveGameViewWithoutRelaxingItsEditor() {
        val profile = StrictPreferences(mapOf("list_resolution" to "1280x720", "list_fps" to "60"))
        val effective = StrictPreferences(mapOf("list_resolution" to "3840x2160", "list_fps" to "59.94", "seekbar_metered_bitrate_kbps" to 70000))
        val view = launchParameterPreferences(profile, effective, mapOf("list_resolution" to "3840x2160"))

        assertEquals("3840x2160", view.getString("list_resolution", null))
        assertEquals("59.94", view.getString("list_fps", null))
        view.edit().putInt("seekbar_metered_bitrate_kbps", 0).apply()
        assertEquals(70000, view.getInt("seekbar_metered_bitrate_kbps", -1))
        assertEquals("1280x720", profile.getString("list_resolution", null))
        assertEquals(0, profile.edits)
        assertEquals(0, effective.edits)
    }

    @Test fun everyNormalizationEditorOperationLeavesProfileAndGameValuesUnchanged() {
        for (hasOverrides in listOf(false, true)) {
            val values = mapOf<String, Any>("text" to "original", "number" to 42, "long" to 99L,
                "fraction" to 1.5f, "flag" to true, "set" to mutableSetOf("original"))
            val profile = StrictPreferences(values)
            val game = StrictPreferences(values)
            val overrides = if (hasOverrides) mapOf("number" to 42) else emptyMap()
            val view = launchParameterPreferences(profile, game, overrides)
            val editor = view.edit().putString("text", "changed").putStringSet("set", mutableSetOf("changed"))
                .putInt("number", 0).putLong("long", 0L).putFloat("fraction", 0f).putBoolean("flag", false)
                .remove("text").clear()
            editor.apply()
            assertTrue(editor.commit())
            assertEquals(values, profile.all)
            assertEquals(values, game.all)
            assertEquals("original", view.getString("text", null))
            assertEquals(42, view.getInt("number", -1))
            assertEquals(99L, view.getLong("long", -1L))
            assertEquals(1.5f, view.getFloat("fraction", -1f), 0f)
            assertTrue(view.getBoolean("flag", false))
            assertEquals(setOf("original"), view.getStringSet("set", null))
            assertEquals(0, profile.edits + game.edits)
        }
    }

    /** A write reaching the actual per-game editor would throw exactly at this boundary. */
    private class StrictPreferences(private val values: Map<String, *>, private val forbidReads: Boolean = false) : SharedPreferences {
        var edits = 0
            private set
        private fun value(key: String?): Any? {
            check(!forbidReads) { "Empty-override selection must not read the game view" }
            return values[key]
        }
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = value(key) as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = value(key) as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = value(key) as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = value(key) as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = value(key) as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = value(key) as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor {
            edits++
            throw IllegalArgumentException("Not a per-game setting: seekbar_metered_bitrate_kbps")
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }
}
