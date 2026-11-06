package com.limelight.shell.settings

import com.limelight.utils.RumblePresets

/** Help for actions that are available only while a stream is running. */
object StreamMenuHelp {
    /** TEMPORARY (#66): the help key of a preset row on the live rumble preset page. */
    fun rumbleKey(preset: RumblePresets): String = "Rumble: ${preset.label}"

    fun description(label: String): String = descriptions[label] ?: when {
        label.startsWith("Keys: ") -> "Sends ${label.removePrefix("Keys: ")} to the PC once, then returns to the stream."
        label.startsWith("Command: ") -> "Runs the host-defined command ${label.removePrefix("Command: ").let { if (it.length > 36) it.take(35) + "\u2026" else it }} on the PC at once. Set in Vibepollo or Apollo."
        // TEMPORARY (#66)
        label.startsWith("Rumble: ") -> RumblePresets.values().firstOrNull { rumbleKey(it) == label }?.description.orEmpty()
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
        "Upscaler" to "Upscales a low-resolution stream, or supersamples a native one. Costs GPU time; lasts for this stream unless saved.",
        "Upscaling" to "Turns SGSR on or off for this stream; the picture may flicker.",
        "Supersampling" to "Renders a native-resolution stream at a larger size, then reduces it. Costs GPU time; lasts for this stream.",
        "Supersampling on" to "Renders at a larger internal size. Costs GPU time.",
        "Supersampling quality" to "For 1080p: 1× renders at 2560 × 1440, 2× at 3840 × 2160 and costs more GPU time and memory.",
        "Mode" to "Applies live.",
        "Sharpness" to "Final sharpening pass, separate from the mode. 0 turns sharpening off. Lasts for this stream.",
        "Save for this game" to "Saves the upscaler state, mode, sharpness and supersampling level for this game's future launches.",
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
        "Key layout" to "Shows or hides the profile's custom special-key layer. Separate from the full keyboard.",
        "Touch sensitivity" to "Toggles extra touchscreen sensitivity scaling, configured in settings.",
        "Rotate" to "Rotates the stream view locally; the host game's display settings are unchanged.",
        // TEMPORARY (#66)
        "Rumble preset" to "Picks how host rumble feels, at once and saved. Stays open: pick again to compare. B goes back.",
    )
}
