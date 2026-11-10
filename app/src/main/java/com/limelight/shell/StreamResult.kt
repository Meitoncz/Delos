package com.limelight.shell

import android.app.ActivityOptions
import android.content.res.Resources
import com.limelight.R
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.view.Display
import androidx.compose.runtime.Immutable
import com.limelight.DelosStreamActivity
import com.limelight.diag.DiagLog
import com.limelight.nvstream.jni.MoonBridge

/** How a stream that was not ended by the player ended (see docs/GUI.md, "Stream exits"). */
enum class StreamResultKind {
    /** The stream never started (a connection stage failed, the device cannot decode it, the shortcut's PC was unusable). */
    FAILED_TO_START,

    /** The stream ran, then the connection broke or the PC ended it with an error. */
    CONNECTION_LOST,

    /** The PC ended the session normally (the game exited, the host closed the stream): no overlay, a toast. */
    HOST_CLOSED,
}

/**
 * What Delos shows once a stream has ended without the player asking for it: [title] and [sentence] in
 * the result overlay (the toast of [StreamResultKind.HOST_CLOSED] is the [title]), the technical
 * [details] behind its Details button, and what Try again needs to start the same game again.
 */
@Immutable
data class StreamResult(
    val kind: StreamResultKind,
    val title: String,
    val sentence: String,
    val details: String?,
    val pcUuid: String,
    val pcName: String,
    val appId: Int,
    val appName: String,
    /** Tells one result from the next (the shell shows each once). */
    val id: Long = System.nanoTime(),
) {
    /** The extras that carry this result to the shell's activity. */
    fun putInto(intent: Intent): Intent = intent
        .putExtra(KIND, kind.name).putExtra(TITLE, title).putExtra(SENTENCE, sentence).putExtra(DETAILS, details)
        .putExtra(PC_UUID, pcUuid).putExtra(PC_NAME, pcName).putExtra(APP_ID, appId).putExtra(APP_NAME, appName)

    companion object {
        private const val KIND = "DelosResultKind"
        private const val TITLE = "DelosResultTitle"
        private const val SENTENCE = "DelosResultSentence"
        private const val DETAILS = "DelosResultDetails"
        private const val PC_UUID = "DelosResultPcUuid"
        private const val PC_NAME = "DelosResultPcName"
        private const val APP_ID = "DelosResultAppId"
        private const val APP_NAME = "DelosResultAppName"

        /**
         * The result [intent] carries, taken out of it: shown once, so a recreated or resumed activity
         * (which still holds the intent) never shows it again.
         */
        fun take(intent: Intent?): StreamResult? {
            intent ?: return null
            val kind = intent.getStringExtra(KIND)?.let { name -> StreamResultKind.entries.firstOrNull { it.name == name } } ?: return null
            val result = StreamResult(
                kind,
                intent.getStringExtra(TITLE) ?: "",
                intent.getStringExtra(SENTENCE) ?: "",
                intent.getStringExtra(DETAILS),
                intent.getStringExtra(PC_UUID) ?: return null,
                intent.getStringExtra(PC_NAME) ?: "",
                intent.getIntExtra(APP_ID, -1),
                intent.getStringExtra(APP_NAME) ?: "",
            )
            intent.removeExtra(KIND)
            intent.removeExtra(TITLE)
            intent.removeExtra(SENTENCE)
            intent.removeExtra(DETAILS)
            intent.removeExtra(PC_UUID)
            intent.removeExtra(PC_NAME)
            intent.removeExtra(APP_ID)
            intent.removeExtra(APP_NAME)
            return result
        }
    }
}

/** The words of a result overlay: [details] is the technical text (stage, error code, hints) behind Details. */
data class ResultText(val title: String, val sentence: String, val details: String)

/**
 * What the player reads when a stream failed: one calm [ResultText.sentence] and the technical
 * [ResultText.details] (the long hints of the former error dialogs).
 *
 * A [stage] is a connection stage that failed to start ("video stream"); null means the connection of a
 * running stream was terminated with [errorCode] (a MoonBridge `ML_ERROR_*`). [ports] are the ports to
 * check ([MoonBridge.stringifyPortFlags], null when none), [portsBlocked] says the connection test found
 * this network blocking Delos, and [decoderFailed] that the video decoder could not start.
 */
