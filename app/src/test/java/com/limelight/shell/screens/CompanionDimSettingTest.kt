package com.limelight.shell.screens

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.limelight.R
import com.limelight.shell.companion.CompanionDim
import com.limelight.shell.settings.SettingsHelp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class CompanionDimSettingTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val res get() = context.resources
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    @Before fun clear() {
        prefs.edit().clear().commit()
    }

    @Test fun defaultsAreOnAndOneMinute() {
        assertEquals(CompanionDim.Policy(enabled = true, minutes = 1), CompanionDim.read(prefs))
    }

    @Test fun everyDelayRoundTrips() {
        CompanionDim.MINUTES.forEach {
            CompanionDim.writeMinutes(prefs, it)
            assertEquals(it, CompanionDim.read(prefs).minutes)
        }
    }

    @Test fun unknownStoredDelayFallsBackToDefault() {
        prefs.edit().putInt(CompanionDim.KEY_MINUTES, 7).commit()
        assertEquals(CompanionDim.DEFAULT_MINUTES, CompanionDim.read(prefs).minutes)
        CompanionDim.writeMinutes(prefs, 3)
        assertEquals(CompanionDim.DEFAULT_MINUTES, CompanionDim.read(prefs).minutes)
    }

    @Test fun offKeepsItsDelay() {
        CompanionDim.writeMinutes(prefs, 5)
        CompanionDim.writeEnabled(prefs, false)
        assertEquals(CompanionDim.Policy(enabled = false, minutes = 5), CompanionDim.read(prefs))
        assertEquals(null, CompanionDim.read(prefs).delayMs)
        CompanionDim.writeEnabled(prefs, true)
        assertEquals(5 * 60_000L, CompanionDim.read(prefs).delayMs)
    }

    @Test fun delayRowHidesWhileOffOrWithoutCompanion() {
        val on = CompanionDim.Policy(enabled = true)
        val off = CompanionDim.Policy(enabled = false)
        assertTrue(companionDimDelayShown(true, on))
        assertFalse(companionDimDelayShown(true, off))
        assertFalse(companionDimDelayShown(false, on))
        assertFalse(companionDimDelayShown(false, off))
    }

    @Test fun everyDelayHasALabel() {
        CompanionDim.MINUTES.forEach {
            val label = res.getQuantityString(R.plurals.settings_native_companion_dim_minutes_value, it, it)
            assertTrue(label, label.isNotBlank() && label.contains(it.toString()))
        }
    }

    @Test fun everyChoiceHasHelpThatFits() {
        listOf("on", "off").forEach {
            val help = NativeSettingsHelp.choice(res, CompanionDim.KEY_ENABLED, it)
            assertTrue(it, help.isNotBlank())
            val text = "${res.getString(R.string.common_on)}\n$help\n\n${res.getString(R.string.settings_native_companion_dim_description)}"
            assertTrue(text, lines(text) <= SettingsHelp.MAX_LINES)
        }
        val delay = "1 minute\n\n${res.getString(R.string.settings_native_companion_dim_minutes_description)}"
        assertTrue(delay, lines(delay) <= SettingsHelp.MAX_LINES)
    }

    @Test fun debugLineShowsTheState() {
        assertEquals("\ncompanion dimming: On, after 5 min", companionDimLine(CompanionDim.Policy(true, 5)))
        assertEquals("\ncompanion dimming: Off", companionDimLine(CompanionDim.Policy(false, 5)))
    }

    private fun lines(text: String): Int = text.split('\n').sumOf { paragraph ->
        var lines = 1
        var used = 0
        paragraph.split(' ').filter { it.isNotEmpty() }.forEach { word ->
            if (used == 0) used = word.length
            else if (used + 1 + word.length <= SettingsHelp.LINE_CHARS) used += 1 + word.length
            else { lines++; used = word.length }
        }
        lines
    }
}
