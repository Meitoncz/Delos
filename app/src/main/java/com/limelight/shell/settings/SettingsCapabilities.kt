package com.limelight.shell.settings

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.media.MediaCodecInfo
import android.os.Build
import android.os.Vibrator
import android.view.Display
import com.limelight.R
import com.limelight.binding.video.MediaCodecHelper
import com.limelight.binding.video.PyroWaveRenderer
import com.limelight.binding.video.SgsrRenderer
import com.limelight.preferences.GlPreferences
import com.limelight.preferences.PreferenceConfiguration
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A resolution of this display offered next to the catalog's fixed ones. Only the numbers are kept: the label is built
 * when the choices are listed, so it follows the language.
 */
data class NativeResolution(val width: Int, val height: Int, val fullscreen: Boolean = false, val portrait: Boolean = false) {
    val value: String get() = "${width}x$height"

    fun label(res: Resources): String = res.getString(when {
        fullscreen && portrait -> R.string.settings_resolution_native_fullscreen_portrait
        fullscreen -> R.string.settings_resolution_native_fullscreen
        portrait -> R.string.settings_resolution_native_portrait
        else -> R.string.settings_resolution_native
    }, value)
}

/** Device rules ported from StreamSettings, separate from rendering and persistence. */
data class SettingsCapabilities(
    val resolutions: List<NativeResolution>,
    val maxResolution: Int,
    val maxFps: Float,
    val hdr: Boolean,
    val brokenHdr: Boolean,
    val hevc: Boolean,
    val av1: Boolean,
    val hidden: Set<String>,
    // Delos: whether this device can decode PyroWave (PyroWaveRenderer)
    val pyroWave: Boolean = false,
    // Delos: whether MediaCodec streams can use the SGSR upscaler (OpenGL ES 3.1)
    val sgsr: Boolean = true,
) {
    fun choices(res: Resources, spec: SettingSpec, values: Map<String, *>): List<SettingChoice> = when (spec.key) {
        "list_resolution" -> {
            val base = spec.choices.filter { (it.value.substringBefore('x').toIntOrNull() ?: 0) <= maxResolution }
            val custom = (values["edit_diy_w_h"] as? String)?.takeIf { it.matches(Regex("[1-9][0-9]*x[1-9][0-9]*")) }
            (base + resolutions.map { SettingChoice(it.label(res), it.value) } +
                listOfNotNull(custom?.let { SettingChoice(res.getString(R.string.settings_resolution_custom, it), it) })).distinctBy { it.value }
        }
        "list_fps" -> {
            val base = spec.choices.filter { values["checkbox_unlock_fps"] == true || (it.value.toFloatOrNull() ?: 0f) <= max(60f, maxFps + 2f) }
            val native = SettingChoice(res.getString(R.string.settings_fps_native, maxFps.roundToInt()), maxFps.roundToInt().toString())
            val custom = (values["custom_refresh_rate"] as? String)?.takeIf { it.toFloatOrNull()?.let { f -> f.isFinite() && f > 0 } == true }
            (base + native + listOfNotNull(custom?.let { SettingChoice(res.getString(R.string.settings_fps_custom, it), it) })).distinctBy { it.value }
        }
        "video_format" -> spec.choices.filter {
            (it.value != "forceh265" || hevc) && (it.value != "forceav1" || av1) && (it.value != "pyrowave" || pyroWave)
        }
        "frame_pacing" -> if (values["video_format"] == "pyrowave") spec.choices.filter { it.value != "fast-vsync" } else spec.choices
        // The classic array repeats a formatted label; make the five stored layouts distinguishable.
        // A selection that matches no preset (Advanced edits in the Debug lab) stays visible instead of looking unset
        "list_rumble_feel" -> if (values[spec.key] == "CUSTOM") spec.choices + SettingChoice(res.getString(R.string.settings_choice_custom), "CUSTOM") else spec.choices
        "keyboard_axi_list" -> spec.choices.mapIndexed { i, choice -> choice.copy(label = res.getString(R.string.settings_layout_number, i + 1)) }
        else -> spec.choices
    }

    companion object {
        fun read(activity: Activity): SettingsCapabilities {
            val pm = activity.packageManager
            val display = activity.windowManager.defaultDisplay
            val modes = display.supportedModes.toList()
            val native = mutableListOf<NativeResolution>()
            val cutout = display.cutout
            if (cutout != null) {
                val mode = display.mode
                val width = mode.physicalWidth - cutout.safeInsetLeft - cutout.safeInsetRight
                val height = mode.physicalHeight - cutout.safeInsetTop - cutout.safeInsetBottom
                addResolution(native, max(width, height), min(width, height), fullscreen = false)
            }
            var maxWidth = 1280
            modes.forEach { mode ->
                val w = max(mode.physicalWidth, mode.physicalHeight)
                val h = min(mode.physicalWidth, mode.physicalHeight)
                if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION) || w > 3840 || h > 2160) {
                    addResolution(native, w, h, fullscreen = cutout != null)
                }
                maxWidth = max(maxWidth, when { w >= 3840 || h >= 2160 -> 3840; w >= 2560 || h >= 1440 -> 2560; w >= 1920 || h >= 1080 -> 1920; else -> 1280 })
            }
            MediaCodecHelper.initialize(activity, GlPreferences.readPreferences(activity).glRenderer)
            val avc = MediaCodecHelper.findProbableSafeDecoder("video/avc", -1)
            val hevc = MediaCodecHelper.findProbableSafeDecoder("video/hevc", -1)
            val av1 = MediaCodecHelper.findProbableSafeDecoder("video/av01", -1)
            listOf("video/avc" to avc, "video/hevc" to hevc).forEach { (mime, codec) ->
                val widths = runCatching { codec?.getCapabilitiesForType(mime)?.videoCapabilities?.supportedWidths }.getOrNull()
                if (widths?.contains(1280) == true) {
                    maxWidth = max(maxWidth, when { widths.contains(3840) -> 3840; widths.contains(1920) -> 1920; else -> 1280 })
                }
            }
            val pyroWave = PyroWaveRenderer.isAvailable()
            val sgsr = SgsrRenderer.isSupported(activity)
            val hidden = mutableSetOf<String>()
            if (!sgsr && !pyroWave) {
                hidden += UPSCALER_KEYS
            }
            if (pm.hasSystemFeature("com.nvidia.feature.shield")) hidden += "checkbox_absolute_mouse_mode"
            hidden += motionHiddenKeys(Build.VERSION.SDK_INT,
                pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER) || pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE))
            if (!pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) hidden += setOf("checkbox_usb_driver", "checkbox_usb_bind_all")
            if (!pm.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) || pm.hasSystemFeature("com.amazon.software.fireos")) hidden += "checkbox_enable_pip"
            val vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator?.hasVibrator() != true) hidden += setOf("checkbox_vibrate_fallback", "seekbar_vibrate_fallback_strength", "checkbox_vibrate_osc", "checkbox_vibrate_keyboard", "checkbox_enable_device_rumble", "list_rumble_feel")
            if (vibrator?.hasAmplitudeControl() != true) hidden += "seekbar_vibrate_fallback_strength"
            val hdr10 = display.hdrCapabilities?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) == true
            val main10 = runCatching { hevc?.getCapabilitiesForType("video/hevc")?.profileLevels?.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 } == true }.getOrDefault(false)
            if (!hdr10 || !main10) hidden += "checkbox_enable_hdr"
            return SettingsCapabilities(native.distinctBy { it.value }, maxWidth,
                modes.maxOfOrNull { it.refreshRate } ?: display.refreshRate,
                hdr10 && main10, PreferenceConfiguration.isShieldAtvFirmwareWithBrokenHdr(), hevc != null, av1 != null, hidden,
                pyroWave, sgsr)
        }

        /**
         * The motion rows that do not apply: an external gamepad's sensors need Android 12, the built-in sensors need
         * a sensor on the device, and Gyro smoothing and the controller state refresh need at least one of the two sources.
         */
        internal fun motionHiddenKeys(sdk: Int, hasDeviceSensors: Boolean): Set<String> = buildSet {
            if (sdk < 31) add("checkbox_gamepad_motion_sensors")
            if (!hasDeviceSensors) addAll(setOf("checkbox_force_device_motion", "checkbox_gamepad_motion_fallback"))
            if (sdk < 31 && !hasDeviceSensors) addAll(setOf("seekbar_gyro_smoothing", "checkbox_gyro_state_refresh"))
        }

        private fun addResolution(into: MutableList<NativeResolution>, width: Int, height: Int, fullscreen: Boolean) {
            if (PreferenceConfiguration.isSquarishScreen(width, height)) into += NativeResolution(height, width, fullscreen, portrait = true)
            into += NativeResolution(width, height, fullscreen)
        }
    }
}