fun describeStreamFailure(res: Resources, stage: String?, errorCode: Int, ports: String?, portsBlocked: Boolean, decoderFailed: Boolean = false): ResultText {
    val blockedHint = res.getString(R.string.stream_result_blocked_hint)
    val title: String
    val sentence: String
    var details: String
    if (stage != null) {
        title = res.getString(R.string.stream_result_start_title)
        details = res.getString(R.string.stream_result_failed_stage, stage, errorCode)
        when {
            errorCode == 403 -> {
                sentence = res.getString(R.string.stream_result_denied)
                details += "\n\n" + res.getString(R.string.stream_result_denied_details)
            }
            errorCode == -408 -> {
                sentence = res.getString(R.string.stream_result_timeout)
                details += "\n\n" + res.getString(R.string.stream_result_timeout_details)
            }
            portsBlocked -> sentence = res.getString(R.string.stream_result_blocked)
            decoderFailed -> {
                sentence = res.getString(R.string.stream_result_decoder)
                details += "\n\n" + res.getString(R.string.stream_result_decoder_details)
            }
            else -> sentence = res.getString(R.string.stream_result_connecting)
        }
    } else if (portsBlocked) {
        // A blocked network supersedes any other explanation
        title = res.getString(R.string.stream_result_lost_title)
        sentence = res.getString(R.string.stream_result_blocked)
        details = blockedHint
    } else {
        when (errorCode) {
            MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC -> {
                title = res.getString(R.string.stream_result_lost_title)
                sentence = res.getString(R.string.stream_result_no_video)
                details = res.getString(R.string.stream_result_no_video_details)
            }
            MoonBridge.ML_ERROR_NO_VIDEO_FRAME -> {
                title = res.getString(R.string.stream_result_lost_title)
                sentence = res.getString(R.string.stream_result_slow)
                details = res.getString(R.string.stream_result_slow_details)
            }
            MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION, MoonBridge.ML_ERROR_PROTECTED_CONTENT -> {
                title = res.getString(R.string.stream_result_closed_title)
                sentence = res.getString(R.string.stream_result_host_error)
                details = res.getString(R.string.stream_result_host_error_details)
            }
            MoonBridge.ML_ERROR_FRAME_CONVERSION -> {
                title = res.getString(R.string.stream_result_closed_title)
                sentence = res.getString(R.string.stream_result_encoding)
                details = res.getString(R.string.stream_result_encoding_details)
            }
            else -> {
                title = res.getString(R.string.stream_result_lost_title)
                sentence = res.getString(R.string.stream_result_ended)
                // Large codes are hex values
                val code = if (Math.abs(errorCode) > 1000) Integer.toHexString(errorCode) else errorCode.toString()
                details = res.getString(R.string.stream_result_terminated_details, code)
            }
        }
    }
    if (stage != null && portsBlocked) details += "\n\n$blockedHint"
    if (!ports.isNullOrEmpty()) details += "\n\n" + res.getString(R.string.stream_result_ports_hint) + "\n" + ports
    return ResultText(title, sentence, details)
}

/** A device that cannot decode H.264 in hardware cannot stream at all. */
fun describeNoH264Decoder(res: Resources): ResultText = ResultText(
    res.getString(R.string.stream_result_start_title), res.getString(R.string.stream_result_no_h264),
    res.getString(R.string.stream_result_no_h264_details),
)

/** Why a shortcut or link could not start its game although it named a known PC. */
enum class ShortcutFailure { OFFLINE, NOT_PAIRED }

fun describeShortcutFailure(res: Resources, pcName: String, failure: ShortcutFailure): ResultText = when (failure) {
    ShortcutFailure.OFFLINE -> ResultText(res.getString(R.string.stream_result_start_title), res.getString(R.string.stream_result_offline, pcName), res.getString(R.string.stream_result_offline_details))
    ShortcutFailure.NOT_PAIRED -> ResultText(res.getString(R.string.stream_result_start_title), res.getString(R.string.stream_result_unpaired, pcName), res.getString(R.string.stream_result_unpaired_details))
}

/** The shown text of the PC ending the stream normally. */
fun hostClosedToast(res: Resources): String = res.getString(R.string.stream_result_host_closed)

/**
 * Where a stream that ended on its own goes: the shell (Home of the PC) with the [StreamResult], where a
 * failure shows the result overlay and a closed stream a toast.
 */
object StreamOutcome {
    /** The stream (or the shortcut that wanted to start one) failed: Delos shows [kind] with [text]. */
    @JvmStatic
    fun failed(activity: Activity, kind: StreamResultKind, text: ResultText, pcUuid: String?, pcName: String?, appId: Int, appName: String?) {
        show(activity, StreamResult(kind, text.title, text.sentence, text.details, pcUuid ?: "", pcName ?: activity.getString(R.string.stream_result_default_pc), appId, appName ?: ""))
    }

    /** The PC ended the session normally. */
    @JvmStatic
    fun hostClosed(game: DelosStreamActivity) {
        val intent = game.intent
        show(
            game,
            StreamResult(
                StreamResultKind.HOST_CLOSED, hostClosedToast(game.resources), "", null,
                intent.getStringExtra(DelosStreamActivity.EXTRA_PC_UUID) ?: "", intent.getStringExtra(DelosStreamActivity.EXTRA_PC_NAME) ?: game.getString(R.string.stream_result_default_pc),
                intent.getIntExtra(DelosStreamActivity.EXTRA_APP_ID, -1), intent.getStringExtra(DelosStreamActivity.EXTRA_APP_NAME) ?: "",
            ),
        )
    }

    /** The stream failed or was terminated; its own intent says which game on which PC. */
    @JvmStatic
    fun failed(game: DelosStreamActivity, kind: StreamResultKind, text: ResultText) {
        val intent = game.intent
        failed(
            game, kind, text, intent.getStringExtra(DelosStreamActivity.EXTRA_PC_UUID), intent.getStringExtra(DelosStreamActivity.EXTRA_PC_NAME),
            intent.getIntExtra(DelosStreamActivity.EXTRA_APP_ID, -1), intent.getStringExtra(DelosStreamActivity.EXTRA_APP_NAME),
        )
    }

    /**
     * Brings the shell to the front with [result] and ends [activity]. From the whole screen a scale-up only
     * fades the shell in over the stream (like the portal exit); a stream on an external display ends there
     * and the shell opens on the device's own screen.
     */
    private fun show(activity: Activity, result: StreamResult) {
        val summary = "Stream ended: ${result.kind} \"${result.title}\"${result.details?.let { " ($it)" }.orEmpty()}" // l10n-ignore
        if (result.kind == StreamResultKind.HOST_CLOSED) DiagLog.i("Stream", summary) else DiagLog.e("Stream", summary)
        val home = Intent().setClassName(activity, "com.limelight.shell.ShellActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        result.putInto(home)
        val options = if (activity is DelosStreamActivity && activity.isOnExternalDisplay && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
        } else {
            val root = activity.window.decorView
            ActivityOptions.makeScaleUpAnimation(root, 0, 0, root.width, root.height)
        }
        activity.startActivity(home, options.toBundle())
        activity.finish()
    }
}
