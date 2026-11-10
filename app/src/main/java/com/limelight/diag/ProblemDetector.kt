package com.limelight.diag

/**
 * What went wrong, in the order the detector prefers when several happened since the last start. The sentence the user reads
 * is built by the UI from string resources (`shell/ui/ProblemOfferCard.kt`); [issueSentence] is the English line of the
 * prefilled GitHub issue, which stays English because the developers read it.
 */
enum class ProblemKind(val issueSentence: String) {
    JAVA_CRASH("Delos stopped unexpectedly the last time."),
    NATIVE_CRASH("Delos crashed in its native code the last time."),
    ANR("Delos stopped responding the last time."),
    UNCLEAN_EXIT("Delos did not close normally the last time."),
    STREAM_FAILED("The last stream could not run."),
}

/** Why an earlier process of the app ended, as `ActivityManager.getHistoricalProcessExitReasons` tells it (API 30+). */
enum class ExitKind { CRASH, NATIVE_CRASH, ANR, INITIALIZATION_FAILURE, OTHER }

data class ExitRecord(val timestamp: Long, val pid: Int, val kind: ExitKind, val description: String)

/**
 * The marker the previous process left (the only signal on API 29): [inForeground] is true from the first started
 * activity until the last one stopped, so a process killed while the app was in the background does not count as a problem.
 */
data class SessionRecord(val pid: Int, val startedAtMs: Long, val updatedAtMs: Long, val inForeground: Boolean) {
    fun format(): String = "${if (inForeground) "foreground" else "background"} $pid $startedAtMs $updatedAtMs"

    companion object {
        fun parse(text: String): SessionRecord? {
            val parts = text.trim().split(' ')
            if (parts.size != 4 || parts[0] !in setOf("foreground", "background")) return null
            return SessionRecord(parts[1].toIntOrNull() ?: return null, parts[2].toLongOrNull() ?: return null,
                parts[3].toLongOrNull() ?: return null, parts[0] == "foreground")
        }
    }
}

/**
 * A problem to offer a report for, as typed facts: the [kind], the [subject] (the exception summary of a crash file, the
 * system's description of an exit, the title of a failed stream; blank when there is none) and [detail] (the stack trace or the
 * system's description; it is scrubbed before use). [viaExitRecord] is true for a crash that only the system's exit record
 * told about. The UI builds the localized card text from these; [summary] is the English line for logs, the issue and the report.
 */
data class Problem(
    val id: String,
    val kind: ProblemKind,
    val atMs: Long,
    val subject: String,
    val detail: String,
    val viaExitRecord: Boolean = false,
) {
    /** The English one-liner for developer-facing places (DiagLog, issue draft, report section). Never shown on the card. */
    val summary: String
        get() = when (kind) {
            ProblemKind.JAVA_CRASH -> if (viaExitRecord) withSubject("Crash") else subject
            ProblemKind.NATIVE_CRASH -> withSubject("Native crash")
            ProblemKind.ANR -> withSubject("Not responding (ANR)")
            ProblemKind.UNCLEAN_EXIT -> "The app was in the foreground when its process ended"
            ProblemKind.STREAM_FAILED -> subject
        }

    private fun withSubject(what: String) = if (subject.isBlank()) what else "$what: $subject".take(SUMMARY_MAX)

    companion object {
        /** The longest summary line; longer subjects are cut (the detail keeps everything). */
        const val SUMMARY_MAX = 180
    }
}

/** What was already offered: one number per source, so nothing is offered twice. Stored by [ProblemMonitor]. */
data class ProblemLedger(val crashMs: Long = 0, val exitMs: Long = 0, val sessionMs: Long = 0, val streamOfferMs: Long = 0)

/** Everything the detector reads at a start of the shell. */
data class StartSignals(
    val nowMs: Long,
    val sdk: Int,
    val currentPid: Int,
    val crash: CrashRecord?,
    val exits: List<ExitRecord>,
    val previousSession: SessionRecord?,
)

