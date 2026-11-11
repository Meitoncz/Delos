package com.limelight.display

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class SecondDisplayOverrideTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    @Test fun defaultsToAutomatic() {
        assertEquals(SecondDisplayOverride.AUTOMATIC, SecondDisplayOverride.read(prefs))
        assertEquals(SecondDisplayOverride.AUTOMATIC, DisplayInventory.overrideOf(context))
    }

    @Test fun storedChoiceIsReadByTheInventory() {
        SecondDisplayOverride.entries.forEach {
            SecondDisplayOverride.write(prefs, it)
            assertEquals(it, SecondDisplayOverride.read(prefs))
            assertEquals(it, DisplayInventory.overrideOf(context))
        }
    }

    @Test fun anUnknownStoredValueMeansAutomatic() {
        prefs.edit().putString(SecondDisplayOverride.KEY, "something_else").apply()
        assertEquals(SecondDisplayOverride.AUTOMATIC, SecondDisplayOverride.read(prefs))
    }

    @Test fun stepsWrapAround() {
        assertEquals(SecondDisplayOverride.EXTERNAL, SecondDisplayOverride.AUTOMATIC.step(1))
        assertEquals(SecondDisplayOverride.BUILT_IN, SecondDisplayOverride.EXTERNAL.step(1))
        assertEquals(SecondDisplayOverride.AUTOMATIC, SecondDisplayOverride.BUILT_IN.step(1))
        assertEquals(SecondDisplayOverride.BUILT_IN, SecondDisplayOverride.AUTOMATIC.step(-1))
    }

    @Test fun classifiesTheSimulatedDefaultDisplayAsMain() {
        val list = DisplayInventory.list(context)
        assertEquals(DisplayRole.MAIN, list.first { it.facts.id == android.view.Display.DEFAULT_DISPLAY }.role)
        assertEquals(null, DisplayInventory.external(context))
    }
}
