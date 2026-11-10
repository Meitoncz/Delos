package com.limelight.shell.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.IBinder
import androidx.compose.runtime.Immutable
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.limelight.LimeLog
import com.limelight.computers.ComputerManagerListener
import com.limelight.computers.ComputerManagerService
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.utils.CacheHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.StringReader

/** A PC as the UI sees it: an immutable snapshot of the service's (mutable) [ComputerDetails]. */
@Immutable
data class Pc(
    val uuid: String,
    /** The name set in Delos (Devices → Rename), else the PC's own. */
    val name: String,
    val serverName: String,
    val online: Boolean,
    val paired: Boolean,
    val runningGameId: Int,
    /** Wake-on-LAN needs the PC's MAC address, known once it has been online. */
    val canWake: Boolean,
    /** The live object of the service, for streaming, box art and server requests. */
    val details: ComputerDetails,
)

/** An app or game of the active PC. */
@Immutable
data class App(val id: Int, val name: String, val hdr: Boolean, val running: Boolean, val nvApp: NvApp)

/** What the home screen shows. */
sealed interface Library {
    data object Loading : Library
    data object NoPc : Library
    data class Offline(val pc: Pc) : Library
    data class Ready(val pc: Pc, val apps: List<App>, val hidden: Set<Int>, val lastPlayed: Int) : Library {
        val hiddenCount: Int get() = hidden.size
    }
}

/**
 * Connects the new UI to [ComputerManagerService] (the same service the classic UI uses): PCs, their
 * state and the app list of the active PC. Polls only while the shell is started, like PcView/AppView.
 * What a profile keeps (hidden games, the last played game, names given to PCs, the PC home shows) is
 * read from the current profile's [settings].
 */
class PcRepository(context: Context, private val settings: () -> SharedPreferences) : DefaultLifecycleObserver {
    private val app = context.applicationContext
    private val prefs: SharedPreferences get() = settings()

    private val pcMap = LinkedHashMap<String, Pc>()
    private val _pcs = MutableStateFlow<List<Pc>>(emptyList())
    val pcs: StateFlow<List<Pc>> = _pcs.asStateFlow()

    private val _library = MutableStateFlow<Library>(Library.Loading)
    val library: StateFlow<Library> = _library.asStateFlow()

    private val _showHidden = MutableStateFlow(false)
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()

    @Volatile
    var binder: ComputerManagerService.ComputerManagerBinder? = null
        private set
    private var started = false
    private var paused = false
    private val forgotten = HashSet<String>()
    private var poller: ComputerManagerService.ApplistPoller? = null
    private var pollerPc: String? = null
    private var rawApps: String? = null
    private var parsedApps: List<NvApp> = emptyList()

