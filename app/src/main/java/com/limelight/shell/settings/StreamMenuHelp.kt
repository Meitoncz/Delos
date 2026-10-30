package com.limelight.shell.settings

/** Help for actions that are available only while a stream is running. */
object StreamMenuHelp {
    fun description(label: String): String = descriptions[label] ?: when {
        label.startsWith("Keys: ") -> "Sends ${label.removePrefix("Keys: ")} to the PC once, then returns to the stream."
        label.startsWith("Command: ") -> "Runs the host-defined command ${label.removePrefix("Command: ")} on the PC at once. Its effect is set in Vibepollo or Apollo, not Delos."
        else -> ""
    }

    private val descriptions = mapOf(
        "Resume" to "Closes this menu and continues the stream. The shortcut that opened the menu closes it too.",
        "Keyboard" to "Opens or closes Android's system keyboard for typing on the PC; the menu closes. Separate from the Delos full keyboard and special keys.",
        "Overlay" to "Cycles full statistics, compact statistics, off. Total latency adds measured network, host and local time; it is not button-to-screen latency. 1% low FPS covers the slowest frames of about ten seconds.",
        "Brightness" to "Adjusts this device's brightness for this stream; the system setting returns afterwards. Does not affect a TV's own brightness.",
        "Screen brightness" to "Changes this device's screen brightness now; the system setting returns afterwards. Does not affect the PC image or an external monitor.",
        "Bitrate" to "Requests a new video bitrate without reconnecting. Needs Vibepollo; the host may cap it and the accepted value is shown. Lasts for this stream; saved defaults are kept.",
        "Apply bitrate" to "Sends the requested bitrate to Vibepollo. The accepted value can be lower than requested. The stream keeps running and saved defaults are unchanged.",
        "Upscaler" to "Local reconstruction: a lower-resolution stream is upscaled to the display size; native-resolution input gets supersampling. Costs device GPU time. Changes here last for this stream.",
        "Upscaling" to "Turns local SGSR reconstruction on or off for this stream. Does not change the resolution the PC sends. The picture may briefly flicker while the surface resets. Saved defaults are kept.",
        "Supersampling" to "Reconstructs native-resolution input at a larger internal size, then reduces it to the display size. Works with all four SGSR modes. Costs GPU time; does not raise the source or network resolution. Lasts for this stream.",
        "Supersampling quality" to "For a native 1080p stream, 1× uses a 2560 × 1440 internal image and 2× uses 3840 × 2160, both reduced to the display size. 2× costs more GPU time and memory. Resets for the next stream.",
        "Mode" to "SGSR 1 uses the current frame; SGSR 2 Video also reuses detail from earlier frames. Separate from sharpness. Applies live; saved defaults are kept.",
        "Sharpness" to "Final sharpening pass, separate from the reconstruction mode. 0 turns off sharpening only. High values emphasize halos and artifacts. Lasts for this stream.",
        "More" to "More stream controls: clipboard transfer, host commands, key combinations, zoom, touch input and on-screen controls. Actions run when selected, without confirmation.",
        "Disconnect" to "Stops streaming and returns to the Delos library. The game keeps running on the PC; it is not quit.",
        "Quit game" to "Asks the host to end the running application, then disconnects. Runs at once, so save first. Unlike Disconnect, it ends the host session.",
        "Send keys" to "Sends a built-in or imported key combination to the PC once; the menu closes. Custom commands: Controls → Import Custom Keys.",
        "Send clipboard" to "Copies this device's clipboard to the PC once. Needs Vibepollo or Apollo clipboard support. Separate from automatic synchronization.",
        "Get clipboard" to "Fetches the PC clipboard to this device once. Needs Vibepollo or Apollo clipboard support; Android may show a clipboard notice.",
        "Commands" to "Lists server commands configured in Vibepollo or Apollo. The host decides what each does; Delos cannot edit them.",
        "Task manager" to "Sends Ctrl + Shift + Esc to the PC, which opens Task Manager on Windows. A keyboard shortcut, not a Delos process manager.",
        "Zoom" to "Toggles the stream's zoom-and-pan controls. Changes the local view, not the encoded resolution. Remember Zoom & Pan decides whether it persists.",
        "Mouse mode" to "Chooses how touch controls the PC: direct touch, trackpad or off, depending on the display. Remember mouse mode decides whether the choice persists.",
        "Local mouse cursor" to "Shows or hides Android's pointer for a physical mouse during this stream. Not the PC cursor in the video. The saved default is unchanged.",
        "On-screen controls" to "Shows or hides the virtual gamepad during this stream. Opacity and layout are set under Controls. Separate from the full keyboard and special keys.",
        "Full keyboard" to "Shows or hides the Delos full on-screen keyboard; size, alignment, opacity and sticky modifiers are set in settings. Not Android's system keyboard.",
        "Key layout" to "Shows or hides the custom special-key layer, using the profile's selected layout and imported commands. Separate from the full keyboard.",
        "Touch sensitivity" to "Toggles extra touchscreen sensitivity scaling, configured in settings. Separate from physical mouse or trackpad sensitivity.",
        "Rotate" to "Rotates the stream view. Affects local orientation and input mapping, not the host game's display settings.",
    )
}
