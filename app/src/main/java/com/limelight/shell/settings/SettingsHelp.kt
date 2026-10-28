package com.limelight.shell.settings

/** English help for the shell. Keys and stored values still come from the preference catalog. */
object SettingsHelp {
    fun description(key: String, fallback: String): String = descriptions[key] ?: fallback

    fun details(spec: SettingSpec, values: Map<String, *>, choices: List<SettingChoice> = spec.choices): String {
        val value = values[spec.key] ?: spec.default
        val selected = choices.firstOrNull { it.value == value } ?: spec.choices.firstOrNull { it.value == value }
        val explanation = selected?.let { choice(spec.key, it.value) }.orEmpty()
        return listOfNotNull(
            selected?.takeIf { explanation.isNotBlank() }?.let { "${it.label}\n$explanation" },
            "The saved choice is unavailable with the current codec or device. Select a supported choice to change it."
                .takeIf { selected != null && selected !in choices },
            context(spec.key, values).takeIf { it.isNotBlank() },
            spec.description,
        ).joinToString("\n\n")
    }

    /** Explanations follow the selected value, including resolutions and rates added by the device. */
    fun choice(key: String, value: String): String = choices[key]?.get(value) ?: when (key) {
        "list_resolution" -> "Requests a ${value.replace("x", " × ")} video stream from the PC. More pixels improve fine detail but increase encoding, decoding and network demands. This is separate from the local upscaler's internal resolution."
        "list_fps" -> "$value frames per second requested from the PC. Higher rates can improve motion and response, at a higher GPU, network and battery cost. The display can only show as many distinct frames as its refresh rate allows. Warp modes multiply this request."
        "list_languages" -> if (value == "default") "Follow the Android language for translated stream messages and system-facing screens. The Delos shell currently uses English." else "Use this language for available translations of stream messages and system-facing screens. The Delos shell currently uses English."
        "keyboard_axi_list" -> "One of five separately editable special-key layouts. Switching layouts keeps the other four. Import and reset act on the selected layout for this user profile."
        else -> ""
    }

    private fun context(key: String, values: Map<String, *>): String {
        val pyro = values["video_format"] == "pyrowave"
        return when {
            key == "frame_pacing" && values["pref_low_latency_frame_balance"] == true && values[key] != "fast-vsync" ->
                "LFR is enabled: MediaCodec uses its Balanced timing variant instead of the selected presentation mode. Warp's extra host frame-rate request still applies. Turn LFR off to compare the other timing modes directly."
            key == "frame_pacing" && values[key] == "fast-vsync" -> "Fast V-Sync takes precedence over LFR. For H.264, HEVC and AV1; PyroWave has its own native presentation path."
            key in setOf("checkbox_async_decode", "checkbox_ultra_low_latency", "pref_low_latency_frame_balance") && pyro ->
                "PyroWave uses its own Vulkan decoder. This MediaCodec option does not apply to it."
            key == "list_sgsr_quality" && pyro -> "PyroWave supports SGSR 2 Video in its native renderer. Both SGSR 2 choices use that same native reconstruction; SGSR 1 choices leave PyroWave upscaling off."
            key == "seekbar_sgsr_sharpness" && pyro -> "This separate sharpness slider applies to the MediaCodec upscaler. PyroWave does not use it."
            key == "checkbox_sgsr" && pyro -> "For PyroWave, select an SGSR 2 Video mode. Native PyroWave upscaling also supports HDR and the live supersampling levels."
            else -> ""
        }
    }

