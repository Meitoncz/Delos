package com.limelight.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi

/**
 * Reads what the last run left and decides, once per problem, whether to offer a report (the state machine itself is
 * [ProblemDetector]). The ledger of what was already offered lives in the default preferences.
 */
object ProblemMonitor {
    private const val PREFS = "diag_problems"
    private var checked = false

    /** The problem to offer at this start of the shell, or null. Runs once per process; call it off the main thread. */
    @Synchronized
    fun detectAtStart(context: Context): Problem? {
        if (checked) return null
        checked = true
        val app = context.applicationContext
        val signals = StartSignals(
            nowMs = System.currentTimeMillis(), sdk = Build.VERSION.SDK_INT, currentPid = Process.myPid(),
            crash = CrashReporter.readCrash(), exits = exitRecords(app), previousSession = CrashReporter.previousSession,
        )
        val detection = ProblemDetector.atStart(signals, readLedger(app))
        writeLedger(app, detection.ledger)
        detection.problem?.let { DiagLog.i("Problem", "Offering a report: ${it.kind} ${it.summary}") }
        return detection.problem
    }

    /** A failed stream as a problem to offer (unless one was offered recently). */
    @Synchronized
    fun streamFailed(context: Context, title: String, details: String?): Problem? {
        val app = context.applicationContext
        val detection = ProblemDetector.streamFailed(readLedger(app), System.currentTimeMillis(), title, details)
        writeLedger(app, detection.ledger)
        return detection.problem
    }

    /** The user dismissed or reported [problem]: its crash files are not needed any more. */
    fun resolve(problem: Problem) {
        DiagLog.i("Problem", "Handled: ${problem.kind} ${problem.id}")
        if (problem.kind == ProblemKind.JAVA_CRASH) CrashReporter.clearCrash()
    }

    private fun exitRecords(context: Context): List<ExitRecord> {
        if (Build.VERSION.SDK_INT < ProblemDetector.EXIT_REASONS_SDK) return emptyList()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        return runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, 16) }.getOrDefault(emptyList())
            .filter { it.processName == context.packageName }
            .map { ExitRecord(it.timestamp, it.pid, exitKind(it.reason), describe(it)) }
    }

    private fun exitKind(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> ExitKind.CRASH
        ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitKind.NATIVE_CRASH
        ApplicationExitInfo.REASON_ANR -> ExitKind.ANR
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> ExitKind.INITIALIZATION_FAILURE
        else -> ExitKind.OTHER
    }

    /** The system's own words for the exit ("signal 11 (SIGSEGV)", "user request after error: Input dispatching timed out"). */
    @RequiresApi(ProblemDetector.EXIT_REASONS_SDK)
    private fun describe(info: ApplicationExitInfo): String {
        val text = info.description?.toString().orEmpty()
        return if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && !text.contains("signal")) "signal ${info.status} $text".trim() else text
    }

    private fun readLedger(context: Context): ProblemLedger {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ProblemLedger(prefs.getLong("crash_ms", 0), prefs.getLong("exit_ms", 0), prefs.getLong("session_ms", 0), prefs.getLong("stream_offer_ms", 0))
    }

    private fun writeLedger(context: Context, ledger: ProblemLedger) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("crash_ms", ledger.crashMs).putLong("exit_ms", ledger.exitMs)
            .putLong("session_ms", ledger.sessionMs).putLong("stream_offer_ms", ledger.streamOfferMs).apply()
    }
}
