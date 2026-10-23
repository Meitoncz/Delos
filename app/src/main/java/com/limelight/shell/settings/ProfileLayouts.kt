package com.limelight.shell.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.limelight.profiles.ProfilesManager
import org.json.JSONArray

/** Private copies of the editable control layouts; classic Artemis profiles keep their old storage. */
object ProfileLayouts {
    private val names = listOf("OSC", "OSC_Keyboard", "OSC_Keyboard_2", "OSC_Keyboard_3", "OSC_Keyboard_4", "OSC_Keyboard_5", "specialPrefs")

    @JvmStatic fun preferences(context: Context, name: String): SharedPreferences {
        val active = ProfilesManager.getInstance().active?.uuid?.toString()
        val users = PreferenceManager.getDefaultSharedPreferences(context).getString("ui_profiles", "[]")
        val user = active?.takeIf { id -> runCatching {
            val json = JSONArray(users)
            (0 until json.length()).any { json.getJSONObject(it).getString("id") == id }
        }.getOrDefault(false) }
        return storage(context, user, name)
    }

    fun initialize(context: Context, id: String, settings: SharedPreferences) {
        if (!settings.getBoolean("ui_layouts_initialized", false)) {
            copy(context, null, id)
            settings.edit().putBoolean("ui_layouts_initialized", true).apply()
        }
    }

    fun copy(context: Context, from: String?, into: String) {
        names.forEach { name ->
            val values = storage(context, from, name).all
            storage(context, into, name).edit().apply {
                clear()
                values.forEach { (key, value) -> putSetting(key, value) }
            }.apply()
        }
    }

    private fun storage(context: Context, id: String?, name: String) =
        context.getSharedPreferences(if (id == null) name else "ui_layout_${id}_$name", Context.MODE_PRIVATE)
}
