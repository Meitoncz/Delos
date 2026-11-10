package com.limelight.shell.data

import android.app.Activity
import android.content.Intent
import com.limelight.DelosStreamActivity
import android.content.res.Resources
import com.limelight.R
import com.limelight.binding.PlatformBinding
import com.limelight.computers.ComputerManagerService
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.HostHttpResponseException
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.shell.settings.GameSettingsStore
import com.limelight.utils.ExternalDisplayControlActivity
import com.limelight.utils.ServerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Starts and quits games from the new UI. The stream itself is the classic `DelosStreamActivity`, started
 * exactly like the classic AppView does (ServerHelper.createStartIntent).
 */
class GameLauncher(private val activity: Activity, private val repo: PcRepository) {

    /** What to start, and whether the virtual display had to be dropped. */
    data class Start(val intent: Intent, val virtualDisplayUnavailable: Boolean)

    /**
     * The intent that starts [app], or null when the PC cannot be reached. Uses the virtual display
     * setting; if the PC has no working virtual display driver it streams the main display instead of
     * asking (no confirmation dialogs in Delos).
     */
    fun prepare(pc: Pc, app: App): Start? {
        val binder = repo.binder ?: return null
        val details = binder.getComputer(pc.uuid) ?: pc.details
        if (details.activeAddress == null) return null
        val start = startIntent(activity, binder, details, app.nvApp)
        val intent = start.intent
        val streamIntent = ServerHelper.getStreamIntent(intent)
        // The PC's name as set in Delos goes to the stream's loader (the new UI's half of the launch scene).
        // External-display launches wrap DelosStreamActivity in a controller Activity, so it belongs to the inner stream intent.
        streamIntent.putExtra(DelosStreamActivity.EXTRA_PC_NAME, pc.name)
        if (streamIntent !== intent) {
            intent.putExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT, streamIntent)
        }
        return start
    }

    /** Quits the running game on the PC; [QuitResult.text] is the message to show. */
    suspend fun quit(pc: Pc, app: App): QuitResult = withContext(Dispatchers.IO) {
        val binder = repo.binder ?: return@withContext QuitResult(QuitOutcome.STARTING)
        val details = binder.getComputer(pc.uuid) ?: pc.details
        try {
            val http = NvHTTP(
                ServerHelper.getCurrentAddressFromComputer(details), details.httpsPort, binder.uniqueId, details.serverCert,
                PlatformBinding.getCryptoProvider(activity),
            )
            if (http.quitApp()) QuitResult(QuitOutcome.QUIT) else QuitResult(QuitOutcome.NOT_QUIT)
        } catch (e: HostHttpResponseException) {
            if (e.errorCode == 599) QuitResult(QuitOutcome.OTHER_DEVICE) else QuitResult(QuitOutcome.NOT_QUIT, e.message)
        } catch (e: Exception) {
            QuitResult(QuitOutcome.UNREACHABLE)
        } finally {
            repo.refresh()
        }
    }

    enum class QuitOutcome { QUIT, STARTING, NOT_QUIT, OTHER_DEVICE, UNREACHABLE }

    /** [detail] is the PC's own message when it refused to quit the game (English, shown as it is). */
    data class QuitResult(val outcome: QuitOutcome, val detail: String? = null) {
        val success: Boolean get() = outcome == QuitOutcome.QUIT

        fun text(res: Resources, pcName: String, appName: String): String = when (outcome) {
            QuitOutcome.QUIT -> res.getString(R.string.home_quit_done, appName)
            QuitOutcome.STARTING -> res.getString(R.string.home_quit_starting, pcName)
            QuitOutcome.NOT_QUIT -> detail ?: res.getString(R.string.home_quit_failed, appName)
            QuitOutcome.OTHER_DEVICE -> res.getString(R.string.home_quit_other_device, appName)
            QuitOutcome.UNREACHABLE -> res.getString(R.string.devices_unreachable, pcName)
        }
    }

    companion object {
        /**
         * The intent that starts [app] on [details] with the current profile's settings and this game's
         * overrides attached, so every way to start a game (the new UI, launcher and PC shortcuts) streams
         * with the same values. [Start.virtualDisplayUnavailable] is set when the virtual display was wanted
         * but the PC has no working driver; the main display is streamed then.
         */
        @JvmStatic
        fun startIntent(
            activity: Activity,
            binder: ComputerManagerService.ComputerManagerBinder,
            details: ComputerDetails,
            app: NvApp,
        ): Start {
            val settings = GameSettingsStore(ProfileStore.currentSettings(activity), details.uuid, app.appId)
            val wantsVirtual = settings.preferences.getBoolean("checkbox_use_virtual_display",
                PreferenceConfiguration.readPreferences(activity).useVirtualDisplay)
            val virtualReady = details.vDisplaySupported && details.vDisplayDriverReady
            val intent = ServerHelper.createStartIntent(activity, app, details, binder, wantsVirtual && virtualReady)
            GameSettingsStore.attach(intent, settings.snapshot())
            return Start(intent, wantsVirtual && !virtualReady)
        }
    }
}
