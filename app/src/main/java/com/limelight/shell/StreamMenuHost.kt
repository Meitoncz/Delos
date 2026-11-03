package com.limelight.shell

import com.limelight.shell.settings.SettingChoice

/**
 * Everything the stream menu's composables read from and do to the running stream. [StreamMenu] implements it
 * with the live [com.limelight.Game]; the debug build's preview (docs/GUI.md, "Stream menu preview") implements
 * it with fake state, so the real menu can be drawn without a stream. The getters are read while composing and
 * again after every change the menu makes, so they may simply return the current value.
 */
internal interface StreamMenuHost {
    /** The name of the game, shown at the left of the bar. */
    val gameName: String

    /** Closes the menu's window at once (the menu has played its exit before). */
    fun dismiss()

    /** Runs [action] once the stream has the focus again (after the menu closed). */
    fun runWhenStreamFocused(action: () -> Unit)

    // Overlay and keyboard
    val overlayOn: Boolean
    val overlayCompact: Boolean
    fun toggleOverlay()
    fun toggleKeyboard()

    // Brightness
    val brightnessPercent: Int
    fun setBrightness(percent: Int)

    // Bitrate: the host's answer to [applyBitrate] arrives later, through [bitrateBusy] and [bitrateMessage]
    val bitrateKbps: Int
    val bitrateBusy: Boolean
    val bitrateMessage: String?
    fun applyBitrate(requested: Int, accepted: (Int) -> Unit)

    // Upscaler and supersampling
    val upscalerAvailable: Boolean
    val upscalerRequested: Boolean
    val upscalerStatus: String
    val supersamplingMode: Boolean
    val supersamplingLevel: Int

    /** 0 = SGSR 1 Standard, 1 = SGSR 1 High Quality, 2 = SGSR 2 Video, 3 = SGSR 2 Video Quality. */
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
    fun toggleFullKeyboard()
    fun toggleKeyLayout()
    val touchSensitivityOn: Boolean
    fun toggleTouchSensitivity()
    fun rotate()

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
