package com.limelight.shell.link

import android.content.res.Resources
import android.os.Process
import android.util.Log
import com.limelight.BuildConfig
import com.limelight.R
import com.limelight.nvstream.http.HostHttpResponseException
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PyroWaveHostInfo
import com.limelight.nvstream.http.PyroWaveProbeResponse
import okhttp3.Call
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CancellationException

/** Why a link test could not produce a number, as a reason the screens can say in a sentence. */
internal enum class LinkFailure {
    HOST_UNSUPPORTED, STREAM_ACTIVE, DENIED, UDP_BLOCKED, NETWORK, TIMEOUT, HOST_ERROR, INVALID_RESPONSE,

    /** Only reported by [LinkTester]; [LinkTestResult.Failed.text] words NOT_CONNECTED and NO_RATE itself, [text] is their fallback. */
    NOT_CONNECTED, NEEDS_NEWER_DELOS, NO_RATE,
}

internal class LinkTestException(val failure: LinkFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

/** One sentence for a failed test ("Delos could not reach the PC"), without technical detail. */
internal fun LinkFailure.text(res: Resources): String = res.getString(
    when (this) {
        LinkFailure.HOST_UNSUPPORTED -> R.string.link_failure_host_unsupported
        LinkFailure.STREAM_ACTIVE -> R.string.link_failure_stream_active
        LinkFailure.DENIED -> R.string.link_failure_denied
        LinkFailure.UDP_BLOCKED -> R.string.link_failure_udp_blocked
        LinkFailure.NETWORK -> R.string.link_failure_network
        LinkFailure.TIMEOUT -> R.string.link_failure_timeout
        LinkFailure.HOST_ERROR -> R.string.link_failure_host_error
        LinkFailure.INVALID_RESPONSE -> R.string.link_failure_invalid_response
        LinkFailure.NEEDS_NEWER_DELOS -> R.string.link_unavailable_newer_delos
        LinkFailure.NOT_CONNECTED, LinkFailure.NO_RATE -> R.string.link_failure_network
    },
)

/** Stops a running test: the HTTPS request and the UDP receiver are torn down from any thread. */
internal class LinkCancel {
    @Volatile var cancelled = false
        private set
    private var call: Call? = null

    @Synchronized
    fun cancel() {
        cancelled = true
        call?.cancel()
    }

    @Synchronized
    fun attach(next: Call) {
        call = next
        if (cancelled) next.cancel()
    }
}

/**
 * One UDP probe as the host document describes it (docs/pyrowave-protocol.md): a UDP receiver on an ephemeral port,
 * the pinned HTTPS request `GET /pyrowave-udp-probe?kbps=&port=&packetsize=&token=[&handshake=1]`, and a score made
 * of the arrival of every sequence number against the host's reliable answer (expected, sent, elapsedMs).
 * Blocking: call from a worker thread. [cancel] aborts it; the coroutine layer ties it to the job.
 */
internal class LinkProbeClient(
    private val http: NvHTTP,
    private val info: PyroWaveHostInfo,
    private val packetSize: Int = LinkPolicy.DEFAULT_PACKET_SIZE,
) {
    private val random = SecureRandom()

    fun probe(kbps: Int, cancel: LinkCancel): ProbeScore {
        require(kbps in LinkPolicy.MIN_KBPS..LinkPolicy.MAX_KBPS) { "kbps out of the host's range" }
        require(packetSize in LinkPolicy.MIN_PACKET_SIZE..LinkPolicy.MAX_PACKET_SIZE) { "packet size out of the host's range" }
        val expected = expectedProbePackets(kbps, packetSize).toInt()
        val handshake = info.udpHandshakeVersion == 1
        val address = resolve(http.hostName)
        val token = ByteArray(LinkPolicy.TOKEN_LENGTH / 2).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val arrivals = LongArray(expected) { -1L }
        val socket = DatagramSocket(null as java.net.SocketAddress?)
        try {
            socket.reuseAddress = false
            socket.receiveBufferSize = LinkPolicy.RECEIVE_BUFFER_BYTES
            socket.soTimeout = RECEIVE_POLL_MS
            socket.bind(InetSocketAddress(0))
        } catch (e: IOException) {
            socket.close()
            throw LinkTestException(LinkFailure.NETWORK, "Could not open the UDP receiver: ${e.message}", e)
        }
        // The OS may grant less than asked (Android caps it); what it granted decides whether loss can be ours
        val granted = socket.receiveBufferSize
        val receiver = Receiver(socket, address, token.toByteArray(Charsets.US_ASCII), packetSize, arrivals, handshake)
        receiver.start()
        try {
            val call = http.newPyroWaveUdpProbeCall(address.hostAddress, kbps, socket.localPort, packetSize, token,
                handshake, LinkPolicy.REQUEST_TIMEOUT_MS)
            cancel.attach(call)
            val body = execute(call, handshake, receiver, cancel)
            if (cancel.cancelled) throw CancellationException("Link test cancelled")
            val response = parseResponse(body)
            if (response.expected != expected.toLong() || response.sent > response.expected) {
                throw LinkTestException(LinkFailure.INVALID_RESPONSE, "Host planned ${response.expected} packets, expected $expected")
            }
            // A bounded tail of reordered packets still counts; its delay is scored against the send schedule.
            Thread.sleep(LinkPolicy.DRAIN_MS)
            receiver.finish()
            receiver.error?.let { throw LinkTestException(LinkFailure.NETWORK, "The UDP receiver failed: ${it.message}", it) }
            val score = scoreProbe(kbps, expected, response.sent.toInt(), response.elapsedMs, arrivals, granted,
                receiver.maxStallUs / 1000.0)
            if (BuildConfig.DEBUG) {
                Log.i(TAG, String.format(Locale.US,
                    "%d kbps expected=%d sent=%d received=%d rcvbuf=%d loss=%.3f%% window=%.3f%% p99=%.2fms growth=%.2fms " +
                        "hostElapsed=%.1fms stall=%.2fms authenticated=%d: %s%s",
                    kbps, expected, score.sent, score.received, granted, score.lossPercent, score.worstWindowLossPercent,
                    score.delayP99Ms, score.delayGrowthMs, score.senderMs, score.maxStallMs, receiver.authenticated,
                    if (score.passed) "PASS" else "FAIL ${score.failure}", if (score.receiverSuspect) " (receiver suspect)" else if (score.jitterWarning) " (jitter warning)" else "")) // l10n-ignore: developer message
            }
            return score
        } finally {
            receiver.finish()
            socket.close()
        }
    }

    private fun parseResponse(body: String): PyroWaveProbeResponse = try {
        PyroWaveProbeResponse.parse(body)
    } catch (e: HostHttpResponseException) {
        // The host fails after announcing its port with a status_code 500 document
        val message = e.errorMessage.orEmpty()
        throw LinkTestException(
            if (message.contains("handshake", ignoreCase = true)) LinkFailure.UDP_BLOCKED else LinkFailure.HOST_ERROR,
            message, e,
        )
    } catch (e: IOException) {
        throw LinkTestException(LinkFailure.INVALID_RESPONSE, e.message.orEmpty(), e)
    } catch (e: org.xmlpull.v1.XmlPullParserException) {
        throw LinkTestException(LinkFailure.INVALID_RESPONSE, e.message.orEmpty(), e)
    }

    private fun execute(call: Call, handshake: Boolean, receiver: Receiver, cancel: LinkCancel): String {
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw failure(response.code, response.body?.string().orEmpty())
                if (handshake) {
                    // The host flushes these headers, then waits for the token before it sends; the body stays pending.
                    val port = response.header("X-PyroWave-Udp-Port")?.let(::validPort)
                        ?: throw LinkTestException(LinkFailure.HOST_ERROR, "The host did not announce a valid UDP port")
                    receiver.senderPort = port
                }
                return response.body?.string().orEmpty()
            }
        } catch (e: LinkTestException) {
            throw e
        } catch (e: IOException) {
            if (cancel.cancelled) throw CancellationException("Link test cancelled")
            if (e is SocketTimeoutException) {
                throw if (receiver.authenticated == 0) {
                    LinkTestException(LinkFailure.UDP_BLOCKED, "No UDP packets arrived before the test timed out", e) // l10n-ignore: developer message
                } else LinkTestException(LinkFailure.TIMEOUT, "The test did not complete in time", e) // l10n-ignore: developer message
            }
            throw LinkTestException(LinkFailure.NETWORK, e.message.orEmpty(), e)
        }
    }

    private fun failure(code: Int, text: String): LinkTestException {
        val message = text.trim().take(200)
        return when {
            code == 403 -> LinkTestException(LinkFailure.DENIED, message.ifEmpty { "Forbidden" }) // l10n-ignore: developer message
            code == 404 -> LinkTestException(LinkFailure.HOST_UNSUPPORTED, "The host has no /pyrowave-udp-probe") // l10n-ignore: developer message
            // Vibepollo answers 400 "Stop streaming before testing bandwidth" while a stream session exists or another probe runs.
            code == 400 && message.contains("streaming", ignoreCase = true) -> LinkTestException(LinkFailure.STREAM_ACTIVE, message)
            else -> LinkTestException(LinkFailure.HOST_ERROR, "HTTP $code: $message") // l10n-ignore: developer message
        }
    }

    private fun validPort(header: String): Int? {
        val text = header.trim()
        if (text.isEmpty() || text.length > 5 || !text.all { it in '0'..'9' }) return null
        return text.toInt().takeIf { it in 1024..65535 }
    }

    /** The first IPv4 address of the PC's name (an IPv6-only PC gets its IPv6 one); the HTTPS request is pinned to the same one. */
    private fun resolve(host: String): InetAddress {
        val all = try {
            InetAddress.getAllByName(host)
        } catch (e: IOException) {
            throw LinkTestException(LinkFailure.NETWORK, "Could not resolve $host", e)
        }
        return all.firstOrNull { it is Inet4Address } ?: all.first()
    }

    /**
     * Reads the datagrams of one probe. Only exact-size packets from the PC that start with the token count; the
     * host's 32-byte warmups are therefore ignored. With the handshake, [senderPort] is the host's announced port:
     * the token is sent there every 100 ms until the first measured packet arrives (this also opens outbound UDP
     * state on both sides), and only packets from that port are accepted.
     */
    private class Receiver(
        private val socket: DatagramSocket,
        private val address: InetAddress,
        private val token: ByteArray,
        private val packetSize: Int,
        private val arrivals: LongArray,
        private val handshake: Boolean,
    ) : Thread("PyroWave link probe") { // l10n-ignore: developer message
        @Volatile var senderPort = 0
        /** Published every 256 datagrams and at the end; the hot loop keeps its own plain counter. */
        @Volatile var authenticated = 0
        @Volatile var maxStallUs = 0L
        @Volatile var error: IOException? = null
        @Volatile private var stopping = false

        fun finish() {
            stopping = true
            if (isAlive && currentThread() !== this) join(1000)
        }

        // Nothing in the loop allocates, logs or takes a lock: the buffer, the packet and the score array exist before
        // the first datagram, and arrival is stamped right after receive() returns. Scoring happens after the loop.
        override fun run() {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: RuntimeException) {
                // Keep the default priority
            }
            val payload = packetSize + LinkPolicy.PAYLOAD_EXTRA_BYTES
            val buffer = ByteArray(payload + 64)
            val packet = DatagramPacket(buffer, buffer.size)
            val start = System.nanoTime()
            var nextToken = 0L
            var count = 0
            var stall = 0L
            while (!stopping) {
                val port = senderPort
                if (handshake && port != 0 && count == 0 && System.nanoTime() >= nextToken) {
                    try {
                        socket.send(DatagramPacket(token, token.size, address, port))
                    } catch (e: IOException) {
                        error = e
                        return
                    }
                    nextToken = System.nanoTime() + LinkPolicy.HANDSHAKE_RETRY_MS * 1_000_000L
                }
                try {
                    packet.setLength(buffer.size)
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: IOException) {
                    if (!stopping) error = e
                    return
                }
                val now = System.nanoTime()
                if (packet.length != payload || packet.address != address) continue
                if (handshake && packet.port != senderPort) continue
                var matches = true
                for (i in token.indices) if (buffer[i] != token[i]) { matches = false; break }
                if (!matches) continue
                count++
                if (count == 1 || (count and 255) == 0) authenticated = count
                val seq = ((buffer[32].toInt() and 0xFF) shl 24) or ((buffer[33].toInt() and 0xFF) shl 16) or
                    ((buffer[34].toInt() and 0xFF) shl 8) or (buffer[35].toInt() and 0xFF)
                // Unique sequence numbers only: duplicates and out-of-range values never inflate the delivery.
                if (seq >= 0 && seq < arrivals.size && arrivals[seq] < 0) arrivals[seq] = (now - start) / 1000
                // How long this datagram waited for the loop and its own handling took; a spike means our thread was late
                val busy = System.nanoTime() - now
                if (busy > stall) { stall = busy; maxStallUs = stall / 1000 }
            }
            authenticated = count
        }
    }

    private companion object {
        const val RECEIVE_POLL_MS = 10
        const val TAG = "DelosLinkTest"
    }
}

