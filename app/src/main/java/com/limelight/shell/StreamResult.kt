package com.limelight.shell

import android.app.ActivityOptions
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
                intent.getStringExtra(PC_NAME) ?: "the PC",
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

private const val START_TITLE = "Couldn't start the stream"
private const val LOST_TITLE = "Connection lost"
private const val CLOSED_TITLE = "The PC closed the stream"

private const val PORTS_HINT = "Check your firewall and port forwarding rules for port(s):"
private const val BLOCKED_HINT = "Your device's current network connection is blocking Delos. Streaming over the Internet may not work while connected to this network."

/**
 * What the player reads when a stream failed: one calm [ResultText.sentence] and the technical
 * [ResultText.details] (the long hints of the former error dialogs).
 *
 * A [stage] is a connection stage that failed to start ("video stream"); null means the connection of a
 * running stream was terminated with [errorCode] (a MoonBridge `ML_ERROR_*`). [ports] are the ports to
 * check ([MoonBridge.stringifyPortFlags], null when none), [portsBlocked] says the connection test found
 * this network blocking Delos, and [decoderFailed] that the video decoder could not start.
 */
fun describeStreamFailure(stage: String?, errorCode: Int, ports: String?, portsBlocked: Boolean, decoderFailed: Boolean = false): ResultText {
    val title: String
    val sentence: String
    var details: String
    if (stage != null) {
        title = START_TITLE
        details = "Failed to start $stage (error $errorCode)"
        when {
            errorCode == 403 -> {
                sentence = "The PC didn't allow Delos to start the app."
                details += "\n\nPermission Denied (Launch Apps)"
            }
            errorCode == -408 -> {
                sentence = "The PC didn't answer in time."
                details += "\n\nConnection Timeout"
            }
            portsBlocked -> sentence = "This network seems to block Delos."
            decoderFailed -> {
                sentence = "This device couldn't start the video decoder."
                details += "\n\nVideo decoder failed to initialize. Your device may not support the selected resolution or frame rate."
            }
            else -> sentence = "Something went wrong while connecting to the PC."
        }
    } else if (portsBlocked) {
        // A blocked network supersedes any other explanation
        title = LOST_TITLE
        sentence = "This network seems to block Delos."
        details = BLOCKED_HINT
    } else {
        when (errorCode) {
            MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC -> {
                title = LOST_TITLE
                sentence = "No video arrived from the PC."
                details = "No video received from host."
            }
            MoonBridge.ML_ERROR_NO_VIDEO_FRAME -> {
                title = LOST_TITLE
                sentence = "The network connection isn't keeping up."
                details = "Your network connection isn't performing well. Reduce your video bitrate setting or try a faster connection."
            }
            MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION, MoonBridge.ML_ERROR_PROTECTED_CONTENT -> {
                title = CLOSED_TITLE
                sentence = "Something went wrong on the PC while starting the stream."
                details = "Something went wrong on your host PC when starting the stream.\n\n" +
                    "Make sure you don't have any DRM-protected content open on your host PC. You can also try restarting your host PC."
            }
            MoonBridge.ML_ERROR_FRAME_CONVERSION -> {
                title = CLOSED_TITLE
                sentence = "The PC reported a video encoding error."
                details = "The host PC reported a fatal video encoding error.\n\n" +
                    "Try disabling HDR mode, changing the streaming resolution, or changing your host PC's display resolution."
            }
            else -> {
                title = LOST_TITLE
                sentence = "The connection to the PC ended unexpectedly."
                // Large codes are hex values
                val code = if (Math.abs(errorCode) > 1000) Integer.toHexString(errorCode) else errorCode.toString()
                details = "The connection was terminated.\n\nError code: $code"
            }
        }
    }
    if (stage != null && portsBlocked) details += "\n\n$BLOCKED_HINT"
    if (!ports.isNullOrEmpty()) details += "\n\n$PORTS_HINT\n$ports"
    return ResultText(title, sentence, details)
}

/** A device that cannot decode H.264 in hardware cannot stream at all. */
fun describeNoH264Decoder(): ResultText = ResultText(
    START_TITLE, "This device can't play the stream.",
    "This device or ROM doesn't support hardware accelerated H.264 playback.",
)

/** Why a shortcut or link could not start its game although it named a known PC. */
enum class ShortcutFailure { OFFLINE, NOT_PAIRED }

fun describeShortcutFailure(pcName: String, failure: ShortcutFailure): ResultText = when (failure) {
    ShortcutFailure.OFFLINE -> ResultText(START_TITLE, "$pcName is offline.", "Computer is offline")
    ShortcutFailure.NOT_PAIRED -> ResultText(START_TITLE, "$pcName isn't paired with this device.", "PC not paired")
}

/** The shown text of the PC ending the stream normally. */
const val HOST_CLOSED_TOAST = "The PC ended the stream"

/**
 * Where a stream that ended on its own goes: the shell (Home of the PC) with the [StreamResult], where a
 * failure shows the result overlay and a closed stream a toast.
 */
object StreamOutcome {
    /** The stream (or the shortcut that wanted to start one) failed: Delos shows [kind] with [text]. */
    @JvmStatic
    fun failed(activity: Activity, kind: StreamResultKind, text: ResultText, pcUuid: String?, pcName: String?, appId: Int, appName: String?) {
        show(activity, StreamResult(kind, text.title, text.sentence, text.details, pcUuid ?: "", pcName ?: "the PC", appId, appName ?: ""))
    }

    /** The PC ended the session normally. */
    @JvmStatic
    fun hostClosed(game: DelosStreamActivity) {
        val intent = game.intent
        show(
            game,
            StreamResult(
                StreamResultKind.HOST_CLOSED, HOST_CLOSED_TOAST, "", null,
                intent.getStringExtra(DelosStreamActivity.EXTRA_PC_UUID) ?: "", intent.getStringExtra(DelosStreamActivity.EXTRA_PC_NAME) ?: "the PC",
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
        val summary = "Stream ended: ${result.kind} \"${result.title}\"${result.details?.let { " ($it)" }.orEmpty()}"
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
