package com.limelight.shell.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.limelight.utils.RumbleCapabilities
import com.limelight.utils.RumblePresets
import com.limelight.utils.RumbleProblem
import com.limelight.utils.RumbleTechnique
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class RumbleTextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val res = context.resources

    private fun resourcesIn(locale: Locale) =
        context.createConfigurationContext(Configuration(res.configuration).apply { setLocale(locale) }).resources

    @Test fun theFeelsAreNamedAsTheyAlwaysWere() {
        assertEquals(listOf("Original", "Soft", "Kick-start", "Pulsing"), RumblePresets.values().map { it.label(res) })
        assertEquals("The game's rumble exactly as it sends it.", RumblePresets.ORIGINAL.description(res))
        assertEquals("A weaker, rounded rumble.", RumblePresets.GENTLE.description(res))
        assertEquals("A strong short kick at the start of each rumble, then the normal level.", RumblePresets.KICK_ONLY.description(res))
        assertEquals("A slow pulsed rumble that feels deeper.", RumblePresets.PULSE_25.description(res))
    }

    @Test fun everyFeelHasItsOwnNameAndATranslatedDescriptionInEveryLanguage() {
        val english = RumblePresets.values().map { it.label(res) }
        for (locale in listOf(Locale.GERMAN, Locale.FRENCH, Locale.JAPANESE, Locale("cs"), Locale("uk"), Locale("es"), Locale("ko"))) {
            val r = resourcesIn(locale)
            val labels = RumblePresets.values().map { it.label(r) }
            assertEquals("$locale", labels.size, labels.toSet().size)
            assertNotEquals("$locale", english, labels)
            for (preset in RumblePresets.values()) {
                assertNotEquals("$locale ${preset.name} description", preset.description(res), preset.description(r))
            }
        }
    }

    @Test fun theProblemsSayWhatTheyAlwaysSaid() {
        assertEquals("This vibrator has no amplitude control", RumbleCapabilities.Unavailable.NO_AMPLITUDE_CONTROL.text(res))
        assertEquals("This vibrator has no amplitude control; plain amplitude is used",
            RumbleProblem.noAmplitudeControl(RumbleTechnique.PULSE).text(res))
        assertEquals("PULSE runs on a single vibrator only; plain amplitude is used",
            RumbleProblem.singleVibratorOnly(RumbleTechnique.PULSE).text(res))
        assertEquals("PULSE failed: java.lang.IllegalStateException: boom; plain amplitude is used",
            RumbleProblem.failed(RumbleTechnique.PULSE, IllegalStateException("boom")).text(res))
    }

    @Test fun aTranslationPlacesTheTechniqueAndTheErrorItself() {
        val de = resourcesIn(Locale.GERMAN)
        val text = RumbleProblem.failed(RumbleTechnique.PULSE, IllegalStateException("boom")).text(de)
        assertEquals("PULSE ist fehlgeschlagen: java.lang.IllegalStateException: boom; es wird die einfache Amplitude verwendet", text)
    }
}
