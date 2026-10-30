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
    )
}

/**
 * English help for the shell. Keys and stored values still come from the preference catalog.
 *
 * Texts are factual and short: what the setting does, what the selected value means, and the limits
 * that matter. The settings screen's right box has a 432 px text width and about 372 px of height at
 * 22 px / 1.45 line height: roughly 39 characters by 11 lines. Two thirds of that is about 7 lines;
 * [LINE_CHARS] and [MAX_LINES] (the box capacity) are checked by the tests; the box scrolls beyond it.
 * The per-game screen's smaller box also scrolls for the longest texts.
 */
object SettingsHelp {
    const val LINE_CHARS = 39
    const val MAX_LINES = 11

    fun description(key: String, fallback: String): String = descriptions[key] ?: fallback

    fun details(spec: SettingSpec, values: Map<String, *>, choices: List<SettingChoice> = spec.choices): String {
        val value = values[spec.key] ?: spec.default
        val selected = choices.firstOrNull { it.value == value } ?: spec.choices.firstOrNull { it.value == value }
        val explanation = selected?.let { choice(spec.key, it.value) }.orEmpty()
        return listOfNotNull(
            selected?.takeIf { explanation.isNotBlank() }?.let { "${it.label}\n$explanation" },
            "The saved choice is unavailable here."
                .takeIf { selected != null && selected !in choices },
            context(spec.key, values).takeIf { it.isNotBlank() },
            spec.description,
        ).joinToString("\n\n")
    }

    /** Explanations follow the selected value, including resolutions and rates added by the device. */
    fun choice(key: String, value: String): String = choices[key]?.get(value) ?: when (key) {
        "list_resolution" -> "Requests a ${value.replace("x", " × ")} stream from the PC. Local upscaling is separate."
        "list_fps" -> "Requests $value FPS from the PC. Warp modes multiply it; the display shows no more frames than its refresh rate."
        "list_languages" -> if (value == "default") "Follows the Android language." else "Uses this language where translations exist."
        "keyboard_axi_list" -> "Edited on its own; switching keeps the other four. Import, export and reset act on it."
        else -> ""
    }

    private fun context(key: String, values: Map<String, *>): String {
        val pyro = values["video_format"] == "pyrowave"
        return when {
            key == "seekbar_bitrate_kbps" -> pyroWaveRecommendedBitrateKbps(values)?.let {
                "PyroWave recommends ${it / 1000} Mbps for the current resolution, FPS and HDR."
            }.orEmpty()
            key == "frame_pacing" && values["pref_low_latency_frame_balance"] == true && values[key] != "fast-vsync" ->
                "LFR is enabled: Balanced variant used."
            key == "frame_pacing" && values[key] == "fast-vsync" -> "Fast V-Sync takes precedence over LFR."
            key in setOf("checkbox_async_decode", "checkbox_ultra_low_latency", "pref_low_latency_frame_balance") && pyro ->
                "PyroWave uses its own Vulkan decoder; this MediaCodec option does not apply."
            key == "list_sgsr_quality" && pyro -> "PyroWave supports all four modes natively."
            else -> ""
        }
    }

