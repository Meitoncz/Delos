package com.limelight.shell.companion

import android.content.Context
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.preference.PreferenceManager
import com.limelight.display.DualScreen
import com.limelight.display.MainScreenChoice
import com.limelight.display.PanelPair
import com.limelight.display.SecondDisplayOverride

/**
 * The dual-screen plan of the device ([DualScreen.plan]) as Compose state that follows displays being added, changed or removed
 * and the two display settings; null while the device has no built-in companion display. Recomposes only when the plan itself
 * changes, not on every display event (brightness and rotation also arrive as "changed").
 */
@Composable
fun rememberDualScreenPlan(): State<PanelPair?> {
    val app = LocalContext.current.applicationContext
    val plan = remember { mutableStateOf(DualScreen.plan(app)) }
    DisposableEffect(app) {
        val manager = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        fun refresh() {
            val next = DualScreen.plan(app)
            if (plan.value != next) plan.value = next
        }
        val displays = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = refresh()
            override fun onDisplayRemoved(displayId: Int) = refresh()
            override fun onDisplayChanged(displayId: Int) = refresh()
        }
        val settings = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SecondDisplayOverride.KEY || key == MainScreenChoice.KEY) refresh()
        }
        manager.registerDisplayListener(displays, Handler(Looper.getMainLooper()))
        prefs.registerOnSharedPreferenceChangeListener(settings)
        refresh()
        onDispose {
            manager.unregisterDisplayListener(displays)
            prefs.unregisterOnSharedPreferenceChangeListener(settings)
        }
    }
    return plan
}
