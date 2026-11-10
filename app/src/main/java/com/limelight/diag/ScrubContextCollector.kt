package com.limelight.diag

import android.content.Context
import android.provider.Settings
import androidx.preference.PreferenceManager
import com.limelight.computers.ComputerDatabaseManager
import com.limelight.shell.data.ProfileStore
import org.json.JSONArray

/**
 * Gathers the values [LogScrubber] must remove by value: the PCs in the stored list (their own names, the names
 * given in Delos, their addresses), the profile names, this install's client id and the stored API key. Called at
 * export time, on a worker thread, so a PC added or renamed a minute ago is covered.
 */
object ScrubContextCollector {
    fun collect(context: Context): ScrubContext {
        val app = context.applicationContext
        val pcNames = LinkedHashSet<String>()
        val hosts = LinkedHashSet<String>()
        val identifiers = LinkedHashSet<String>()
        val names = LinkedHashSet<String>()
        val secrets = LinkedHashSet<String>()

        runCatching {
            val database = ComputerDatabaseManager(app)
            try {
                database.allComputers.forEach { pc ->
                    pc.name?.let(pcNames::add)
                    pc.uuid?.let(identifiers::add)
                    pc.macAddress?.let(hosts::add)
                    listOf(pc.localAddress, pc.remoteAddress, pc.manualAddress, pc.ipv6Address).forEach { tuple -> tuple?.address?.let(hosts::add) }
                }
            } finally {
                database.close()
            }
        }

        val defaults = PreferenceManager.getDefaultSharedPreferences(app)
        defaults.getString("sgdb_api_key", null)?.let(secrets::add)
        runCatching {
            val profiles = JSONArray(defaults.getString("ui_profiles", "[]"))
            for (i in 0 until profiles.length()) {
                val profile = profiles.getJSONObject(i)
                names += profile.optString("name")
                identifiers += profile.optString("id")
                // The names given to PCs in Delos live in each profile's own preferences
                app.getSharedPreferences("ui_profile_${profile.optString("id")}", Context.MODE_PRIVATE).all
                    .filterKeys { it.startsWith("ui_pc_name_") }.values.forEach { (it as? String)?.let(pcNames::add) }
            }
        }
        ProfileStore.currentSettings(app).all.filterKeys { it.startsWith("ui_pc_name_") }.values.forEach { (it as? String)?.let(pcNames::add) }

        runCatching { app.openFileInput("uniqueid").bufferedReader().use { it.readText().trim() } }.getOrNull()?.let(identifiers::add)
        Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)?.let(identifiers::add)
        runCatching { Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()?.let(names::add)

        return ScrubContext(
            pcNames = pcNames.filter { it.isNotBlank() },
            hostAddresses = hosts.filter { it.isNotBlank() },
            names = names.filter { it.isNotBlank() },
            identifiers = identifiers.filter { it.isNotBlank() },
            secrets = secrets.filter { it.isNotBlank() },
        )
    }
}
