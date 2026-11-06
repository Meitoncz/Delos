package com.limelight.shell.settings

import com.limelight.preferences.PreferenceConfiguration

/** Reads the same effective values as the settings rows, without depending on the chosen bitrate. */
internal fun pyroWaveRecommendedBitrateKbps(values: Map<String, *>): Int? {
    if (values["video_format"] != "pyrowave") return null
    return PreferenceConfiguration.getDefaultBitrate(
        values["list_resolution"] as? String ?: "1280x720",
        values["list_fps"] as? String ?: "60",
        "pyrowave",
        values["checkbox_enable_hdr"] as? Boolean ?: false,
        values["list_pyrowave_chroma"] == "444",
    )
}

/**
 * English help for the shell. Keys and stored values still come from the preference catalog.
 *
 * Texts are factual and short: what the setting does, what the selected value means, and the one
 * limit that matters. Settings apply on the next stream unless a text says otherwise.
 *
 * The settings screen's right box is 432 stage px wide and about 302 px tall at the largest sensible
 * interface scale (125%: 27.5 px text, 1.45 line height). The box does not grow with the scale, the
 * text does: about 31 characters by 7 lines. A typical text uses 4-5 of them, and the dynamic notes
 * are included in the budget. [LINE_CHARS] and [MAX_LINES] are checked by the tests; the box scrolls
 * beyond it. The per-game screen's smaller box also scrolls for the longest texts.
 */
object SettingsHelp {
    const val LINE_CHARS = 31
    const val MAX_LINES = 7

    fun description(key: String, fallback: String): String = descriptions[key] ?: fallback

