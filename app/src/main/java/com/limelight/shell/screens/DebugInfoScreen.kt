package com.limelight.shell.screens

import android.content.Context
import android.hardware.Sensor
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.limelight.BuildConfig
import com.limelight.diag.DiagExport
import com.limelight.diag.DiagLog
import com.limelight.diag.DiagnosticReport
import com.limelight.shell.LocalShell
import com.limelight.shell.Screen
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.rememberMarginBringIntoView
import com.limelight.shell.ui.rememberFocusedListNavigation
import com.limelight.shell.ui.focusedListBoundary
import com.limelight.shell.ui.focusedListRow
import com.limelight.shell.ui.RowValue
import com.limelight.shell.ui.SettingRow
import com.limelight.shell.ui.SettingRowList
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.icon
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.fadeEdges
import com.limelight.shell.ui.focusEffects
import com.limelight.shell.ui.glass
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx
import com.limelight.utils.DeviceUtils
import com.limelight.utils.DeviceVibrator
import com.limelight.utils.RumbleCapabilities
import com.limelight.utils.RumblePlayback
import com.limelight.utils.RumblePreferences
import com.limelight.utils.RumblePresets
import com.limelight.utils.RumbleTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** A row of the debug screen; [details] fill the panel on the right while it is focused. */
private data class DebugRow(
    val key: String,
    val section: String,
    val icon: String,
    val label: String,
    val value: RowValue,
    val description: String,
    val details: String? = null,
)

