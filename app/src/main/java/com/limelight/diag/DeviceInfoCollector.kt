package com.limelight.diag

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.view.Display
import com.limelight.BuildConfig
import com.limelight.preferences.GlPreferences
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.SettingsCatalog
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Reads the facts of [DeviceInfo] from the system. Everything is read-only and needs no permission beyond network state. */
object DeviceInfoCollector {
    fun collect(context: Context): DeviceInfo {
        val app = context.applicationContext
        val defaults = SettingsCatalog(app).all.filter { it.key in StreamSettingsSnapshot.KEYS }.associate { it.key to it.default }
        return DeviceInfo(
            delosVersion = BuildConfig.VERSION_NAME,
            buildType = BuildConfig.BUILD_TYPE,
            versionCode = BuildConfig.VERSION_CODE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            androidRelease = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH.orEmpty(),
            buildId = Build.ID,
            soc = soc(),
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            gpu = GlPreferences.readPreferences(app).glRenderer.ifBlank { "unknown (no stream started yet)" },
            screen = screen(app),
            network = network(app),
            locale = Locale.getDefault().toLanguageTag(),
            // Only the offset, which is what reading the log's times needs; the region would say where the user lives
            timeZone = "UTC" + ZoneId.systemDefault().rules.getOffset(Instant.now()).id.replace("Z", "+00:00"),
            settings = StreamSettingsSnapshot.read(ProfileStore.currentSettings(app).all, defaults),
        )
    }

    private fun soc(): String {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim() else ""
        return name.ifBlank { Build.HARDWARE }
    }

    /** The default display's mode: size, refresh rate and density, as the stream sees it. */
    private fun screen(context: Context): String {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
            ?: return "unknown"
        val mode = display.mode
        return String.format(Locale.ROOT, "%dx%d at %.1f Hz, %d dpi", mode.physicalWidth, mode.physicalHeight, mode.refreshRate,
            context.resources.displayMetrics.densityDpi)
    }

    /** The kind of the active network; never its name (the Wi-Fi name is personal data). */
    private fun network(context: Context): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "unknown"
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return "none") ?: return "none"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "other"
        }
    }
}
