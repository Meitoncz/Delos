package com.limelight.shell.data

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import com.limelight.R
import com.limelight.binding.PlatformBinding
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.http.PairingManager.PairState
import com.limelight.nvstream.wol.WakeOnLanSender
import com.limelight.utils.ServerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.net.UnknownHostException

/**
 * Adding, pairing, waking, unpairing and removing PCs for the new UI, with the same backend calls as
 * the classic PcView and AddComputerManually. Every call runs off the main thread and reports what to
 * show instead of opening dialogs.
 */
open class PcSetup(context: Context, private val repo: PcRepository, private val profiles: ProfileStore) {
    private val app = context.applicationContext

    /** Why a pairing did not happen; [PairResult.Failed.text] turns it into the sentence to show. */
    enum class PairFailure { STARTING, OFFLINE, PIN_WRONG, CODE_WRONG, BUSY, GAME_RUNNING, NOT_FOUND, UNSUPPORTED, FAILED }

    sealed interface PairResult {
        data object Paired : PairResult

        /** [detail] is the backend's own message of an unexpected [PairFailure.FAILED] (English, shown as it is). */
        data class Failed(val reason: PairFailure, val detail: String? = null) : PairResult {
            fun text(res: Resources, pcName: String): String = when (reason) {
                PairFailure.STARTING -> res.getString(R.string.devices_starting)
                PairFailure.OFFLINE -> res.getString(R.string.pairing_failed_offline, pcName)
                PairFailure.PIN_WRONG -> res.getString(R.string.pairing_failed_pin_wrong)
                PairFailure.CODE_WRONG -> res.getString(R.string.pairing_failed_code_wrong)
                PairFailure.BUSY -> res.getString(R.string.pairing_failed_busy, pcName)
                PairFailure.GAME_RUNNING -> res.getString(R.string.pairing_failed_game_running, pcName)
                PairFailure.NOT_FOUND -> res.getString(R.string.pairing_failed_not_found, pcName)
                PairFailure.UNSUPPORTED -> res.getString(R.string.pairing_failed_unsupported, pcName)
                PairFailure.FAILED -> detail ?: res.getString(R.string.pairing_failed, pcName)
            }
        }
    }

    /** What adding a PC by address came to. */
    sealed interface AddResult {
        /** The new PC's uuid. */
        data class Added(val uuid: String) : AddResult
        data object Starting : AddResult
        data object NotAnAddress : AddResult
        data object NoAnswer : AddResult

        /** The sentence to show when nothing was added; null once [Added]. */
        fun errorText(res: Resources, address: String): String? = when (this) {
            is Added -> null
            Starting -> res.getString(R.string.devices_starting)
            NotAnAddress -> res.getString(R.string.devices_add_not_an_address, address)
            NoAnswer -> res.getString(R.string.devices_add_no_answer, address)
        }
    }

    /** What waking a PC came to. */
    enum class WakeResult {
        SENT, NO_MAC, FAILED;

        /** The sentence to show when the signal was not sent; null once [SENT]. */
        fun errorText(res: Resources, pcName: String): String? = when (this) {
            SENT -> null
            NO_MAC -> res.getString(R.string.devices_wake_unsupported, pcName)
            FAILED -> res.getString(R.string.devices_wake_failed)
        }
    }

    /** What unpairing a PC came to; [text] is the sentence to show. */
    enum class UnpairResult {
        UNPAIRED, STARTING, OFFLINE, NOT_PAIRED, FAILED, UNREACHABLE;

        val success: Boolean get() = this == UNPAIRED

        fun text(res: Resources, pcName: String): String = when (this) {
            UNPAIRED -> res.getString(R.string.devices_unpaired, pcName)
            STARTING -> res.getString(R.string.devices_starting)
            OFFLINE -> res.getString(R.string.devices_unpair_offline, pcName)
            NOT_PAIRED -> res.getString(R.string.devices_unpair_not_paired, pcName)
            FAILED -> res.getString(R.string.devices_unpair_failed, pcName)
            UNREACHABLE -> res.getString(R.string.devices_unreachable, pcName)
        }
    }

    /** A new PIN for the user to type on the PC. */
    open fun newPin(): String = PairingManager.generatePinString()