/**
 * Debug info (Settings → About → Debug info), in the style of the settings: the device (Android, kernel,
 * model, vibration motor), the diagnostic log (a preview of exactly what a problem report contains, Share, Copy
 * summary and Clear; docs/DIAGNOSTICS.md), a vibration test with its strength, and every connected controller with its
 * ids, motion sensors and vibration; the panel on the right shows the focused row's details. Controllers
 * are listed again whenever one connects or disconnects.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DebugInfoScreen() {
    val context = LocalContext.current
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val shell = LocalShell.current
    val feedback = LocalFeedback.current
    val device = remember { DeviceVibrator.get(context) }
    var strength by remember { mutableIntStateOf(220) }
    var deviceContinuous by remember { mutableStateOf(false) }
    var padsContinuous by remember { mutableStateOf(false) }
    var devices by remember { mutableIntStateOf(0) }
    val pads = remember(devices) { gamepads() }
    val preferences = remember(context) { ProfileStore.currentSettings(context) }
    var profile by remember(preferences) { mutableStateOf(RumblePreferences.readProfile(preferences)) }
    var translatorEnabled by remember(preferences) { mutableStateOf(RumblePreferences.isEnabled(preferences)) }
    var activePreset by remember(preferences) { mutableStateOf(RumblePreferences.activePreset(preferences)) }
    var advancedExpanded by remember { mutableStateOf(false) }
    val playbackState = remember { mutableStateOf<RumblePlayback?>(null) }
    var playback by playbackState
    val scope = rememberCoroutineScope()
    var patternJob by remember { mutableStateOf<Job?>(null) }
    var patternName by remember { mutableStateOf<String?>(null) }
    var patternRun by remember { mutableIntStateOf(0) }
    var amplitude by remember { mutableIntStateOf(0) }
    val capabilities = remember { RumbleCapabilities.get(context) }
    var problem by remember { mutableStateOf<String?>(null) }
    // The diagnostic report exactly as it would be shared now (built off the main thread); null while it is being built
    var report by remember { mutableStateOf<DiagnosticReport?>(null) }
    var reportRun by remember { mutableIntStateOf(0) }
    LaunchedEffect(reportRun) { report = DiagExport.report(context) }

    fun shareLog() {
        scope.launch {
            val ready = report ?: DiagExport.report(context)
            val uri = DiagExport.writeForSharing(context, ready)
            runCatching { context.startActivity(DiagExport.shareIntent(uri)) }
                .onFailure { shell.showToast("No app can share the log", Symbols.ERROR) }
        }
    }

    fun copySummary() {
        scope.launch {
            DiagExport.copyToClipboard(context, DiagExport.summary(context))
            shell.showToast("Diagnostic summary copied", Symbols.CHECK_CIRCLE)
        }
    }

    fun clearLogs() {
        scope.launch {
            withContext(Dispatchers.IO) {
                DiagLog.clear()
                DiagExport.clearShared(context)
            }
            report = null
            reportRun++
            shell.showToast("Diagnostic logs cleared", Symbols.CHECK_CIRCLE)
        }
    }

    fun stopLab() {
        patternRun++
        patternJob?.cancel()
        patternJob = null
        patternName = null
        playback?.stop()
        playback = null
        amplitude = 0
    }

    fun stopAll() {
        stopLab()
        deviceContinuous = false
        padsContinuous = false
        device?.cancel()
        gamepads().forEach { DeviceVibrator.get(it)?.cancel() }
    }

    fun updateProfile(edited: RumbleTranslator.Profile) {
        stopLab()
        // An advanced edit keeps the technique and pulse timings of the selected preset
        val updated = edited.withTechniqueOf(profile)
        profile = updated
        activePreset = null
        RumblePreferences.saveCustom(preferences, updated, translatorEnabled)
    }

    fun playPattern(pattern: RumblePattern, selectedProfile: RumbleTranslator.Profile) {
        stopAll()
        val vibrator = device?.takeIf { it.hasVibrator() }
        if (vibrator == null) {
            shell.showToast("This device has no vibration motor", Symbols.INFO)
            return
        }
        val run = patternRun
        val runPlayback = RumblePlayback(selectedProfile)
        playback = runPlayback
        patternName = pattern.label
        patternJob = scope.launch {
            try {
                playRumblePattern(pattern, runPlayback, vibrator)
            } finally {
                // A cancelled older pattern must never stop its replacement.
                if (patternRun == run) {
                    runPlayback.stop()
                    playback = null
                    patternName = null
                    amplitude = 0
                    patternJob = null
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { playbackState.value?.stop() }
    }
    LaunchedEffect(playback) {
        val activePlayback = playback
        if (activePlayback != null) {
            while (isActive) {
                amplitude = activePlayback.lastAmplitude
                problem = activePlayback.problem
                delay(16)
            }
        }
    }

    // Connected and disconnected controllers show up at once
    DisposableEffect(Unit) {
        val input = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { devices++ }
            override fun onInputDeviceRemoved(deviceId: Int) { devices++ }
            override fun onInputDeviceChanged(deviceId: Int) { devices++ }
        }
        input.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        onDispose {
            input.unregisterInputDeviceListener(listener)
            device?.cancel()
            gamepads().forEach { DeviceVibrator.get(it)?.cancel() }
        }
    }
    // Continuous vibration follows the strength while it runs
    LaunchedEffect(deviceContinuous, strength) { if (deviceContinuous) device?.let { rumble(it, strength) } }
    LaunchedEffect(padsContinuous, strength, pads) {
        if (padsContinuous) pads.mapNotNull { DeviceVibrator.get(it)?.takeIf { v -> v.hasVibrator() } }.forEach { rumble(it, strength) }
    }

    val hasMotor = device?.hasVibrator() == true
    val presetName = activePreset?.label ?: "Custom"
    val modeName = "$presetName · translator ${if (translatorEnabled) "on" else "off"}"
    val rows = buildList {
        add(DebugRow("android", "Device", Symbols.ANDROID_LOGO, "Android", RowValue.Info("${DeviceUtils.getSDKVersionName()} · API ${Build.VERSION.SDK_INT}"),
            "The Android version of this device and its API level."))
        add(DebugRow("kernel", "Device", Symbols.MEMORY, "Kernel", RowValue.Info(System.getProperty("os.version").orEmpty().substringBefore('-')),
            "The Linux kernel version.", System.getProperty("os.version")))
        add(DebugRow("model", "Device", Symbols.DEVICE_MODEL, "Device", RowValue.Info("${DeviceUtils.getManufacturer()} ${DeviceUtils.getModel()}"),
            "The manufacturer and model.", "Board: ${Build.BOARD}\nHardware: ${Build.HARDWARE}\nSoC: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "unknown"}\nBuild: ${Build.DISPLAY}"))
        add(DebugRow("motor", "Device", Symbols.VIBRATION, "Vibration motor", RowValue.Info(if (hasMotor) "Yes" else "No"),
            if (hasMotor) "This device can vibrate." else "This device has no vibration motor."))
        // What a problem report would contain; the panel on the right previews exactly that text
        val logValue = when {
            report == null -> "Reading…"
            !DiagLog.enabled && report?.logLines == 0 -> "Off"
            else -> report!!.sizeLabel()
        }
        add(DebugRow("diag_log", "Diagnostics", Symbols.DIAGNOSTIC_LOGS, "Diagnostic log", RowValue.Info(logValue),
            "Events and errors Delos kept on this device, with addresses and names removed. This is what Share sends.",
            report?.preview() ?: "Reading the log…"))
        add(DebugRow("diag_share", "Diagnostics", Symbols.SHARE_LOG, "Share diagnostic log", RowValue.Action { shareLog() },
            "Opens Android's share sheet with the text shown in the preview. Nothing is sent until you pick an app."))
        add(DebugRow("diag_copy", "Diagnostics", Symbols.COPY_SUMMARY, "Copy diagnostic summary", RowValue.Action { copySummary() },
            "Copies the device block and the last 100 log lines, ready to paste into an issue. The same names and addresses are removed."))
        add(DebugRow("diag_clear", "Diagnostics", Symbols.DELETE, "Clear diagnostic logs", RowValue.Action { clearLogs() },
            "Deletes the log on this device. New events are logged again unless Keep diagnostic logs is off (Settings → About)."))
        // Debug builds only (the row is never added in a release build): the way to see the crash capture and the report card work
        if (BuildConfig.DEBUG) {
            add(DebugRow("diag_test_crash", "Diagnostics", Symbols.TEST_CRASH, "Trigger test crash", RowValue.Action {
                throw IllegalStateException("Test crash from Debug info")
            }, "Debug builds only. Crashes Delos on purpose; the next start offers the problem report, once."))
        }
        // Why an experimental preset is unavailable
        add(DebugRow("cap_vibrators", "Device haptic capabilities", Symbols.VIBRATORS, "Vibrators",
            RowValue.Info(if (capabilities.vibratorCount == 1) "1 (single)" else "${capabilities.vibratorCount}"),
            "How many vibrators the system reports. One means every preset drives the same motor."))
        add(DebugRow("cap_amplitude", "Device haptic capabilities", Symbols.AMPLITUDE_SUPPORT, "Amplitude control",
            RowValue.Info(if (capabilities.amplitudeControl) "Yes" else "No"), "Whether the vibrator can change its strength."))
        add(DebugRow("cap_problem", "Device haptic capabilities", Symbols.WARNING, "Last problem",
            RowValue.Info(problem ?: "None"),
            "Shows when the last played preset could not use its technique and played the plain amplitude path instead, and why."))
        add(DebugRow("vibrate_once", "Vibration test", Symbols.DEVICE_VIBRATION, "Vibrate once", RowValue.Action {
            stopAll()
            device?.let { DeviceVibrator.vibrateOnce(it, 1000) }
        }, "One second at full strength.").let { if (hasMotor) it else it.copy(description = "This device has no vibration motor.") })
        add(DebugRow("vibrate_continuous", "Vibration test", Symbols.CONTINUOUS, "Continuous vibration", RowValue.Toggle(deviceContinuous) {
            stopAll()
            deviceContinuous = it
        },
            "Keeps vibrating at the strength below until it is turned off or you leave this screen."))
        add(DebugRow("strength", "Vibration test", Symbols.STRENGTH, "Strength", RowValue.Slider(strength, 5, 255, 5, "") { strength = it },
            "The strength of continuous vibration, 1–255, for the device and the controllers."))
        add(DebugRow("rumble_preset", "Rumble laboratory", Symbols.PRESET, "Current preset", RowValue.Info(presetName),
            "Select a preset below to save it and immediately play the Test sample.",
            "Mode: $modeName\nAdvanced edits are saved as Custom, even if the values match a preset."))
        RumblePresets.values().forEach { preset ->
            val unavailable = capabilities.unavailableReason(preset.profile.technique)
            if (unavailable != null) {
                add(DebugRow("rumble_preset_${preset.name}", "Rumble laboratory", preset.icon(), preset.label,
                    RowValue.Info("Problem"), "${preset.description} Problem: $unavailable.", unavailable))
                return@forEach
            }
            add(DebugRow("rumble_preset_${preset.name}", "Rumble laboratory", preset.icon(), preset.label,
                RowValue.Text(if (activePreset == preset) "Active" else "Select") {
                    RumblePreferences.applyPreset(preferences, preset)
                    profile = preset.profile
                    translatorEnabled = preset.enabled
                    activePreset = preset
                    // Pass the selection explicitly: this click still owns the previous composition's callbacks.
                    playPattern(RumblePattern.TEST, if (preset.enabled) preset.profile else RumbleTranslator.ORIGINAL)
                }, if (activePreset == preset && problem != null) "${preset.description} Problem: $problem." else preset.description))
        }
        add(DebugRow("rumble_amplitude", "Rumble laboratory", Symbols.LIVE_AMPLITUDE, "Live amplitude · $presetName", RowValue.Info("$amplitude / 255"),
            "Live computed device amplitude, including the start kick and smoothing as they settle.",
            "Mode: $modeName\nPattern: ${patternName ?: "Stopped"}\nBoth host motors receive the labelled input percentage; this is not the final output percentage."))
        add(DebugRow("rumble_stop", "Rumble laboratory", Symbols.STOP, "Stop vibration", RowValue.Action { stopAll() },
            "Stop the laboratory pattern and all continuous device/controller vibration tests."))
        add(DebugRow("rumble_test", "Rumble laboratory", Symbols.REPLAY, "Replay Test sample", RowValue.Action {
            playPattern(RumblePattern.TEST, if (translatorEnabled) profile else RumbleTranslator.ORIGINAL)
        }, RumblePattern.TEST.description))
        RumblePattern.entries.filter { it != RumblePattern.TEST }.forEach { pattern ->
            add(DebugRow("rumble_pattern_${pattern.name}", "Rumble patterns", pattern.icon, pattern.label,
                RowValue.Action { playPattern(pattern, if (translatorEnabled) profile else RumbleTranslator.ORIGINAL) }, pattern.description,
                "Live amplitude: $amplitude / 255\nMode: $modeName\nPlaying: ${patternName ?: "Stopped"}\nInputs are host motor percentages, not final device output. Select another pattern to replace this one, or use Stop vibration."))
        }
        add(DebugRow("rumble_advanced", "Rumble advanced", Symbols.ADVANCED, "Advanced", RowValue.Toggle(advancedExpanded) {
            advancedExpanded = it
        }, "Show manual controls. Editing a value marks the preset as Custom and stops the current pattern."))
        if (advancedExpanded) {
            add(DebugRow("rumble_enabled", "Rumble advanced", Symbols.TRANSLATOR, "Enable rumble translator", RowValue.Toggle(translatorEnabled) {
                stopLab()
                translatorEnabled = it
                activePreset = null
                RumblePreferences.saveCustom(preferences, profile, it)
            }, "Off uses Original translation. On applies these parameters to streaming rumble and lab patterns."))
            add(DebugRow("rumble_gain", "Rumble advanced", Symbols.GAIN, "Gain", RowValue.Slider((profile.gain * 100).roundToInt(), 50, 300, 1, "%") {
                updateProfile(RumbleTranslator.Profile(it / 100f, profile.curve, profile.minAmplitude, profile.kickMs, profile.lowWeight, profile.highWeight, profile.smoothing))
            }, "Overall rumble multiplier, 50–300%. Changing a parameter stops the current pattern."))
            add(DebugRow("rumble_curve", "Rumble advanced", Symbols.CURVE, "Curve", RowValue.Slider((profile.curve * 100).roundToInt(), 50, 300, 1, "%") {
                updateProfile(RumbleTranslator.Profile(profile.gain, it / 100f, profile.minAmplitude, profile.kickMs, profile.lowWeight, profile.highWeight, profile.smoothing))
            }, "Response exponent: below 100% boosts weak rumble; above 100% softens it."))
            add(DebugRow("rumble_min", "Rumble advanced", Symbols.MIN_AMPLITUDE, "Minimum amplitude", RowValue.Slider(profile.minAmplitude, 0, 255, 1, "") {
                updateProfile(RumbleTranslator.Profile(profile.gain, profile.curve, it, profile.kickMs, profile.lowWeight, profile.highWeight, profile.smoothing))
            }, "Minimum output for a nonzero host request. Zero input remains silent."))
            add(DebugRow("rumble_kick", "Rumble advanced", Symbols.PRESET_KICK, "Start kick", RowValue.Slider(profile.kickMs, 0, 120, 1, " ms") {
                updateProfile(RumbleTranslator.Profile(profile.gain, profile.curve, profile.minAmplitude, it, profile.lowWeight, profile.highWeight, profile.smoothing))
            }, "Start from silence with a full-strength kick, then settle to the translated amplitude."))
            add(DebugRow("rumble_low", "Rumble advanced", Symbols.LOW_WEIGHT, "Low motor weight", RowValue.Slider((profile.lowWeight * 100).roundToInt(), 0, 100, 1, "%") {
                updateProfile(RumbleTranslator.Profile(profile.gain, profile.curve, profile.minAmplitude, profile.kickMs, it / 100f, profile.highWeight, profile.smoothing))
            }, "Low-frequency host motor contribution to the single device vibrator. Original: 80%."))
            add(DebugRow("rumble_high", "Rumble advanced", Symbols.HIGH_WEIGHT, "High motor weight", RowValue.Slider((profile.highWeight * 100).roundToInt(), 0, 100, 1, "%") {
                updateProfile(RumbleTranslator.Profile(profile.gain, profile.curve, profile.minAmplitude, profile.kickMs, profile.lowWeight, it / 100f, profile.smoothing))
            }, "High-frequency host motor contribution to the single device vibrator. Original: 33%."))
            add(DebugRow("rumble_smoothing", "Rumble advanced", Symbols.SMOOTHING, "Smoothing", RowValue.Slider((profile.smoothing * 100).roundToInt(), 0, 100, 1, "%") {
                updateProfile(RumbleTranslator.Profile(profile.gain, profile.curve, profile.minAmplitude, profile.kickMs, profile.lowWeight, profile.highWeight, it / 100f))
            }, "Limit rapid amplitude changes. Zero responds immediately; higher values settle more slowly."))
            add(DebugRow("rumble_reset", "Rumble advanced", Symbols.RESET, "Reset parameters to Original", RowValue.Action {
                updateProfile(RumbleTranslator.ORIGINAL)
            }, "Restore Original parameters as Custom. The enable switch is unchanged."))
        }
        add(DebugRow("osc_preview", "On-screen controls", Symbols.JOYSTICK, "Preview on-screen controls", RowValue.Action { shell.navigate(Screen.OscPreview) },
            "Your on-screen controls without a stream: try every button, the D-pad and the sticks, nothing is sent. The gear in the corner edits the layout, as in a stream."))
        if (pads.isEmpty()) {
            add(DebugRow("no_pads", "Controllers", Symbols.SPORTS_ESPORTS, "No controller connected", RowValue.Info(""),
                "Connect a controller and it shows up here at once. The built-in controls of a handheld count as one."))
        } else {
            pads.forEach { pad ->
                val vibrator = DeviceVibrator.get(pad)
                val vibrates = vibrator?.hasVibrator() == true
                val sensors = sensors(pad)
                val ids = String.format(Locale.ROOT, "%04x:%04x", pad.vendorId, pad.productId)
                add(DebugRow("pad_${pad.id}", "Controllers", Symbols.SPORTS_ESPORTS, pad.name, RowValue.Text(ids) {
                    stopAll()
                    if (vibrates) DeviceVibrator.vibrateOnce(vibrator, 1000) else shell.showToast("${pad.name} cannot vibrate", Symbols.INFO)
                },
                    "Vendor and product id $ids (${pad.vendorId}_${pad.productId}). Motion sensors: $sensors. Vibration: ${if (vibrates) "supported, select to test" else "not supported"}.",
                    pad.toString()))
            }
            add(DebugRow("pads_continuous", "Controllers", Symbols.PADS_CONTINUOUS, "Continuous vibration on controllers", RowValue.Toggle(padsContinuous) {
                stopAll()
                padsContinuous = it
            },
                "Every controller that can vibrate keeps vibrating at the strength above."))
        }
    }

    var focusedKey by remember { mutableStateOf<String?>(null) }
    val focused = rows.firstOrNull { it.key == focusedKey }
    val navigation = rememberFocusedListNavigation(rows.map { it.key })
    val window = LocalWindowInfo.current
    LaunchedEffect(Unit) {
        snapshotFlow { window.isWindowFocused }.first { it }
        feedback.silenceNextFocus()
        navigation.requester(rows.first().key).requestFocus()
    }
    val k = stage.dpPerPx * LocalDensity.current.density
    val listState = rememberScrollState()
    val bringIntoView = rememberMarginBringIntoView(listState, before = 72f * k, after = 110f * k, stagePx = k)

    Box(Modifier.fillMaxSize()) {
        BasicText("Debug info", style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 46.tpx),
            modifier = Modifier.offset(150.px, 124.px))
        Row(Modifier.fillMaxSize().padding(start = 150.px, end = 150.px, top = 230.px, bottom = 110.px), horizontalArrangement = Arrangement.spacedBy(56.px)) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView, com.limelight.shell.ui.LocalEntranceStagger provides com.limelight.shell.ui.rememberEntranceStagger()) {
                // One ring glides between the rows; the host carries the list's weight and faded edges, so the ring fades with the rows
                SettingRowList(Modifier.weight(1f).fadeEdges(stage, top = 14f, bottom = 90f)) {
                    Column(
                        Modifier.fillMaxSize().focusedListBoundary(navigation).verticalScroll(listState)
                            .padding(start = 18.px, end = 18.px, top = 14.px, bottom = 140.px),
                        verticalArrangement = Arrangement.spacedBy(10.px),
                    ) {
                        var previous = ""
                        rows.forEach { row ->
                            if (row.section != previous) {
                                val section = row.section
                                key("section-$section") {
                                    BasicText(section.uppercase(Locale.ROOT), style = TextStyle(color = colors.text3, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 16.tpx, letterSpacing = .08.em),
                                        modifier = Modifier.padding(start = 26.px, top = 10.px, bottom = 2.px))
                                }
                                previous = section
                            }
                            key(row.key) {
                                SettingRow(row.icon, row.label, row.value, Modifier.focusedListRow(navigation, row.key),
                                    description = row.description.takeIf { it.length <= 100 }, onFocused = { focusedKey = row.key })
                            }
                        }
                    }
                }
            }
            DetailsPanel(focused, Modifier.width(520.px))
        }
    }
}

/** The focused row's description and details, scrolled with up / down once focused (like the settings). */
@Composable
private fun DetailsPanel(row: DebugRow?, modifier: Modifier) {
    val colors = LocalDelosColors.current
    val shape = RoundedCornerShape(34.px)
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val k = LocalStage.current.dpPerPx * LocalDensity.current.density
    LaunchedEffect(row?.key) { scroll.scrollTo(0) }
    Column(
        modifier.onPreviewKeyEvent { event ->
            val direction = when (event.key) { Key.DirectionDown -> 1; Key.DirectionUp -> -1; else -> 0 }
            val canScroll = if (direction > 0) scroll.canScrollForward else scroll.canScrollBackward
            if (direction != 0 && canScroll) {
                if (event.type == KeyEventType.KeyDown) scope.launch { scroll.animateScrollBy(direction * 130f * k) }
                true
            } else false
        }.focusEffects(onClick = {}, corner = 34f, focusScale = 1f, clickCue = null, pressScale = 1f)
            .clip(shape).glass(colors.surface).border(1.px, colors.line, shape).verticalScroll(scroll).padding(44.px),
        verticalArrangement = Arrangement.spacedBy(18.px),
    ) {
        Box(Modifier.size(120.px).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
            Symbol(row?.icon ?: Symbols.INFO, 64.tpx, colors.onAccent, filled = true)
        }
        BasicText(row?.label ?: "Debug info", style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 30.tpx), modifier = Modifier.padding(top = 8.px))
        BasicText(row?.description ?: "Information about this device and its controllers, the diagnostic log and a vibration test.",
            style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 22.tpx, lineHeight = 1.45.em))
        row?.details?.let {
            BasicText(it, style = TextStyle(color = colors.text3, fontFamily = FontFamily.Monospace, fontSize = 16.tpx, lineHeight = 1.4.em))
        }
    }
}

