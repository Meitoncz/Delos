package com.limelight.shell.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import com.limelight.Game
import com.limelight.utils.ExternalDisplayControlActivity
import java.util.UUID

/** Sparse overrides belong to a user, PC and app. Unchanged options keep following the user profile. */
class GameSettingsStore(private val base: SharedPreferences, pc: String, appId: Int) {
    private val prefix = prefixFor(pc, appId)
    val preferences: SharedPreferences = object : ValueOverlay(base, { overrides() }), BitrateOverrideState {
        override val hasBitrateOverride: Boolean get() = isCustom("seekbar_bitrate_kbps")
        override fun edit(): SharedPreferences.Editor = GameEditor(base.edit())
        override fun contains(key: String) =
            if (key.startsWith(CODEC_BITRATE_PREFIX)) base.contains(prefix + key) else super.contains(key)
        override fun getInt(key: String, defValue: Int) =
            if (key.startsWith(CODEC_BITRATE_PREFIX)) base.getInt(prefix + key, defValue) else super.getInt(key, defValue)
    }

    fun overrides(): Map<String, *> = base.all.filterKeys { it.startsWith(prefix) }
        .mapKeys { it.key.removePrefix(prefix) }.filterKeys { it in KEYS }

    fun isCustom(key: String) = key in KEYS && base.contains(prefix + key)
    fun reset(key: String) {
        if (key !in KEYS) return
        val editor = base.edit().remove(prefix + key)
        if (key == "seekbar_bitrate_kbps") clearCodecBitrates(editor)
        editor.apply()
    }
    fun resetAll() { preferences.edit().clear().apply() }

    private fun clearCodecBitrates(editor: SharedPreferences.Editor) {
        val slots = prefix + CODEC_BITRATE_PREFIX
        base.all.keys.forEach { key -> if (key.startsWith(slots)) editor.remove(key) }
    }

    /** Capture the effective values for this launch, including inherited values. Never activate a profile. */
    fun snapshot(): Bundle = Bundle().apply {
        val effective = preferences.all
        KEYS.forEach { key ->
            when (val value = effective[key]) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
            }
        }
    }

    private inner class GameEditor(private val editor: SharedPreferences.Editor) : SharedPreferences.Editor {
        private fun put(key: String, value: Any?) = apply {
            require(key in KEYS || key.startsWith(CODEC_BITRATE_PREFIX)) { "Not a per-game setting: $key" }
            editor.putSetting(prefix + key, value)
        }
        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values)
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String) = put(key, null)
        override fun clear() = apply {
            KEYS.forEach { editor.remove(prefix + it) }
            clearCodecBitrates(editor)
        }
        override fun commit() = editor.commit()
        override fun apply() = editor.apply()
    }

    companion object {
        val STREAM_KEYS = listOf("list_resolution", "list_fps", "seekbar_bitrate_kbps", "video_format", "frame_pacing", "checkbox_async_decode")
        val DISPLAY_KEYS = listOf("checkbox_enable_hdr", "checkbox_full_range", "list_video_scale_mode",
            "seekbar_resolution_scale_factor", "checkbox_use_virtual_display", "checkbox_enforce_display_mode", "checkbox_sgsr", "list_sgsr_quality", "seekbar_sgsr_sharpness")
        val KEYS = (STREAM_KEYS + DISPLAY_KEYS).toSet()
        private const val EXTRA = "com.limelight.shell.GAME_SETTINGS"

        private fun pcPrefix(pc: String) = "ui_game_${UUID.nameUUIDFromBytes(pc.toByteArray(Charsets.UTF_8))}_"
        private fun prefixFor(pc: String, appId: Int) = "${pcPrefix(pc)}${appId}_"

        /** Removes every override of every app on [pc] from [base] (a PC that Delos forgets). */
        fun purge(base: SharedPreferences, pc: String) {
            val prefix = pcPrefix(pc)
            val stale = base.all.keys.filter { it.startsWith(prefix) }
            if (stale.isNotEmpty()) base.edit().apply { stale.forEach { remove(it) } }.apply()
        }

        /** External-display launches wrap the actual Game intent; attach the snapshot to that inner intent. */
        fun attach(intent: Intent, snapshot: Bundle) {
            @Suppress("DEPRECATION")
            val nested = intent.getParcelableExtra<Intent>(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT)
            if (nested != null) {
                attach(nested, snapshot)
                intent.putExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT, nested)
            } else intent.putExtra(EXTRA, Bundle(snapshot))
        }

        /** Delos: only the stream Activity sees its launch snapshot, including through wrapped contexts. */
        @JvmStatic fun overlay(context: Context, base: SharedPreferences): SharedPreferences {
            var current = context
            while (current is ContextWrapper && current !is Activity) {
                val next = current.baseContext
                if (next === current) break
                current = next
            }
            val game = current as? Game ?: return base
            val snapshot = game.intent?.getBundleExtra(EXTRA) ?: return base
            @Suppress("DEPRECATION")
            val values = KEYS.mapNotNull { key ->
                val value = snapshot.get(key)
                val valid = when {
                    key.startsWith("checkbox_") -> value is Boolean
                    key.startsWith("seekbar_") -> value is Int
                    else -> value is String
                }
                if (valid) key to value else null
            }.toMap()
            return ValueOverlay(base) { values }
        }
    }
}

/** Identifies per-game preferences and whether the active bitrate is owned or inherited. */
internal interface BitrateOverrideState {
    val hasBitrateOverride: Boolean
}

/** Reads through to the base for every non-overridden value; listeners still observe profile edits. */
private open class ValueOverlay(
    private val base: SharedPreferences,
    private val patch: () -> Map<String, *>,
) : SharedPreferences by base {
    override fun getAll(): Map<String, *> = (base.all + patch()).filterKeys { !isCodecBitrateStorageKey(it) }
    override fun contains(key: String) = patch().containsKey(key) || base.contains(key)
    override fun getString(key: String, defValue: String?): String? {
        val values = patch()
        return if (values.containsKey(key)) values[key] as? String ?: defValue else base.getString(key, defValue)
    }
    override fun getInt(key: String, defValue: Int) = (patch()[key] as? Number)?.toInt() ?: base.getInt(key, defValue)
    override fun getLong(key: String, defValue: Long) = (patch()[key] as? Number)?.toLong() ?: base.getLong(key, defValue)
    override fun getFloat(key: String, defValue: Float) = (patch()[key] as? Number)?.toFloat() ?: base.getFloat(key, defValue)
    override fun getBoolean(key: String, defValue: Boolean) = patch()[key] as? Boolean ?: base.getBoolean(key, defValue)
}
