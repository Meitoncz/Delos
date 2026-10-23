package com.limelight.shell.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.profiles.ProfilesManager
import com.limelight.profiles.SettingsProfile
import com.limelight.shell.data.Profile
import java.util.UUID

/**
 * The user's preferences are authoritative; their stream keys are projected into an ordinary Artemis
 * settings profile. The stream can keep using ProfilesManager without knowing about the new shell.
 * Complete snapshots (including defaults) prevent a later edit of global settings leaking across users.
 */
class StreamProfileStore(private val context: Context, val catalog: SettingsCatalog) {
    private val manager = ProfilesManager.getInstance()
    private val keys = catalog.all.filter { it.kind != SettingKind.ACTION }.map { it.key }.toSet()
    private var current: Profile? = null
    private var prefs: SharedPreferences? = null
    private var projecting = false
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!projecting && key in keys) project()
    }

    /** Seeds older user profiles once, or copies all stream values as part of normal profile creation. */
    fun activate(profile: Profile?, settings: SharedPreferences) {
        prefs?.unregisterOnSharedPreferenceChangeListener(listener)
        current = profile
        prefs = settings
        if (profile == null) return
        if (!settings.getBoolean(INITIALIZED, false)) {
            val source = manager.getOverlayingSharedPreferences(context).all
            val editor = settings.edit()
            catalog.all.filter { it.kind != SettingKind.ACTION }.forEach { spec ->
                editor.putSetting(spec.key, source[spec.key] ?: defaultValue(spec, source))
            }
            editor.putBoolean(INITIALIZED, true).apply()
        }
        resume()
        settings.registerOnSharedPreferenceChangeListener(listener)
    }

    /** Picks up edits made by the classic profile editor, and restores this user's active profile. */
    fun resume() {
        val profile = current ?: return
        val settings = prefs ?: return
        val linked = manager.profiles.firstOrNull { it.uuid.toString() == profile.id }
        if (linked != null) {
            projecting = true
            try {
                val editor = settings.edit()
                linked.options?.forEach { (key, value) -> if (key in keys) editor.putSetting(key, value) }
                editor.apply()
            } finally { projecting = false }
        }
        project()
    }

    fun rename(profile: Profile) {
        manager.profiles.firstOrNull { it.uuid.toString() == profile.id }?.let {
            it.name = "Delos · ${profile.name}"
            manager.update(it)
        }
        if (current?.id == profile.id) current = profile
    }

    /** Removes the linked Artemis settings profile of a deleted user profile. */
    fun delete(id: String) {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return
        if (current?.id == id) {
            prefs?.unregisterOnSharedPreferenceChangeListener(listener)
            current = null
            prefs = null
        }
        if (manager.profiles.any { it.uuid == uuid }) manager.delete(uuid)
    }

    private fun project() {
        val profile = current ?: return
        val settings = prefs ?: return
        val id = UUID.fromString(profile.id)
        val options = settings.all.filterKeys { it in keys }.toMutableMap()
        val existing = manager.profiles.firstOrNull { it.uuid == id }
        val now = System.currentTimeMillis()
        if (existing == null) {
            manager.add(SettingsProfile(id, "Delos · ${profile.name}", now, now, options))
        } else if (existing.options != options || existing.name != "Delos · ${profile.name}") {
            existing.options = options
            existing.name = "Delos · ${profile.name}"
            existing.modifiedUtc = now
            manager.update(existing)
        }
        if (manager.active?.uuid != id) manager.setActive(id)
    }

    private fun defaultValue(spec: SettingSpec, source: Map<String, *>): Any? = when (spec.key) {
        "seekbar_bitrate_kbps" -> PreferenceConfiguration.getDefaultBitrate(
            source["list_resolution"] as? String ?: "1280x720", source["list_fps"] as? String ?: "60",
        )
        "seekbar_metered_bitrate_kbps" -> 0
        "checkbox_small_icon_mode" -> PreferenceConfiguration.getDefaultSmallMode(context)
        "checkbox_gamepad_motion_sensors" -> Build.VERSION.SDK_INT != 31 && (spec.default as? Boolean ?: true)
        else -> spec.default
    }

    fun close() {
        prefs?.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object { private const val INITIALIZED = "ui_stream_initialized" }
}

internal fun SharedPreferences.Editor.putSetting(key: String, value: Any?): SharedPreferences.Editor = apply {
    when (value) {
        null -> remove(key)
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is Double -> putInt(key, value.toInt()) // Gson deserializes Artemis integer options as doubles.
        is String -> putString(key, value)
        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
    }
}
