package com.limelight.shell.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.staticCompositionLocalOf
import com.limelight.R

enum class Cue { MOVE, SELECT, BACK, OPEN, ERROR }

/**
 * UI sounds (tools/sounds/make_ui_sounds.py, the prototype's blips) and haptics. Created by the
 * activity, released in onDestroy.
 */
class Feedback(context: Context) {
    var soundsEnabled = true
    var hapticsEnabled = true

    /** The view used for haptic feedback; set by the activity once the content view exists. */
    var view: View? = null

    // USAGE_GAME plays on the media stream, so the volume keys and a muted media volume apply. The earlier
    // USAGE_ASSISTANCE_SONIFICATION used the system stream, which ignores the media volume.
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val sounds = mapOf(
        Cue.MOVE to pool.load(context, R.raw.ui_move, 1),
        Cue.SELECT to pool.load(context, R.raw.ui_select, 1),
        Cue.BACK to pool.load(context, R.raw.ui_back, 1),
        Cue.OPEN to pool.load(context, R.raw.ui_open, 1),
        Cue.ERROR to pool.load(context, R.raw.ui_error, 1),
    )

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else context.getSystemService(Vibrator::class.java)

    /**
     * Proper haptic actuators (LRAs) report their resonant frequency and play the system's short,
     * crisp effects well. Plain rumble motors (e.g. the AYN Odin) report none: the system ticks are
     * too short to be felt on them, so they get longer, stronger pulses instead. The frequency can only
     * be read on Android 14+; older versions always get the pulses, which also work on LRAs.
     */
    private val rumbleMotorOnly: Boolean =
        vibrator?.hasVibrator() == true &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || vibrator.resonantFrequency.isNaN())

    private var focusSilenced = true

    /** The next focus change is not announced: the initial focus of a screen is silent, like in the prototype. */
    fun silenceNextFocus() {
        focusSilenced = true
    }

    /** Called whenever an element gains focus. */
    fun focusMoved() {
        if (focusSilenced) focusSilenced = false else play(Cue.MOVE)
    }

    fun play(cue: Cue) {
        if (soundsEnabled) sounds[cue]?.let { pool.play(it, 1f, 1f, 1, 0, 1f) }
        if (hapticsEnabled) {
            if (rumbleMotorOnly) pulse(cue) else view?.performHapticFeedback(hapticFor(cue))
        }
    }

    fun release() = pool.release()

    private fun hapticFor(cue: Cue): Int = when (cue) {
        Cue.MOVE, Cue.BACK -> HapticFeedbackConstants.CLOCK_TICK
        Cue.SELECT, Cue.OPEN ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        Cue.ERROR ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    }

    /**
     * Rumble motor fallback: pulses long enough for the motor to spin up. Tested on the Odin:
     * 40 ms at 70 % cannot be felt, 60 ms at 86 % barely, 80 ms at full strength clearly;
     * 70 / 100 ms at full strength felt too strong, 60 / 80 ms still a bit too much.
     */
    private fun pulse(cue: Cue) {
        val v = vibrator ?: return
        val full = if (v.hasAmplitudeControl()) 255 else VibrationEffect.DEFAULT_AMPLITUDE
        val effect = when (cue) {
            Cue.MOVE, Cue.BACK -> VibrationEffect.createOneShot(50, full)
            Cue.SELECT, Cue.OPEN -> VibrationEffect.createOneShot(70, full)
            Cue.ERROR -> VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), intArrayOf(0, full, 0, full), -1)
        }
        // Touch usage: follows the system's touch feedback intensity, like the regular haptics
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
        }
    }
}

val LocalFeedback = staticCompositionLocalOf<Feedback> { error("Feedback not provided") }
