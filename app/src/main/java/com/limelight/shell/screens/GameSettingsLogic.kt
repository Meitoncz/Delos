package com.limelight.shell.screens

import android.content.res.Resources
import com.limelight.R
import com.limelight.shell.settings.SettingChoice
import com.limelight.shell.settings.SettingKind
import com.limelight.shell.settings.SettingSpec
import com.limelight.shell.settings.choiceLabel
import java.util.Locale

/** Pure rules of the per-game settings screen (the composables only draw them). */

/** The banner chip: how many of this game's settings differ from the profile. */
internal fun customSettingsLabel(res: Resources, count: Int): String =
    if (count <= 0) res.getString(R.string.settings_game_follows_profile) else res.getQuantityString(R.plurals.settings_game_custom_count, count, count)

/** How many of [keys] (a tab's settings) the game overrides; [custom] is the game's stored overrides. */
internal fun customCount(custom: Set<String>, keys: Collection<String>): Int = keys.count { it in custom }

/** The panel's summary while no row is focused: where the custom settings are, or what makes a setting custom. */
internal fun customSummary(res: Resources, stream: Int, display: Int): String =
    if (stream + display == 0) res.getString(R.string.settings_game_summary_none) else res.getString(R.string.settings_game_summary, stream, display)

/** The parts that are present, joined with the middle dot ("From profile · Recommended: 225 Mbps"). */
internal fun dotJoin(res: Resources, vararg parts: String?): String =
    parts.filterNotNull().reduceOrNull { joined, next -> res.getString(R.string.settings_screen_dot_join, joined, next) }.orEmpty()

/** [value] of [spec] as its row shows it (the profile's value in "Profile: …"), like the row's own value label. */
internal fun profileValueLabel(res: Resources, spec: SettingSpec, value: Any?, choices: List<SettingChoice>): String = when (spec.kind) {
    SettingKind.TOGGLE -> res.getString(if (value == true) R.string.settings_game_value_on else R.string.settings_game_value_off)
    SettingKind.CHOICE -> spec.choiceLabel(res, value, choices)
    SettingKind.SLIDER -> {
        val number = (value as? Number)?.toInt() ?: spec.min
        val label = if (number % spec.divisor == 0) (number / spec.divisor).toString()
        else String.format(res.configuration.locales[0], "%.1f", number.toDouble() / spec.divisor)
        "$label${spec.unit}"
    }
    else -> value?.toString().orEmpty()
}