    private val descriptions = mapOf(
        "list_resolution" to "The size of the video sent by the PC. Lower resolutions reduce load and bandwidth; higher resolutions preserve more detail. Your bitrate is preserved; only PyroWave recalculates it when resolution changes. Applies to the next stream.",
        "list_fps" to "How many video frames the PC sends each second. Match it to your display and what the PC can sustain. Your bitrate is preserved; only PyroWave recalculates it when the rate changes. Applies to the next stream.",
        "seekbar_bitrate_kbps" to "The requested video data rate in Mbps. Higher values reduce compression artifacts but need a stable connection. Excessive bitrate can cause stutter and packet loss. PyroWave needs much more bandwidth than H.264, HEVC or AV1. Applies to the next stream; Stream menu → Bitrate changes only the running session.",
        "seekbar_metered_bitrate_kbps" to "Video bitrate when Android marks the active network as metered, such as mobile data or some hotspots. Zero uses one quarter of the normal video bitrate. Lower values save data at the expense of image quality. Applies to the next stream.",
        "frame_pacing" to "How decoded frames reach the display: quicker delivery favors response, while paced delivery favors regular motion. The choices below explain each method. Warp Drive and Warp 2 may not work on every device. Applies to the next stream.",
        "checkbox_async_decode" to "Let MediaCodec notify Delos when decoder buffers are ready instead of repeatedly polling for them. Independent of frame pacing; available for H.264, HEVC and AV1. Try it for responsiveness, and turn it off if decoding becomes unstable. Applies to the next stream.",
        "pref_low_latency_frame_balance" to "A low-latency variant of Balanced pacing that polls the decoder more frequently and releases queued frames close to the next display refresh. It overrides other MediaCodec pacing choices except Fast V-Sync. Results depend on the device; it is separate from Ultra Low Latency. Applies to the next stream.",
        "checkbox_ultra_low_latency" to "Try additional decoder-specific low-latency settings beyond the normal decoder setup. This is a MediaCodec option, separate from frame pacing and LFR. Support depends on the chip, codec and driver; it is not a guaranteed latency reduction. Turn it off if video becomes unstable. Applies to the next stream.",
        "checkbox_use_virtual_display" to "Ask Vibepollo or Apollo to use a virtual PC display for streaming. With the host's virtual-display support configured, it can match the requested resolution and frame rate without changing your physical monitor. This is the PC's display, not a monitor attached to the Android device. Applies to the next stream.",
        "checkbox_auto_orientation" to "Use the Android device's orientation when starting the stream. This chooses its initial portrait or landscape orientation; it does not rotate the PC desktop by itself.",
        "checkbox_auto_invert_video_resolution" to "Swap the requested width and height for a portrait stream, for example 1920 × 1080 becomes 1080 × 1920. Requires Auto Orientation. The host must support the resulting resolution. Applies to the next stream.",
        "checkbox_enable_view_top_center" to "Align a stream that does not fill the screen to the top center instead of the screen center. This changes where the picture appears, not its transmitted resolution. Applies to the next stream.",
        "seekbar_resolution_scale_factor" to "Scale the resolution requested for the host's virtual display. At 150%, a 1920 × 1080 stream requests a 2880 × 1620 PC display while the transmitted video stays 1920 × 1080. Can improve detail in some games but increases PC rendering load. Requires compatible Vibepollo or Apollo virtual-display support. Separate from local supersampling. Applies to the next stream.",
        "list_video_scale_mode" to "How the received picture fits your Android screen. This affects local presentation and touch coordinates, not the resolution sent over the network. Applies to the next stream.",
        "edit_diy_w_h" to "Add a resolution to the Video resolution choices. Enter width x height, for example 1920x1080, then select it in Video resolution. The host and decoder must support it. A different aspect ratio can add black bars or require cropping.",
        "edit_diy_bitrate" to "Enter a video bitrate in Mbps, including decimal values. Saving it also updates Video bitrate and can exceed the slider's range. A higher number cannot fix a slow connection. Applies to the next stream.",
        "custom_refresh_rate" to "Add a custom FPS value to Video frame rate, then select it there. Decimal rates are allowed. The host, game, decoder and display determine what can actually be delivered and shown. Applies to the next stream.",
        "checkbox_enforce_display_mode" to "Ask Android more strongly to use the selected display mode for the stream surface. Useful on devices that otherwise ignore refresh-rate requests. Some firmware, including Chromecast 4K, may behave poorly with it. Applies to the next stream.",
        "checkbox_enable_hdr" to "Request HDR video. The game, host GPU, decoder and connected display must all support HDR. Supported paths include ten-bit HEVC, AV1 and PyroWave; the final format is negotiated with the host. It cannot turn SDR content into HDR. Applies to the next stream; changing it also recalculates PyroWave's suggested bitrate.",
        "checkbox_enable_fullexdisplay" to "Show the stream on an attached monitor or TV, with the Delos loader and stream menu there too. The configured stream resolution and FPS remain in use; Android chooses a compatible display mode. Built-in handheld controls can operate the external-screen menu. HDR requires a compatible display and connection. Applies to the next stream.",
        "checkbox_sgsr" to "Reconstruct a lower-resolution stream to the display size on this device. H.264, HEVC and AV1 offer SGSR 1 and SGSR 2 Video; PyroWave has native SGSR 2 Video. HDR is supported on compatible output paths. Uses extra GPU time and cannot restore detail missing from the source. At native resolution the live menu offers supersampling instead. Saved defaults apply to the next stream; live menu changes last for that session.",
        "list_sgsr_quality" to "Choose the reconstruction method, independently of final sharpness. SGSR 1 processes one frame; SGSR 2 Video estimates motion and combines reliable history. Moving text, scene changes and low-resolution input can affect the result. All modes add GPU work. Saved defaults apply to the next stream; the stream menu can switch modes live.",
        "seekbar_sgsr_sharpness" to "Strength of the separate final sharpening pass for the MediaCodec upscaler. Zero disables that sharpening, not SGSR reconstruction. High values can emphasize edges and compression artifacts. PyroWave does not use this control. Saved defaults apply to the next stream; live changes affect only that session.",
        "list_audio_config" to "The audio channels requested from the PC. Use Stereo for handheld speakers and most headphones. Surround needs a compatible host configuration and playback system. This is channel layout, not a volume control. Applies to the next stream.",
        "checkbox_enable_audiofx" to "Allow Android's system equalizer and other audio effects to process streamed sound. Can add audio delay; leave off when minimizing latency matters more than system effects. Applies to the next stream.",
        "checkbox_host_audio" to "Play the game's sound on the PC as well as this device. Disable it to keep the PC speakers quiet while streaming. Applies to the next stream.",
        "seekbar_deadzone" to "Ignore small stick movements near the center to reduce drift. Positive values create a dead zone; negative values compensate for an existing controller dead zone and can make centering difficult. Start near zero and adjust to your controller. Applies to the next stream.",
        "checkbox_multi_controller" to "Report gamepad connections to the PC as controllers appear or disappear. When off, Delos keeps a gamepad present even when Android has not detected one. Useful for games that need a controller at launch. Applies to the next stream.",
        "checkbox_usb_driver" to "Use the Delos USB driver for supported Xbox and Nintendo Pro controllers that Android cannot handle natively. USB access may require Android's permission prompt. Bluetooth controllers still use Android's input system. Applies to the next stream.",
        "checkbox_usb_bind_all" to "Let the Delos USB driver claim supported controllers even when Android already supports them. Requires the USB driver option. Use it to work around native-driver problems; it can change which controller features are available. Applies to the next stream.",
        "checkbox_mouse_emulation" to "Hold Start during a stream to switch the gamepad between normal game input and mouse emulation. The selected analog scrolling option controls scrolling in mouse mode. Applies to the next stream.",
        "analog_scrolling" to "Choose which stick scrolls the PC page while the gamepad is in mouse-emulation mode. Does not change stick input during normal gamepad use. Applies to the next stream.",
        "checkbox_flip_face_buttons" to "Swap A with B and X with Y for gamepad input and on-screen controls. Useful when matching Nintendo-style face-button positions. This is stream input, separate from navigating the Delos interface. Applies to the next stream.",
        "checkbox_gamepad_touchpad_as_mouse" to "Always use a connected controller's touchpad as a PC mouse, including when the emulated host controller has a touchpad of its own. Leave off to let supported hosts use native touchpad input. Applies to the next stream.",
        "checkbox_gamepad_motion_sensors" to "Allow supported hosts to receive a connected gamepad's motion sensors. Requires a compatible controller and Android version. Disable it if you do not use motion controls or want to reduce sensor traffic. Applies to the next stream.",
        "checkbox_gamepad_motion_fallback" to "Use this device's motion sensors when the connected controller or Android cannot supply controller motion. The host may see an emulated PlayStation controller. Requires device motion sensors. Applies to the next stream.",
        "checkbox_force_device_motion" to "Use this device's motion sensors even when the connected gamepad reports working sensors. Useful for handheld aiming with an external controller connected. Requires the motion fallback setting. Applies to the next stream.",
        "checkbox_enable_joyconfix" to "Enable a compatibility path for Switch Joy-Con controllers with missing or incorrectly recognized buttons. Leave off if the controllers already work correctly. Applies to the next stream.",
        "checkbox_gamepad_enable_battery_report" to "Report supported gamepad battery information to the host. Disable it as a troubleshooting step if battery polling coincides with stutter in touch, mouse or stylus input. Not all controllers expose battery data. Applies to the next stream.",
        "checkbox_enable_rumble" to "Send host rumble feedback to supported gamepads. The controller, connection and Android driver determine which vibration effects work. Device vibration is configured separately. Applies to the next stream.",
        "checkbox_vibrate_fallback" to "Use the handheld's vibration motor for game rumble when the connected gamepad cannot provide it. This is game feedback, separate from interface haptics. Requires a device vibration motor. Applies to the next stream.",
        "checkbox_enable_device_rumble" to "Use the handheld's vibration motor even when a connected controller supports rumble. Does not override controllers handled by the built-in USB driver. Requires device vibration support. Applies to the next stream.",
        "seekbar_vibrate_fallback_strength" to "Adjust how strongly this device vibrates for emulated game rumble. This does not change interface haptics or an external controller's own motor strength. Requires a device with amplitude control. Applies to the next stream.",
        "mouse_mode_list" to "How touchscreen gestures become PC input. The selected mode below explains its gestures. Stylus input remains available when touch is disabled. External-display streaming uses the handheld as a natural trackpad. Applies to the next stream; Mouse mode in the stream menu can change the running session.",
        "checkbox_remember_mouse_mode" to "Save mouse-mode changes made during a stream for later sessions. When off, stream-menu changes are temporary and the next stream starts with the saved touchscreen mode.",
        "checkbox_mouse_local_cursor" to "Show Android's local pointer for a physical mouse instead of keeping it captured and hidden. This is the pointer on this device; it does not hide the cursor drawn by the PC in the streamed image. Applies to the next stream.",
        "checkbox_multi_touch_gestures" to "In multi-touch mode, three-finger tap opens the system keyboard, four-finger tap the full keyboard and five-finger tap the stream menu. These gestures are separate from the configurable controller menu shortcut and edge swipe. Applies to the next stream.",
        "checkbox_mouse_nav_buttons" to "Forward physical mouse Back and Forward buttons to the PC. Some Android devices misreport these buttons, so disable it if right-clicking or navigation behaves incorrectly. Applies to the next stream.",
        "checkbox_absolute_mouse_mode" to "Use desktop-style absolute mouse positioning instead of relative game-style movement. Can make remote desktop work more natural, but many games require relative input. Applies to the next stream.",
        "seekbar_trackpad_sensitivity_x" to "Horizontal sensitivity for physical trackpad input. Larger magnitudes move the pointer farther; negative values reverse horizontal movement. Separate from touchscreen and virtual-touchpad sensitivity. Applies to the next stream.",
        "seekbar_trackpad_sensitivity_y" to "Vertical sensitivity for physical trackpad input. Larger magnitudes move the pointer farther; negative values reverse vertical movement. Separate from touchscreen and virtual-touchpad sensitivity. Applies to the next stream.",
        "checkbox_trackpad_drag_drop_vibration" to "Vibrate when a hold activates drag-and-drop on supported Samsung trackpads. Does not add vibration to all ordinary mouse clicks. Applies to the next stream.",
        "seekbar_trackpad_drag_drop_threshold" to "How long to hold on a supported Samsung trackpad before a drag begins. Shorter holds react sooner but may start an unwanted drag. Applies to the next stream.",
        "checkbox_trackpad_swap_axis" to "Exchange horizontal and vertical physical trackpad input. A compatibility fix for devices that report a rotated trackpad; leave off when movement already follows your fingers. Applies to the next stream.",
        "checkbox_force_qwerty" to "Use QWERTY key translation for stream input. Disable it when using another keyboard layout with a compatible host. Some legacy hosts require QWERTY. This does not change the layout of the Delos menus. Applies to the next stream.",
        "checkbox_right_alt_as_meta" to "Send Right Alt as the Mac Command key, including key combinations. Left Alt stays unchanged. Intended for streaming a Mac desktop. Applies to the next stream.",
        "checkbox_ignore_synth_events" to "Ignore synthetic Android input events. Can avoid accidental extra keys from buggy mouse or input drivers, but also prevents normal system soft-keyboard input. Leave off unless troubleshooting that issue. Applies to the next stream.",
        "checkbox_enable_commit_text" to "Forward text produced in chunks by Android keyboards, such as swipe typing, predictions and voice dictation. Useful on the desktop; some games expect individual key presses and may not accept it. Applies to the next stream.",
        "checkbox_smart_clipboard_sync" to "Upload this device's clipboard when the stream starts or regains focus; fetch the PC clipboard when the stream loses focus. Requires Vibepollo or Apollo clipboard support. For manual transfer, use Send clipboard or Get clipboard in the stream menu. Applies to the next stream.",
        "checkbox_smart_clipboard_sync_toast" to "Show a brief message when automatic clipboard transfer completes or fails. Does not enable clipboard synchronization by itself. Applies to the next stream.",
        "checkbox_hide_clipboard_content" to "Mark clipboard content received from the PC as sensitive so compatible Android versions avoid displaying its preview. This does not encrypt or disable clipboard transfer. Applies to the next stream.",
        "seekbar_keyboard_axi_opacity" to "Opacity of the full on-screen keyboard. Lower values reveal more of the game underneath; higher values make keys easier to read. Separate from the virtual gamepad opacity. Applies to the next stream.",
        "onscreen_keyboard_autofit" to "Disable automatic fitting of the full keyboard so the saved width, height and alignment can be used. Turn it off again to let the keyboard fit the viewport automatically. Applies to the next stream.",
        "seekbar_onscreen_keyboard_height" to "Height of the full on-screen keyboard when automatic fitting is disabled. More height makes keys larger but covers more of the game. Applies to the next stream.",
        "seekbar_onscreen_keyboard_width" to "Width of the full on-screen keyboard when automatic fitting is disabled. Choose a width that leaves the keyboard reachable on your screen. Applies to the next stream.",
        "list_onscreen_keyboard_align_mode" to "Where the full on-screen keyboard is anchored horizontally within the viewport. Used with its manual size settings. Applies to the next stream.",
        "checkbox_enable_sticky_modifier_key_virtual_keyboard" to "Keep virtual Shift, Ctrl, Alt and similar modifiers active after tapping them so combinations can be entered one key at a time. Applies to the full and custom on-screen keyboards. Applies to the next stream.",
        "checkbox_disable_warnings" to "Hide on-screen connection-quality warning messages during streaming. This only hides warnings; it does not fix the network or remove the performance statistics. Applies to the next stream.",
        "checkbox_remember_zoom_pan" to "Save the stream picture's zoom and pan position for future sessions. When off, those adjustments are temporary. Use Zoom in the stream menu to adjust the picture.",
        "checkbox_enable_pip" to "Allow the stream to remain visible in Android Picture-in-Picture when multitasking. It is an observer view: game control is not available inside the small window. Requires device and firmware support. Applies to the next stream.",
        "checkbox_show_onscreen_controls" to "Show the virtual gamepad over the stream for touchscreen play. Its layout, visibility rules and opacity are configured below. The stream menu can toggle it for the running session. Applies to the next stream.",
        "checkbox_hide_osc_when_has_gamepad" to "Hide the virtual gamepad when Android detects a physical controller. The saved on-screen layout remains available when that controller disconnects. Applies to the next stream.",
        "checkbox_vibrate_osc" to "Give local vibration feedback for virtual gamepad buttons. This is touch feedback, separate from game rumble and interface haptics. Requires a vibration motor. Applies to the next stream.",
        "checkbox_only_show_L3R3" to "Show only the two virtual stick-click buttons, L3 and R3, instead of the full virtual gamepad. Useful when a controller lacks convenient stick clicks. Applies to the next stream.",
        "checkbox_show_guide_button" to "Include the Guide/Home button in the virtual gamepad. Its action depends on the host and the game platform. Applies to the next stream.",
        "seekbar_osc_opacity" to "Opacity of virtual gamepad and custom special-key controls. Lower values reveal the game; higher values make buttons easier to see. Separate from full-keyboard opacity. Applies to the next stream.",
        "checkbox_enable_analog_stick_new" to "Allow virtual stick centers to move within their interaction area instead of staying at fixed positions. Can make touch control more comfortable. Applies to the next stream.",
        "seekbar_osc_free_analog_stick_opacity" to "Visibility of the virtual free-stick background when it is not moving. A low value keeps the picture clearer; a higher value makes the stick's area easier to find. Applies to the next stream.",
        "option_reset_osc_preference" to "Immediately restore the current profile's virtual gamepad layout to its default sizes and positions. Custom placement is replaced. Other user profiles are kept.",
        "checkbox_enable_keyboard" to "Show the custom special-key layer over the stream. It is separate from the full keyboard and uses the selected layout profile below. Its opacity follows the virtual gamepad. Applies to the next stream.",
        "checkbox_enable_clear_default_special_button" to "Hide the built-in key combinations from the stream menu's Send keys list, leaving your imported custom commands. Does not disable the normal keyboard. Applies to the next stream.",
        "checkbox_vibrate_keyboard" to "Vibrate locally when tapping custom on-screen keyboard buttons. This is touch feedback rather than rumble sent by the game. Requires a vibration motor. Applies to the next stream.",
        "keyboard_axi_list" to "Choose which of the current user profile's five special-key layouts is active. Each can be edited separately. Import, export and reset use the selected layout. Applies to the next stream.",
        "checkbox_enable_keyboard_square" to "Draw ordinary custom special-key buttons as squares instead of circles. Changes their appearance, not which keys they send. Applies to the next stream.",
        "option_reset_osc" to "Immediately restore the selected special-key layout to its defaults. Custom buttons and positions in that layout are replaced. Other layouts and user profiles are kept.",
        "import_keyboard_file" to "Choose a keyboard-layout file with Android's file picker. Import replaces the current profile's selected special-key layout. Export that layout first if you want a backup.",
        "export_keyboard_file" to "Save the current profile's selected special-key layout to a file using Android's file picker. Use the file as a backup or import it on another device.",
        "checkbox_enable_touch_sensitivity" to "Apply extra sensitivity scaling to touchscreen game input. The axis controls below adjust horizontal and vertical movement. Separate from physical trackpad and virtual mouse-trackpad sensitivity. Applies to the next stream; the menu can toggle it live.",
        "checkbox_enable_global_touch_sensitivity" to "Apply touchscreen sensitivity scaling across the whole screen. When off, only the right half is affected, leaving the other half suitable for movement controls. Requires touchscreen sensitivity. Applies to the next stream.",
        "checkbox_enable_touch_sensitivity_rotation_auto" to "Recenter scaled touch coordinates when they run beyond the screen boundary, so high-sensitivity dragging can continue. Can help camera movement in touch-controlled games. Requires touchscreen sensitivity. Applies to the next stream.",
        "seekbar_touch_sensitivity_opacity_x" to "Horizontal sensitivity for multi-touch game input. Higher values turn a small finger movement into a larger in-game movement. Requires touchscreen sensitivity. Applies to the next stream.",
        "seekbar_touch_sensitivity_opacity_y" to "Vertical sensitivity for multi-touch game input. Higher values turn a small finger movement into a larger in-game movement. Requires touchscreen sensitivity. Applies to the next stream.",
        "seekbar_touchpad_sensitivity_opacity" to "Horizontal pointer sensitivity shared by touchscreen trackpad mode and custom virtual touchpad controls. These two input methods can feel different at the same value. Separate from physical trackpad sensitivity. Applies to the next stream.",
        "seekbar_touchpad_sensitivity_y_opacity" to "Vertical pointer sensitivity shared by touchscreen trackpad mode and custom virtual touchpad controls. These two input methods can feel different at the same value. Separate from physical trackpad sensitivity. Applies to the next stream.",
        "checkbox_enable_perf_overlay" to "Show stream statistics, including FPS, 1% low FPS, bitrate, packet loss and latency measurements. Total latency combines measured network, host and local processing time; it is not complete button-to-screen latency. The stream menu cycles full, compact and off. Applies to the next stream.",
        "checkbox_enable_perf_overlay_lite" to "Use a single compact statistics line at the top center instead of the full panel. The full panel's corner is set separately. The stream menu's Overlay button cycles full, compact and off. Applies to the next stream.",
        "video_format" to "How the PC encodes streamed video. Select a codec below to see its compatibility and bandwidth tradeoffs. The host and decoder must both support it; the performance overlay reports the format actually in use. Selecting PyroWave calculates its recommended bitrate. Other codec selections, including leaving PyroWave, preserve the current bitrate; adjust it manually if needed. Applies to the next stream.",
        "checkbox_unlock_fps" to "Show frame-rate choices above the display's detected capability. Extra host frames can improve freshness in some modes but increase load and cannot raise the screen's physical refresh rate. Applies to the next stream.",
        "checkbox_reduce_refresh_rate" to "Allow Android to choose a lower display refresh rate when suitable for the stream. Can reduce power use, at the cost of slower presentation in some cases. Separate from the requested video FPS. Applies to the next stream.",
        "checkbox_full_range" to "Request full-range video levels instead of the normal limited range. Only enable when the host and display path interpret those levels correctly; a mismatch can crush blacks and bright details. This is separate from HDR and is not a general image-quality upgrade. Applies to the next stream.",
        "checkbox_prevent_packet_loss" to "Send a small control packet approximately every 20 ms while connected, to discourage Wi-Fi power saving between transfers. Separate from Android's Wi-Fi locks. May help device-specific unexplained packet loss; it cannot recover lost packets or fix weak signal and congestion. Applies to the next stream.",
        "list_languages" to "Language for translated stream messages and system-facing screens. The new Delos shell, settings help and menus currently use English.",
        "import_special_button_file" to "Import custom key combinations for Send keys and the special-key controls. Import replaces the current profile's custom command configuration. This is separate from importing button positions with Import keyboard layout.",
        "option_help_custom_keys" to "Open instructions for the supported custom-key file format and key combinations. Use Import Custom Keys to load the resulting file.",
        "pref_debug_info" to "Open device and controller diagnostics without starting a stream. Inspect Android, kernel and device details, controller IDs and sensors, test vibration, and preview or edit on-screen controls. No game is launched.",
        "ui_match_game_colors" to "Use three colors extracted from the selected game's cover for the animated background. Falls back to artwork from the PC when needed. Requires Material You colors to be off. This changes background colors, not the game picture.",
    )