    /**
     * The selected value, its notes and the dynamic hints form the first paragraph; the setting's own
     * description follows after a blank line.
     */
    fun details(spec: SettingSpec, values: Map<String, *>, choices: List<SettingChoice> = spec.choices): String {
        val value = values[spec.key] ?: spec.default
        val selected = choices.firstOrNull { it.value == value } ?: spec.choices.firstOrNull { it.value == value }
        val explanation = selected?.let { choice(spec.key, it.value) }.orEmpty()
        val state = listOfNotNull(
            selected?.takeIf { explanation.isNotBlank() }?.let { "${it.label}\n$explanation" },
            "The saved choice is unavailable".takeIf { selected != null && selected !in choices },
            context(spec.key, values).takeIf { it.isNotBlank() },
        ).joinToString("\n")
        return listOf(state, spec.description).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /** Explanations follow the selected value, including resolutions and rates added by the device. */
    fun choice(key: String, value: String): String = choices[key]?.get(value) ?: when (key) {
        "list_resolution" -> "Requests a ${value.replace("x", " × ")} stream."
        "list_fps" -> "Requests $value FPS. Warp modes multiply it."
        "list_languages" -> if (value == "default") "Follows the Android language." else "Uses this language where translations exist."
        "keyboard_axi_list" -> "Import, export and reset act on this layout."
        else -> ""
    }

    private fun context(key: String, values: Map<String, *>): String {
        val pyro = values["video_format"] == "pyrowave"
        return when {
            key == "seekbar_bitrate_kbps" -> pyroWaveRecommendedBitrateKbps(values)?.let {
                "PyroWave recommends ${it / 1000} Mbps here."
            }.orEmpty()
            key == "frame_pacing" && values["pref_low_latency_frame_balance"] == true && values[key] != "fast-vsync" ->
                "LFR is enabled: Balanced used."
            key == "frame_pacing" && values[key] == "fast-vsync" -> "Fast V-Sync overrides LFR."
            key in setOf("checkbox_async_decode", "checkbox_ultra_low_latency", "pref_low_latency_frame_balance") && pyro ->
                "Not used with PyroWave."
            key == "list_pyrowave_chroma" && !pyro -> "Needs the PyroWave codec."
            key == "list_pyrowave_chroma" && values["checkbox_enable_hdr"] == true -> "HDR is on: 4:2:0 used."
            key == "list_sgsr_quality" && pyro -> "PyroWave offers all four modes"
            else -> ""
        }
    }

    private val descriptions = mapOf(
        "list_resolution" to "Resolution the PC sends. More pixels mean more detail and load.",
        "list_fps" to "Frames per second the PC sends. Match your display.",
        "seekbar_bitrate_kbps" to "Video bitrate in Mbps. Too high stutters on a weak link. Stream menu → Bitrate: this stream only.",
        "seekbar_metered_bitrate_kbps" to "Bitrate on a metered network (mobile data, some hotspots). 0 uses a quarter of the normal bitrate.",
        "frame_pacing" to "Warp modes may not work on every device.",
        "checkbox_async_decode" to "MediaCodec callbacks instead of polling. H.264, HEVC and AV1 only. Turn off if decoding is unstable.",
        "pref_low_latency_frame_balance" to "Balanced pacing that polls the decoder more often. Overrides other MediaCodec pacing except Fast V-Sync. Device-dependent.",
        "checkbox_ultra_low_latency" to "Extra MediaCodec low-latency options. Gain depends on chip and driver. Turn off if video is unstable.",
        "checkbox_use_virtual_display" to "Asks Vibepollo or Apollo for a virtual PC display at the requested resolution and FPS. Needs host support.",
        "checkbox_auto_orientation" to "Starts the stream in the device's current portrait or landscape orientation. The PC desktop is not rotated.",
        "checkbox_auto_invert_video_resolution" to "Swaps width and height for a portrait stream (1080 × 1920). Needs Auto Orientation and host support.",
        "checkbox_enable_view_top_center" to "Aligns a stream that does not fill the screen to the top center instead of the middle.",
        "seekbar_resolution_scale_factor" to "Scales the host virtual display: at 150%, 1920 × 1080 renders as 2880 × 1620 on the PC. Costs PC GPU.",
        "list_video_scale_mode" to "Local picture fit only.",
        "edit_diy_w_h" to "Adds a resolution to Video resolution: enter width x height (1920x1080), then select it there.",
        "edit_diy_bitrate" to "Sets the bitrate in Mbps; decimals and values above the slider's range allowed. Also updates Video bitrate.",
        "custom_refresh_rate" to "Adds a custom FPS (decimals allowed) to Video frame rate; select it there. Host, game and display limit the result.",
        "checkbox_enforce_display_mode" to "Asks Android firmly to use the selected display mode. Helps if refresh-rate requests are ignored; may misbehave on some firmware.",
        "checkbox_enable_hdr" to "Requests HDR video. Needs game, host GPU, decoder and display support (10-bit HEVC, AV1 or PyroWave).",
        "checkbox_enable_fullexdisplay" to "Shows the stream and menu on an attached monitor or TV at the configured resolution and FPS. The handheld controls the menu.",
        "checkbox_sgsr" to "Upscales a lower-resolution stream to the display size on this device. Costs GPU time. Menu changes last one session.",
        "list_pyrowave_chroma" to "4:4:4 costs bandwidth and GPU.",
        "list_sgsr_quality" to "Reconstruction method. All add GPU work.",
        "seekbar_sgsr_sharpness" to "Final sharpening pass, separate from the mode. 0 turns sharpening off; high values add artifacts. Menu changes last one session.",
        "list_sgsr_supersampling_level" to "Internal size when the stream already matches the display. Menu changes last one session.",
        "list_audio_config" to "Channels the PC sends. Surround needs matching playback.",
        "checkbox_enable_audiofx" to "Lets Android's system equalizer and effects process stream audio. May add delay.",
        "checkbox_host_audio" to "Plays game audio on the PC as well as here. Turn off to keep the PC speakers quiet.",
        "seekbar_deadzone" to "Positive values ignore small stick movement near center (drift). Negative values offset a controller's own dead zone.",
        "checkbox_multi_controller" to "Reports gamepads as they connect or disconnect. When off, one gamepad always stays present, for games that need one at launch.",
        "checkbox_usb_driver" to "Uses the Delos USB driver for Xbox and Nintendo Pro controllers Android cannot handle natively. May ask for USB permission.",
        "checkbox_usb_bind_all" to "Lets the USB driver also claim controllers Android supports natively. Needs the USB driver. A workaround.",
        "checkbox_mouse_emulation" to "Hold Start during a stream to switch the gamepad between game input and mouse emulation.",
        "analog_scrolling" to "Stick that scrolls in gamepad mouse mode.",
        "checkbox_flip_face_buttons" to "Swaps A/B and X/Y for gamepad input and on-screen controls (Nintendo layout). Not Delos navigation.",
        "checkbox_gamepad_touchpad_as_mouse" to "Always uses a controller's touchpad as a PC mouse. Off lets supported hosts receive native touchpad input.",
        "checkbox_gamepad_motion_sensors" to "Lets supported hosts receive the gamepad's motion sensors. Needs a compatible controller. Turn off to cut sensor traffic.",
        "checkbox_gamepad_motion_fallback" to "Uses this device's motion sensors when the controller supplies none; the host may see a PlayStation controller.",
        "checkbox_force_device_motion" to "Uses this device's motion sensors even if the gamepad has working ones. Needs the motion fallback.",
        "checkbox_enable_joyconfix" to "Compatibility path for Joy-Cons with missing or misrecognized buttons. Leave off if they work.",
        "checkbox_gamepad_enable_battery_report" to "Reports gamepad battery status to the host. Turn off if battery polling coincides with touch or mouse stutter.",
        "checkbox_enable_rumble" to "Sends host rumble to supported gamepads. Controller, connection and driver decide which effects work.",
        "list_rumble_feel" to "How game rumble feels. Next stream.",
        "checkbox_vibrate_fallback" to "Uses the handheld's vibration motor for game rumble when the gamepad cannot. Needs a vibration motor.",
        "checkbox_enable_device_rumble" to "Uses the handheld's vibration motor even when a controller supports rumble. Not for the built-in USB driver.",
        "seekbar_vibrate_fallback_strength" to "Strength of this device's vibration for game rumble. Needs amplitude control. Not interface haptics.",
        "mouse_mode_list" to "How touch becomes PC input. Stream menu: this session only.",
        "checkbox_remember_mouse_mode" to "Saves mouse-mode changes made in a stream. When off, they last only that stream.",
        "checkbox_mouse_local_cursor" to "Shows Android's pointer for a physical mouse instead of hiding it. Not the cursor the PC draws in the video.",
        "checkbox_multi_touch_gestures" to "In multi-touch mode: three fingers open the system keyboard, four the full keyboard, five the stream menu.",
        "checkbox_mouse_nav_buttons" to "Forwards a physical mouse's Back and Forward buttons to the PC. Turn off if right-click or navigation misbehaves.",
        "checkbox_absolute_mouse_mode" to "Absolute positioning instead of relative game movement. Suits remote desktop; many games need relative input.",
        "seekbar_trackpad_sensitivity_x" to "Horizontal sensitivity of a physical trackpad. Larger moves farther; negative reverses.",
        "seekbar_trackpad_sensitivity_y" to "Vertical sensitivity of a physical trackpad. Larger moves farther; negative reverses.",
        "checkbox_trackpad_drag_drop_vibration" to "Vibrates when a hold starts drag-and-drop on supported Samsung trackpads. Not for ordinary clicks.",
        "seekbar_trackpad_drag_drop_threshold" to "Hold time on a supported Samsung trackpad before a drag starts. Shorter may start unwanted drags.",
        "checkbox_trackpad_swap_axis" to "Swaps horizontal and vertical trackpad input for devices that report a rotated trackpad. Leave off if movement is right.",
        "checkbox_force_qwerty" to "Uses QWERTY key translation for stream input. Turn off for another layout with a compatible host.",
        "checkbox_right_alt_as_meta" to "Sends Right Alt as the Mac Command key, including combinations. Left Alt is unchanged. For streaming a Mac.",
        "checkbox_ignore_synth_events" to "Ignores synthetic Android input events. Stops stray keys from buggy drivers but blocks soft-keyboard input.",
        "checkbox_enable_commit_text" to "Forwards text from swipe typing and dictation in chunks. Good on the desktop; some games expect single key presses.",
        "checkbox_smart_clipboard_sync" to "Uploads this device's clipboard when the stream starts or regains focus; fetches the PC's when it loses focus. Needs Vibepollo or Apollo.",
        "checkbox_smart_clipboard_sync_toast" to "Shows a brief message when automatic clipboard transfer succeeds or fails. Does not enable synchronization.",
        "checkbox_hide_clipboard_content" to "Marks clipboard content from the PC as sensitive so Android hides its preview. Does not encrypt or disable transfer.",
        "seekbar_keyboard_axi_opacity" to "Opacity of the full on-screen keyboard. Separate from virtual gamepad opacity.",
        "onscreen_keyboard_autofit" to "Turns off automatic fitting of the full keyboard so the saved width, height and alignment apply.",
        "seekbar_onscreen_keyboard_height" to "Height of the full on-screen keyboard when automatic fitting is off. Taller: larger keys, more of the game covered.",
        "seekbar_onscreen_keyboard_width" to "Width of the full on-screen keyboard when automatic fitting is off. Keep it reachable on your screen.",
        "list_onscreen_keyboard_align_mode" to "Horizontal anchor of the manually sized full keyboard.",
        "checkbox_enable_sticky_modifier_key_virtual_keyboard" to "Keeps virtual Shift, Ctrl, Alt and similar modifiers active after a tap, so combinations can be typed one key at a time.",
        "checkbox_disable_warnings" to "Hides on-screen connection-quality warnings during streaming. Statistics stay.",
        "checkbox_remember_zoom_pan" to "Saves the stream's zoom and pan for later sessions. When off, adjustments last only that stream.",
        "checkbox_enable_pip" to "Keeps the stream in Picture-in-Picture while multitasking. View only: no game control in the small window.",
        "checkbox_show_onscreen_controls" to "Shows the virtual gamepad over the stream for touch play. The stream menu toggles it for the session.",
        "checkbox_hide_osc_when_has_gamepad" to "Hides the virtual gamepad while Android detects a physical controller; it returns when the controller disconnects.",
        "checkbox_vibrate_osc" to "Local vibration when pressing virtual gamepad buttons. Touch feedback, not game rumble. Needs a vibration motor.",
        "checkbox_only_show_L3R3" to "Shows only the virtual L3 and R3 stick-click buttons instead of the full gamepad.",
        "checkbox_show_guide_button" to "Adds the Guide/Home button to the virtual gamepad; its action depends on host and platform.",
        "seekbar_osc_opacity" to "Opacity of the virtual gamepad and custom special-key controls. Separate from full-keyboard opacity.",
        "checkbox_enable_analog_stick_new" to "Lets virtual stick centers move within their interaction area instead of staying fixed.",
        "seekbar_osc_free_analog_stick_opacity" to "Visibility of the free virtual stick's background while idle. Low keeps the picture clearer; high is easier to find.",
        "option_reset_osc_preference" to "Restores this profile's virtual gamepad to default sizes and positions at once. Other profiles are kept.",
        "checkbox_enable_keyboard" to "Shows the custom special-key layer over the stream, using the layout selected below. Separate from the full keyboard.",
        "checkbox_enable_clear_default_special_button" to "Hides the built-in key combinations from the stream menu's Send keys, leaving your imported commands.",
        "checkbox_vibrate_keyboard" to "Local vibration when tapping custom on-screen keyboard buttons; not game rumble. Needs a vibration motor.",
        "keyboard_axi_list" to "Active one of this profile's five special-key layouts.",
        "checkbox_enable_keyboard_square" to "Draws custom special-key buttons as squares instead of circles. The keys sent are unchanged.",
        "option_reset_osc" to "Restores the selected special-key layout to defaults at once. Other layouts and profiles are kept.",
        "import_keyboard_file" to "Replaces the selected special-key layout with a file from Android's picker. Export first for a backup.",
        "export_keyboard_file" to "Saves the selected special-key layout to a file, as a backup or for another device.",
        "checkbox_enable_touch_sensitivity" to "Extra sensitivity scaling for touchscreen game input, set by the axis sliders below. The stream menu toggles it live.",
        "checkbox_enable_global_touch_sensitivity" to "Applies touch sensitivity to the whole screen; when off, only to the right half, leaving the left for movement controls.",
        "checkbox_enable_touch_sensitivity_rotation_auto" to "Recenters scaled touch coordinates that run past the screen edge so high-sensitivity drags can continue.",
        "seekbar_touch_sensitivity_opacity_x" to "Horizontal sensitivity of multi-touch game input; higher turns small finger movement into larger in-game movement.",
        "seekbar_touch_sensitivity_opacity_y" to "Vertical sensitivity of multi-touch game input; higher turns small finger movement into larger in-game movement.",
        "seekbar_touchpad_sensitivity_opacity" to "Horizontal pointer sensitivity for touchscreen trackpad mode and custom virtual touchpads.",
        "seekbar_touchpad_sensitivity_y_opacity" to "Vertical pointer sensitivity for touchscreen trackpad mode and custom virtual touchpads.",
        "checkbox_enable_perf_overlay" to "Shows FPS, 1% low FPS, bitrate, packet loss and latency. Total latency is not button-to-screen. The stream menu cycles full, compact, off.",
        "checkbox_enable_perf_overlay_lite" to "Shows one compact statistics line at the top center instead of the full panel. The stream menu cycles full, compact, off.",
        "video_format" to "Codec the PC encodes with; host and decoder must support it.",
        "checkbox_unlock_fps" to "Shows frame-rate choices above the display's detected capability. Raises host load; refresh rate is unchanged.",
        "checkbox_reduce_refresh_rate" to "Lets Android choose a lower display refresh rate when suitable. Saves power but can slow presentation.",
        "checkbox_full_range" to "Requests full-range video levels instead of limited range. Enable only if host and display agree, or blacks and highlights crush.",
        "checkbox_prevent_packet_loss" to "Sends a small control packet about every 20 ms to discourage Wi-Fi power saving. May help device-specific loss.",
        "list_languages" to "Language of stream messages and system screens. The Delos shell and its help stay English.",
        "import_special_button_file" to "Imports custom key combinations for Send keys and the special-key controls, replacing this profile's custom commands.",
        "option_help_custom_keys" to "Opens instructions for the custom-key file format. Load the resulting file with Import Custom Keys.",
        "pref_debug_info" to "Opens device and controller diagnostics without a stream: device details, controller IDs and sensors, vibration test, control preview.",
        "ui_match_game_colors" to "Takes three colors from the selected game's cover for the animated background; falls back to PC artwork. Needs Material You off.",
    )

    private val choices = mapOf(
        "frame_pacing" to mapOf(
            "latency" to "Shows the newest frame at once; may stutter.",
            "balanced" to "Matches frames to the display refresh with a small queue.",
            "cap-fps" to "Balanced; asks the host for fewer frames near refresh.",
            "smoothness" to "Steady delivery; adds buffering and latency.",
            "warp" to "Requests twice the FPS (60 → 120); shows the newest frame.",
            "warp2" to "Requests four times the FPS (60 → 240).",
            "fast-vsync" to "Syncs to the lower of FPS and refresh. MediaCodec only.",
        ),
        "video_format" to mapOf(
            "auto" to "Picks H.264 or HEVC. AV1 and PyroWave need selecting.",
            "forceav1" to "Efficient at low bitrates. Needs AV1 encode on the host and decode here.",
            "forceh265" to "Less bandwidth than H.264; supports 10-bit HDR. May fall back.",
            "neverh265" to "Broadest compatibility; needs more bandwidth. No HDR.",
            "pyrowave" to "GPU wavelet codec with its own Vulkan decoder. Needs a fast link and far higher bitrate.",
        ),
        "list_pyrowave_chroma" to mapOf(
            "420" to "Half-resolution color. Cheapest.",
            "444" to "Full color. Uses 4:2:0 if the host cannot.",
        ),
        "list_sgsr_quality" to mapOf(
            "standard" to "Edge reconstruction from the current frame. Cheapest.",
            "high" to "A stronger spatial filter on the current frame.",
            "temporal" to "Reuses earlier frames; text and fast motion can break it.",
            "temporal-quality" to "Adds a reactive mask. Most GPU cost.",
        ),
        "list_sgsr_supersampling_level" to mapOf(
            "1" to "Renders 1080p at 2560 × 1440.",
            "2" to "Renders 1080p at 3840 × 2160. Costs more GPU time and memory.",
        ),
        "list_video_scale_mode" to mapOf(
            "fit" to "Whole picture; black bars if aspect ratios differ.",
            "fill" to "Fills the screen; crops if aspect ratios differ.",
            "stretch" to "Fills the screen without cropping; the picture distorts.",
        ),
        "list_audio_config" to mapOf(
            "2" to "Two channels for handheld speakers and most headphones.",
            "51" to "Six channels. Needs a 5.1 playback path.",
            "71" to "Eight channels. Use Stereo if channels are missing.",
        ),
        "list_rumble_feel" to mapOf(
            "ORIGINAL" to "The game's rumble exactly as it sends it.",
            "GENTLE" to "A weaker, rounded rumble.",
            "KICK_ONLY" to "A strong short kick at the start of each rumble, then the normal level.",
            "PULSE_25" to "A slow pulsed rumble that feels deeper.",
            "CUSTOM" to "Values edited under About → Debug info.",
        ),
        "analog_scrolling" to mapOf(
            "none" to "Neither stick scrolls.",
            "right" to "The right stick scrolls.",
            "left" to "The left stick scrolls.",
        ),
        "mouse_mode_list" to mapOf(
            "0" to "Direct multi-touch; the host must support touch.",
            "1" to "Touch a point to point or click there.",
            "2" to "Relative pointer movement; used for external displays.",
            "3" to "Relative movement for mouse-style games.",
            "4" to "No touch input; stylus and edge swipe still work.",
            "5" to "Direct touch with the mouse buttons swapped.",
        ),
        "list_onscreen_keyboard_align_mode" to mapOf(
            "left" to "Anchored to the left edge.",
            "center" to "Centered horizontally.",
            "right" to "Anchored to the right edge.",
        ),
    )
}
