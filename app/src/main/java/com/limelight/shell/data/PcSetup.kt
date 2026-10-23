package com.limelight.shell.data

import android.content.Context
import android.net.Uri
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
class PcSetup(context: Context, private val repo: PcRepository) {
    private val app = context.applicationContext

    sealed interface PairResult {
        data object Paired : PairResult
        data class Failed(val message: String) : PairResult
    }

    /** A new PIN for the user to type on the PC. */
    fun newPin(): String = PairingManager.generatePinString()

    /**
     * Pairs [pc] with [pin] (shown to the user, or the PIN of a one-time code with its [passphrase]).
     * Blocks until the PIN is entered on the PC; cancelling the coroutine abandons the attempt.
     */
    suspend fun pair(pc: Pc, pin: String, passphrase: String?): PairResult {
        val binder = repo.binder ?: return PairResult.Failed("Delos is still starting, try again")
        val details = binder.getComputer(pc.uuid) ?: pc.details
        if (details.state != ComputerDetails.State.ONLINE || details.activeAddress == null) {
            return PairResult.Failed("${pc.name} is offline")
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
                        PairState.PIN_WRONG -> PairResult.Failed(if (passphrase == null) "The PIN was wrong, try again" else "The code or passphrase was wrong")
                        PairState.ALREADY_IN_PROGRESS -> PairResult.Failed("${pc.name} is already pairing with another device")
                        else -> PairResult.Failed(
                            if (details.runningGameId != 0) "Quit the game on ${pc.name} first, then pair again"
                            else "Pairing with ${pc.name} failed"
                        )
                    }
                } catch (e: UnknownHostException) {
                    PairResult.Failed("${pc.name} cannot be found")
                } catch (e: FileNotFoundException) {
                    PairResult.Failed("${pc.name} does not support pairing")
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    PairResult.Failed(e.message ?: "Pairing with ${pc.name} failed")
                }
            }
        } finally {
            repo.resumePolling()
        }
    }

    /**
     * Adds the PC at [address] (host or IP, optionally with a port). Returns the new PC's uuid, or the
     * message to show when it cannot be reached.
     */
    suspend fun add(address: String): Result<String> = withContext(Dispatchers.IO) {
        val binder = repo.binder ?: return@withContext Result.failure(Exception("Delos is still starting, try again"))
        val uri = parseAddress(address.trim()) ?: return@withContext Result.failure(Exception("“$address” is not an address"))
        val details = ComputerDetails()
        details.manualAddress = ComputerDetails.AddressTuple(uri.host, if (uri.port == -1) NvHTTP.DEFAULT_HTTP_PORT else uri.port)
        val added = try {
            binder.addComputerBlocking(details)
        } catch (e: IllegalArgumentException) {
            // OkHttp cannot canonicalize the host name
            return@withContext Result.failure(Exception("“$address” is not an address"))
        }
        val uuid = details.uuid
        if (added && uuid != null) {
            repo.remember(uuid)
            Result.success(uuid)
        } else {
            Result.failure(Exception("No PC answered at $address"))
        }
    }

    /** Sends the Wake-on-LAN packet; returns the error to show, or null. */
    suspend fun wake(pc: Pc): String? = withContext(Dispatchers.IO) {
        val details = repo.binder?.getComputer(pc.uuid) ?: pc.details
        if (details.macAddress == null) return@withContext "${pc.name} cannot be woken up from here"
        try {
            WakeOnLanSender.sendWolPacket(details)
            null
        } catch (e: Exception) {
            "The wake-up signal could not be sent"
        }
    }

    /** Unpairs [pc] on the PC itself; returns the message to show. */
    suspend fun unpair(pc: Pc): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val binder = repo.binder ?: return@withContext false to "Delos is still starting, try again"
        val details = binder.getComputer(pc.uuid) ?: pc.details
        if (details.state != ComputerDetails.State.ONLINE || details.activeAddress == null) {
            return@withContext false to "${pc.name} has to be online to unpair"
        }
        try {
            val http = http(details, binder.uniqueId)
            if (http.pairState != PairState.PAIRED) return@withContext false to "${pc.name} is not paired"
            http.unpair()
            val ok = http.pairState == PairState.NOT_PAIRED
            if (ok) binder.invalidateStateForComputer(pc.uuid)
            ok to if (ok) "Unpaired from ${pc.name}" else "${pc.name} could not be unpaired"
        } catch (e: Exception) {
            false to "${pc.name} cannot be reached"
        }
    }

    /** Removes [pc] from Delos (it can be found and paired again). */
    fun remove(pc: Pc) {
        val binder = repo.binder ?: return
        binder.removeComputer(binder.getComputer(pc.uuid) ?: pc.details)
        repo.forget(pc.uuid)
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
