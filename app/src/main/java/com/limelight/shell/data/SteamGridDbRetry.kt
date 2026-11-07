package com.limelight.shell.data

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay

/**
 * Retries for SteamGridDB calls that failed for a passing reason (the service busy or briefly unreachable, a dropped
 * connection). The screens keep their loader up while it retries and only show "not reachable" once every attempt has failed.
 */
object SgdbRetry {
    /** Waits before the 2nd, 3rd and 4th attempt: four attempts in about four seconds of waiting. */
    val DELAYS_MS = longArrayOf(500, 1200, 2500)

    private val serverTrouble = Regex("answered (429|5\\d\\d)")

    /** Whether trying again can help: a network failure or an overloaded/failing server, never a rejected key or a bad file. */
    fun isTransient(error: Throwable): Boolean = when (error) {
        is CancellationException -> false
        is SteamGridDbException -> error.message?.let { serverTrouble.containsMatchIn(it) } == true
        is IOException -> true
        else -> false
    }

    /** Runs [block], retrying after each wait in [delays] while the failure [isTransient]; the last failure is thrown. */
    suspend fun <T> run(
        delays: LongArray = DELAYS_MS,
        wait: suspend (Long) -> Unit = { delay(it) },
        block: suspend () -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Throwable) {
                if (e is CancellationException || !isTransient(e) || attempt >= delays.size) throw e
                wait(delays[attempt++])
            }
        }
    }
}
