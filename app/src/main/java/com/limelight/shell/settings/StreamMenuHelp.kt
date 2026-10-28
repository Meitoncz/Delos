package com.limelight.shell.settings

/** Help for actions that are available only while a stream is running. */
object StreamMenuHelp {
    fun description(label: String): String = descriptions[label] ?: when {
        label.startsWith("Keys: ") -> "Send ${label.removePrefix("Keys: ")} to the PC once, then return to the stream. Built-in and imported key combinations are shown here."
        label.startsWith("Command: ") -> "Run the host-defined command ${label.removePrefix("Command: ")} on the PC. Its effect is configured in Vibepollo or Apollo, not in Delos. Selecting it runs it immediately."
        else -> ""
    }

    private val descriptions = mapOf(
        "Resume" to "Close this menu and continue the connected stream. The same controller shortcut that opened the menu closes it too.",
        "Keyboard" to "Open or close Android's system keyboard for typing on the PC. The menu closes so input can reach the stream. This is separate from the full Delos keyboard and custom special-key layout.",
        "Overlay" to "Cycle full statistics → compact statistics → off. Full statistics include FPS, 1% low FPS, bandwidth, packet loss and latency. Total latency combines measured network, host and local processing time; it is not complete button-to-screen latency. 1% low FPS describes the slowest frames over roughly ten seconds; long interruptions are excluded.",
        "Brightness" to "Adjust this device's brightness for this stream. The Android system brightness setting is restored when the stream ends. Does not adjust an attached TV's own brightness controls.",
        "Screen brightness" to "Change this device's screen brightness immediately for the running session. The system setting returns afterwards. It does not change the PC image or an external monitor's brightness setting.",
        "Bitrate" to "Request a new video bitrate without reconnecting. Requires a compatible Vibepollo host. The host may cap the request, and the accepted value is reported. Changes last for this stream; profile and per-game defaults are kept.",
        "Apply bitrate" to "Send the requested bitrate to Vibepollo. Delos reports the value accepted by the host, which can be lower than your request. The request does not restart the stream or replace saved profile defaults.",
        "Upscaler" to "Open local image reconstruction controls. A lower-resolution stream can be reconstructed to the display size; native-resolution input offers supersampling. This adds device GPU work, not network resolution. Changes on this page last for this stream only.",
        "Upscaling" to "Enable or disable local SGSR reconstruction for this stream. Lower-resolution video is reconstructed to the display size. This does not change the resolution sent by the PC. A surface reset may briefly interrupt the picture. Saved defaults are kept.",
        "Supersampling" to "Reconstruct native-resolution input to a larger internal image, then reduce it to the display size. Can improve edge appearance at extra GPU cost. It does not create a true higher-resolution source and does not increase network resolution. Changes last for this stream only.",
        "Supersampling quality" to "For a native 1080p stream, 1× uses a 2560 × 1440 internal image and 2× uses 3840 × 2160. Both are reduced back to the display resolution. 2× costs more GPU time and memory. This is local reconstruction, separate from the PC's virtual-display resolution scale. Resets for the next stream.",
        "Mode" to "Choose the local reconstruction method. SGSR 1 processes the current frame; SGSR 2 Video estimates motion and combines reliable detail from earlier frames. This is separate from final sharpness. Changes apply live and do not replace saved defaults.",
        "Sharpness" to "Strength of the separate final MediaCodec sharpening pass. Zero disables sharpening while reconstruction remains active. High settings can emphasize halos and compression artifacts. PyroWave does not use this slider. Changes last for this stream only.",
        "More" to "Additional stream controls: clipboard transfer, host commands, key combinations, zoom, touch input and on-screen controls. Actions run when selected; there is no extra confirmation dialog.",
        "Disconnect" to "Stop streaming and return to the Delos library. The game keeps running on the PC so you can resume later. This does not quit the game.",
        "Quit game" to "Ask the host to end the running application, disconnect and return to the Delos library. The action runs immediately; save your game first if needed. Unlike Disconnect, it ends the host session.",
        "Send keys" to "Choose a built-in or imported key combination to send once to the PC. The menu closes after sending it. Configure custom commands through Controls → Import Custom Keys.",
        "Send clipboard" to "Copy this device's current clipboard to the PC using compatible Vibepollo or Apollo clipboard support. This is a one-time transfer, separate from automatic clipboard synchronization.",
        "Get clipboard" to "Fetch the PC clipboard to this device using compatible Vibepollo or Apollo clipboard support. Android may show a clipboard access or preview message. This is a one-time transfer.",
        "Commands" to "Choose a server command configured in Vibepollo or Apollo. The host decides what each command does. Delos does not invent or edit those commands.",
        "Task manager" to "Send Ctrl + Shift + Esc to the PC and return to the stream. On Windows this opens Task Manager. This is a keyboard shortcut, not a separate Delos process manager.",
        "Zoom" to "Toggle the stream's zoom-and-pan controls. This changes the local view, not the resolution encoded by the PC. Remember Zoom & Pan in settings controls whether adjustments survive the session.",
        "Mouse mode" to "Choose how touch input controls the PC: direct touch, trackpad or disabled, depending on the display. The right-hand panel explains the selected mode. Remember mouse mode determines whether changes persist. Local mouse cursor controls Android's physical-mouse pointer, not the cursor drawn by the PC.",
        "Local mouse cursor" to "Show or hide Android's pointer for a physical mouse during this stream. This is the local pointer, not the PC cursor embedded in the video. Does not change the saved profile default.",
        "On-screen controls" to "Show or hide the virtual gamepad during this stream. Its opacity and layout are configured under Controls; the layout editor changes its positions. This is separate from the full keyboard and special-key layer.",
        "Full keyboard" to "Show or hide the Delos full on-screen keyboard. Its size, alignment, opacity and sticky modifiers are configured in settings. This is separate from Android's system keyboard.",
        "Key layout" to "Show or hide the custom special-key layer. Its selected layout and imported commands come from the current profile's Controls settings. This is separate from the full keyboard.",
        "Touch sensitivity" to "Toggle extra touchscreen sensitivity scaling. The saved horizontal, vertical and whole-screen options control its behavior. This is separate from physical mouse or trackpad sensitivity.",
        "Rotate" to "Rotate the stream view. This affects local orientation and input mapping, not the host game's own display settings.",
    )
}
