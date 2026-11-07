package com.limelight.shell.ui

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.limelight.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Debug builds only: A/B switches for measuring what the home screen costs per frame, set while the app runs with
 * `adb shell setprop debug.delos.perf <letters>` (read again once a second by [PerfFlagsPoller]; `setprop debug.delos.perf ""`
 * clears it). Every letter disables or reverts one thing; with none set the app behaves normally. Release builds ignore
 * the property entirely ([has] is false, the poller returns at once).
 *
 *  - a: the game background's opacity as one full-screen offscreen layer (the old way) instead of `ModulateAlpha`
 *  - b: Compose's `Crossfade` for the game backgrounds instead of `HeroCrossfade`
 *  - c: the animated background repaints at 60 Hz under a game background
 *  - d: every tile has its glow shadow, not only the focused one
 *  - e: no animated background
 *  - f: no game background
 *  - g: the game background is always the still one (never the playing animation)
 *  - h: no animated tile, logo or game page artwork (first frames only)
 *  - i: the playing hero's layer at full resolution (no low-resolution layer)
 *  - j: the game background does not tilt
 *  - k: no focus pulse, glow or sweep on the elements
 */
object PerfFlags {
    private var letters by mutableStateOf("")

    /** Whether switch [letter] is set; read in composition, a layer block or a draw block and it updates when the property changes. */
    fun has(letter: Char): Boolean = BuildConfig.DEBUG && letter in letters

    internal fun update(value: String) {
        if (value != letters) letters = value
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    internal fun read(): String = try {
        val properties = Class.forName("android.os.SystemProperties")
        properties.getMethod("get", String::class.java, String::class.java).invoke(null, "debug.delos.perf", "") as? String ?: ""
    } catch (e: Exception) {
        ""
    }
}

/** Puts it in the shell root: re-reads `debug.delos.perf` once a second so the switches work without restarting the app. */
@Composable
fun PerfFlagsPoller() {
    if (!BuildConfig.DEBUG) return
    LaunchedEffect(Unit) {
        while (true) {
            PerfFlags.update(withContext(Dispatchers.IO) { PerfFlags.read() })
            delay(1000)
        }
    }
}
