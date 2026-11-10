package com.limelight.diag

import android.content.Context
import androidx.preference.PreferenceManager
import com.limelight.BuildConfig
import com.limelight.utils.ToastKind
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Severity of a diagnostic line. [letter] is what the log shows, like logcat. */
enum class DiagLevel(val letter: Char) { DEBUG('D'), INFO('I'), WARN('W'), ERROR('E') }

/** The text of one log line, one place so the reader (export, crash report) and the writer agree. */
object DiagLine {
    /** A single message is cut here, so one runaway line cannot fill the 1 MiB file. */
    const val MAX_MESSAGE_CHARS = 8 * 1024

    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    /** `2026-11-10 12:34:56.789 I/Stream [main] message`, in the device's time zone. */
    fun format(epochMillis: Long, zone: ZoneId, level: DiagLevel, tag: String, thread: String, message: String): String {
        val text = if (message.length > MAX_MESSAGE_CHARS) message.substring(0, MAX_MESSAGE_CHARS) + "… (cut)" else message
        return "${time.format(Instant.ofEpochMilli(epochMillis).atZone(zone))} ${level.letter}/$tag [$thread] $text"
    }
}

/**
 * The app's own diagnostic log (docs/DIAGNOSTICS.md): INFO and above in a release build, DEBUG as well in a debug
 * build, kept only on this device in a small rolling file until the user shares it. Fed by [com.limelight.LimeLog],
 * the native log callback and a few explicit events. Calls before [init], with the "Keep diagnostic logs" switch off
 * or below the level are no-ops that allocate nothing, so every call site may stay in a hot path's error branch.
 */
object DiagLog {
    @Volatile private var writer: DiagLogWriter? = null
    @Volatile private var minLevel = DiagLevel.INFO
    private val zone: ZoneId by lazy { ZoneId.systemDefault() }

    /** The "Keep diagnostic logs" switch. Off: nothing is written; what is already on disk stays until it is cleared. */
    @Volatile @JvmStatic var enabled = true
        private set

    /** Turns the log on or off and remembers it for every profile (the switch is about this device). */
    @JvmStatic
    fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext).edit().putBoolean(PREF_ENABLED, on).apply()
    }

    /** The stored switch; on by default. */
    @JvmStatic
    fun storedEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext).getBoolean(PREF_ENABLED, true)

    /** Starts the log in the app's private files directory. Safe to call again. */
    @JvmStatic
    fun init(context: Context) {
        if (writer != null) return
        synchronized(this) {
            if (writer != null) return
            enabled = storedEnabled(context)
            minLevel = if (BuildConfig.DEBUG) DiagLevel.DEBUG else DiagLevel.INFO
            writer = DiagLogWriter(File(context.applicationContext.filesDir, DIR))
        }
        // A process start is the boundary the reader of the log needs most
        i("App", "Process started: Delos ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}), pid ${android.os.Process.myPid()}")
    }

    @JvmStatic fun d(tag: String, message: String?) = log(DiagLevel.DEBUG, tag, message)
    @JvmStatic fun i(tag: String, message: String?) = log(DiagLevel.INFO, tag, message)
    @JvmStatic fun w(tag: String, message: String?) = log(DiagLevel.WARN, tag, message)
    @JvmStatic fun e(tag: String, message: String?) = log(DiagLevel.ERROR, tag, message)

    /** An error with its stack trace (the trace is scrubbed with the rest when the log is exported). */
    @JvmStatic
    fun e(tag: String, message: String, error: Throwable) = log(DiagLevel.ERROR, tag, message + "\n" + CrashText.stackTrace(error))

    @JvmStatic
    fun log(level: DiagLevel, tag: String, message: String?) {
        val sink = writer ?: return
        if (!enabled || level < minLevel) return
        val now = System.currentTimeMillis()
        // Formatted here and not on the writer thread: the thread name is only known to the caller
        sink.offer(DiagLine.format(now, zone, level, tag, Thread.currentThread().name, message ?: "null"))
    }

    /** A toast that tells the user about a problem (warnings and errors only; the rest is not diagnostic). */
    @JvmStatic
    fun logToast(kind: ToastKind, text: String) {
        when (kind) {
            ToastKind.ERROR -> e("Toast", text)
            ToastKind.WARNING -> w("Toast", text)
            else -> Unit
        }
    }

    /** Called from native code (MoonBridge.bridgeNativeLog): [priority] is an android.util.Log priority. */
    @JvmStatic
    fun nativeLog(priority: Int, tag: String, message: String) {
        val level = when {
            priority >= android.util.Log.ERROR -> DiagLevel.ERROR
            priority == android.util.Log.WARN -> DiagLevel.WARN
            priority == android.util.Log.INFO -> DiagLevel.INFO
            else -> DiagLevel.DEBUG
        }
        log(level, tag, message.trimEnd('\n', '\r'))
    }

    /** The whole log as written, oldest line first; empty before [init]. */
    @JvmStatic
    fun readAll(): String = writer?.readAll().orEmpty()

    @JvmStatic
    fun sizeBytes(): Long = writer?.sizeBytes() ?: 0L

    /** Waits (briefly) until the lines queued so far are on disk; the crash handler calls this. */
    @JvmStatic
    fun flush() { writer?.flush(timeoutMs = 500) }

    @JvmStatic
    fun clear() { writer?.clear() }

    private const val DIR = "diagnostics"
    const val PREF_ENABLED = "diag_logs_enabled"
}