/** Connected gamepads and joysticks with two stick axes, like the classic debug screen counted them. */
private fun gamepads(): List<InputDevice> = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter { dev ->
    val sources = dev.sources
    val pad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    pad && axis(dev, MotionEvent.AXIS_X) && axis(dev, MotionEvent.AXIS_Y)
}

private fun axis(dev: InputDevice, axis: Int) =
    dev.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) != null || dev.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD) != null

private fun sensors(dev: InputDevice): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "unknown below Android 12"
    val found = buildList {
        if (dev.sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) add("accelerometer")
        if (dev.sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) add("gyroscope")
    }
    return found.joinToString(", ").ifEmpty { "none reported" }
}

private fun rumble(vibrator: Vibrator, strength: Int) {
    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(1000), intArrayOf(strength.coerceIn(1, 255)), 0))
}

/** Fixed host-motor inputs; the shared playback path owns kick and smoothing timing. */
private enum class RumblePattern(val icon: String, val label: String, val description: String) {
    TEST(Symbols.REPLAY, "Test", "A short tap, one second of steady rumble, a rising ramp and a decaying explosion."),
    STEADY_25(Symbols.PATTERN_STEADY_25, "Steady 25% · quiet engine", "Two seconds of low engine rumble, both host motors at 25%."),
    STEADY_50(Symbols.PATTERN_STEADY_50, "Steady 50% · cruising engine", "Two seconds of medium engine rumble, both host motors at 50%."),
    STEADY_75(Symbols.PATTERN_STEADY_75, "Steady 75% · rough terrain", "Two seconds of strong terrain rumble, both host motors at 75%."),
    STEADY_100(Symbols.PATTERN_STEADY_100, "Steady 100% · maximum force", "Two seconds of maximum host rumble, both host motors at 100%."),
    RAMP(Symbols.PATTERN_RAMP, "Ramp up · acceleration", "Both host motors rise from silence to full input over two seconds."),
    TAP_30(Symbols.PATTERN_TAP_30, "30 ms taps · light impacts", "Four full-input 30 ms taps separated by 250 ms of silence."),
    TAP_60(Symbols.PATTERN_TAP_60, "60 ms taps · weapon fire", "Four full-input 60 ms taps separated by 250 ms of silence."),
    TAP_100(Symbols.PATTERN_TAP_100, "100 ms taps · heavy impacts", "Four full-input 100 ms taps separated by 250 ms of silence."),
    HEARTBEAT(Symbols.PATTERN_HEARTBEAT, "Heartbeat · paired pulses", "Four pairs of 70 ms / 100 ms full-input pulses, like a heartbeat."),
    EXPLOSION(Symbols.PATTERN_EXPLOSION, "Explosion · decaying blast", "A full-input blast that decays to silence over two seconds."),
    FLUTTER(Symbols.PATTERN_FLUTTER, "Flutter · rapid machinery", "Twenty full-input 20 ms pulses, each followed by 40 ms of silence."),
}

