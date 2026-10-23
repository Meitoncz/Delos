package com.limelight.shell.settings

import android.content.Context
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What Delos does when it starts (GUI.md, start screen): [defaultPc] uuid, connect to it right away with [skipStart]. */
data class Startup(val defaultPc: String? = null, val skipStart: Boolean = false)

/** Stores [Startup] in the default SharedPreferences (`ui_default_pc`, `ui_skip_start`). */
class StartupStore(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val state = MutableStateFlow(Startup(prefs.getString(KEY_DEFAULT_PC, null), prefs.getBoolean(KEY_SKIP_START, false)))

    val startup: StateFlow<Startup> = state.asStateFlow()

    fun update(transform: (Startup) -> Startup) {
        val next = transform(state.value)
        if (next == state.value) return
        prefs.edit().putString(KEY_DEFAULT_PC, next.defaultPc).putBoolean(KEY_SKIP_START, next.skipStart).apply()
        state.value = next
    }

    private companion object {
        const val KEY_DEFAULT_PC = "ui_default_pc"
        const val KEY_SKIP_START = "ui_skip_start"
    }
}
