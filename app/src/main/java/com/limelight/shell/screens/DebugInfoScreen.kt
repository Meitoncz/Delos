package com.limelight.shell.screens

import android.content.Context
import android.content.res.Resources
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.annotation.StringRes
import com.limelight.BuildConfig
import com.limelight.R
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
import com.limelight.shell.ui.description
import com.limelight.shell.ui.label
import com.limelight.shell.ui.text
import com.limelight.shell.ui.textResources
import com.limelight.shell.ui.tpx
import com.limelight.utils.DeviceUtils
import com.limelight.utils.DeviceVibrator
import com.limelight.utils.RumbleCapabilities
import com.limelight.utils.RumblePlayback
import com.limelight.utils.RumblePreferences
import com.limelight.utils.RumblePresets
import com.limelight.utils.RumbleProblem
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

/** The groups of the debug screen, in the order their rows appear. */
private enum class DebugSection(@StringRes val title: Int) {
    DEVICE(R.string.debug_section_device),
    DIAGNOSTICS(R.string.debug_section_diagnostics),
    HAPTICS(R.string.debug_section_haptics),
    VIBRATION_TEST(R.string.debug_section_vibration_test),
    RUMBLE_LAB(R.string.debug_section_rumble_lab),
    RUMBLE_PATTERNS(R.string.debug_section_rumble_patterns),
    RUMBLE_ADVANCED(R.string.debug_section_rumble_advanced),
    ON_SCREEN_CONTROLS(R.string.debug_section_osc),
    CONTROLLERS(R.string.debug_section_controllers),
}

