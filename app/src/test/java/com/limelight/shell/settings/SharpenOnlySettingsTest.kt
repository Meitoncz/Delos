package com.limelight.shell.settings

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.limelight.preferences.UpscalerMode
import com.limelight.profiles.ProfilesManager
import com.limelight.shadows.ShadowGameManager
import com.limelight.shadows.ShadowMoonBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en", shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
class SharpenOnlySettingsTest {
    private lateinit var context: Context
    private lateinit var catalog: SettingsCatalog

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        ProfilesManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ProfilesManager.getInstance().load(context)
        catalog = SettingsCatalog(context)
    }

    private fun spec(key: String) = catalog.all.first { it.key == key }

    @Test fun sharpenOnlyKeepsEnabledAndSharpnessButNotTheSupersamplingQuality() {
        val prefs = context.getSharedPreferences("sharpen_only", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("checkbox_sgsr", true).commit()
        val stream = StreamSettingsStore(catalog) { prefs }
        for (mode in UpscalerMode.values()) {
            prefs.edit().putString("list_sgsr_quality", mode.value()).commit()
            assertTrue(mode.name, stream.enabled(spec("checkbox_sgsr"), null))
            assertTrue(mode.name, stream.enabled(spec("list_sgsr_quality"), null))
            assertTrue(mode.name, stream.enabled(spec("seekbar_sgsr_sharpness"), null))
            assertEquals(mode.name, mode.scales(), stream.enabled(spec("list_sgsr_supersampling_level"), null))
        }
        stream.close()
    }

    @Test fun theSupersamplingQualityExplainsWhyItIsOff() {
        val values = mapOf<String, Any>("list_sgsr_quality" to "sharpen")
        val help = SettingsHelp.details(context.resources, spec("list_sgsr_supersampling_level"), values)
        assertTrue(help, help.contains("Not used by Sharpen only."))
        assertFalse(SettingsHelp.details(context.resources, spec("list_sgsr_supersampling_level"), mapOf<String, Any>("list_sgsr_quality" to "high"))
            .contains("Not used by"))
    }

    @Test fun theModeIsDescribedWithWhatItDoesAndItsCost() {
        val help = SettingsHelp.choice(context.resources, "list_sgsr_quality", "sharpen")
        assertTrue(help.contains("No scaling"))
        assertTrue(help.contains("GPU"))
        assertTrue(SettingsHelp.description(context.resources, "seekbar_sgsr_sharpness", "").contains("Sharpen only"))
    }
}
