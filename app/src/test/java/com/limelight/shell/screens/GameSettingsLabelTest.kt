package com.limelight.shell.screens

import com.limelight.shell.settings.GameSettingsStore
import com.limelight.shell.settings.SettingChoice
import com.limelight.shell.settings.SettingKind
import com.limelight.shell.settings.SettingSpec
import com.limelight.shell.settings.SettingsTab
import org.junit.Assert.assertEquals
import org.junit.Test

class GameSettingsLabelTest {
    private fun spec(kind: SettingKind, choices: List<SettingChoice> = emptyList(), unit: String = "", divisor: Int = 1, min: Int = 0) =
        SettingSpec("k", "Label", "", "c", "s", SettingsTab.STREAM, kind, null, choices, min = min, unit = unit, divisor = divisor)

    @Test fun noOverridesFollowTheProfile() {
        assertEquals("Follows your profile", customSettingsLabel(0))
    }

    @Test fun overridesAreCountedWithTheRightNumber() {
        assertEquals("1 custom setting", customSettingsLabel(1))
        assertEquals("3 custom settings", customSettingsLabel(3))
    }

    @Test fun tabBadgesCountOnlyThatTabsOverrides() {
        val custom = setOf("list_resolution", "video_format", "checkbox_enable_hdr")
        assertEquals(2, customCount(custom, GameSettingsStore.STREAM_KEYS))
        assertEquals(1, customCount(custom, GameSettingsStore.DISPLAY_KEYS))
        assertEquals(0, customCount(emptySet(), GameSettingsStore.STREAM_KEYS))
        assertEquals(custom.size, customCount(custom, GameSettingsStore.STREAM_KEYS) + customCount(custom, GameSettingsStore.DISPLAY_KEYS))
    }

    @Test fun summaryNamesTheTabsOrExplainsHowASettingBecomesCustom() {
        assertEquals("2 on Stream · 1 on Display", customSummary(2, 1))
        assertEquals("0 on Stream · 3 on Display", customSummary(0, 3))
        assertEquals("Change a row and it becomes custom for this game.", customSummary(0, 0))
    }

    @Test fun profileValueIsShownLikeTheRowShowsIt() {
        val choices = listOf(SettingChoice("1080p", "1920x1080"), SettingChoice("720p", "1280x720"))
        assertEquals("1080p", profileValueLabel(spec(SettingKind.CHOICE, choices), "1920x1080", choices))
        assertEquals("On", profileValueLabel(spec(SettingKind.TOGGLE), true, emptyList()))
        assertEquals("Off", profileValueLabel(spec(SettingKind.TOGGLE), false, emptyList()))
        assertEquals("Off", profileValueLabel(spec(SettingKind.TOGGLE), null, emptyList()))
        assertEquals("40 Mbps", profileValueLabel(spec(SettingKind.SLIDER, unit = " Mbps", divisor = 1000), 40000, emptyList()))
        assertEquals("1.5x", profileValueLabel(spec(SettingKind.SLIDER, unit = "x", divisor = 100), 150, emptyList()))
        assertEquals("5", profileValueLabel(spec(SettingKind.SLIDER, min = 5), null, emptyList()))
    }
}