/** A row of the debug screen; [details] fill the panel on the right while it is focused. */
private data class DebugRow(
    val key: String,
    val section: DebugSection,
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
    val res = textResources()
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
    var playing by remember { mutableStateOf<RumblePattern?>(null) }
    var patternRun by remember { mutableIntStateOf(0) }
    var amplitude by remember { mutableIntStateOf(0) }
    val capabilities = remember { RumbleCapabilities.get(context) }
    var problem by remember { mutableStateOf<RumbleProblem?>(null) }
    // The diagnostic report exactly as it would be shared now (built off the main thread); null while it is being built
    var report by remember { mutableStateOf<DiagnosticReport?>(null) }
    var reportRun by remember { mutableIntStateOf(0) }
    LaunchedEffect(reportRun) { report = DiagExport.report(context) }

    fun shareLog() {
        scope.launch {
            val ready = report ?: DiagExport.report(context)
            val uri = DiagExport.writeForSharing(context, ready)
            runCatching { context.startActivity(DiagExport.shareIntent(uri, res.getString(R.string.debug_share_label))) }
                .onFailure { shell.showToast(res.getString(R.string.debug_share_no_app_toast), Symbols.ERROR) }
        }
    }

    fun copySummary() {
        scope.launch {
            DiagExport.copyToClipboard(context, DiagExport.summary(context))
            shell.showToast(res.getString(R.string.debug_summary_copied_toast), Symbols.CHECK_CIRCLE)
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
            shell.showToast(res.getString(R.string.debug_logs_cleared_toast), Symbols.CHECK_CIRCLE)
        }
    }

    fun stopLab() {
        patternRun++
        patternJob?.cancel()
        patternJob = null
        playing = null
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
            shell.showToast(res.getString(R.string.debug_no_motor_toast), Symbols.INFO)
            return
        }
        val run = patternRun
        val runPlayback = RumblePlayback(selectedProfile)
        playback = runPlayback
        playing = pattern
        patternJob = scope.launch {
            try {
                playRumblePattern(pattern, runPlayback, vibrator)
            } finally {
                // A cancelled older pattern must never stop its replacement.
                if (patternRun == run) {
                    runPlayback.stop()
                    playback = null
                    playing = null
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
    val presetName = activePreset?.label(res) ?: res.getString(R.string.debug_preset_custom)
    val problemText = problem?.text(res)
    val modeName = res.getString(if (translatorEnabled) R.string.debug_mode_translator_on else R.string.debug_mode_translator_off, presetName)
    val stopped = res.getString(R.string.debug_stopped)
    val playingName = playing?.let { res.getString(it.label) } ?: stopped
    val rows = buildList {
        add(DebugRow("android", DebugSection.DEVICE, Symbols.ANDROID_LOGO, "Android", RowValue.Info("${DeviceUtils.getSDKVersionName()} · API ${Build.VERSION.SDK_INT}"), // l10n-ignore
            res.getString(R.string.debug_android_description)))
        add(DebugRow("kernel", DebugSection.DEVICE, Symbols.MEMORY, res.getString(R.string.debug_kernel_label), RowValue.Info(System.getProperty("os.version").orEmpty().substringBefore('-')),
            res.getString(R.string.debug_kernel_description), System.getProperty("os.version")))
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else res.getString(R.string.debug_soc_unknown)
        add(DebugRow("model", DebugSection.DEVICE, Symbols.DEVICE_MODEL, res.getString(R.string.debug_model_label), RowValue.Info("${DeviceUtils.getManufacturer()} ${DeviceUtils.getModel()}"),
            res.getString(R.string.debug_model_description), res.getString(R.string.debug_model_details, Build.BOARD, Build.HARDWARE, soc, Build.DISPLAY)))
        add(DebugRow("motor", DebugSection.DEVICE, Symbols.VIBRATION, res.getString(R.string.debug_motor_label), RowValue.Info(res.getString(if (hasMotor) R.string.debug_yes else R.string.debug_no)),
            res.getString(if (hasMotor) R.string.debug_motor_yes_description else R.string.debug_motor_no_description)))
        // What a problem report would contain; the panel on the right previews exactly that text
        val logValue = when {
            report == null -> res.getString(R.string.debug_log_reading)
            !DiagLog.enabled && report?.logLines == 0 -> res.getString(R.string.debug_log_off)
            else -> report!!.sizeLabel(res)
        }
        add(DebugRow("diag_log", DebugSection.DIAGNOSTICS, Symbols.DIAGNOSTIC_LOGS, res.getString(R.string.debug_log_label), RowValue.Info(logValue),
            res.getString(R.string.debug_log_description),
            report?.preview(res) ?: res.getString(R.string.debug_log_preview_reading)))
        add(DebugRow("diag_share", DebugSection.DIAGNOSTICS, Symbols.SHARE_LOG, res.getString(R.string.debug_share_label), RowValue.Action { shareLog() },
            res.getString(R.string.debug_share_description)))
        add(DebugRow("diag_copy", DebugSection.DIAGNOSTICS, Symbols.COPY_SUMMARY, res.getString(R.string.debug_copy_label), RowValue.Action { copySummary() },
            res.getString(R.string.debug_copy_description)))
        add(DebugRow("diag_clear", DebugSection.DIAGNOSTICS, Symbols.DELETE, res.getString(R.string.debug_clear_label), RowValue.Action { clearLogs() },
            res.getString(R.string.debug_clear_description)))
        // Debug builds only (the row is never added in a release build): the way to see the crash capture and the report card work
        if (BuildConfig.DEBUG) {
            add(DebugRow("diag_test_crash", DebugSection.DIAGNOSTICS, Symbols.TEST_CRASH, "Trigger test crash", RowValue.Action { // l10n-ignore: debug builds only
                throw IllegalStateException("Test crash from Debug info") // l10n-ignore
            }, "Debug builds only. Crashes Delos on purpose; the next start offers the problem report, once.")) // l10n-ignore
        }
        // Why an experimental preset is unavailable
        add(DebugRow("cap_vibrators", DebugSection.HAPTICS, Symbols.VIBRATORS, res.getString(R.string.debug_vibrators_label),
            RowValue.Info(if (capabilities.vibratorCount == 1) res.getString(R.string.debug_vibrators_single) else "${capabilities.vibratorCount}"),
            res.getString(R.string.debug_vibrators_description)))
        add(DebugRow("cap_amplitude", DebugSection.HAPTICS, Symbols.AMPLITUDE_SUPPORT, res.getString(R.string.debug_amplitude_label),
            RowValue.Info(res.getString(if (capabilities.amplitudeControl) R.string.debug_yes else R.string.debug_no)), res.getString(R.string.debug_amplitude_description)))
        add(DebugRow("cap_problem", DebugSection.HAPTICS, Symbols.WARNING, res.getString(R.string.debug_last_problem_label),
            RowValue.Info(problemText ?: res.getString(R.string.debug_none)),
            res.getString(R.string.debug_last_problem_description)))
        add(DebugRow("vibrate_once", DebugSection.VIBRATION_TEST, Symbols.DEVICE_VIBRATION, res.getString(R.string.debug_vibrate_once_label), RowValue.Action {
            stopAll()
            device?.let { DeviceVibrator.vibrateOnce(it, 1000) }
        }, res.getString(R.string.debug_vibrate_once_description)).let { if (hasMotor) it else it.copy(description = res.getString(R.string.debug_motor_no_description)) })
        add(DebugRow("vibrate_continuous", DebugSection.VIBRATION_TEST, Symbols.CONTINUOUS, res.getString(R.string.debug_continuous_label), RowValue.Toggle(deviceContinuous) {
            stopAll()
            deviceContinuous = it
        },
            res.getString(R.string.debug_continuous_description)))
        add(DebugRow("strength", DebugSection.VIBRATION_TEST, Symbols.STRENGTH, res.getString(R.string.debug_strength_label), RowValue.Slider(strength, 5, 255, 5, "") { strength = it },
            res.getString(R.string.debug_strength_description)))
        add(DebugRow("rumble_preset", DebugSection.RUMBLE_LAB, Symbols.PRESET, res.getString(R.string.debug_preset_label), RowValue.Info(presetName),
            res.getString(R.string.debug_preset_description),
            res.getString(R.string.debug_preset_details, modeName)))
        RumblePresets.values().forEach { preset ->
            val unavailable = capabilities.unavailableReason(preset.profile.technique)?.text(res)
            if (unavailable != null) {
                add(DebugRow("rumble_preset_${preset.name}", DebugSection.RUMBLE_LAB, preset.icon(), preset.label(res),
                    RowValue.Info(res.getString(R.string.debug_preset_problem)), res.getString(R.string.debug_preset_problem_description, preset.description(res), unavailable), unavailable))
                return@forEach
            }
            add(DebugRow("rumble_preset_${preset.name}", DebugSection.RUMBLE_LAB, preset.icon(), preset.label(res),
                RowValue.Text(res.getString(if (activePreset == preset) R.string.debug_preset_active else R.string.debug_preset_select)) {
                    RumblePreferences.applyPreset(preferences, preset)
                    profile = preset.profile
                    translatorEnabled = preset.enabled
                    activePreset = preset
                    // Pass the selection explicitly: this click still owns the previous composition's callbacks.
                    playPattern(RumblePattern.TEST, if (preset.enabled) preset.profile else RumbleTranslator.ORIGINAL)
                }, if (activePreset == preset && problemText != null) res.getString(R.string.debug_preset_problem_description, preset.description(res), problemText) else preset.description(res)))
        }
        add(DebugRow("rumble_amplitude", DebugSection.RUMBLE_LAB, Symbols.LIVE_AMPLITUDE, res.getString(R.string.debug_live_amplitude_label, presetName), RowValue.Info("$amplitude / 255"),
            res.getString(R.string.debug_live_amplitude_description),
            res.getString(R.string.debug_live_amplitude_details, modeName, playingName)))
        add(DebugRow("rumble_stop", DebugSection.RUMBLE_LAB, Symbols.STOP, res.getString(R.string.debug_stop_label), RowValue.Action { stopAll() },
            res.getString(R.string.debug_stop_description)))
        add(DebugRow("rumble_test", DebugSection.RUMBLE_LAB, Symbols.REPLAY, res.getString(R.string.debug_replay_label), RowValue.Action {
            playPattern(RumblePattern.TEST, if (translatorEnabled) profile else RumbleTranslator.ORIGINAL)
        }, res.getString(RumblePattern.TEST.description)))
        RumblePattern.entries.filter { it != RumblePattern.TEST }.forEach { pattern ->
            add(DebugRow("rumble_pattern_${pattern.name}", DebugSection.RUMBLE_PATTERNS, pattern.icon, res.getString(pattern.label),
                RowValue.Action { playPattern(pattern, if (translatorEnabled) profile else RumbleTranslator.ORIGINAL) }, res.getString(pattern.description),
                res.getString(R.string.debug_pattern_details, amplitude, modeName, playingName)))
        }
        add(DebugRow("rumble_advanced", DebugSection.RUMBLE_ADVANCED, Symbols.ADVANCED, res.getString(R.string.debug_advanced_label), RowValue.Toggle(advancedExpanded) {
            advancedExpanded = it
        }, res.getString(R.string.debug_advanced_description)))
        if (advancedExpanded) {
            add(DebugRow("rumble_enabled", DebugSection.RUMBLE_ADVANCED, Symbols.TRANSLATOR, res.getString(R.string.debug_enabled_label), RowValue.Toggle(translatorEnabled) {
                stopLab()
                translatorEnabled = it
                activePreset = null
                RumblePreferences.saveCustom(preferences, profile, it)
            }, res.getString(R.string.debug_enabled_description)))
            add(DebugRow("rumble_gain", DebugSection.RUMBLE_ADVANCED, Symbols.GAIN, res.getString(R.string.debug_gain_label), RowValue.Slider((profile.gain * 100).roundToInt(), 50, 300, 1, "%") {
                updateProfile(RumbleTranslator.Profile(it / 100f, profile.curve, profile.minAmplitude, profile.kickMs, profile.lowWeight, profile.highWeight, profile.smoothing))
            }, res.getString(R.string.debug_gain_description)))
            add(DebugRow("rumble_curve", DebugSection.RUMBLE_ADVANCED, Symbols.CURVE, res.getString(R.string.debug_curve_label), RowValue.Slider((profile.curve * 100).roundToInt(), 50, 300, 1, "%") {
                updateProfile(RumbleTranslator.Profile(profile.gain, it / 100f, profile.minAmplitude, profile.kickMs, profile.lowWeight, profile.highWeight, profile.smoothing))
            }, res.getString(R.string.debug_curve_description, 100)))
            add(DebugRow("rumble_min", DebugSection.RUMBLE_ADVANCED, Symbols.MIN_AMPLITUDE, res.getString(R.string.debug_min_label), RowValue.Slider(profile.minAmplitude, 0, 255, 1, "") {
                updateProfile(RumbleTranslator.Profile(profile.gain, profile.curve, it, profile.kickMs, profile.lowWeight, profile.highWeight, profile.smoothing))
            }, res.getString(R.string.debug_min_description)))
            add(DebugRow("rumble_kick", DebugSection.RUMBLE_ADVANCED, Symbols.PRESET_KICK, res.getString(R.string.debug_kick_label), RowValue.Slider(profile.kickMs, 0, 120, 1, " ms") {
                updateProfile(RumbleTranslator.Profile(profile.gain, profile.curve, profile.minAmplitude, it, profile.lowWeight, profile.highWeight, profile.smoothing))
            }, res.getString(R.string.debug_kick_description)))
            add(DebugRow("rumble_low", DebugSection.RUMBLE_ADVANCED, Symbols.LOW_WEIGHT, res.getString(R.string.debug_low_label), RowValue.Slider((profile.lowWeight * 100).roundToInt(), 0, 100, 1, "%") {
                updateProfile(RumbleTranslator.Profile(profile.gain, profile.curve, profile.minAmplitude, profile.kickMs, it / 100f, profile.highWeight, profile.smoothing))
            }, res.getString(R.string.debug_low_description)))
            add(DebugRow("rumble_high", DebugSection.RUMBLE_ADVANCED, Symbols.HIGH_WEIGHT, res.getString(R.string.debug_high_label), RowValue.Slider((profile.highWeight * 100).roundToInt(), 0, 100, 1, "%") {
                updateProfile(RumbleTranslator.Profile(profile.gain, profile.curve, profile.minAmplitude, profile.kickMs, profile.lowWeight, it / 100f, profile.smoothing))
            }, res.getString(R.string.debug_high_description)))
            add(DebugRow("rumble_smoothing", DebugSection.RUMBLE_ADVANCED, Symbols.SMOOTHING, res.getString(R.string.debug_smoothing_label), RowValue.Slider((profile.smoothing * 100).roundToInt(), 0, 100, 1, "%") {
                updateProfile(RumbleTranslator.Profile(profile.gain, profile.curve, profile.minAmplitude, profile.kickMs, profile.lowWeight, profile.highWeight, it / 100f))
            }, res.getString(R.string.debug_smoothing_description)))
            add(DebugRow("rumble_reset", DebugSection.RUMBLE_ADVANCED, Symbols.RESET, res.getString(R.string.debug_reset_label), RowValue.Action {
                updateProfile(RumbleTranslator.ORIGINAL)
            }, res.getString(R.string.debug_reset_description)))
        }
        add(DebugRow("osc_preview", DebugSection.ON_SCREEN_CONTROLS, Symbols.JOYSTICK, res.getString(R.string.debug_osc_label), RowValue.Action { shell.navigate(Screen.OscPreview) },
            res.getString(R.string.debug_osc_description)))
        if (pads.isEmpty()) {
            add(DebugRow("no_pads", DebugSection.CONTROLLERS, Symbols.SPORTS_ESPORTS, res.getString(R.string.debug_no_pads_label), RowValue.Info(""),
                res.getString(R.string.debug_no_pads_description)))
        } else {
            pads.forEach { pad ->
                val vibrator = DeviceVibrator.get(pad)
                val vibrates = vibrator?.hasVibrator() == true
                val sensors = sensors(res, pad)
                val ids = String.format(Locale.ROOT, "%04x:%04x", pad.vendorId, pad.productId)
                add(DebugRow("pad_${pad.id}", DebugSection.CONTROLLERS, Symbols.SPORTS_ESPORTS, pad.name, RowValue.Text(ids) {
                    stopAll()
                    if (vibrates) DeviceVibrator.vibrateOnce(vibrator, 1000) else shell.showToast(res.getString(R.string.debug_pad_no_vibration_toast, pad.name), Symbols.INFO)
                },
                    res.getString(if (vibrates) R.string.debug_pad_description_vibrates else R.string.debug_pad_description_silent, ids, "${pad.vendorId}_${pad.productId}", sensors),
                    pad.toString()))
            }
            add(DebugRow("pads_continuous", DebugSection.CONTROLLERS, Symbols.PADS_CONTINUOUS, res.getString(R.string.debug_pads_continuous_label), RowValue.Toggle(padsContinuous) {
                stopAll()
                padsContinuous = it
            },
                res.getString(R.string.debug_pads_continuous_description)))
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
        BasicText(stringResource(R.string.debug_title), style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 46.tpx),
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
                        var previous: DebugSection? = null
                        rows.forEach { row ->
                            if (row.section != previous) {
                                val section = row.section
                                key("section-${section.name}") {
                                    BasicText(stringResource(section.title).uppercase(Locale.ROOT), style = TextStyle(color = colors.text3, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 16.tpx, letterSpacing = .08.em),
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
        BasicText(row?.label ?: stringResource(R.string.debug_title), style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 30.tpx), modifier = Modifier.padding(top = 8.px))
        BasicText(row?.description ?: stringResource(R.string.debug_panel_default),
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

private fun sensors(res: Resources, dev: InputDevice): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return res.getString(R.string.debug_sensors_unknown)
    val accelerometer = dev.sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    val gyroscope = dev.sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
    return res.getString(
        when {
            accelerometer && gyroscope -> R.string.debug_sensors_both
            accelerometer -> R.string.debug_sensors_accelerometer
            gyroscope -> R.string.debug_sensors_gyroscope
            else -> R.string.debug_sensors_none
        },
    )
}

private fun rumble(vibrator: Vibrator, strength: Int) {
    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(1000), intArrayOf(strength.coerceIn(1, 255)), 0))
}

/** Fixed host-motor inputs; the shared playback path owns kick and smoothing timing. */
private enum class RumblePattern(val icon: String, @StringRes val label: Int, @StringRes val description: Int) {
    TEST(Symbols.REPLAY, R.string.debug_pattern_test_label, R.string.debug_pattern_test_description),
    STEADY_25(Symbols.PATTERN_STEADY_25, R.string.debug_pattern_steady_25_label, R.string.debug_pattern_steady_25_description),
    STEADY_50(Symbols.PATTERN_STEADY_50, R.string.debug_pattern_steady_50_label, R.string.debug_pattern_steady_50_description),
    STEADY_75(Symbols.PATTERN_STEADY_75, R.string.debug_pattern_steady_75_label, R.string.debug_pattern_steady_75_description),
    STEADY_100(Symbols.PATTERN_STEADY_100, R.string.debug_pattern_steady_100_label, R.string.debug_pattern_steady_100_description),
    RAMP(Symbols.PATTERN_RAMP, R.string.debug_pattern_ramp_label, R.string.debug_pattern_ramp_description),
    TAP_30(Symbols.PATTERN_TAP_30, R.string.debug_pattern_tap_30_label, R.string.debug_pattern_tap_30_description),
    TAP_60(Symbols.PATTERN_TAP_60, R.string.debug_pattern_tap_60_label, R.string.debug_pattern_tap_60_description),
    TAP_100(Symbols.PATTERN_TAP_100, R.string.debug_pattern_tap_100_label, R.string.debug_pattern_tap_100_description),
    HEARTBEAT(Symbols.PATTERN_HEARTBEAT, R.string.debug_pattern_heartbeat_label, R.string.debug_pattern_heartbeat_description),
    EXPLOSION(Symbols.PATTERN_EXPLOSION, R.string.debug_pattern_explosion_label, R.string.debug_pattern_explosion_description),
    FLUTTER(Symbols.PATTERN_FLUTTER, R.string.debug_pattern_flutter_label, R.string.debug_pattern_flutter_description),
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
