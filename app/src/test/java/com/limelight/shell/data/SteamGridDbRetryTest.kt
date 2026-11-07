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
        assertTrue(SgdbRetry.isTransient(SteamGridDbException("SteamGridDB answered 503")))
        assertTrue(SgdbRetry.isTransient(SteamGridDbException("SteamGridDB answered 429")))
        assertFalse(SgdbRetry.isTransient(SteamGridDbException(SteamGridDbClient.KEY_REJECTED)))
        assertFalse(SgdbRetry.isTransient(SteamGridDbException("SteamGridDB answered 400")))
        assertFalse(SgdbRetry.isTransient(SteamGridDbException("image too large")))
        assertFalse(SgdbRetry.isTransient(IllegalStateException("bug")))
    }

    @Test fun aCallThatRecoversReturnsItsResultAfterTheWaits() {
        val waits = mutableListOf<Long>()
        var calls = 0
        val result = run(waits) { if (++calls < 3) throw SteamGridDbException("SteamGridDB answered 502") else "ok" }
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
            run(waits) { calls++; throw SteamGridDbException(SteamGridDbClient.KEY_REJECTED) }
            fail("expected the failure")
        } catch (e: SteamGridDbException) {
            assertEquals(SteamGridDbClient.KEY_REJECTED, e.message)
        }
        assertEquals(1, calls)
        assertTrue(waits.isEmpty())
    }
}