/** The result of a whole test run. */
internal sealed interface LinkTestOutcome {
    /** [jitter]: it passed, with p99 transit variation above 4 ms ("a little jitter"). */
    data class Done(val test: LinkTest, val jitter: Boolean = false) : LinkTestOutcome
    data class NoRate(val result: LinkSearchResult.NoRate) : LinkTestOutcome
    data object Cancelled : LinkTestOutcome
}

/**
 * The whole calibration against one host: the document's search over real probes. Starts at [startKbps] (the selected
 * bitrate), bounded by the PC's routed wired link speed (`PyroWaveHostLinkMbps`, when it knows one) and 3 Gbps.
 * Blocking; throws [LinkTestException] on a network or host failure. The result is only returned, never applied.
 */
internal fun runLinkTest(
    http: NvHTTP,
    info: PyroWaveHostInfo,
    startKbps: Int,
    transport: LinkTransport,
    cancel: LinkCancel,
    nowMs: () -> Long = System::currentTimeMillis,
    onStep: (LinkStep) -> Unit = {},
): LinkTestOutcome {
    if (!info.supportsLinkTest()) throw LinkTestException(LinkFailure.HOST_UNSUPPORTED, "The host does not advertise the UDP probe")
    val client = LinkProbeClient(http, info)
    val cap = if (info.hostLinkMbps > 0) minOf(LinkPolicy.MAX_KBPS, info.hostLinkMbps * 1000) else LinkPolicy.MAX_KBPS
    val search = try {
        searchLink(startKbps, cap, { kbps -> client.probe(kbps, cancel) }, { cancel.cancelled }, onStep)
    } catch (e: CancellationException) {
        if (!cancel.cancelled) throw e
        LinkSearchResult.Cancelled
    }
    return when (val result = search) {
        is LinkSearchResult.Confirmed -> LinkTestOutcome.Done(
            LinkTest(result.wireKbps, nowMs(), transport, info.criticalFecPercent, LinkPolicy.DEFAULT_PACKET_SIZE),
            result.score.jitterWarning,
        )
        is LinkSearchResult.NoRate -> LinkTestOutcome.NoRate(result)
        LinkSearchResult.Cancelled -> LinkTestOutcome.Cancelled
    }
}
