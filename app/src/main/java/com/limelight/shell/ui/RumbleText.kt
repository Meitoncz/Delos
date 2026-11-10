package com.limelight.shell.ui

import android.content.res.Resources
import com.limelight.R
import com.limelight.shell.settings.SettingsHelp
import com.limelight.utils.RumbleCapabilities
import com.limelight.utils.RumblePresets
import com.limelight.utils.RumbleProblem

/**
 * The text of the rumble types (`utils/Rumble*.java` hold identities and typed results only; the saved preset ids never change).
 * Resolved at display time from the [Resources] the screen holds, so it follows the app language.
 */

/** The name of a rumble feel: the Settings list, the Debug laboratory and the stream menu say it alike. */
fun RumblePresets.label(res: Resources): String = res.getString(when (this) {
    RumblePresets.ORIGINAL -> R.string.rumble_preset_original
    RumblePresets.GENTLE -> R.string.rumble_preset_soft
    RumblePresets.KICK_ONLY -> R.string.rumble_preset_kick_start
    RumblePresets.PULSE_25 -> R.string.rumble_preset_pulsing
})

/** What a rumble feel does: the same sentence as the explanation of its choice in Settings. */
fun RumblePresets.description(res: Resources): String = SettingsHelp.choice(res, "list_rumble_feel", name)

/** Why a preset cannot run on this device. */
fun RumbleCapabilities.Unavailable.text(res: Resources): String = res.getString(when (this) {
    RumbleCapabilities.Unavailable.NO_AMPLITUDE_CONTROL -> R.string.rumble_unavailable_no_amplitude
})

/** Why the running stream could not use the technique of its preset (plain amplitude is used instead). */
fun RumbleProblem.text(res: Resources): String = when (kind) {
    RumbleProblem.Kind.NO_AMPLITUDE_CONTROL -> res.getString(R.string.rumble_problem_no_amplitude)
    RumbleProblem.Kind.SINGLE_VIBRATOR_ONLY -> res.getString(R.string.rumble_problem_single_vibrator, technique.name)
    RumbleProblem.Kind.FAILED -> res.getString(R.string.rumble_problem_failed, technique.name, detail.orEmpty())
}
