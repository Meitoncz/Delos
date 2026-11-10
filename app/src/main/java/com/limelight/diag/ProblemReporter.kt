package com.limelight.diag

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What "Report this problem" does, in the order the user sees it (docs/DIAGNOSTICS.md): the whole sanitized log is saved
 * to the Downloads folder, then the GitHub new-issue page opens in the browser with the form prefilled. The user attaches
 * the file and submits; nothing is sent by the app and no account or token is involved.
 */
object ProblemReporter {
    class Prepared(val url: String, val report: DiagnosticReport, val fileName: String)

    /** Builds the scrubbed report (with the problem's section) and the prefilled address, off the main thread. */
    suspend fun prepare(context: Context, problem: Problem): Prepared = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val info = DeviceInfoCollector.collect(app)
        val scrubber = LogScrubber(ScrubContextCollector.collect(app))
        val fileName = "delos-diagnostic-${ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT))}.txt"
        val report = DiagnosticReports.build(
            info, DiagLog.readAll(), scrubber, DiagExport.generated(),
            sections = listOf("Problem" to ProblemIssue.section(problem, DiagExport.generated())),
        )
        Prepared(IssueUrl.build(ProblemIssue.draft(problem, info, fileName), scrub = scrubber::scrub), report, fileName)
    }

    /**
     * Writes [report] to the Downloads folder through MediaStore (no permission needed on any supported Android version) and
     * returns its address, or null when that failed.
     */
    suspend fun saveToDownloads(context: Context, report: DiagnosticReport, fileName: String): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return@withContext null
        val written = runCatching {
            resolver.openOutputStream(uri)?.use { it.write(report.text.toByteArray(Charsets.UTF_8)) } != null
        }.getOrDefault(false)
        if (!written) {
            runCatching { resolver.delete(uri, null, null) }
            return@withContext null
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        runCatching { resolver.update(uri, values, null, null) }
        uri
    }

    /** The browser opening [url]; false when no app handles it. */
    fun openIssue(context: Context, url: String): Boolean =
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}
