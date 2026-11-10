package com.limelight.diag

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns the on-device log into text the user can hand to someone (docs/DIAGNOSTICS.md): the report is built and scrubbed
 * on a worker thread from the PC list as it is right now, and leaves the device only through the Android share sheet or
 * the clipboard, both started by the user.
 */
object DiagExport {
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

    /** The scrubbed report of the whole log, with optional extra [sections]. */
    suspend fun report(context: Context, sections: List<Pair<String, String>> = emptyList()): DiagnosticReport =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            DiagnosticReports.build(
                DeviceInfoCollector.collect(app), DiagLog.readAll(), LogScrubber(ScrubContextCollector.collect(app)),
                generated(), sections,
            )
        }

    /** The scrubbed device block and the newest lines, for the clipboard. */
    suspend fun summary(context: Context): DiagnosticReport = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        DiagnosticReports.summary(DeviceInfoCollector.collect(app), DiagLog.readAll(), LogScrubber(ScrubContextCollector.collect(app)), generated())
    }

    fun generated(): String = ZonedDateTime.now(ZoneId.systemDefault()).format(stamp) + " (device time)"

    /** Writes [report] into the app's cache and returns its content URI for the share sheet; older shared files are removed. */
    suspend fun writeForSharing(context: Context, report: DiagnosticReport): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "delos-diagnostic-${ZonedDateTime.now().format(fileStamp)}.txt")
        file.writeText(report.text, Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Removes the file of an earlier share from the cache. */
    fun clearShared(context: Context) {
        File(context.cacheDir, SHARE_DIR).listFiles()?.forEach { it.delete() }
    }

    /** The Android share sheet for the file at [uri]; the receiving app gets read access to that one file only. */
    fun shareIntent(uri: Uri, title: String = "Share diagnostic log"): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Delos diagnostic log")
            clipData = ClipData.newRawUri("Delos diagnostic log", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title)
    }

    fun copyToClipboard(context: Context, report: DiagnosticReport) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Delos diagnostic summary", report.text))
    }

    const val SHARE_DIR = "diagnostics"
}
