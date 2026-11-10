package com.limelight.shell.screens

import androidx.test.core.app.ApplicationProvider
import com.limelight.shell.settings.GameSettingsStore
import com.limelight.shell.settings.SettingChoice
import com.limelight.shell.settings.SettingKind
import com.limelight.shell.settings.SettingSpec
import com.limelight.shell.settings.SettingsTab
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class GameSettingsLabelTest {
    private val res get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private fun spec(kind: SettingKind, choices: List<SettingChoice> = emptyList(), unit: String = "", divisor: Int = 1, min: Int = 0) =
        SettingSpec("k", "Label", "", "c", "s", SettingsTab.STREAM, kind, null, choices, min = min, unit = unit, divisor = divisor)

    @Test fun noOverridesFollowTheProfile() {
        assertEquals("Follows your profile", customSettingsLabel(res, 0))
    }

    @Test fun overridesAreCountedWithTheRightNumber() {
        assertEquals("1 custom setting", customSettingsLabel(res, 1))
        assertEquals("3 custom settings", customSettingsLabel(res, 3))
    }

    @Test fun tabBadgesCountOnlyThatTabsOverrides() {
        val custom = setOf("list_resolution", "video_format", "checkbox_enable_hdr")
        assertEquals(2, customCount(custom, GameSettingsStore.STREAM_KEYS))
        assertEquals(1, customCount(custom, GameSettingsStore.DISPLAY_KEYS))
        assertEquals(0, customCount(emptySet(), GameSettingsStore.STREAM_KEYS))
        assertEquals(custom.size, customCount(custom, GameSettingsStore.STREAM_KEYS) + customCount(custom, GameSettingsStore.DISPLAY_KEYS))
    }

    @Test fun summaryNamesTheTabsOrExplainsHowASettingBecomesCustom() {
        assertEquals("2 on Stream · 1 on Display", customSummary(res, 2, 1))
        assertEquals("0 on Stream · 3 on Display", customSummary(res, 0, 3))
        assertEquals("Change a row and it becomes custom for this game.", customSummary(res, 0, 0))
    }

    @Test fun profileValueIsShownLikeTheRowShowsIt() {
        val choices = listOf(SettingChoice("1080p", "1920x1080"), SettingChoice("720p", "1280x720"))
        assertEquals("1080p", profileValueLabel(res, spec(SettingKind.CHOICE, choices), "1920x1080", choices))
        assertEquals("On", profileValueLabel(res, spec(SettingKind.TOGGLE), true, emptyList()))
        assertEquals("Off", profileValueLabel(res, spec(SettingKind.TOGGLE), false, emptyList()))
        assertEquals("Off", profileValueLabel(res, spec(SettingKind.TOGGLE), null, emptyList()))
        assertEquals("40 Mbps", profileValueLabel(res, spec(SettingKind.SLIDER, unit = " Mbps", divisor = 1000), 40000, emptyList()))
        assertEquals("1.5x", profileValueLabel(res, spec(SettingKind.SLIDER, unit = "x", divisor = 100), 150, emptyList()))
        assertEquals("5", profileValueLabel(res, spec(SettingKind.SLIDER, min = 5), null, emptyList()))
    }
}
