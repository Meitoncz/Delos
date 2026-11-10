package com.limelight.shell.ui

import com.limelight.diag.Problem
import com.limelight.diag.ProblemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The text of the problem card is built from the typed [Problem] and string resources, in English exactly as before. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class ProblemOfferTextTest {
    private val res = RuntimeEnvironment.getApplication().resources

    private fun problem(kind: ProblemKind, subject: String = "", viaExitRecord: Boolean = false) =
        Problem("p", kind, 1, subject, "", viaExitRecord)

    @Test fun everyKindHasItsEnglishSentence() {
        assertEquals("Delos stopped unexpectedly the last time.", problemSentence(res, ProblemKind.JAVA_CRASH))
        assertEquals("Delos crashed in its native code the last time.", problemSentence(res, ProblemKind.NATIVE_CRASH))
        assertEquals("Delos stopped responding the last time.", problemSentence(res, ProblemKind.ANR))
        assertEquals("Delos did not close normally the last time.", problemSentence(res, ProblemKind.UNCLEAN_EXIT))
        assertEquals("The last stream could not run.", problemSentence(res, ProblemKind.STREAM_FAILED))
        // The issue text for the developers is the same English
        ProblemKind.entries.forEach { assertEquals(it.issueSentence, problemSentence(res, it)) }
    }

    @Test fun aCrashFileShowsItsExceptionSummaryAsIs() {
        val crash = problem(ProblemKind.JAVA_CRASH, "IllegalStateException: boom (at com.limelight.Foo.bar)")
        assertEquals("IllegalStateException: boom (at com.limelight.Foo.bar)", problemSummary(res, crash))
    }

    @Test fun exitRecordsPrefixTheSystemsDescription() {
        assertEquals("Crash: crash", problemSummary(res, problem(ProblemKind.JAVA_CRASH, "crash", viaExitRecord = true)))
        assertEquals("Crash", problemSummary(res, problem(ProblemKind.JAVA_CRASH, "", viaExitRecord = true)))
        assertEquals("Native crash: signal 11 (SIGSEGV)", problemSummary(res, problem(ProblemKind.NATIVE_CRASH, "signal 11 (SIGSEGV)", true)))
        assertEquals("Native crash", problemSummary(res, problem(ProblemKind.NATIVE_CRASH, "", true)))
        assertEquals("Not responding (ANR): Input dispatching timed out", problemSummary(res, problem(ProblemKind.ANR, "Input dispatching timed out", true)))
        assertEquals("Not responding (ANR)", problemSummary(res, problem(ProblemKind.ANR, "", true)))
    }

    @Test fun theEnglishSummaryOfTheDevelopersMatchesTheCard() {
        listOf(
            problem(ProblemKind.JAVA_CRASH, "crash", viaExitRecord = true),
            problem(ProblemKind.NATIVE_CRASH, "signal 11 (SIGSEGV)", viaExitRecord = true),
            problem(ProblemKind.ANR, "", viaExitRecord = true),
            problem(ProblemKind.UNCLEAN_EXIT),
            problem(ProblemKind.STREAM_FAILED, "Connection lost"),
        ).forEach { assertEquals(it.summary, problemSummary(res, it)) }
    }

    @Test fun anUncleanExitAndAFailedStreamNeedNoSubject() {
        assertEquals("The app was in the foreground when its process ended", problemSummary(res, problem(ProblemKind.UNCLEAN_EXIT)))
        assertEquals("Connection lost", problemSummary(res, problem(ProblemKind.STREAM_FAILED, "Connection lost")))
    }

    @Test fun aLongSubjectIsCutLikeTheEnglishSummary() {
        val long = "x".repeat(400)
        assertEquals(Problem.SUMMARY_MAX, problemSummary(res, problem(ProblemKind.STREAM_FAILED, long)).length)
        assertNotEquals(long, problemSummary(res, problem(ProblemKind.STREAM_FAILED, long)))
    }
}
