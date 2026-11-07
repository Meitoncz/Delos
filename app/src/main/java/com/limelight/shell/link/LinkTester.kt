package com.limelight.shell.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.limelight.binding.PlatformBinding
import com.limelight.nvstream.http.HostHttpResponseException
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PyroWaveHostInfo
import com.limelight.shell.data.Pc
import com.limelight.shell.data.PcRepository
import com.limelight.utils.ServerHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The kind of network the device is on right now. */
internal fun currentTransport(context: Context): LinkTransport {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return LinkTransport.OTHER
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return LinkTransport.OTHER
    return when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> LinkTransport.ETHERNET
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LinkTransport.WIFI
        else -> LinkTransport.OTHER
    }
}

/**
 * The last confirmed link test per PC and kind of network, with its date. A result is only ever shown and used for the
 * recommendation, with its age; it is never applied by itself, and a PC or network that was not tested has none.
 * [revision] changes with every write, so screens that read a result recompose.
 */
internal class LinkTestStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("delos_link_tests", Context.MODE_PRIVATE)

    var revision by mutableIntStateOf(0)
        private set

    private fun key(pcUuid: String, transport: LinkTransport) = "$pcUuid/${transport.name}"

    fun get(pcUuid: String, transport: LinkTransport): LinkTest? {
        @Suppress("UNUSED_EXPRESSION") revision
        return LinkTest.decode(prefs.getString(key(pcUuid, transport), null))
    }

    fun put(pcUuid: String, test: LinkTest) {
        prefs.edit().putString(key(pcUuid, test.transport), test.encode()).apply()
        revision++
    }
}

/** How a link test of the UI ended. */
internal sealed interface LinkTestResult {
    data class Done(val test: LinkTest, val jitter: Boolean = false) : LinkTestResult
    /** [text] says why, in a sentence. */
    data class Failed(val text: String) : LinkTestResult
    data object Cancelled : LinkTestResult
}

/** Runs a link test for [pc] from the shell: fresh `/serverinfo`, then the document's search. */
internal class LinkTester(val context: Context, private val repo: PcRepository) {
    /**
     * Starts at [startKbps] (the selected bitrate). Cancelling the calling coroutine stops the probe and tears
     * down its request and UDP socket. Never applies anything.
     */
    suspend fun run(pc: Pc, startKbps: Int, onStep: (LinkStep) -> Unit): LinkTestResult {
        val cancel = LinkCancel()
        return withContext(Dispatchers.IO) coroutine@{
            // The probe blocks; cancelling the job runs the watcher's finally, which tears the request and socket down
            val watcher = launch { try { awaitCancellation() } finally { cancel.cancel() } }
            try {
                val binder = repo.binder ?: return@coroutine LinkTestResult.Failed("Delos is still connecting to ${pc.name}.")
                val details = binder.getComputer(pc.uuid) ?: pc.details
                if (details.activeAddress == null) return@coroutine LinkTestResult.Failed(LinkFailure.NETWORK.text())
                val http = NvHTTP(
                    ServerHelper.getCurrentAddressFromComputer(details), details.httpsPort, binder.uniqueId, details.serverCert,
                    PlatformBinding.getCryptoProvider(context),
                )
                val info = PyroWaveHostInfo.parse(http.getServerInfo(true))
                if (!info.supportsLinkTest()) {
                    return@coroutine LinkTestResult.Failed(
                        if (info.linkTestNeedsNewerClient()) "This PC needs a newer Delos for the link test."
                        else LinkFailure.HOST_UNSUPPORTED.text(),
                    )
                }
                when (val outcome = runLinkTest(http, info, startKbps, currentTransport(context), cancel, onStep = onStep)) {
                    is LinkTestOutcome.Done -> LinkTestResult.Done(outcome.test, outcome.jitter)
                    is LinkTestOutcome.NoRate -> LinkTestResult.Failed(outcome.text)
                    LinkTestOutcome.Cancelled -> LinkTestResult.Cancelled
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkTestException) {
                LinkTestResult.Failed(e.failure.text())
            } catch (e: HostHttpResponseException) {
                LinkTestResult.Failed(if (e.errorCode == 403) LinkFailure.DENIED.text() else LinkFailure.HOST_ERROR.text())
            } catch (e: Exception) {
                LinkTestResult.Failed(LinkFailure.NETWORK.text())
            } finally {
                watcher.cancel()
            }
        }
    }
}
