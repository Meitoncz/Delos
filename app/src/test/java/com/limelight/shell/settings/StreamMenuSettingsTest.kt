package com.limelight.shell.settings

import android.content.Context
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class StreamMenuSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val res = context.resources

    private fun resourcesIn(locale: Locale) =
        context.createConfigurationContext(Configuration(res.configuration).apply { setLocale(locale) }).resources

    @Test fun thePrintedLettersAndShouldersStayAsTheyAre() {
        val names = listOf(
            KeyEvent.KEYCODE_BUTTON_A to "A", KeyEvent.KEYCODE_BUTTON_B to "B", KeyEvent.KEYCODE_BUTTON_X to "X", KeyEvent.KEYCODE_BUTTON_Y to "Y",
            KeyEvent.KEYCODE_BUTTON_L1 to "L1", KeyEvent.KEYCODE_BUTTON_R1 to "R1", KeyEvent.KEYCODE_BUTTON_L2 to "L2", KeyEvent.KEYCODE_BUTTON_R2 to "R2",
            KeyEvent.KEYCODE_BUTTON_THUMBL to "L3", KeyEvent.KEYCODE_BUTTON_THUMBR to "R3",
        )
        for (locale in listOf(Locale.US, Locale.JAPANESE, Locale.GERMAN)) for ((code, name) in names) {
            assertEquals("$locale", name, StreamMenuSettings.buttonName(resourcesIn(locale), code))
        }
    }

    @Test fun theWordsOnAPadAreResources() {
        assertEquals("Start", StreamMenuSettings.buttonName(res, KeyEvent.KEYCODE_BUTTON_START))
        assertEquals("Select", StreamMenuSettings.buttonName(res, KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals("Home", StreamMenuSettings.buttonName(res, KeyEvent.KEYCODE_BUTTON_MODE))
        val ja = resourcesIn(Locale.JAPANESE)
        assertEquals("スタート", StreamMenuSettings.buttonName(ja, KeyEvent.KEYCODE_BUTTON_START))
        assertEquals("セレクト", StreamMenuSettings.buttonName(ja, KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals("ホーム", StreamMenuSettings.buttonName(ja, KeyEvent.KEYCODE_BUTTON_MODE))
    }

    @Test fun aShortcutIsReadAsTheButtonsJoinedWithPlus() {
        assertEquals("L3 + R3", StreamMenuSettings.label(res, StreamMenuSettings.DEFAULT_BUTTONS))
        assertEquals("Start + Select", StreamMenuSettings.label(res, setOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START)))
    }
}
