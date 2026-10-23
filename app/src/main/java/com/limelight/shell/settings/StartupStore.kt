package com.limelight.shell.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What Delos does when it starts (GUI.md, start screen): [defaultPc] uuid, connect to it right away with [skipStart]. */
data class Startup(val defaultPc: String? = null, val skipStart: Boolean = false)

/** Stores [Startup] in the current profile's [settings] (`ui_default_pc`, `ui_skip_start`). */
class StartupStore(private val settings: () -> SharedPreferences) {
    private val state = MutableStateFlow(read())

    val startup: StateFlow<Startup> = state.asStateFlow()

    fun update(transform: (Startup) -> Startup) {
        val next = transform(state.value)
        if (next == state.value) return
        settings().edit().putString(KEY_DEFAULT_PC, next.defaultPc).putBoolean(KEY_SKIP_START, next.skipStart).apply()
        state.value = next
    }

    /** Reads the settings again (after switching profiles). */
    fun reload() {
        state.value = read()
    }

    private fun read() = settings().let { Startup(it.getString(KEY_DEFAULT_PC, null), it.getBoolean(KEY_SKIP_START, false)) }

    private companion object {
        const val KEY_DEFAULT_PC = "ui_default_pc"
        const val KEY_SKIP_START = "ui_skip_start"
    }
}
