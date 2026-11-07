package com.limelight.shell.screens

import com.limelight.shell.settings.SettingChoice
import com.limelight.shell.settings.SettingKind
import com.limelight.shell.settings.SettingSpec
import com.limelight.shell.settings.choiceLabel
import java.util.Locale

/** Pure rules of the per-game settings screen (the composables only draw them). */

/** The banner chip: how many of this game's settings differ from the profile. */
internal fun customSettingsLabel(count: Int): String = when {
    count <= 0 -> "Follows your profile"
    count == 1 -> "1 custom setting"
    else -> "$count custom settings"
}

/** How many of [keys] (a tab's settings) the game overrides; [custom] is the game's stored overrides. */
internal fun customCount(custom: Set<String>, keys: Collection<String>): Int = keys.count { it in custom }

/** The panel's summary while no row is focused: where the custom settings are, or what makes a setting custom. */
internal fun customSummary(stream: Int, display: Int): String =
    if (stream + display == 0) "Change a row and it becomes custom for this game." else "$stream on Stream · $display on Display"

/** [value] of [spec] as its row shows it (the profile's value in "Profile: …"), like the row's own value label. */
internal fun profileValueLabel(spec: SettingSpec, value: Any?, choices: List<SettingChoice>): String = when (spec.kind) {
    SettingKind.TOGGLE -> if (value == true) "On" else "Off"
    SettingKind.CHOICE -> spec.choiceLabel(value, choices)
    SettingKind.SLIDER -> {
        val number = (value as? Number)?.toInt() ?: spec.min
        val label = if (number % spec.divisor == 0) (number / spec.divisor).toString()
        else String.format(Locale.US, "%.1f", number.toDouble() / spec.divisor)
        "$label${spec.unit}"
    }
    else -> value?.toString().orEmpty()
}