/** The problem to offer now (or null) and the ledger to store, which already counts it as offered. */
data class Detection(val problem: Problem?, val ledger: ProblemLedger)

/**
 * The state machine behind the report offer (docs/DIAGNOSTICS.md): it turns the markers of the last run into at most one
 * [Problem], and every source it looked at is marked in the ledger, so the same problem is never offered a second time,
 * whether it was reported, dismissed or ignored.
 */
object ProblemDetector {
    /** Older signals are no longer the user's concern: an offer a week late would confuse. */
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** After an offer for a failed stream the next one waits this long (a PC that is off fails every time). */
    const val STREAM_OFFER_COOLDOWN_MS = 30L * 60 * 1000

    /** ActivityManager only keeps exit reasons from Android 11 on; before that the session marker is the fallback. */
    const val EXIT_REASONS_SDK = 30

    fun atStart(signals: StartSignals, ledger: ProblemLedger): Detection {
        val candidates = mutableListOf<Problem>()
        val fresh = { ms: Long -> signals.nowMs - ms <= MAX_AGE_MS }

        val crash = signals.crash?.takeIf { it.whenMs > ledger.crashMs }
        if (crash != null && fresh(crash.whenMs)) {
            candidates += Problem("crash-${crash.whenMs}", ProblemKind.JAVA_CRASH, crash.whenMs, CrashFile.summary(crash), crash.stackTrace)
        }

        val newExits = signals.exits.filter { it.timestamp > ledger.exitMs }
        for (exit in newExits) {
            if (!fresh(exit.timestamp)) continue
            val kind = when (exit.kind) {
                ExitKind.NATIVE_CRASH -> ProblemKind.NATIVE_CRASH
                ExitKind.ANR -> ProblemKind.ANR
                // The handler's own file tells more; an exit record of the same process is the same crash
                ExitKind.CRASH, ExitKind.INITIALIZATION_FAILURE -> if (signals.crash?.pid == exit.pid) null else ProblemKind.JAVA_CRASH
                ExitKind.OTHER -> null
            } ?: continue
            candidates += Problem("exit-${exit.timestamp}", kind, exit.timestamp, exit.description, exit.description, viaExitRecord = true)
        }

        val session = signals.previousSession
        val useSession = signals.sdk < EXIT_REASONS_SDK
        if (useSession && session != null && session.inForeground && session.pid != signals.currentPid &&
            session.startedAtMs > ledger.sessionMs && fresh(session.updatedAtMs) && signals.crash?.pid != session.pid
        ) {
            candidates += Problem("session-${session.startedAtMs}", ProblemKind.UNCLEAN_EXIT, session.updatedAtMs, "", "")
        }

        val next = ledger.copy(
            crashMs = maxOf(ledger.crashMs, signals.crash?.whenMs ?: 0),
            exitMs = maxOf(ledger.exitMs, newExits.maxOfOrNull { it.timestamp } ?: 0),
            sessionMs = if (useSession) maxOf(ledger.sessionMs, session?.startedAtMs ?: 0) else ledger.sessionMs,
        )
        // The richest signal wins: a Java crash has a stack trace, a native crash and an ANR have the system's description
        val best = candidates.minWithOrNull(compareBy<Problem> { it.kind.ordinal }.thenByDescending { it.atMs })
        return Detection(best, next)
    }

    /** A stream that failed (the secondary entry): offered unless one was offered within the cooldown. */
    fun streamFailed(ledger: ProblemLedger, nowMs: Long, title: String, details: String?): Detection {
        if (nowMs - ledger.streamOfferMs < STREAM_OFFER_COOLDOWN_MS) return Detection(null, ledger)
        val problem = Problem("stream-$nowMs", ProblemKind.STREAM_FAILED, nowMs, title.take(Problem.SUMMARY_MAX), details.orEmpty())
        return Detection(problem, ledger.copy(streamOfferMs = nowMs))
    }
}
