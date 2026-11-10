package com.limelight.shell.data

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SteamGridDbRetryTest {
    private fun <T> run(waits: MutableList<Long> = mutableListOf(), block: suspend () -> T): T =
        runBlocking { SgdbRetry.run(wait = { waits += it }, block = block) }

    @Test fun networkAndServerTroubleAreWorthRetryingButARejectedKeyIsNot() {
        assertTrue(SgdbRetry.isTransient(SocketTimeoutException("timeout")))
        assertTrue(SgdbRetry.isTransient(IOException("connection reset")))
        assertTrue(SgdbRetry.isTransient(SteamGridDbException(SgdbFailure.API_HTTP, 503)))
        assertTrue(SgdbRetry.isTransient(SteamGridDbException(SgdbFailure.API_HTTP, 429)))
        assertFalse(SgdbRetry.isTransient(SteamGridDbException(SgdbFailure.KEY_REJECTED)))
        assertFalse(SgdbRetry.isTransient(SteamGridDbException(SgdbFailure.API_HTTP, 400)))
        assertFalse(SgdbRetry.isTransient(SteamGridDbException(SgdbFailure.IMAGE_TOO_LARGE)))
        assertFalse(SgdbRetry.isTransient(IllegalStateException("bug")))
    }

    @Test fun aCallThatRecoversReturnsItsResultAfterTheWaits() {
        val waits = mutableListOf<Long>()
        var calls = 0
        val result = run(waits) { if (++calls < 3) throw SteamGridDbException(SgdbFailure.API_HTTP, 502) else "ok" }
        assertEquals("ok", result)
        assertEquals(3, calls)
        assertEquals(listOf(500L, 1200L), waits)
    }

    @Test fun everyAttemptFailingThrowsTheLastErrorAfterFourTries() {
        val waits = mutableListOf<Long>()
        var calls = 0
        try {
            run(waits) { calls++; throw IOException("down $calls") }
            fail("expected the failure")
        } catch (e: IOException) {
            assertEquals("down 4", e.message)
        }
        assertEquals(4, calls)
        assertEquals(SgdbRetry.DELAYS_MS.toList(), waits)
    }

    @Test fun aRejectedKeyFailsAtOnceWithoutWaiting() {
        val waits = mutableListOf<Long>()
        var calls = 0
        try {
            run(waits) { calls++; throw SteamGridDbException(SgdbFailure.KEY_REJECTED) }
            fail("expected the failure")
        } catch (e: SteamGridDbException) {
            assertEquals(SgdbFailure.KEY_REJECTED, e.failure)
        }
        assertEquals(1, calls)
        assertTrue(waits.isEmpty())
    }
}
