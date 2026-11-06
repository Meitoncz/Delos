package com.limelight.shell.settings

import android.content.SharedPreferences
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.utils.RumblePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.roundToInt

/** Writes the classic keys with their original types into the selected user's preferences. */
class StreamSettingsStore(val catalog: SettingsCatalog, private val settings: () -> SharedPreferences) {
    private val state = MutableStateFlow<Map<String, *>>(emptyMap<String, Any>())
    val values = state.asStateFlow()
    private var observed: SharedPreferences? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    init { reload() }

    fun reload() {
        observed?.unregisterOnSharedPreferenceChangeListener(listener)
        observed = settings().also { it.registerOnSharedPreferenceChangeListener(listener) }
        refresh()
    }

    private fun refresh() {
        val prefs = settings()
        // "Rumble feel" is derived from the saved rumble parameters, never stored itself
        state.value = prefs.all.filterKeys { !isCodecBitrateStorageKey(it) } + (RumblePreferences.FEEL to RumblePreferences.feelValue(prefs))
    }

    fun value(spec: SettingSpec): Any? = state.value[spec.key] ?: spec.default

    fun enabled(spec: SettingSpec, caps: SettingsCapabilities?): Boolean {
        if (spec.key == "checkbox_enable_hdr" && caps?.brokenHdr == true) return false
        // PyroWave has its own Vulkan decoder; MediaCodec-only options do not apply.
        if (spec.key in setOf("checkbox_async_decode", "checkbox_ultra_low_latency", "pref_low_latency_frame_balance") &&
            state.value["video_format"] == "pyrowave") return false
        // 4:4:4 is PyroWave-only and is never offered with HDR (the P010 bridge is 4:2:0).
        if (spec.key == "list_pyrowave_chroma" &&
            (state.value["video_format"] != "pyrowave" || state.value["checkbox_enable_hdr"] == true)) return false
        if (spec.key in UPSCALER_KEYS && caps?.sgsr == false && state.value["video_format"] != "pyrowave") return false
        if (spec.key == "pref_low_latency_frame_balance" && state.value["frame_pacing"] == "fast-vsync") return false
        fun dependencyEnabled(key: String?, seen: Set<String>): Boolean {
            if (key == null) return true
            if (key in seen) return false
            val parent = catalog.all.firstOrNull { it.key == key } ?: return true
            val v = value(parent)
            return v != false && v != "" && dependencyEnabled(parent.dependency, seen + key)
        }
        return dependencyEnabled(spec.dependency, emptySet())
    }

    fun write(spec: SettingSpec, value: Any) {
        val prefs = settings()
        if (spec.key == RumblePreferences.FEEL) {
            RumblePreferences.applyFeel(prefs, value as String)
            return
        }
        val editor = prefs.edit().putSetting(spec.key, value)
        val before = prefs.getString("video_format", "auto")!!
        val format = if (spec.key == "video_format") value as String else before
        fun resolution() = if (spec.key == "list_resolution") value as String else prefs.getString("list_resolution", "1280x720")!!
        fun fps() = if (spec.key == "list_fps") value as String else prefs.getString("list_fps", "60")!!
        fun hdr() = if (spec.key == "checkbox_enable_hdr") value as Boolean else prefs.getBoolean("checkbox_enable_hdr", false)
        val gameBitrate = prefs as? BitrateOverrideState
        if (spec.key == "video_format" && format != before) {
            if (prefs.contains("seekbar_bitrate_kbps") && gameBitrate?.hasBitrateOverride != false) {
                editor.putInt(codecBitrateKey(before), prefs.getInt("seekbar_bitrate_kbps", -1))
            }
            val target = codecBitrateKey(format)
            when {
                prefs.contains(target) -> editor.putInt("seekbar_bitrate_kbps", prefs.getInt(target, -1))
                format == "pyrowave" -> editor.putInt("seekbar_bitrate_kbps",
                    PreferenceConfiguration.getDefaultBitrate(resolution(), fps(), format, hdr()))
                before == "pyrowave" && gameBitrate != null -> editor.remove("seekbar_bitrate_kbps")
                before == "pyrowave" -> editor.putInt("seekbar_bitrate_kbps",
                    PreferenceConfiguration.getDefaultBitrate(resolution(), fps(), format, hdr()))
                // First use of another ordinary codec preserves the current bitrate and inheritance.
            }
        } else if (format == "pyrowave" && (spec.key == "list_resolution" || spec.key == "list_fps" ||
                spec.key == "checkbox_enable_hdr")) {
            editor.putInt("seekbar_bitrate_kbps", PreferenceConfiguration.getDefaultBitrate(resolution(), fps(), format, hdr()))
        }
        editor.apply()
    }

    /** Returns a short validation error, or null after applying the value. No confirmation dialogs. */
    fun writeText(spec: SettingSpec, input: String): String? {
        val value = input.trim().lowercase(Locale.ROOT)
        when (spec.key) {
            "edit_diy_w_h" -> {
                val parts = value.split('x').map { it.toIntOrNull() }
                if (parts.size != 2 || parts.any { it == null || it !in 1..32768 }) return "Use width x height, for example 1920x1080"
                write(spec, "${parts[0]}x${parts[1]}")
            }
            "custom_refresh_rate" -> {
                val fps = value.toFloatOrNull()
                if (fps == null || !fps.isFinite() || fps <= 0f || fps > 1000f) return "Enter a frame rate above 0 and up to 1000"
                write(spec, String.format(Locale.US, "%.3f", fps).trimEnd('0').trimEnd('.'))
            }
            "edit_diy_bitrate" -> {
                val mbps = value.toFloatOrNull()
                if (mbps == null || !mbps.isFinite() || mbps <= 0 || mbps > 9999) return "Enter a bitrate above 0 and up to 9999 Mbps"
                val prefs = settings()
                prefs.edit().putString(spec.key, value).putInt("seekbar_bitrate_kbps", (mbps * 1000).roundToInt()).apply()
            }
            else -> write(spec, input.trim())
        }
        return null
    }


    /** Match the classic screen's fallback when device rules remove the selected resolution or FPS. */
    fun normalize(caps: SettingsCapabilities) {
        listOf("list_resolution", "list_fps", "video_format").forEach { key ->
            val spec = catalog.all.first { it.key == key }
            val choices = caps.choices(spec, state.value)
            if (choices.isNotEmpty() && choices.none { it.value == value(spec) }) {
                val fallback = if (key == "list_resolution") choices.lastOrNull { it in spec.choices } ?: choices.first()
                    else choices.firstOrNull { it.value == if (key == "list_fps") "60" else "auto" } ?: choices.first()
                write(spec, fallback.value)
            }
        }
        if (caps.brokenHdr) catalog.all.firstOrNull { it.key == "checkbox_enable_hdr" }?.let { write(it, false) }
    }

    fun close() { observed?.unregisterOnSharedPreferenceChangeListener(listener) }
}

internal const val CODEC_BITRATE_PREFIX = "ui_codec_bitrate_"
internal fun codecBitrateKey(codec: String) = CODEC_BITRATE_PREFIX + codec
internal fun isCodecBitrateStorageKey(key: String) =
    key.startsWith(CODEC_BITRATE_PREFIX) || (key.startsWith("ui_game_") && key.contains("_$CODEC_BITRATE_PREFIX"))