    private val descriptions = mapOf(
        "list_resolution" to "Resolution of the video the PC sends. More pixels mean more detail and more encoding, decoding and bandwidth load. Applies next stream.",
        "list_fps" to "Frames per second the PC sends. Match your display and what the PC sustains. Applies next stream.",
        "seekbar_bitrate_kbps" to "Requested video bitrate in Mbps. Higher reduces artifacts but needs a stable link; too high causes stutter and packet loss. Applies next stream; Stream menu → Bitrate changes only the running session.",
        "seekbar_metered_bitrate_kbps" to "Video bitrate on a metered network (mobile data, some hotspots). 0 uses a quarter of the normal bitrate. Applies next stream.",
        "frame_pacing" to "How frames reach the display. Warp Drive and Warp 2 may not work on every device. Applies next stream.",
        "checkbox_async_decode" to "MediaCodec signals ready buffers with callbacks instead of polling. H.264, HEVC and AV1 only; independent of frame pacing. Turn off if decoding is unstable. Applies next stream.",
        "pref_low_latency_frame_balance" to "Balanced pacing that polls the decoder more often and releases frames near the next refresh. Overrides other MediaCodec pacing except Fast V-Sync. Device-dependent; separate from Ultra Low Latency. Applies next stream.",
        "checkbox_ultra_low_latency" to "Extra decoder-specific low-latency options (MediaCodec only). Gain depends on chip, codec and driver. Turn off if video is unstable. Applies next stream.",
        "checkbox_use_virtual_display" to "Asks Vibepollo or Apollo for a virtual PC display matching the requested resolution and FPS, leaving your physical monitor unchanged. Needs host virtual-display support. Applies next stream.",
        "checkbox_auto_orientation" to "Starts the stream in the device's current portrait or landscape orientation. Does not rotate the PC desktop. Applies next stream.",
        "checkbox_auto_invert_video_resolution" to "Swaps width and height for a portrait stream (1920 × 1080 becomes 1080 × 1920). Needs Auto Orientation; the host must support the result. Applies next stream.",
        "checkbox_enable_view_top_center" to "Aligns a stream that does not fill the screen to the top center instead of the middle. Transmitted resolution is unchanged. Applies next stream.",
        "seekbar_resolution_scale_factor" to "Scales the host virtual display: at 150%, a 1920 × 1080 stream renders a 2880 × 1620 PC display but still transmits 1920 × 1080. Costs PC GPU load; needs Vibepollo or Apollo virtual display. Separate from local supersampling. Applies next stream.",
        "list_video_scale_mode" to "How the picture fits your screen. Affects local presentation and touch mapping, not the network resolution. Applies next stream.",
        "edit_diy_w_h" to "Adds a resolution to Video resolution: enter width x height (1920x1080), then select it there. Host and decoder must support it; another aspect ratio adds black bars or cropping.",
        "edit_diy_bitrate" to "Sets the video bitrate in Mbps; decimals allowed, values above the slider's range allowed. Also updates Video bitrate. Applies next stream.",
        "custom_refresh_rate" to "Adds a custom FPS (decimals allowed) to Video frame rate; select it there. Host, game, decoder and display limit what is delivered and shown. Applies next stream.",
        "checkbox_enforce_display_mode" to "Asks Android firmly to use the selected display mode for the stream surface. Helps devices that ignore refresh-rate requests; may misbehave on some firmware, e.g. Chromecast 4K. Applies next stream.",
        "checkbox_enable_hdr" to "Requests HDR video. Needs game, host GPU, decoder and display support (10-bit HEVC, AV1 or PyroWave); the format is negotiated with the host. Cannot turn SDR content into HDR. Applies next stream.",
        "checkbox_enable_fullexdisplay" to "Shows the stream, loader and stream menu on an attached monitor or TV, keeping the configured resolution and FPS; Android picks a compatible display mode. Handheld controls operate the menu. HDR needs a compatible display. Applies next stream.",
        "checkbox_sgsr" to "Upscales a lower-resolution stream to the display size on this device (H.264, HEVC, AV1, PyroWave; HDR supported). Costs GPU time; cannot add missing detail. Native-resolution input gets supersampling instead. Defaults apply next stream; menu changes last one session.",
        "list_sgsr_quality" to "Reconstruction method (MediaCodec and PyroWave); all add GPU work. Menu changes apply live.",
        "seekbar_sgsr_sharpness" to "Final sharpening pass, separate from the reconstruction mode (MediaCodec and PyroWave). 0 turns off sharpening only. High values emphasize edges and artifacts. Defaults apply next stream; menu changes last one session.",
        "list_audio_config" to "Audio channels requested from the PC. Surround needs host and playback support. Applies next stream.",
        "checkbox_enable_audiofx" to "Lets Android's system equalizer and audio effects process stream audio. May add delay; leave off for the lowest latency. Applies next stream.",
        "checkbox_host_audio" to "Plays game audio on the PC as well as this device. Turn off to keep the PC speakers quiet. Applies next stream.",
        "seekbar_deadzone" to "Positive values ignore small stick movement near center (drift); negative values offset a controller's own dead zone and can make centering hard. Applies next stream.",
        "checkbox_multi_controller" to "Reports gamepads to the PC as they connect or disconnect. When off, one gamepad always stays present, even if Android sees none; useful for games that need a controller at launch. Applies next stream.",
        "checkbox_usb_driver" to "Uses the Delos USB driver for supported Xbox and Nintendo Pro controllers that Android cannot handle natively; may show a USB permission prompt. Bluetooth controllers are unaffected. Applies next stream.",
        "checkbox_usb_bind_all" to "Lets the Delos USB driver also claim controllers Android supports natively. Needs the USB driver option. A workaround for native-driver problems; can change available controller features. Applies next stream.",
        "checkbox_mouse_emulation" to "Hold Start during a stream to switch the gamepad between game input and mouse emulation. Scrolling follows Analog scrolling. Applies next stream.",
        "analog_scrolling" to "Stick that scrolls in gamepad mouse mode; normal stick input is unchanged. Applies next stream.",
        "checkbox_flip_face_buttons" to "Swaps A/B and X/Y for gamepad input and on-screen controls, for Nintendo-style layouts. Stream input only, not Delos navigation. Applies next stream.",
        "checkbox_gamepad_touchpad_as_mouse" to "Always uses a controller's touchpad as a PC mouse, even if the emulated host controller has its own touchpad. Off lets supported hosts receive native touchpad input. Applies next stream.",
        "checkbox_gamepad_motion_sensors" to "Lets supported hosts receive the gamepad's motion sensors. Needs a compatible controller and Android version. Turn off to cut sensor traffic. Applies next stream.",
        "checkbox_gamepad_motion_fallback" to "Uses this device's motion sensors when the controller or Android supplies none; the host may see an emulated PlayStation controller. Needs device sensors. Applies next stream.",
        "checkbox_force_device_motion" to "Uses this device's motion sensors even when the gamepad reports working ones, e.g. handheld aiming with an external controller. Needs the motion fallback. Applies next stream.",
        "checkbox_enable_joyconfix" to "Compatibility path for Joy-Cons with missing or misrecognized buttons. Leave off if they work. Applies next stream.",
        "checkbox_gamepad_enable_battery_report" to "Reports supported gamepad battery status to the host. Turn off if battery polling coincides with stutter in touch, mouse or stylus input; not all controllers expose it. Applies next stream.",
        "checkbox_enable_rumble" to "Sends host rumble to supported gamepads; controller, connection and Android driver decide which effects work. Device vibration is separate. Applies next stream.",
        "checkbox_vibrate_fallback" to "Uses the handheld's vibration motor for game rumble when the gamepad cannot. Not interface haptics. Needs a vibration motor. Applies next stream.",
        "checkbox_enable_device_rumble" to "Uses the handheld's vibration motor even when a connected controller supports rumble. Does not override controllers on the built-in USB driver. Applies next stream.",
        "seekbar_vibrate_fallback_strength" to "Strength of this device's vibration for emulated game rumble. Does not affect interface haptics or a controller's own motor. Needs amplitude control. Applies next stream.",
        "mouse_mode_list" to "How touch becomes PC input; stylus works even with touch off. Applies next stream; stream menu → Mouse mode changes the running session.",
        "checkbox_remember_mouse_mode" to "Saves mouse-mode changes made in a stream for later sessions. When off, they last only that stream.",
        "checkbox_mouse_local_cursor" to "Shows Android's pointer for a physical mouse instead of capturing and hiding it. Does not hide the cursor the PC draws in the video. Applies next stream.",
        "checkbox_multi_touch_gestures" to "In multi-touch mode: a three-finger tap opens the system keyboard, four fingers the full keyboard, five the stream menu. Separate from the controller menu shortcut and edge swipe. Applies next stream.",
        "checkbox_mouse_nav_buttons" to "Forwards a physical mouse's Back and Forward buttons to the PC. Turn off if right-click or navigation misbehaves on devices that misreport them. Applies next stream.",
        "checkbox_absolute_mouse_mode" to "Desktop-style absolute positioning instead of relative game movement. Suits remote desktop; many games need relative input. Applies next stream.",
        "seekbar_trackpad_sensitivity_x" to "Horizontal sensitivity of a physical trackpad. Larger magnitude moves farther; negative reverses. Separate from touchscreen and virtual-touchpad sensitivity. Applies next stream.",
        "seekbar_trackpad_sensitivity_y" to "Vertical sensitivity of a physical trackpad. Larger magnitude moves farther; negative reverses. Separate from touchscreen and virtual-touchpad sensitivity. Applies next stream.",
        "checkbox_trackpad_drag_drop_vibration" to "Vibrates when a hold starts drag-and-drop on supported Samsung trackpads; not for ordinary clicks. Applies next stream.",
        "seekbar_trackpad_drag_drop_threshold" to "Hold time on a supported Samsung trackpad before a drag starts. Shorter reacts sooner but may start unwanted drags. Applies next stream.",
        "checkbox_trackpad_swap_axis" to "Swaps horizontal and vertical physical trackpad input, for devices that report a rotated trackpad. Leave off if movement already follows your fingers. Applies next stream.",
        "checkbox_force_qwerty" to "Uses QWERTY key translation for stream input. Turn off for another layout with a compatible host; some legacy hosts need QWERTY. Does not affect Delos menus. Applies next stream.",
        "checkbox_right_alt_as_meta" to "Sends Right Alt as the Mac Command key, including combinations; Left Alt is unchanged. For streaming a Mac. Applies next stream.",
        "checkbox_ignore_synth_events" to "Ignores synthetic Android input events. Can stop stray keys from buggy mouse or input drivers, but also blocks soft-keyboard input. Leave off unless troubleshooting. Applies next stream.",
        "checkbox_enable_commit_text" to "Forwards text that Android keyboards produce in chunks (swipe typing, predictions, dictation). Good on the desktop; some games expect single key presses. Applies next stream.",
        "checkbox_smart_clipboard_sync" to "Uploads this device's clipboard when the stream starts or regains focus; fetches the PC clipboard when it loses focus. Needs Vibepollo or Apollo clipboard support. Manual: Send or Get clipboard in the stream menu. Applies next stream.",
        "checkbox_smart_clipboard_sync_toast" to "Shows a brief message when automatic clipboard transfer succeeds or fails. Does not enable synchronization itself. Applies next stream.",
        "checkbox_hide_clipboard_content" to "Marks clipboard content from the PC as sensitive so compatible Android versions hide its preview. Does not encrypt or disable transfer. Applies next stream.",
        "seekbar_keyboard_axi_opacity" to "Opacity of the full on-screen keyboard. Separate from virtual gamepad opacity. Applies next stream.",
        "onscreen_keyboard_autofit" to "Turns off automatic fitting of the full keyboard so the saved width, height and alignment apply. Applies next stream.",
        "seekbar_onscreen_keyboard_height" to "Height of the full on-screen keyboard when automatic fitting is off. Taller means larger keys but more of the game covered. Applies next stream.",
        "seekbar_onscreen_keyboard_width" to "Width of the full on-screen keyboard when automatic fitting is off; keep it reachable on your screen. Applies next stream.",
        "list_onscreen_keyboard_align_mode" to "Horizontal anchor of the manually sized full keyboard in the viewport. Applies next stream.",
        "checkbox_enable_sticky_modifier_key_virtual_keyboard" to "Keeps virtual Shift, Ctrl, Alt and similar modifiers active after a tap, so combinations can be entered one key at a time. Full and custom on-screen keyboards. Applies next stream.",
        "checkbox_disable_warnings" to "Hides on-screen connection-quality warnings during streaming. Does not fix the network or remove statistics. Applies next stream.",
        "checkbox_remember_zoom_pan" to "Saves the stream's zoom and pan for later sessions. When off, adjustments last only that stream. Adjust with Zoom in the stream menu.",
        "checkbox_enable_pip" to "Keeps the stream visible in Android Picture-in-Picture while multitasking. View only: no game control in the small window. Needs device and firmware support. Applies next stream.",
        "checkbox_show_onscreen_controls" to "Shows the virtual gamepad over the stream for touch play; layout and opacity are set below. The stream menu toggles it for the session. Applies next stream.",
        "checkbox_hide_osc_when_has_gamepad" to "Hides the virtual gamepad while Android detects a physical controller; it returns when the controller disconnects. Applies next stream.",
        "checkbox_vibrate_osc" to "Local vibration when pressing virtual gamepad buttons. Touch feedback, not game rumble or interface haptics. Needs a vibration motor. Applies next stream.",
        "checkbox_only_show_L3R3" to "Shows only the virtual L3 and R3 stick-click buttons instead of the full gamepad. Applies next stream.",
        "checkbox_show_guide_button" to "Adds the Guide/Home button to the virtual gamepad; its action depends on host and platform. Applies next stream.",
        "seekbar_osc_opacity" to "Opacity of the virtual gamepad and custom special-key controls. Separate from full-keyboard opacity. Applies next stream.",
        "checkbox_enable_analog_stick_new" to "Lets virtual stick centers move within their interaction area instead of staying fixed. Applies next stream.",
        "seekbar_osc_free_analog_stick_opacity" to "Visibility of the free virtual stick's background while idle. Low keeps the picture clearer; high makes the area easier to find. Applies next stream.",
        "option_reset_osc_preference" to "Restores this profile's virtual gamepad to default sizes and positions at once, replacing custom placement. Other profiles are kept.",
        "checkbox_enable_keyboard" to "Shows the custom special-key layer over the stream, using the selected layout below. Separate from the full keyboard; opacity follows the virtual gamepad. Applies next stream.",
        "checkbox_enable_clear_default_special_button" to "Hides the built-in key combinations from the stream menu's Send keys, leaving your imported commands. The normal keyboard stays. Applies next stream.",
        "checkbox_vibrate_keyboard" to "Local vibration when tapping custom on-screen keyboard buttons; not game rumble. Needs a vibration motor. Applies next stream.",
        "keyboard_axi_list" to "Chooses the active one of this profile's five special-key layouts. Applies next stream.",
        "checkbox_enable_keyboard_square" to "Draws custom special-key buttons as squares instead of circles; the keys sent are unchanged. Applies next stream.",
        "option_reset_osc" to "Restores the selected special-key layout to defaults at once, replacing its custom buttons and positions. Other layouts and profiles are kept.",
        "import_keyboard_file" to "Replaces the selected special-key layout with a file chosen in Android's picker. Export first for a backup.",
        "export_keyboard_file" to "Saves the selected special-key layout to a file via Android's picker, as a backup or for another device.",
        "checkbox_enable_touch_sensitivity" to "Extra sensitivity scaling for touchscreen game input, set by the axis sliders below. Separate from trackpad and virtual-touchpad sensitivity. Applies next stream; the stream menu toggles it live.",
        "checkbox_enable_global_touch_sensitivity" to "Applies touch sensitivity to the whole screen; when off, only to the right half, leaving the left for movement controls. Needs touchscreen sensitivity. Applies next stream.",
        "checkbox_enable_touch_sensitivity_rotation_auto" to "Recenters scaled touch coordinates that run past the screen edge so high-sensitivity drags can continue; helps camera control. Needs touchscreen sensitivity. Applies next stream.",
        "seekbar_touch_sensitivity_opacity_x" to "Horizontal sensitivity of multi-touch game input; higher turns small finger movement into larger in-game movement. Needs touchscreen sensitivity. Applies next stream.",
        "seekbar_touch_sensitivity_opacity_y" to "Vertical sensitivity of multi-touch game input; higher turns small finger movement into larger in-game movement. Needs touchscreen sensitivity. Applies next stream.",
        "seekbar_touchpad_sensitivity_opacity" to "Horizontal pointer sensitivity shared by touchscreen trackpad mode and custom virtual touchpads, which can feel different at the same value. Separate from physical trackpad sensitivity. Applies next stream.",
        "seekbar_touchpad_sensitivity_y_opacity" to "Vertical pointer sensitivity shared by touchscreen trackpad mode and custom virtual touchpads, which can feel different at the same value. Separate from physical trackpad sensitivity. Applies next stream.",
        "checkbox_enable_perf_overlay" to "Shows stream statistics: FPS, 1% low FPS, bitrate, packet loss, latency. Total latency adds measured network, host and local time; it is not button-to-screen latency. The stream menu cycles full, compact, off. Applies next stream.",
        "checkbox_enable_perf_overlay_lite" to "Shows one compact statistics line at the top center instead of the full panel (its corner is set separately). Overlay in the stream menu cycles full, compact, off. Applies next stream.",
        "video_format" to "Codec the PC encodes with; host and decoder must support it. Each codec keeps its own bitrate. Applies next stream.",
        "checkbox_unlock_fps" to "Shows frame-rate choices above the display's detected capability. Extra host frames can improve freshness in some modes but raise load; the screen's refresh rate is unchanged. Applies next stream.",
        "checkbox_reduce_refresh_rate" to "Lets Android choose a lower display refresh rate when suitable for the stream. Saves power but can slow presentation. Separate from the requested video FPS. Applies next stream.",
        "checkbox_full_range" to "Requests full-range video levels instead of limited range. Enable only if the host and display interpret them correctly; a mismatch crushes blacks and highlights. Separate from HDR. Applies next stream.",
        "checkbox_prevent_packet_loss" to "Sends a small control packet about every 20 ms to discourage Wi-Fi power saving between transfers. Separate from Android Wi-Fi locks. May help unexplained device-specific loss; cannot fix weak signal or congestion. Applies next stream.",
        "list_languages" to "Language for translated stream messages and system-facing screens. The Delos shell and its help are English only.",
        "import_special_button_file" to "Imports custom key combinations for Send keys and the special-key controls, replacing this profile's custom commands. Separate from Import keyboard layout, which imports button positions.",
        "option_help_custom_keys" to "Opens instructions for the custom-key file format and key combinations. Load the resulting file with Import Custom Keys.",
        "pref_debug_info" to "Opens device and controller diagnostics without starting a stream: Android, kernel and device details, controller IDs and sensors, a vibration test, and on-screen control preview and editing.",
        "ui_match_game_colors" to "Takes three colors from the selected game's cover for the animated background, falling back to PC artwork. Needs Material You colors off. Changes the background, not the game picture.",
    )

