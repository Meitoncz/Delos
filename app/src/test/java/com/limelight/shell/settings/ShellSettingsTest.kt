package com.limelight.shell.settings

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.profiles.ProfilesManager
import com.limelight.shadows.ShadowGameManager
import com.limelight.shadows.ShadowMoonBridge
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.GlobalKeys
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import com.limelight.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
class ShellSettingsTest {
    private lateinit var context: Context
    private lateinit var catalog: SettingsCatalog

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        ProfilesManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ProfilesManager.getInstance().load(context)
        catalog = SettingsCatalog(context)
    }

    @Test fun catalogIncludesEveryXmlControlAndPreservesValueTypes() {
        val keys = mutableSetOf<String>()
        context.resources.getXml(R.xml.preferences).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name != "PreferenceCategory" && xml.name != "PreferenceScreen") {
                    xml.getAttributeValue("http://schemas.android.com/apk/res/android", "key")?.let(keys::add)
                }
            }
        }
        // 116 in step 6.7, minus the five Artemis menu options the Delos stream menu replaced
        // and the post-stream latency message; in the settings audit full screen, game optimization, the
        // overlay zoom button and the two update links; in the switch-over (6.9) the classic UI's resume
        // dialog, icon size and overlay position; the classic on-screen controls skin (Delos draws its own)
        assertEquals(105, keys.size)
        assertEquals(keys, catalog.all.map { it.key }.toSet())
        assertEquals(keys.size, catalog.all.size)
        assertTrue(catalog.all.all { it.label.isNotBlank() && it.section.isNotBlank() })
        assertEquals(13, catalog.all.map { it.category }.distinct().size)
        assertEquals(SettingKind.TOGGLE, spec("checkbox_enable_pip").kind)
        assertEquals(SettingKind.CHOICE, spec("list_languages").kind)
        assertEquals(SettingKind.SLIDER, spec("seekbar_bitrate_kbps").kind)
        assertEquals(1000, spec("seekbar_bitrate_kbps").divisor)
        assertEquals(1000, spec("seekbar_bitrate_kbps").min)
        assertEquals(1000, spec("seekbar_bitrate_kbps").step)
        assertEquals(1000000, spec("seekbar_bitrate_kbps").max)
    }

    @Test fun categoriesAreAssignedToApprovedTabs() {
        assertEquals(SettingsTab.AUDIO, spec("list_audio_config").tab)
        assertEquals(SettingsTab.AUDIO, spec("checkbox_host_audio").tab)
        assertEquals(SettingsTab.CONTROLS, spec("checkbox_smart_clipboard_sync").tab)
        assertEquals(SettingsTab.CONTROLS, spec("seekbar_touch_sensitivity_opacity_x").tab)
        assertEquals(SettingsTab.CONTROLS, spec("keyboard_axi_list").tab)
        assertEquals(SettingsTab.DISPLAY, spec("checkbox_enable_hdr").tab)
        assertEquals(SettingsTab.DISPLAY, spec("checkbox_use_virtual_display").tab)
        assertEquals(SettingsTab.STREAM, spec("video_format").tab)
        assertEquals(SettingsTab.ABOUT, spec("list_languages").tab)
    }

    @Test fun everyCatalogSettingAndChoiceHasSpecificHelp() {
        catalog.all.forEach { spec ->
            assertTrue("Missing help for ${spec.key}", SettingsHelp.description(spec.key, "").isNotBlank())
            spec.choices.forEach { choice ->
                assertTrue("Missing help for ${spec.key}/${choice.value}", SettingsHelp.choice(spec.key, choice.value).isNotBlank())
            }
        }
        listOf("frame_pacing", "video_format").forEach { key ->
            assertTrue(spec(key).choices.none { it.label.contains("prefer", ignoreCase = true) })
        }
        assertTrue(spec("frame_pacing").matches("Warp 2"))
        assertTrue(SettingsHelp.choice("list_resolution", "2560x1600").contains("2560 × 1600"))
        assertTrue(SettingsHelp.choice("list_fps", "119.88").contains("119.88"))
    }

    @Test fun helpFollowsSelectedPacingAndExplainsLfrPrecedence() {
        val pacing = spec("frame_pacing")
        val values = mutableMapOf<String, Any>("frame_pacing" to "warp2", "pref_low_latency_frame_balance" to true)
        val warp = SettingsHelp.details(pacing, values)
        assertTrue(warp.startsWith("Warp 2\n"))
        assertTrue(warp.contains("four times"))
        assertTrue(warp.contains("LFR is enabled"))
        values["frame_pacing"] = "fast-vsync"
        val nano = SettingsHelp.details(pacing, values)
        assertTrue(nano.startsWith("Fast V-Sync"))
        assertFalse(nano.contains("four times"))
        assertFalse(nano.contains("LFR is enabled"))
        assertTrue(nano.contains("takes precedence over LFR"))
    }

    @Test fun pyroWaveExposesItsNativeUpscalerButNotMediaCodecOnlyControls() {
        val prefs = context.getSharedPreferences("help_pyrowave", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("video_format", "pyrowave").putBoolean("checkbox_sgsr", true).commit()
        val stream = StreamSettingsStore(catalog) { prefs }
        assertTrue(stream.enabled(spec("checkbox_sgsr"), null))
        assertTrue(stream.enabled(spec("list_sgsr_quality"), null))
        assertTrue(stream.enabled(spec("seekbar_sgsr_sharpness"), null))
        listOf("checkbox_async_decode", "checkbox_ultra_low_latency", "pref_low_latency_frame_balance").forEach {
            assertFalse("Should not apply to PyroWave: $it", stream.enabled(spec(it), null))
        }
        assertTrue(SettingsHelp.details(spec("list_sgsr_quality"), prefs.all).contains("all four modes"))
        prefs.edit().putBoolean("checkbox_sgsr", false).commit()
        assertFalse(stream.enabled(spec("list_sgsr_quality"), null))
        prefs.edit().putString("video_format", "forceav1").putBoolean("checkbox_sgsr", true).commit()
        assertTrue(stream.enabled(spec("seekbar_sgsr_sharpness"), null))
        assertTrue(stream.enabled(spec("checkbox_async_decode"), null))
        stream.close()
    }

    @Test fun unavailableStoredChoiceIsNotShownAsAnotherMode() {
        val pacing = spec("frame_pacing")
        val supported = pacing.choices.filter { it.value != "fast-vsync" }
        assertEquals("Fast V-Sync (Nano Pacer) (unavailable)", pacing.choiceLabel("fast-vsync", supported))
        assertEquals(supported.first(), nextSettingChoice("fast-vsync", supported, 1))
        assertEquals(supported.last(), nextSettingChoice("fast-vsync", supported, -1))
        assertNull(nextSettingChoice("fast-vsync", emptyList(), 1))
        val help = SettingsHelp.details(pacing, mapOf("frame_pacing" to "fast-vsync"), supported)
        assertTrue(help.startsWith("Fast V-Sync"))
        assertTrue(help.contains("saved choice is unavailable"))
    }

    @Test fun noExperimentalOrBetaLabels() {
        // Device-dependent features say so in their descriptions instead
        val labels = catalog.all.flatMap { spec -> listOf(spec.label) + spec.choices.map { it.label } }
        assertTrue(labels.none { it.contains("experimental", ignoreCase = true) || it.contains("(beta)", ignoreCase = true) })
        assertTrue(spec("frame_pacing").description.contains("may not work on every device"))
    }

    @Test fun searchShortcutConsumesBothEdgesAndOpensOnceOnReleaseOnlyInSettings() {
        val keys = GlobalKeys()
        var opened = 0
        keys.onSearch = { opened++ }
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_Y)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_Y)
        assertFalse(keys.dispatch(down))
        assertFalse(keys.dispatch(up))
        keys.canSearch = { true }
        assertTrue(keys.dispatch(down))
        assertEquals(0, opened)
        assertTrue(keys.dispatch(up))
        assertEquals(1, opened)
        keys.canSearch = { false } // A dialog, portal or another screen must not open search.
        assertFalse(keys.dispatch(down))
        assertFalse(keys.dispatch(up))
        assertEquals(1, opened)
    }

    @Test fun newProfileCopiesStreamValuesThenSwitchingKeepsUsersIndependent() {
        val base = PreferenceManager.getDefaultSharedPreferences(context)
        base.edit().putInt("seekbar_bitrate_kbps", 15000).putBoolean("checkbox_enable_perf_overlay", true).commit()
        val profiles = ProfileStore(context)
        val first = profiles.create("One", 0)
        val stream = StreamSettingsStore(catalog, profiles::settings)
        stream.write(spec("seekbar_bitrate_kbps"), 22000)
        val second = profiles.create("Two", 1)
        assertEquals(22000, profiles.settings().getInt("seekbar_bitrate_kbps", -1))
        stream.reload()
        stream.write(spec("seekbar_bitrate_kbps"), 38000)
        base.edit().putBoolean("checkbox_enable_perf_overlay", false).commit()
        profiles.switchTo(first.id)
        assertEquals(22000, profiles.settings().getInt("seekbar_bitrate_kbps", -1))
        val overlay = ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
        assertEquals(22000, overlay.getInt("seekbar_bitrate_kbps", -1))
        assertTrue(overlay.getBoolean("checkbox_enable_perf_overlay", false))
        assertEquals(first.id, ProfilesManager.getInstance().active.uuid.toString())
        profiles.switchTo(second.id)
        assertEquals(38000, ProfilesManager.getInstance().getOverlayingSharedPreferences(context).getInt("seekbar_bitrate_kbps", -1))
        assertEquals(15000, base.getInt("seekbar_bitrate_kbps", -1))
        stream.close()
        profiles.streamProfiles.close()
    }

    @Test fun streamReaderReceivesResolutionFpsCodecAndBitrateFromUserProfile() {
        val profiles = ProfileStore(context)
        profiles.create("Player", 0)
        val stream = StreamSettingsStore(catalog, profiles::settings)
        stream.write(spec("list_resolution"), "1920x1080")
        stream.write(spec("list_fps"), "90")
        stream.write(spec("video_format"), "neverh265")
        stream.write(spec("seekbar_bitrate_kbps"), 34000)
        val config = PreferenceConfiguration.readPreferences(context)
        assertEquals(1920, config.width)
        assertEquals(1080, config.height)
        assertEquals(90f, config.fps, 0f)
        assertEquals(34000, config.bitrate)
        assertEquals(PreferenceConfiguration.FormatOption.FORCE_H264, config.videoFormat)
        stream.close()
        profiles.streamProfiles.close()
    }

    @Test fun profileSurvivesRestartAndClassicProfileEditsArePickedUp() {
        val profiles = ProfileStore(context)
        val user = profiles.create("Player", 0)
        profiles.settings().edit().putInt("seekbar_bitrate_kbps", 29000).commit()
        profiles.streamProfiles.close()
        val manager = ProfilesManager.getInstance()
        manager.load(context) // Gson restores numeric options as doubles.
        val active = manager.active
        active.options["seekbar_bitrate_kbps"] = 41000.0
        manager.update(active)
        val reopened = ProfileStore(context)
        reopened.streamProfiles.resume()
        assertEquals(user.id, reopened.profiles.value.currentId)
        assertEquals(41000, reopened.settings().getInt("seekbar_bitrate_kbps", -1))
        reopened.streamProfiles.close()
    }

    @Test fun nonPyroWaveQualityChangesPreserveTheUsersBitrate() {
        val prefs = context.getSharedPreferences("test-settings", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("list_resolution", "1280x720").putString("list_fps", "60")
            .putInt("seekbar_bitrate_kbps", 34000).commit()
        val stream = StreamSettingsStore(catalog) { prefs }
        listOf("auto", "forceav1", "forceh265", "neverh265").forEach { codec ->
            stream.write(spec("video_format"), codec)
            stream.write(spec("list_resolution"), "1920x1080")
            stream.write(spec("list_fps"), "120")
            stream.write(spec("checkbox_enable_hdr"), true)
            assertEquals("Bitrate changed for $codec", 34000, prefs.getInt("seekbar_bitrate_kbps", -1))
        }
        stream.close()
    }

    @Test fun pyroWaveRecalculatesBitrateButLeavingItPreservesTheCurrentValue() {
        val prefs = context.getSharedPreferences("test-settings", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("list_resolution", "1920x1080").putString("list_fps", "60").commit()
        val stream = StreamSettingsStore(catalog) { prefs }
        stream.write(spec("video_format"), "pyrowave")
        assertEquals(225000, prefs.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("list_fps"), "120")
        assertEquals(445000, prefs.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("checkbox_enable_hdr"), true)
        assertEquals(530000, prefs.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("checkbox_enable_hdr"), false)
        assertEquals(445000, prefs.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("list_resolution"), "2560x1440")
        assertEquals(PreferenceConfiguration.getDefaultBitrate("2560x1440", "120", "pyrowave", false),
            prefs.getInt("seekbar_bitrate_kbps", -1))
        stream.write(spec("seekbar_bitrate_kbps"), 310000)
        stream.write(spec("video_format"), "auto")
        assertEquals(310000, prefs.getInt("seekbar_bitrate_kbps", -1))
        assertEquals(PreferenceConfiguration.MAX_BITRATE_KBPS, spec("seekbar_bitrate_kbps").max)
        stream.close()
    }

    @Test fun editableLayoutsAreCopiedAndResetOnlyForTheCurrentUser() {
        context.getSharedPreferences("OSC", Context.MODE_PRIVATE).edit().putString("button", "original").commit()
        val profiles = ProfileStore(context)
        val first = profiles.create("One", 0)
        assertEquals("original", ProfileLayouts.preferences(context, "OSC").getString("button", null))
        ProfileLayouts.preferences(context, "OSC").edit().putString("button", "custom").commit()
        val second = profiles.create("Two", 1)
        assertEquals("custom", ProfileLayouts.preferences(context, "OSC").getString("button", null))
        ProfileLayouts.preferences(context, "OSC").edit().clear().commit()
        profiles.switchTo(first.id)
        assertEquals("custom", ProfileLayouts.preferences(context, "OSC").getString("button", null))
        profiles.switchTo(second.id)
        assertTrue(ProfileLayouts.preferences(context, "OSC").all.isEmpty())
        assertEquals("original", context.getSharedPreferences("OSC", Context.MODE_PRIVATE).getString("button", null))
        profiles.streamProfiles.close()
    }

    @Test fun validationRejectsInvalidNumbersWithoutChangingSettings() {
        val prefs = context.getSharedPreferences("test-validation", Context.MODE_PRIVATE)
        val stream = StreamSettingsStore(catalog) { prefs }
        listOf("NaN", "Infinity", "-1", "0", "oops").forEach {
            assertNotNull(stream.writeText(spec("custom_refresh_rate"), it))
            assertNotNull(stream.writeText(spec("edit_diy_bitrate"), it))
        }
        assertNotNull(stream.writeText(spec("edit_diy_w_h"), "1920x0"))
        assertTrue(prefs.all.isEmpty())
        assertNull(stream.writeText(spec("custom_refresh_rate"), "59.940"))
        assertEquals("59.94", prefs.getString("custom_refresh_rate", null))
        assertNull(stream.writeText(spec("edit_diy_bitrate"), "10.25"))
        assertEquals(10250, prefs.getInt("seekbar_bitrate_kbps", -1))
        stream.close()
    }

    @Test fun nestedDependenciesDisableChildrenAndKeepThemSearchable() {
        val prefs = context.getSharedPreferences("test-dependencies", Context.MODE_PRIVATE)
        val stream = StreamSettingsStore(catalog) { prefs }
        // The strength depends on emulated rumble, which depends on rumble itself
        prefs.edit().putBoolean("checkbox_enable_rumble", false).putBoolean("checkbox_vibrate_fallback", true).commit()
        assertFalse(stream.enabled(spec("seekbar_vibrate_fallback_strength"), null))
        prefs.edit().putBoolean("checkbox_enable_rumble", true).commit()
        assertTrue(stream.enabled(spec("seekbar_vibrate_fallback_strength"), null))
        assertTrue(spec("seekbar_vibrate_fallback_strength").matches("rumble"))
        stream.close()
    }

    @Test fun deviceChoicesRetainNativeAndCustomValuesAndFilterUnsupportedCodecs() {
        val caps = SettingsCapabilities(listOf(SettingChoice("Native", "2400x1080")), 1920, 60f, false, false, false, false, emptySet())
        val values = mapOf("edit_diy_w_h" to "2560x1600", "custom_refresh_rate" to "59.94")
        val resolution = caps.choices(spec("list_resolution"), values).map { it.value }
        assertTrue("2400x1080" in resolution)
        assertTrue("2560x1600" in resolution)
        assertFalse("3840x2160" in resolution)
        assertTrue("59.94" in caps.choices(spec("list_fps"), values).map { it.value })
        assertFalse("120" in caps.choices(spec("list_fps"), values).map { it.value })
        assertTrue("120" in caps.choices(spec("list_fps"), values + ("checkbox_unlock_fps" to true)).map { it.value })
        assertEquals(setOf("auto", "neverh265"), caps.choices(spec("video_format"), values).map { it.value }.toSet())
        assertTrue("pyrowave" in caps.copy(pyroWave = true).choices(spec("video_format"), values).map { it.value })
    }

    private fun spec(key: String) = catalog.all.first { it.key == key }
}
