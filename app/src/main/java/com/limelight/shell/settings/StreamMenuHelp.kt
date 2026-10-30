package com.limelight.shell.settings

/** Help for actions that are available only while a stream is running. */
object StreamMenuHelp {
    fun description(label: String): String = descriptions[label] ?: when {
        label.startsWith("Keys: ") -> "Sends ${label.removePrefix("Keys: ")} to the PC once, then returns to the stream."
        label.startsWith("Command: ") -> "Runs the host-defined command ${label.removePrefix("Command: ")} on the PC at once. Set in Vibepollo or Apollo."
        else -> ""
    }

    private val descriptions = mapOf(
        "Resume" to "Closes this menu and continues the stream.",
        "Keyboard" to "Opens or closes Android's system keyboard for typing on the PC. Not the Delos full keyboard.",
        "Overlay" to "Cycles full statistics, compact statistics, off. 1% low FPS covers the slowest frames of about ten seconds.",
        "Brightness" to "Adjusts this device's brightness for this stream; the system setting returns afterwards.",
        "Screen brightness" to "Changes this device's screen brightness now; the system setting returns afterwards. Not the PC or a monitor.",
        "Bitrate" to "Requests a new video bitrate without reconnecting. Needs Vibepollo; the host may cap it. Lasts for this stream.",
        "Apply bitrate" to "Sends the requested bitrate to Vibepollo. The accepted value can be lower. Saved defaults are unchanged.",
        "Upscaler" to "Upscales a lower-resolution stream to the display size; native-resolution input gets supersampling. Costs GPU time. Lasts for this stream.",
        "Upscaling" to "Turns local SGSR reconstruction on or off for this stream. The PC's resolution is unchanged; the picture may flicker briefly.",
        "Supersampling" to "Reconstructs native-resolution input at a larger internal size, then reduces it to the display size. Costs GPU time. Lasts for this stream.",
        "Supersampling quality" to "For a 1080p stream, 1× renders internally at 2560 × 1440 and 2× at 3840 × 2160. 2× costs more GPU time and memory.",
        "Mode" to "SGSR 1 uses the current frame; SGSR 2 Video also reuses earlier frames. Applies live.",
        "Sharpness" to "Final sharpening pass, separate from the mode. 0 turns sharpening off. Lasts for this stream.",
        "More" to "More stream controls: clipboard, host commands, key combinations, zoom, touch and on-screen controls. Actions run at once.",
        "Disconnect" to "Stops streaming and returns to the Delos library. The game keeps running on the PC.",
        "Quit game" to "Asks the host to end the running application, then disconnects. Runs at once, so save first.",
        "Send keys" to "Sends a built-in or imported key combination to the PC once; the menu closes. Import custom ones under Controls.",
        "Send clipboard" to "Copies this device's clipboard to the PC once. Needs Vibepollo or Apollo clipboard support.",
        "Get clipboard" to "Fetches the PC clipboard to this device once. Needs Vibepollo or Apollo clipboard support.",
        "Commands" to "Lists server commands configured in Vibepollo or Apollo. The host decides what each does.",
        "Task manager" to "Sends Ctrl + Shift + Esc to the PC, which opens Task Manager on Windows.",
        "Zoom" to "Toggles the stream's zoom-and-pan controls. Local view only; Remember Zoom & Pan decides whether it persists.",
        "Mouse mode" to "Chooses how touch controls the PC: direct touch, trackpad or off. Remember mouse mode decides whether it persists.",
        "Local mouse cursor" to "Shows or hides Android's pointer for a physical mouse during this stream. Not the PC cursor in the video.",
        "On-screen controls" to "Shows or hides the virtual gamepad during this stream. Opacity and layout are set under Controls.",
        "Full keyboard" to "Shows or hides the Delos full on-screen keyboard. Not Android's system keyboard.",
        "Key layout" to "Shows or hides the custom special-key layer from the profile's selected layout. Separate from the full keyboard.",
        "Touch sensitivity" to "Toggles extra touchscreen sensitivity scaling, configured in settings.",
        "Rotate" to "Rotates the stream view locally; the host game's display settings are unchanged.",
    )
}
