package com.limelight.shell.settings

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecInfo
import android.os.Build
import android.os.Vibrator
import android.view.Display
import com.limelight.binding.video.MediaCodecHelper
import com.limelight.binding.video.PyroWaveRenderer
import com.limelight.preferences.GlPreferences
import com.limelight.preferences.PreferenceConfiguration
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Device rules ported from StreamSettings, separate from rendering and persistence. */
data class SettingsCapabilities(
    val resolutions: List<SettingChoice>,
    val maxResolution: Int,
    val maxFps: Float,
    val hdr: Boolean,
    val brokenHdr: Boolean,
    val hevc: Boolean,
    val av1: Boolean,
    val hidden: Set<String>,
    // Delos: whether this device can decode PyroWave (PyroWaveRenderer)
    val pyroWave: Boolean = false,
) {
    fun choices(spec: SettingSpec, values: Map<String, *>): List<SettingChoice> = when (spec.key) {
        "list_resolution" -> {
            val base = spec.choices.filter { (it.value.substringBefore('x').toIntOrNull() ?: 0) <= maxResolution }
            val custom = (values["edit_diy_w_h"] as? String)?.takeIf { it.matches(Regex("[1-9][0-9]*x[1-9][0-9]*")) }
            (base + resolutions + listOfNotNull(custom?.let { SettingChoice("Custom ($it)", it) })).distinctBy { it.value }
        }
        "list_fps" -> {
            val base = spec.choices.filter { values["checkbox_unlock_fps"] == true || (it.value.toFloatOrNull() ?: 0f) <= max(60f, maxFps + 2f) }
            val native = SettingChoice("Native (${maxFps.roundToInt()} FPS)", maxFps.roundToInt().toString())
            val custom = (values["custom_refresh_rate"] as? String)?.takeIf { it.toFloatOrNull()?.let { f -> f.isFinite() && f > 0 } == true }
            (base + native + listOfNotNull(custom?.let { SettingChoice("Custom ($it FPS)", it) })).distinctBy { it.value }
        }
        "video_format" -> spec.choices.filter {
            (it.value != "forceh265" || hevc) && (it.value != "forceav1" || av1) && (it.value != "pyrowave" || pyroWave)
        }
        "frame_pacing" -> if (values["video_format"] == "pyrowave") spec.choices.filter { it.value != "fast-vsync" } else spec.choices
        // The classic array repeats a formatted label; make the five stored layouts distinguishable.
        "keyboard_axi_list" -> spec.choices.mapIndexed { i, choice -> choice.copy(label = "Layout ${i + 1}") }
        else -> spec.choices
    }

    companion object {
        fun read(activity: Activity): SettingsCapabilities {
            val pm = activity.packageManager
            val display = activity.windowManager.defaultDisplay
            val modes = display.supportedModes.toList()
            val native = mutableListOf<SettingChoice>()
            val cutout = display.cutout
            if (cutout != null) {
                val mode = display.mode
                val width = mode.physicalWidth - cutout.safeInsetLeft - cutout.safeInsetRight
                val height = mode.physicalHeight - cutout.safeInsetTop - cutout.safeInsetBottom
                addResolution(native, max(width, height), min(width, height), "Native")
            }
            var maxWidth = 1280
            modes.forEach { mode ->
                val w = max(mode.physicalWidth, mode.physicalHeight)
                val h = min(mode.physicalWidth, mode.physicalHeight)
                if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION) || w > 3840 || h > 2160) {
                    addResolution(native, w, h, if (cutout != null) "Native fullscreen" else "Native")
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
            val hidden = mutableSetOf<String>()
            if (!com.limelight.binding.video.SgsrRenderer.isSupported(activity)) {
                hidden += setOf("checkbox_sgsr", "list_sgsr_quality", "seekbar_sgsr_sharpness")
            }
            if (pm.hasSystemFeature("com.nvidia.feature.shield")) hidden += "checkbox_absolute_mouse_mode"
            if (Build.VERSION.SDK_INT < 31) hidden += "checkbox_gamepad_motion_sensors"
            if (!pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER) && !pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE)) {
                hidden += setOf("checkbox_force_device_motion", "checkbox_gamepad_motion_fallback")
            }
            if (!pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) hidden += setOf("checkbox_usb_driver", "checkbox_usb_bind_all")
            if (!pm.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) || pm.hasSystemFeature("com.amazon.software.fireos")) hidden += "checkbox_enable_pip"
            val vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator?.hasVibrator() != true) hidden += setOf("checkbox_vibrate_fallback", "seekbar_vibrate_fallback_strength", "checkbox_vibrate_osc", "checkbox_vibrate_keyboard", "checkbox_enable_device_rumble")
            if (vibrator?.hasAmplitudeControl() != true) hidden += "seekbar_vibrate_fallback_strength"
            val hdr10 = display.hdrCapabilities?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) == true
            val main10 = runCatching { hevc?.getCapabilitiesForType("video/hevc")?.profileLevels?.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 } == true }.getOrDefault(false)
            if (!hdr10 || !main10) hidden += "checkbox_enable_hdr"
            return SettingsCapabilities(native.distinctBy { it.value }, maxWidth,
                modes.maxOfOrNull { it.refreshRate } ?: display.refreshRate,
                hdr10 && main10, PreferenceConfiguration.isShieldAtvFirmwareWithBrokenHdr(), hevc != null, av1 != null, hidden,
                PyroWaveRenderer.isAvailable())
        }

        private fun addResolution(into: MutableList<SettingChoice>, width: Int, height: Int, prefix: String) {
            if (PreferenceConfiguration.isSquarishScreen(width, height)) into += SettingChoice("$prefix portrait (${height}x$width)", "${height}x$width")
            into += SettingChoice("$prefix (${width}x$height)", "${width}x$height")
        }
    }
}