    private val choices = mapOf(
        "frame_pacing" to mapOf(
            "latency" to "Shows the newest frame at once, dropping older ones. Best response; may stutter if FPS and refresh differ.",
            "balanced" to "Schedules frames with the display refresh using a small bounded queue. LFR is a variant of this mode.",
            "cap-fps" to "Paced; near the display refresh it requests slightly fewer host frames so the queue does not grow.",
            "smoothness" to "Steady delivery rather than immediate presentation; can add buffering and latency.",
            "warp" to "Requests twice the selected FPS (60 → 120), shows the freshest frame. More host, decoder and network load; display refresh unchanged.",
            "warp2" to "Requests four times the selected FPS (60 → 240), shows the freshest frame. Heavier than Warp Drive; display refresh unchanged.",
            "fast-vsync" to "Nano Pacer times the newest frame to the lower of stream FPS and refresh, dropping stale ones. MediaCodec only.",
        ),
        "video_format" to mapOf(
            "auto" to "Negotiates H.264 or HEVC by decoder support; AV1 and PyroWave need explicit selection.",
            "forceav1" to "Efficient at low bitrates. Needs AV1 encode on the host and decode here; not inherently lower latency. May fall back.",
            "forceh265" to "Less bandwidth than H.264; supports 10-bit HDR. Needs host encode and device decode; may fall back.",
            "neverh265" to "Broadest compatibility, good for troubleshooting; needs more bandwidth. No HDR on this path.",
            "pyrowave" to "Vibepollo GPU wavelet codec, own Vulkan decoder. Needs a strong local link and a far higher bitrate (recalculated when resolution, FPS or HDR change).",
        ),
        "list_sgsr_quality" to mapOf(
            "standard" to "SGSR 1 Standard: edge reconstruction from the current frame only. Cheapest; no motion trails.",
            "high" to "SGSR 1 High Quality: another spatial filter on the current frame, not just stronger sharpening.",
            "temporal" to "SGSR 2 Video: estimates motion and reuses reliable detail from earlier frames. More GPU cost; text and fast changes can break it.",
            "temporal-quality" to "SGSR 2 Video Quality: adds a reactive mask, nine-tap reconstruction and better color-history clipping. More GPU cost.",
        ),
        "list_video_scale_mode" to mapOf(
            "fit" to "Whole picture at its aspect ratio; black bars if shapes differ. Nothing cropped or stretched.",
            "fill" to "Fills the screen at the picture's aspect ratio; crops when shapes differ.",
            "stretch" to "Fills the screen without cropping; shapes look wider or taller if aspect ratios differ.",
        ),
        "list_audio_config" to mapOf(
            "2" to "Two-channel stereo for handheld speakers and most headphones. Surround virtualization comes from the host or audio system.",
            "51" to "Six channels (front L/R, center, rear L/R, subwoofer). Needs a 5.1 playback path; handheld speakers stay two physical channels.",
            "71" to "Eight channels for a 7.1 system. Use Stereo if channels are missing or voices are hard to hear.",
        ),
        "analog_scrolling" to mapOf(
            "none" to "Neither stick scrolls.",
            "right" to "The right stick scrolls.",
            "left" to "The left stick scrolls.",
        ),
        "mouse_mode_list" to mapOf(
            "0" to "Forwards touch contacts for direct multi-touch; the host must support touch.",
            "1" to "Direct single-touch: touch a point in the stream to point or click there.",
            "2" to "Natural trackpad: finger movement moves the PC pointer relative to its position. Used for external displays.",
            "3" to "Game-tuned trackpad: relative movement for games that need mouse-style input.",
            "4" to "Touch is not forwarded; stylus and the stream-menu edge swipe still work.",
            "5" to "Direct single-touch with the mouse buttons swapped.",
        ),
        "list_onscreen_keyboard_align_mode" to mapOf(
            "left" to "Anchored to the left edge.",
            "center" to "Centered horizontally.",
            "right" to "Anchored to the right edge.",
        ),
    )
}