    /** Set once the service has reported every known PC at least once. */
    private var everyPcReported = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val b = service as ComputerManagerService.ComputerManagerBinder
            Thread {
                b.waitForReady()
                binder = b
                synchronized(this@PcRepository) { if (started && !paused) startPolling() }
            }.start()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binder = null
        }
    }

    fun bind() {
        app.bindService(Intent(app, ComputerManagerService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        stopPolling()
        app.unbindService(connection)
    }

    override fun onStart(owner: LifecycleOwner) = synchronized(this) {
        started = true
        if (binder != null && !paused) startPolling()
    }

    override fun onStop(owner: LifecycleOwner) = synchronized(this) {
        started = false
        stopPolling()
    }

    /** The PC the home screen shows: the last chosen one, else the first paired one (online preferred). */
    private fun activePc(): Pc? {
        val paired = pcMap.values.filter { it.paired }
        val chosen = prefs.getString(KEY_ACTIVE_PC, null)
        return paired.firstOrNull { it.uuid == chosen } ?: paired.firstOrNull { it.online } ?: paired.firstOrNull()
    }

    fun selectPc(uuid: String) {
        prefs.edit().putString(KEY_ACTIVE_PC, uuid).apply()
        update()
    }

    fun setShowHidden(show: Boolean) {
        _showHidden.value = show
        update()
    }

    fun hiddenIds(pcUuid: String): Set<Int> =
        prefs.getStringSet(hiddenAppsKey(pcUuid), emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()

    fun setHidden(pcUuid: String, appId: Int, hidden: Boolean) {
        val ids = hiddenIds(pcUuid).toMutableSet()
        if (hidden) ids += appId else ids -= appId
        prefs.edit().putStringSet(hiddenAppsKey(pcUuid), ids.map(Int::toString).toSet()).apply()
        update()
    }

    private fun startPolling() {
        val b = binder ?: return
        b.startPolling(object : ComputerManagerListener {
            override fun notifyComputerUpdated(details: ComputerDetails) = onComputerUpdated(details)
        })
        // The service reports every known PC right away; if there are none, there is nothing to wait for
        Thread {
            Thread.sleep(1500)
            synchronized(this) {
                everyPcReported = true
                update()
            }
        }.start()
    }

    private fun stopPolling() {
        poller?.stop()
        poller = null
        pollerPc = null
        binder?.stopPolling()
    }

    private fun onComputerUpdated(details: ComputerDetails) = synchronized(this) {
        val uuid = details.uuid ?: return@synchronized
        // A PC removed in the meantime may still report once from its polling thread
        if (uuid in forgotten) return@synchronized
        pcMap[uuid] = snapshot(uuid, details)
        _pcs.value = pcMap.values.toList()
        if (uuid == activePc()?.uuid && details.rawAppList != null && details.rawAppList != rawApps) {
            parse(details.rawAppList)
        }
        update()
    }

    private fun snapshot(uuid: String, details: ComputerDetails): Pc {
        val serverName = details.name ?: "PC"
        return Pc(
            uuid = uuid,
            name = prefs.getString(customNameKey(uuid), null) ?: serverName,
            serverName = serverName,
            online = details.state == ComputerDetails.State.ONLINE,
            paired = details.pairState == PairingManager.PairState.PAIRED,
            runningGameId = details.runningGameId,
            canWake = details.macAddress != null,
            details = details,
        )
    }

    /** A name for the PC used only in Delos on this device; null or blank goes back to the PC's own. */
    fun rename(uuid: String, name: String?) = synchronized(this) {
        val clean = name?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit().apply { if (clean == null) remove(customNameKey(uuid)) else putString(customNameKey(uuid), clean) }.apply()
        val pc = pcMap[uuid] ?: return@synchronized
        pcMap[uuid] = snapshot(uuid, pc.details)
        _pcs.value = pcMap.values.toList()
        update()
    }

    /** Drops a PC the service has removed (Devices → Remove). */
    fun forget(uuid: String) = synchronized(this) {
        forgotten += uuid
        pcMap.remove(uuid)
        prefs.edit().remove(customNameKey(uuid)).apply()
        if (prefs.getString(KEY_ACTIVE_PC, null) == uuid) prefs.edit().remove(KEY_ACTIVE_PC).apply()
        _pcs.value = pcMap.values.toList()
        update()
    }

    /** A PC that is added again (pairing a removed PC once more) is shown again. */
    fun remember(uuid: String) = synchronized(this) { forgotten -= uuid }

    /** Stops polling while a PC is paired (like PcView), until [resumePolling]. */
    fun pausePolling() = synchronized(this) {
        paused = true
        stopPolling()
    }

    fun resumePolling() = synchronized(this) {
        paused = false
        if (started && binder != null) startPolling()
    }

    /** Restarts polling, which also searches the local network again. */
    fun rescan() = synchronized(this) {
        if (paused || !started || binder == null) return@synchronized
        stopPolling()
        startPolling()
    }

    /** Waits until [uuid] matches [condition] (online after Wake-on-LAN, paired after pairing); null after [timeoutMs]. */
    suspend fun awaitPc(uuid: String, timeoutMs: Long, condition: (Pc) -> Boolean): Pc? =
        withTimeoutOrNull(timeoutMs) { pcs.first { list -> list.any { it.uuid == uuid && condition(it) } }.first { it.uuid == uuid } }

    private fun parse(raw: String) {
        try {
            parsedApps = NvHTTP.getAppListByReader(StringReader(raw))
            rawApps = raw
        } catch (e: Exception) {
            LimeLog.warning("New UI: unreadable app list: $e") // l10n-ignore
        }
    }

    /** Recomputes [library] and keeps the app list poller on the active PC. */
    private fun update() = synchronized(this) {
        val pc = activePc()
        if (pc?.uuid != pollerPc) {
            poller?.stop()
            poller = null
            rawApps = null
            parsedApps = emptyList()
            pollerPc = pc?.uuid
            if (pc != null) {
                // The service's own ComputerDetails object, so the poller sees state changes
                val live = binder?.getComputer(pc.uuid) ?: pc.details
                loadCachedApps(pc.uuid)
                if (started) poller = binder?.createAppListPoller(live)?.also { it.start() }
            }
        }
        _library.value = when {
            pc == null -> if (everyPcReported) Library.NoPc else Library.Loading
            !pc.online && rawApps == null -> Library.Offline(pc)
            rawApps == null -> Library.Loading
            else -> {
                val hidden = hiddenIds(pc.uuid)
                val visible = parsedApps.filter { _showHidden.value || it.appId !in hidden }
                Library.Ready(
                    pc,
                    order(visible.map { App(it.appId, it.appName, it.isHdrSupported, it.appId == pc.runningGameId && pc.runningGameId != 0, it) }, pc.uuid),
                    hidden.filter { id -> parsedApps.any { it.appId == id } }.toSet(),
                    lastPlayed(pc.uuid),
                )
            }
        }
    }

    /**
     * The running game first, then every game played on this PC from the most recent one, then the ones
     * never played in the PC's order.
     */
    private fun order(apps: List<App>, pcUuid: String): List<App> {
        val played = playedTimes(pcUuid)
        val recent = apps.filter { it.id in played }.sortedByDescending { played.getValue(it.id) }
        val ordered = recent + apps.filter { it.id !in played }
        val running = ordered.firstOrNull { it.running } ?: return ordered
        return listOf(running) + (ordered - running)
    }

    /**
     * When each game on [pcUuid] was last started (app id → time), kept with the profile as
     * "id:time,id:time"; the single last played game of earlier versions counts as the most recent one.
     */
    private fun playedTimes(pcUuid: String): Map<Int, Long> {
        val stored = prefs.getString(playedKey(pcUuid), null)
        if (stored == null) {
            val last = prefs.getInt(lastPlayedKey(pcUuid), -1)
            return if (last >= 0) mapOf(last to 1L) else emptyMap()
        }
        return stored.split(',').mapNotNull { entry ->
            val (id, time) = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            (id.toIntOrNull() ?: return@mapNotNull null) to (time.toLongOrNull() ?: return@mapNotNull null)
        }.toMap()
    }

    /** Asks the service for a fresh state and app list of the active PC (after a game was started or quit). */
    fun refresh() {
        val uuid = pollerPc ?: return
        binder?.invalidateStateForComputer(uuid)
        poller?.pollNow()
    }

    fun lastPlayed(pcUuid: String): Int = prefs.getInt(lastPlayedKey(pcUuid), -1)

    fun setLastPlayed(pcUuid: String, appId: Int) {
        val played = playedTimes(pcUuid) + (appId to System.currentTimeMillis())
        prefs.edit()
            .putInt(lastPlayedKey(pcUuid), appId)
            .putString(playedKey(pcUuid), played.entries.joinToString(",") { "${it.key}:${it.value}" })
            .apply()
        update()
    }

    private fun loadCachedApps(uuid: String) {
        try {
            parse(CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(app.cacheDir, "applist", uuid)))
        } catch (_: Exception) {
            // No cached list yet: wait for the poller
        }
    }

    private fun lastPlayedKey(uuid: String) = "ui_last_app_$uuid"

    private fun playedKey(uuid: String) = "ui_played_apps_$uuid"

    private fun customNameKey(uuid: String) = "ui_pc_name_$uuid"

    /** Another profile's settings apply: its names for the PCs, its PC, hidden games and last played game. */
    fun profileChanged() = synchronized(this) {
        pcMap.replaceAll { uuid, pc -> snapshot(uuid, pc.details) }
        _pcs.value = pcMap.values.toList()
        update()
    }

    private companion object {
        const val KEY_ACTIVE_PC = "ui_active_pc"
    }
}
