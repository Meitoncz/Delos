package com.limelight.shell.data

import com.limelight.nvstream.http.PairingManager.PairState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcPairedTest {
    @Test fun aServerAnswerWinsOverWhatWasRemembered() {
        assertTrue(resolvePaired(PairState.PAIRED, remembered = false))
        assertFalse(resolvePaired(PairState.NOT_PAIRED, remembered = true))
    }

    @Test fun anOfflinePcThatWasPairedStaysPaired() {
        // Offline at app start: the database has no pair state, so it is null
        assertTrue(resolvePaired(null, remembered = true))
    }

    @Test fun anOfflinePcNeverPairedStaysUnpaired() {
        assertFalse(resolvePaired(null, remembered = false))
    }

    @Test fun pairingOutcomesThatAreNotServerInfoDoNotUnpair() {
        assertTrue(resolvePaired(PairState.FAILED, remembered = true))
        assertTrue(resolvePaired(PairState.PIN_WRONG, remembered = true))
    }
}
