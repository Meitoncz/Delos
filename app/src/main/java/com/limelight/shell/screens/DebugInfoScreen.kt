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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.limelight.shell.LocalShell
import com.limelight.shell.Screen
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.MarginBringIntoView
import com.limelight.shell.ui.RowValue
import com.limelight.shell.ui.SettingRow
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.fadeEdges
import com.limelight.shell.ui.focusEffects
import com.limelight.shell.ui.glass
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx
import com.limelight.utils.DeviceUtils
import com.limelight.utils.DeviceVibrator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

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
 * model, vibration motor), a vibration test with its strength, and every connected controller with its
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
    LaunchedEffect(deviceContinuous, strength) { device?.let { if (deviceContinuous) rumble(it, strength) else it.cancel() } }
    LaunchedEffect(padsContinuous, strength, pads) {
        pads.mapNotNull { DeviceVibrator.get(it)?.takeIf { v -> v.hasVibrator() } }.forEach { if (padsContinuous) rumble(it, strength) else it.cancel() }
    }

    val hasMotor = device?.hasVibrator() == true
    val rows = buildList {
        add(DebugRow("android", "Device", Symbols.DEPLOYED_CODE, "Android", RowValue.Info("${DeviceUtils.getSDKVersionName()} · API ${Build.VERSION.SDK_INT}"),
            "The Android version of this device and its API level."))
        add(DebugRow("kernel", "Device", Symbols.MEMORY, "Kernel", RowValue.Info(System.getProperty("os.version").orEmpty().substringBefore('-')),
            "The Linux kernel version.", System.getProperty("os.version")))
        add(DebugRow("model", "Device", Symbols.DEVICES, "Device", RowValue.Info("${DeviceUtils.getManufacturer()} ${DeviceUtils.getModel()}"),
            "The manufacturer and model.", "Board: ${Build.BOARD}\nHardware: ${Build.HARDWARE}\nSoC: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "unknown"}\nBuild: ${Build.DISPLAY}"))
        add(DebugRow("motor", "Device", Symbols.VIBRATION, "Vibration motor", RowValue.Info(if (hasMotor) "Yes" else "No"),
            if (hasMotor) "This device can vibrate." else "This device has no vibration motor."))
        add(DebugRow("vibrate_once", "Vibration test", Symbols.MOBILE_VIBRATE, "Vibrate once", RowValue.Action {
            device?.let { DeviceVibrator.vibrateOnce(it, 1000) }
        }, "One second at full strength.").let { if (hasMotor) it else it.copy(description = "This device has no vibration motor.") })
        add(DebugRow("vibrate_continuous", "Vibration test", Symbols.GRAPHIC_EQ, "Continuous vibration", RowValue.Toggle(deviceContinuous) { deviceContinuous = it },
            "Keeps vibrating at the strength below until it is turned off or you leave this screen."))
        add(DebugRow("strength", "Vibration test", Symbols.TUNE, "Strength", RowValue.Slider(strength, 5, 255, 5, "") { strength = it },
            "The strength of continuous vibration, 1–255, for the device and the controllers."))
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
                    if (vibrates) DeviceVibrator.vibrateOnce(vibrator, 1000) else shell.showToast("${pad.name} cannot vibrate", Symbols.INFO)
                },
                    "Vendor and product id $ids (${pad.vendorId}_${pad.productId}). Motion sensors: $sensors. Vibration: ${if (vibrates) "supported, select to test" else "not supported"}.",
                    pad.toString()))
            }
            add(DebugRow("pads_continuous", "Controllers", Symbols.GRAPHIC_EQ, "Continuous vibration on controllers", RowValue.Toggle(padsContinuous) { padsContinuous = it },
                "Every controller that can vibrate keeps vibrating at the strength above."))
        }
    }

    var focusedKey by remember { mutableStateOf<String?>(null) }
    val focused = rows.firstOrNull { it.key == focusedKey }
    val first = remember { FocusRequester() }
    val window = LocalWindowInfo.current
    LaunchedEffect(Unit) {
        snapshotFlow { window.isWindowFocused }.first { it }
        feedback.silenceNextFocus()
        runCatching { first.requestFocus() }
    }
    val k = stage.dpPerPx * LocalDensity.current.density
    val bringIntoView = remember(k) { MarginBringIntoView(before = 72f * k, after = 110f * k) }

    Box(Modifier.fillMaxSize()) {
        BasicText("Debug info", style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 46.tpx),
            modifier = Modifier.offset(150.px, 124.px))
        Row(Modifier.fillMaxSize().padding(start = 150.px, end = 150.px, top = 230.px, bottom = 110.px), horizontalArrangement = Arrangement.spacedBy(56.px)) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView, com.limelight.shell.ui.LocalEntranceStagger provides com.limelight.shell.ui.rememberEntranceStagger()) {
                LazyColumn(
                    Modifier.weight(1f).fadeEdges(stage, top = 14f, bottom = 90f),
                    contentPadding = PaddingValues(start = 18.px, end = 18.px, top = 14.px, bottom = 140.px),
                    verticalArrangement = Arrangement.spacedBy(10.px),
                ) {
                    var previous = ""
                    rows.forEachIndexed { index, row ->
                        if (row.section != previous) {
                            val section = row.section
                            item("section-$section") {
                                BasicText(section.uppercase(Locale.ROOT), style = TextStyle(color = colors.text3, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 16.tpx, letterSpacing = .08.em),
                                    modifier = Modifier.padding(start = 26.px, top = 10.px, bottom = 2.px))
                            }
                            previous = section
                        }
                        item(row.key) {
                            SettingRow(row.icon, row.label, row.value, if (index == 0) Modifier.focusRequester(first) else Modifier,
                                description = row.description.takeIf { it.length <= 100 }, onFocused = { focusedKey = row.key })
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
        BasicText(row?.description ?: "Information about this device and its controllers, and a vibration test.",
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