private suspend fun playRumblePattern(pattern: RumblePattern, playback: RumblePlayback, vibrator: Vibrator) {
    suspend fun hold(percent: Int, milliseconds: Long) {
        val raw = (percent * 65535 / 100).toShort()
        playback.single(vibrator, raw, raw)
        delay(milliseconds)
    }
    when (pattern) {
        RumblePattern.TEST -> {
            hold(100, 60)
            hold(0, 180)
            hold(50, 1000)
            hold(0, 180)
            for (percent in 0..100 step 5) hold(percent, 25)
            hold(0, 120)
            for (percent in 100 downTo 0 step 5) hold(percent, 35)
        }
        RumblePattern.STEADY_25 -> hold(25, 2000)
        RumblePattern.STEADY_50 -> hold(50, 2000)
        RumblePattern.STEADY_75 -> hold(75, 2000)
        RumblePattern.STEADY_100 -> hold(100, 2000)
        RumblePattern.RAMP -> for (percent in 0..100 step 2) hold(percent, 40)
        RumblePattern.TAP_30, RumblePattern.TAP_60, RumblePattern.TAP_100 -> {
            val duration = when (pattern) {
                RumblePattern.TAP_30 -> 30L
                RumblePattern.TAP_60 -> 60L
                else -> 100L
            }
            repeat(4) {
                hold(100, duration)
                hold(0, 250)
            }
        }
        RumblePattern.HEARTBEAT -> repeat(4) {
            hold(100, 70)
            hold(0, 100)
            hold(100, 100)
            hold(0, 500)
        }
        RumblePattern.EXPLOSION -> for (percent in 100 downTo 0 step 2) hold(percent, 40)
        RumblePattern.FLUTTER -> repeat(20) {
            hold(100, 20)
            hold(0, 40)
        }
    }
}