    private val choices = mapOf(
        "frame_pacing" to mapOf(
            "latency" to "Display the newest decoded frame as soon as possible and discard older queued frames. Favors response over perfectly even motion; may stutter when stream FPS and display refresh do not align.",
            "balanced" to "Schedule decoded frames with the display refresh using a small bounded queue. A middle ground between fast response and consistent motion. LFR is a separate variant of this mode.",
            "cap-fps" to "Use paced presentation and, when the stream is close to the display refresh rate, request slightly fewer host frames to avoid a steadily growing queue. Favors regular motion with controlled latency.",
            "smoothness" to "Favor steady frame delivery instead of immediate presentation. Can add noticeable buffering and latency. Useful when even motion matters more than the fastest response.",
            "warp" to "Request twice the selected FPS from the PC, then favor the freshest available decoded frame. For example, 60 FPS requests 120 FPS. Can reduce frame age, but increases host, decoder and network load. Does not double the display refresh rate.",
            "warp2" to "Request four times the selected FPS from the PC, then favor the freshest decoded frame. For example, 60 FPS requests 240 FPS. More demanding than Warp Drive and only useful if the host and decoder can keep up. Does not quadruple the display refresh rate.",
            "fast-vsync" to "Nano Pacer times the newest available frame to a cadence based on the lower of stream FPS and display refresh, dropping stale frames while waiting. Aims to balance response and regular motion. MediaCodec only; it takes precedence over LFR.",
        ),
        "video_format" to mapOf(
            "auto" to "Let Delos and the host negotiate compatible H.264 or HEVC based on decoder support and stream requirements. AV1 and PyroWave require explicit selection. Useful as a compatibility default; check the overlay for the actual codec.",
            "forceav1" to "Request AV1, an efficient codec that can preserve detail at lower bitrates. Requires AV1 encoding on the host and AV1 decoding on this device. It is not automatically lower-latency than HEVC. Negotiation may fall back when the host cannot provide it.",
            "forceh265" to "Request HEVC (H.265). Usually needs less bandwidth than H.264 for similar quality and supports ten-bit HDR on compatible hardware. Requires host encoding and device decoding support; negotiation can fall back if unavailable.",
            "neverh265" to "Use H.264 for broad compatibility and troubleshooting. Usually needs more bandwidth than newer codecs for similar quality. This stream path does not provide HDR; host requirements can still limit the final negotiated format.",
            "pyrowave" to "Use Vibepollo's GPU wavelet codec with an independent Vulkan decoder. Needs a compatible host and device and much higher bitrate; a strong local connection is important. Supports HDR and native SGSR 2 Video. MediaCodec asynchronous decoding, LFR and Ultra Low Latency do not apply.",
        ),
        "list_sgsr_quality" to mapOf(
            "standard" to "SGSR 1 Standard reconstructs edges from the current frame without using motion history. Lower complexity and no temporal history to trail behind moving objects. Separate final sharpness is adjustable. MediaCodec only.",
            "high" to "SGSR 1 High Quality uses a different spatial filter with RGB-derived brightness and a blend of edge and smooth estimates. Processes only the current frame; it is not just a stronger sharpening setting. MediaCodec only.",
            "temporal" to "SGSR 2 Video estimates motion from decoded frames and reuses reliable detail from earlier frames. Can improve reconstruction but adds GPU cost and can struggle with disocclusion, text or fast changes. Available for MediaCodec and PyroWave.",
            "temporal-quality" to "Adds a reactive mask, nine-tap reconstruction and improved color-history clipping to the MediaCodec SGSR 2 Video path. Aims to reduce unreliable history at extra GPU cost. PyroWave uses its regular native SGSR 2 Video path for this choice.",
        ),
        "list_video_scale_mode" to mapOf(
            "fit" to "Preserve the complete picture and its aspect ratio. Black bars appear when the screen and stream have different shapes. Nothing is cropped or stretched.",
            "fill" to "Preserve the aspect ratio while filling the screen. Parts of the picture are cropped when the screen and stream have different shapes.",
            "stretch" to "Fill the whole screen without cropping. If the aspect ratios differ, circles and other shapes can look wider or taller.",
        ),
        "list_audio_config" to mapOf(
            "2" to "Two-channel stereo. Best starting point for handheld speakers and ordinary headphones. Surround virtualization, if wanted, belongs to the host or audio system.",
            "51" to "Six channels: front left/right, center, rear left/right and subwoofer. Requires a compatible 5.1 audio configuration and playback path; it does not make a handheld's speakers into six physical channels.",
            "71" to "Eight channels for a compatible 7.1 surround system. Requires host and playback support. Use Stereo if channels are missing or voices become hard to hear.",
        ),
        "analog_scrolling" to mapOf(
            "none" to "Neither stick sends scrolling while emulating a mouse.",
            "right" to "Use the right analog stick for scrolling in gamepad mouse mode.",
            "left" to "Use the left analog stick for scrolling in gamepad mouse mode.",
        ),
        "mouse_mode_list" to mapOf(
            "0" to "Forward touch contacts to a compatible host for direct multi-touch input. Useful for touch-enabled games and applications. The host must support the touch protocol.",
            "1" to "Direct single-touch mouse input: touch a position in the stream to point or click there. Useful for desktop controls laid out on screen.",
            "2" to "Natural trackpad behavior: finger movement moves the PC pointer relative to its current position, so you do not need to touch the target itself. Used for external-display control.",
            "3" to "Trackpad behavior tuned for games rather than direct touch positioning. Relative pointer movement can suit games that need mouse-style input.",
            "4" to "Do not forward ordinary touchscreen input to the PC. Stylus input remains available; the configured edge swipe can still open the stream menu.",
            "5" to "Direct single-touch input with the mouse buttons exchanged. Useful when the application's primary touch action should behave as the other mouse button.",
        ),
        "list_onscreen_keyboard_align_mode" to mapOf(
            "left" to "Anchor the manually sized full keyboard to the left side of the viewport.",
            "center" to "Center the manually sized full keyboard horizontally.",
            "right" to "Anchor the manually sized full keyboard to the right side of the viewport.",
        ),
    )
}
