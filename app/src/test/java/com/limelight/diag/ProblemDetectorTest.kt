package com.limelight.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cases of docs/DIAGNOSTICS.md, "What starts an offer": markers in, at most one problem out, never twice. */
class ProblemDetectorTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private fun crash(at: Long = now - minute, pid: Int = 100) =
        CrashRecord(at, pid, "main", "java.lang.IllegalStateException: boom", "java.lang.IllegalStateException: boom\n\tat com.limelight.Foo.bar(Foo.kt:1)", listOf("l1"))

    private fun signals(
        sdk: Int = 33, crash: CrashRecord? = null, exits: List<ExitRecord> = emptyList(), session: SessionRecord? = null, pid: Int = 200,
    ) = StartSignals(now, sdk, pid, crash, exits, session)

    private fun exit(kind: ExitKind, at: Long = now - minute, pid: Int = 100, text: String = "") = ExitRecord(at, pid, kind, text)

    @Test fun nothingHappenedMeansNoOffer() {
        val result = ProblemDetector.atStart(signals(), ProblemLedger())
        assertNull(result.problem)
        assertEquals(ProblemLedger(), result.ledger)
    }

    @Test fun aJavaCrashMarkerOffersTheCrashWithItsSummary() {
        val result = ProblemDetector.atStart(signals(crash = crash()), ProblemLedger())
        assertEquals(ProblemKind.JAVA_CRASH, result.problem!!.kind)
        assertTrue(result.problem!!.summary, result.problem!!.summary.startsWith("IllegalStateException: boom"))
        assertTrue(result.problem!!.detail.contains("com.limelight.Foo.bar"))
        assertEquals(now - minute, result.ledger.crashMs)
    }

    @Test fun theSameCrashIsNeverOfferedTwice() {
        val first = ProblemDetector.atStart(signals(crash = crash()), ProblemLedger())
        val second = ProblemDetector.atStart(signals(crash = crash()), first.ledger)
        assertNull(second.problem)
    }

    @Test fun aNewerCrashIsOfferedAgain() {
        val first = ProblemDetector.atStart(signals(crash = crash(now - 2 * minute)), ProblemLedger())
        val second = ProblemDetector.atStart(signals(crash = crash(now - minute)), first.ledger)
        assertEquals(ProblemKind.JAVA_CRASH, second.problem!!.kind)
    }

    @Test fun aNativeCrashAndAnrComeFromTheExitReasons() {
        val native = ProblemDetector.atStart(signals(exits = listOf(exit(ExitKind.NATIVE_CRASH, text = "signal 11 (SIGSEGV)"))), ProblemLedger())
        assertEquals(ProblemKind.NATIVE_CRASH, native.problem!!.kind)
        assertTrue(native.problem!!.summary.contains("SIGSEGV"))
        val anr = ProblemDetector.atStart(signals(exits = listOf(exit(ExitKind.ANR, text = "user request after error: Input dispatching timed out"))), ProblemLedger())
        assertEquals(ProblemKind.ANR, anr.problem!!.kind)
    }

    @Test fun anExitReasonIsOfferedOnceToo() {
        val exits = listOf(exit(ExitKind.ANR))
        val first = ProblemDetector.atStart(signals(exits = exits), ProblemLedger())
        assertEquals(ProblemKind.ANR, first.problem!!.kind)
        assertNull(ProblemDetector.atStart(signals(exits = exits), first.ledger).problem)
    }

    @Test fun otherExitReasonsAreNoProblem() {
        val result = ProblemDetector.atStart(signals(exits = listOf(exit(ExitKind.OTHER, text = "user requested"))), ProblemLedger())
        assertNull(result.problem)
        assertEquals("seen, so never reconsidered", now - minute, result.ledger.exitMs)
    }

    @Test fun theCrashExitRecordOfTheSameProcessIsTheSameCrash() {
        val result = ProblemDetector.atStart(signals(crash = crash(pid = 100), exits = listOf(exit(ExitKind.CRASH, pid = 100))), ProblemLedger())
        assertEquals(ProblemKind.JAVA_CRASH, result.problem!!.kind)
        assertEquals("the handler's crash file, not the bare exit record", "crash-${now - minute}", result.problem!!.id)
        // and after the crash file is deleted, the exit record alone does not bring it back
        val later = ProblemDetector.atStart(signals(exits = listOf(exit(ExitKind.CRASH, pid = 100))), result.ledger)
        assertNull(later.problem)
    }

    @Test fun aCrashExitWithoutACrashFileIsStillReported() {
        val result = ProblemDetector.atStart(signals(exits = listOf(exit(ExitKind.CRASH, text = "crash"))), ProblemLedger())
        assertEquals(ProblemKind.JAVA_CRASH, result.problem!!.kind)
        val init = ProblemDetector.atStart(signals(exits = listOf(exit(ExitKind.INITIALIZATION_FAILURE, at = now - 5 * minute))), ProblemLedger())
        assertEquals(ProblemKind.JAVA_CRASH, init.problem!!.kind)
    }

    @Test fun onApi29ANonCleanForegroundExitIsTheFallback() {
        val session = SessionRecord(pid = 100, startedAtMs = now - 10 * minute, updatedAtMs = now - 9 * minute, inForeground = true)
        val result = ProblemDetector.atStart(signals(sdk = 29, session = session), ProblemLedger())
        assertEquals(ProblemKind.UNCLEAN_EXIT, result.problem!!.kind)
        assertNull("once per session", ProblemDetector.atStart(signals(sdk = 29, session = session), result.ledger).problem)
    }

    @Test fun aProcessThatEndedInTheBackgroundIsNoProblem() {
        val session = SessionRecord(100, now - 10 * minute, now - 9 * minute, inForeground = false)
        assertNull(ProblemDetector.atStart(signals(sdk = 29, session = session), ProblemLedger()).problem)
    }

    @Test fun theSessionMarkerIsIgnoredFromApi30() {
        val session = SessionRecord(100, now - 10 * minute, now - 9 * minute, inForeground = true)
        assertNull(ProblemDetector.atStart(signals(sdk = 30, session = session), ProblemLedger()).problem)
    }

    @Test fun theMarkerOfTheRunningProcessIsNoProblem() {
        val session = SessionRecord(200, now - minute, now - minute, inForeground = true)
        assertNull(ProblemDetector.atStart(signals(sdk = 29, pid = 200, session = session), ProblemLedger()).problem)
    }

    @Test fun aCrashOnApi29IsNotAlsoReportedAsAnUncleanExit() {
        val session = SessionRecord(100, now - 10 * minute, now - 2 * minute, inForeground = true)
        val result = ProblemDetector.atStart(signals(sdk = 29, crash = crash(pid = 100), session = session), ProblemLedger())
        assertEquals(ProblemKind.JAVA_CRASH, result.problem!!.kind)
        assertEquals(now - 10 * minute, result.ledger.sessionMs)
    }

    @Test fun severalProblemsGiveOneOfferWithTheRichestFirstAndAllCountAsOffered() {
        val result = ProblemDetector.atStart(
            signals(crash = crash(pid = 100), exits = listOf(exit(ExitKind.ANR, at = now - 30 * minute, pid = 50), exit(ExitKind.NATIVE_CRASH, at = now - 20 * minute, pid = 60))),
            ProblemLedger(),
        )
        assertEquals(ProblemKind.JAVA_CRASH, result.problem!!.kind)
        val again = ProblemDetector.atStart(signals(crash = crash(pid = 100), exits = listOf(exit(ExitKind.ANR, at = now - 30 * minute, pid = 50), exit(ExitKind.NATIVE_CRASH, at = now - 20 * minute, pid = 60))), result.ledger)
        assertNull(again.problem)
    }

    @Test fun ofTwoOfTheSameKindTheNewestWins() {
        val result = ProblemDetector.atStart(
            signals(exits = listOf(exit(ExitKind.ANR, at = now - 50 * minute, pid = 1, text = "old"), exit(ExitKind.ANR, at = now - 5 * minute, pid = 2, text = "new"))),
            ProblemLedger(),
        )
        assertTrue(result.problem!!.summary.contains("new"))
    }

    @Test fun signalsOlderThanAWeekAreDroppedButStillMarked() {
        val old = now - 8 * day
        val result = ProblemDetector.atStart(signals(crash = crash(at = old), exits = listOf(exit(ExitKind.ANR, at = old))), ProblemLedger())
        assertNull(result.problem)
        assertEquals(old, result.ledger.crashMs)
        assertEquals(old, result.ledger.exitMs)
    }

    @Test fun aFailedStreamIsOfferedOnceAndThenWaitsOutTheCooldown() {
        val first = ProblemDetector.streamFailed(ProblemLedger(), now, "Connection lost", "Error 5")
        assertEquals(ProblemKind.STREAM_FAILED, first.problem!!.kind)
        assertEquals("Error 5", first.problem!!.detail)
        assertEquals(now, first.ledger.streamOfferMs)
        assertNull(ProblemDetector.streamFailed(first.ledger, now + 5 * minute, "Connection lost", null).problem)
        assertEquals(ProblemKind.STREAM_FAILED,
            ProblemDetector.streamFailed(first.ledger, now + ProblemDetector.STREAM_OFFER_COOLDOWN_MS, "Connection lost", null).problem!!.kind)
    }

    @Test fun theSessionMarkerTextRoundTrips() {
        val record = SessionRecord(123, 1000, 2000, inForeground = true)
        assertEquals(record, SessionRecord.parse(record.format()))
        assertEquals("background 123 1000 2000", record.copy(inForeground = false).format())
        assertNull(SessionRecord.parse("garbage"))
        assertNull(SessionRecord.parse("running 1 2 3"))
    }
}