    /**
     * Pairs [pc] with [pin] (shown to the user, or the PIN of a one-time code with its [passphrase]).
     * Blocks until the PIN is entered on the PC; cancelling the coroutine abandons the attempt.
     */
    open suspend fun pair(pc: Pc, pin: String, passphrase: String?): PairResult {
        val binder = repo.binder ?: return PairResult.Failed(PairFailure.STARTING)
        val details = binder.getComputer(pc.uuid) ?: pc.details
        if (details.state != ComputerDetails.State.ONLINE || details.activeAddress == null) {
            return PairResult.Failed(PairFailure.OFFLINE)
        }
        repo.pausePolling()
        try {
            return runInterruptible(Dispatchers.IO) {
                try {
                    val http = http(details, binder.uniqueId)
                    if (http.pairState == PairState.PAIRED) return@runInterruptible PairResult.Paired
                    val pm = http.pairingManager
                    when (pm.pair(http.getServerInfo(true), pin, passphrase)) {
                        PairState.PAIRED -> {
                            // Pin the certificate for later HTTPS use, and read the pair state again
                            binder.getComputer(pc.uuid)?.serverCert = pm.pairedCert
                            binder.invalidateStateForComputer(pc.uuid)
                            PairResult.Paired
                        }
                        PairState.PIN_WRONG -> PairResult.Failed(if (passphrase == null) PairFailure.PIN_WRONG else PairFailure.CODE_WRONG)
                        PairState.ALREADY_IN_PROGRESS -> PairResult.Failed(PairFailure.BUSY)
                        else -> PairResult.Failed(if (details.runningGameId != 0) PairFailure.GAME_RUNNING else PairFailure.FAILED)
                    }
                } catch (e: UnknownHostException) {
                    PairResult.Failed(PairFailure.NOT_FOUND)
                } catch (e: FileNotFoundException) {
                    PairResult.Failed(PairFailure.UNSUPPORTED)
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    PairResult.Failed(PairFailure.FAILED, e.message)
                }
            }
        } finally {
            repo.resumePolling()
        }
    }

    /** Adds the PC at [address] (host or IP, optionally with a port). */
    suspend fun add(address: String): AddResult = withContext(Dispatchers.IO) {
        val binder = repo.binder ?: return@withContext AddResult.Starting
        val uri = parseAddress(address.trim()) ?: return@withContext AddResult.NotAnAddress
        val details = ComputerDetails()
        details.manualAddress = ComputerDetails.AddressTuple(uri.host, if (uri.port == -1) NvHTTP.DEFAULT_HTTP_PORT else uri.port)
        val added = try {
            binder.addComputerBlocking(details)
        } catch (e: IllegalArgumentException) {
            // OkHttp cannot canonicalize the host name
            return@withContext AddResult.NotAnAddress
        }
        val uuid = details.uuid
        if (added && uuid != null) {
            repo.remember(uuid)
            AddResult.Added(uuid)
        } else {
            AddResult.NoAnswer
        }
    }

    /** Sends the Wake-on-LAN packet. */
    suspend fun wake(pc: Pc): WakeResult = withContext(Dispatchers.IO) {
        val details = repo.binder?.getComputer(pc.uuid) ?: pc.details
        if (details.macAddress == null) return@withContext WakeResult.NO_MAC
        try {
            WakeOnLanSender.sendWolPacket(details)
            WakeResult.SENT
        } catch (e: Exception) {
            WakeResult.FAILED
        }
    }

    /** Unpairs [pc] on the PC itself. */
    suspend fun unpair(pc: Pc): UnpairResult = withContext(Dispatchers.IO) {
        val binder = repo.binder ?: return@withContext UnpairResult.STARTING
        val details = binder.getComputer(pc.uuid) ?: pc.details
        if (details.state != ComputerDetails.State.ONLINE || details.activeAddress == null) {
            return@withContext UnpairResult.OFFLINE
        }
        try {
            val http = http(details, binder.uniqueId)
            if (http.pairState != PairState.PAIRED) return@withContext UnpairResult.NOT_PAIRED
            http.unpair()
            val ok = http.pairState == PairState.NOT_PAIRED
            if (ok) binder.invalidateStateForComputer(pc.uuid)
            if (ok) UnpairResult.UNPAIRED else UnpairResult.FAILED
        } catch (e: Exception) {
            UnpairResult.UNREACHABLE
        }
    }

    /** Removes [pc] from Delos (it can be found and paired again). */
    fun remove(pc: Pc) {
        val binder = repo.binder ?: return
        binder.removeComputer(binder.getComputer(pc.uuid) ?: pc.details)
        repo.forget(pc.uuid)
        profiles.forgetGameSettings(pc.uuid)
    }

    private fun http(details: ComputerDetails, uniqueId: String) = NvHTTP(
        ServerHelper.getCurrentAddressFromComputer(details), details.httpsPort, uniqueId, details.serverCert,
        PlatformBinding.getCryptoProvider(app),
    )

    /** Like AddComputerManually: 192.168.1.20, 192.168.1.20:47989, host.local, [::1] or ::1. */
    private fun parseAddress(input: String): Uri? {
        if (input.isEmpty()) return null
        Uri.parse("art://$input").takeIf { !it.host.isNullOrEmpty() }?.let { return it }
        return Uri.parse("art://[$input]").takeIf { !it.host.isNullOrEmpty() }
    }
}
