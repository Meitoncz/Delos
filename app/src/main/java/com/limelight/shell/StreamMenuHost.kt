package com.limelight.shell

import com.limelight.shell.settings.SettingChoice
import com.limelight.utils.RumblePresets

/** What the host answered to the last Apply on the Bitrate page. */
internal sealed interface BitrateResult {
    data class Accepted(val kbps: Int) : BitrateResult
    data class Limited(val kbps: Int) : BitrateResult
    data class Failed(val reason: String) : BitrateResult
}

/**
 * Everything the stream menu's composables read from and do to the running stream. [StreamMenu] implements it
 * with the live [com.limelight.Game]; the debug build's preview (docs/GUI.md, "Stream menu preview") implements
 * it with fake state, so the real menu can be drawn without a stream. The getters are read while composing and
 * again after every change the menu makes, so they may simply return the current value.
 */
internal interface StreamMenuHost {
    /** The name of the game, shown above the tiles. */
    val gameName: String

    /** Closes the menu's window at once (the menu has played its exit before). */
    fun dismiss()

    /**
     * How much the stream behind the menu is blurred, 0 to 1: it follows the soft gradient behind the tiles (in with the
     * entrance, out with the exit). A host without a window of its own to blur (the debug preview) ignores it.
     */
    fun setBackdrop(amount: Float) = Unit

    /** Runs [action] once the stream has the focus again (after the menu closed). */
    fun runWhenStreamFocused(action: () -> Unit)

    // Overlay and keyboard
    val overlayOn: Boolean
    val overlayCompact: Boolean
    fun toggleOverlay()
    fun toggleSystemKeyboard()

    // Brightness
    val brightnessPercent: Int
    fun setBrightness(percent: Int)

    // Bitrate: the host's answer to [applyBitrate] arrives later, through [bitrateBusy] and [bitrateResult]
    val bitrateKbps: Int
    val bitrateBusy: Boolean
    val bitrateResult: BitrateResult?
    fun applyBitrate(requested: Int, accepted: (Int) -> Unit)

    // Upscaler and supersampling
    val upscalerAvailable: Boolean
    val upscalerRequested: Boolean
    val upscalerStatus: String
    val supersamplingMode: Boolean
    val supersamplingLevel: Int

    /** The position in the Mode row ([com.limelight.preferences.UpscalerMode.index]): 0 = SGSR 1 Standard … 4 = Sharpen only. */
    val upscalerMode: Int
    val upscalerSharpness: Int
    fun setUpscalingEnabled(on: Boolean)
    fun setUpscalingMode(mode: Int)
    fun setUpscalingSharpness(percent: Int)
    fun setSupersamplingLevel(level: Int)

    /** False for a stream without its launch identity: there is no game to save the upscaler for. */
    val canSaveUpscaler: Boolean
    fun saveUpscaler()

    // More
    fun sendClipboard()
    fun getClipboard()
    fun sendTaskManager()
    val zoomOn: Boolean
    fun toggleZoom()
    val onscreenControlsOn: Boolean
    fun toggleOnscreenControls()
    val fullKeyboardOn: Boolean
    fun toggleFullKeyboard()
    val touchSensitivityOn: Boolean
    fun toggleTouchSensitivity()

    // Live selection of the rumble presets (same as the Debug laboratory) in a running stream
    /** The active preset, or null for Custom (parameters edited in the laboratory). */
    val rumblePreset: RumblePresets?

    /** Why [preset] cannot run on this device (an experimental technique the hardware lacks), or null when it can. */
    fun rumbleUnavailable(preset: RumblePresets): String? = null

    /** Why the running stream could not use the technique of the active preset (plain amplitude is used instead), or null. */
    val rumbleRuntimeProblem: String? get() = null

    /** Why [preset] shows a problem: the device cannot run it, or it is active and its technique failed in the stream. */
    fun rumbleProblem(preset: RumblePresets): String? =
        rumbleUnavailable(preset) ?: if (preset == rumblePreset) rumbleRuntimeProblem else null

    /** Saves [preset] like the laboratory does and applies it to the running stream at once; no sample is played. */
    fun selectRumblePreset(preset: RumblePresets)

    // Mouse mode
    val allowChangeMouseMode: Boolean
    val mouseMode: Int
    val rememberMouseMode: Boolean
    fun mouseModes(): List<SettingChoice>
    fun setMouseMode(mode: Int)
    val localMouseCursorVisible: Boolean
    fun toggleLocalMouseCursor()

    // Key combinations and the PC's commands
    val specialKeys: List<String>
    fun sendSpecialKey(index: Int)
    val serverCommands: List<String>
    fun runServerCommand(index: Int)

    // Leaving the stream
    fun leave(quit: Boolean)
}
