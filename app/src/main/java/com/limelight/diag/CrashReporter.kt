package com.limelight.diag

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Process
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Leaves the markers the next start reads (docs/DIAGNOSTICS.md): the uncaught-exception handler writes the stack trace
 * and the newest log lines to `crash.txt` and then `crash.marker`, and the lifecycle callbacks keep `session.marker` up
 * to date (the only signal on API 29). Everything stays in the app's private files; nothing is sent.
 */
object CrashReporter {
    private const val CRASH_FILE = "crash.txt"
    private const val CRASH_MARKER = "crash.marker"
    private const val SESSION_MARKER = "session.marker"

    private val handling = AtomicBoolean(false)
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "DiagSession").apply { isDaemon = true } }
    private var dir: File? = null
    private var startedAtMs = 0L
    private var started = 0

    /** The marker of the process before this one, read before this process overwrote it. */
    @Volatile var previousSession: SessionRecord? = null
        private set

    /** Installs the handler (chained to the previous one, which still ends the process) and starts the session marker. */
    fun install(app: Application) {
        val files = File(app.filesDir, "diagnostics").apply { mkdirs() }
        dir = files
        startedAtMs = System.currentTimeMillis()
        previousSession = runCatching { SessionRecord.parse(File(files, SESSION_MARKER).readText()) }.getOrNull()
        writeSession(foreground = false)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // A crash inside the handler must not hide the original one
            if (handling.compareAndSet(false, true)) runCatching { record(thread, error) }
            previous?.uncaughtException(thread, error)
        }

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { if (started++ == 0) writeSession(foreground = true) }
            override fun onActivityStopped(activity: Activity) { if (--started <= 0) { started = 0; writeSession(foreground = false) } }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun record(thread: Thread, error: Throwable) {
        val files = dir ?: return
        // "Keep diagnostic logs" off: nothing of the crash is kept on the device either
        if (!DiagLog.enabled) return
        DiagLog.e("Crash", "Uncaught exception on thread ${thread.name}", error)
        DiagLog.flush()
        val now = System.currentTimeMillis()
        val record = CrashFile.record(now, Process.myPid(), thread.name, error, DiagLog.readAll())
        writeAtomically(File(files, CRASH_FILE), CrashFile.format(record))
        // The marker comes last: a crash file without it is an interrupted write and is ignored
        writeAtomically(File(files, CRASH_MARKER), "$now ${Process.myPid()}")
    }

    /** The crash the last run left, or null when there is none or its write was interrupted. */
    fun readCrash(): CrashRecord? {
        val files = dir ?: return null
        if (!File(files, CRASH_MARKER).isFile) return null
        return runCatching { CrashFile.parse(File(files, CRASH_FILE).readText()) }.getOrNull()
    }

    /** Removes the crash files once the problem has been reported or dismissed. */
    fun clearCrash() {
        val files = dir ?: return
        File(files, CRASH_MARKER).delete()
        File(files, CRASH_FILE).delete()
    }

    private fun writeSession(foreground: Boolean) {
        val files = dir ?: return
        val text = SessionRecord(Process.myPid(), startedAtMs, System.currentTimeMillis(), foreground).format()
        writer.execute { runCatching { writeAtomically(File(files, SESSION_MARKER), text) } }
    }

    private fun writeAtomically(target: File, text: String) {
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeText(text, Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            target.delete()
            temporary.renameTo(target)
        }
    }
}
