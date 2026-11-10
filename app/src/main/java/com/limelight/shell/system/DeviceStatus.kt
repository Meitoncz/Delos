package com.limelight.shell.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.text.format.DateFormat
import com.limelight.shell.settings.ClockFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Battery of the handheld: [level] 0–1. */
@Immutable
data class BatteryStatus(val level: Float, val charging: Boolean)

/** Wi-Fi: [level] 0–4 signal bars, null when there is no Wi-Fi connection. */
@Immutable
data class WifiStatus(val level: Int?)

/** Battery level and charging state, from the sticky battery broadcast. */
@Composable
fun rememberBatteryStatus(): State<BatteryStatus?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<BatteryStatus?>(null) }
    DisposableEffect(context) {
        fun update(intent: Intent?) {
            intent ?: return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            state.value = BatteryStatus(
                level / scale.toFloat(),
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            )
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = update(intent)
        }
        update(context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

/** Wi-Fi connection and signal strength of the default network. */
@Composable
fun rememberWifiStatus(): State<WifiStatus> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(WifiStatus(null)) }
    DisposableEffect(context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                state.value = WifiStatus(if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) bars(wifi, caps.signalStrength) else null)
            }

            override fun onLost(network: Network) {
                state.value = WifiStatus(null)
            }
        }
        connectivity?.registerDefaultNetworkCallback(callback)
        onDispose { connectivity?.unregisterNetworkCallback(callback) }
    }
    return state
}

/** RSSI → 0–4 bars (4 = full). */
private fun bars(wifi: WifiManager?, rssi: Int): Int {
    if (rssi == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) return 4
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wifi != null) {
        (wifi.calculateSignalLevel(rssi) * 4f / wifi.maxSignalLevel.coerceAtLeast(1)).roundToInt()
    } else {
        @Suppress("DEPRECATION")
        WifiManager.calculateSignalLevel(rssi, 5)
    }
}

/**
 * The current time as a 24 or 12 hour clock in the app language ([format]; System follows Android), updated on
 * every minute.
 */
@Composable
fun rememberClock(format: ClockFormat): State<String> {
    val context = LocalContext.current
    val h24 = when (format) {
        ClockFormat.SYSTEM -> DateFormat.is24HourFormat(context)
        ClockFormat.H12 -> false
        ClockFormat.H24 -> true
    }
    // The pattern and the AM / PM text follow the app language; the key re-reads them when it changes
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    // ICU 72 puts U+202F before AM / PM; the shell fonts have no glyph for it
    val pattern = remember(h24, locale) { DateFormat.getBestDateTimePattern(locale, if (h24) "Hm" else "hm").replace('\u202f', ' ') }
    fun now() = SimpleDateFormat(pattern, locale).format(Date())
    val state = remember(pattern) { mutableStateOf(now()) }
    LaunchedEffect(context, pattern) {
        while (true) {
            state.value = now()
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    return state
}
