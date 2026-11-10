package com.limelight.shell

import android.content.Context
import android.content.res.Resources
import com.limelight.R
import android.graphics.BitmapFactory
import android.os.Build
import android.view.ViewGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import com.limelight.DelosStreamActivity
import com.limelight.binding.video.LaunchArrival
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.AppearanceStore
import com.limelight.shell.settings.GameSettingsStore
import com.limelight.shell.ui.EaseOut
import com.limelight.shell.ui.GameArt
import com.limelight.shell.ui.HyperspaceConfig
import com.limelight.shell.ui.LaunchCard
import com.limelight.shell.ui.LaunchHandoff
import com.limelight.shell.ui.LaunchScene
import com.limelight.shell.ui.LaunchStageQueue
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.Stage
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.delosColors
import com.limelight.shell.ui.formatLaunchStreamParameters
import com.limelight.shell.ui.rememberLaunchClock
import com.limelight.shell.ui.systemAccentHue
import com.limelight.utils.CacheHelper
import com.limelight.utils.ConnectionProgress
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.random.Random

/** Continues the shell's tunnel and real stage queue, revealing only a presented video frame. */
class StreamLoader private constructor(
    private val activity: DelosStreamActivity,
    private val view: ComposeView,
    private val pcName: String,
    initialParameters: String,
    val reducedMotion: Boolean,
) : ConnectionProgress {
    private val stages = LaunchStageQueue(LaunchHandoff.line, LaunchHandoff.lineAt, LINE_MS)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val target = mutableFloatStateOf(LaunchHandoff.progress)
    private val streaming = mutableStateOf(false)
    /** The decoder side of the launch arrival. */
    private val arrival get() = activity.surfaceController
    private val parameters = mutableStateOf(initialParameters)
    private val origin = LaunchHandoff.startedAt
    private var removed = false

    override fun setMessage(message: String) {
        activity.runOnUiThread {
            if (!removed) {
                stages.enqueue(lineFor(activity.resources, message, pcName))
                target.floatValue = (target.floatValue + .08f).coerceAtMost(.92f)
                changes.trySend(Unit)
            }
        }
    }

    /** Connection readiness is not presentation readiness; LoaderScene checks both independently. */
    override fun dismiss() {
        activity.runOnUiThread {
            if (!removed) {
                streaming.value = true
                changes.trySend(Unit)
            }
        }
    }

    private fun dispose() {
        if (removed) return
        removed = true
        changes.close()
        arrival.setLaunchArrivalProgress(-1f)
        // One launch, one handoff: nothing of it may start the next launch (a shortcut launch has no shell overlay)
        if (LaunchHandoff.startedAt == origin) LaunchHandoff.reset()
    }

    private fun remove() {
        dispose()
        (view.parent as? ViewGroup)?.removeView(view)
    }

    companion object {
        /** The longest the reveal waits for the first presented frame after the stream runs. */
        private const val FRAME_WAIT_MS = 3000L

        const val LINE_MS = HyperspaceConfig.LINE_MS
        private val told = HashMap<String, Int>()

        @JvmStatic fun resetLines() {
            synchronized(told) { told.clear() }
            LaunchHandoff.line = null
            LaunchHandoff.lineAt = 0L
        }

        @JvmStatic fun firstLine(res: Resources, pcName: String): String = lineFor(res, "platform", pcName).also {
            LaunchHandoff.line = it
            LaunchHandoff.lineAt = System.nanoTime()
        }

        private fun lineFor(res: Resources, message: String, pcName: String): String {
            val m = message.lowercase()
            val key = STAGE_LINES.keys.firstOrNull { it in m } ?: return message
            val options = res.getStringArray(STAGE_LINES.getValue(key))
            val n = synchronized(told) { (told[key] ?: Random.nextInt(options.size)).also { told[key] = it + 1 } }
            return String.format(res.configuration.locales[0], options[n % options.size], pcName)
        }

        /** The funny line told for each connection stage (matched against the native stage message): arrays of sentences with the PC name as %1$s. */
        private val STAGE_LINES = linkedMapOf(
            "platform" to R.array.stream_loader_lines_platform,
            "name resolution" to R.array.stream_loader_lines_name_resolution,
            "rtsp" to R.array.stream_loader_lines_rtsp,
            "control stream" to R.array.stream_loader_lines_control_stream,
            "video stream" to R.array.stream_loader_lines_video_stream,
            "audio stream" to R.array.stream_loader_lines_audio_stream,
            "input stream" to R.array.stream_loader_lines_input_stream,
            "unlocking" to R.array.stream_loader_lines_unlocking,
        )

        @JvmStatic fun greeting(res: Resources, pcName: String): String = res.getString(R.string.stream_loader_greeting, pcName)

        /** Before negotiation show the selected policy honestly; after setup use the active codec. */
        @JvmStatic fun parameters(res: Resources, config: PreferenceConfiguration, activeFormat: Int, width: Int, height: Int): String {
            val codec = when {
                activeFormat and MoonBridge.VIDEO_FORMAT_MASK_PYROWAVE != 0 -> "PyroWave"
                activeFormat and MoonBridge.VIDEO_FORMAT_MASK_AV1 != 0 -> "AV1"
                activeFormat and MoonBridge.VIDEO_FORMAT_MASK_H265 != 0 -> "HEVC"
                activeFormat and MoonBridge.VIDEO_FORMAT_MASK_H264 != 0 -> "H.264"
                else -> when (config.videoFormat) {
                    PreferenceConfiguration.FormatOption.FORCE_AV1 -> "AV1"
                    PreferenceConfiguration.FormatOption.FORCE_HEVC -> "HEVC"
                    PreferenceConfiguration.FormatOption.FORCE_H264 -> "H.264"
                    PreferenceConfiguration.FormatOption.PYROWAVE -> "PyroWave"
                    else -> res.getString(R.string.stream_loader_codec_auto)
                }
            }
            return formatLaunchStreamParameters(width, height, codec, config.fps)
        }

        /** The shell uses the same profile and per-game overrides attached to its launch intent. */
        fun parametersFor(context: Context, pcUuid: String?, appId: Int): String {
            val base = ProfileStore.currentSettings(context)
            val game = if (pcUuid != null && appId >= 0) GameSettingsStore(base, pcUuid, appId) else null
            val prefs = launchParameterPreferences(base, game?.preferences ?: base, game?.overrides().orEmpty())
            val config = PreferenceConfiguration.readPreferences(context, prefs)
            // readPreferences' legacy codec reader reads the context; this shell context is not DelosStreamActivity.
            config.videoFormat = when (prefs.getString("video_format", "auto")) {
                "forceav1" -> PreferenceConfiguration.FormatOption.FORCE_AV1
                "forceh265" -> PreferenceConfiguration.FormatOption.FORCE_HEVC
                "neverh265" -> PreferenceConfiguration.FormatOption.FORCE_H264
                "pyrowave" -> PreferenceConfiguration.FormatOption.PYROWAVE
                else -> PreferenceConfiguration.FormatOption.AUTO
            }
            return parameters(context.resources, config, 0, config.width, config.height)
        }

        @JvmStatic fun show(activity: DelosStreamActivity, config: PreferenceConfiguration): StreamLoader {
            val intent = activity.intent
            val title = intent.getStringExtra(DelosStreamActivity.EXTRA_APP_NAME).orEmpty()
            val pcName = intent.getStringExtra(DelosStreamActivity.EXTRA_PC_NAME) ?: activity.getString(R.string.stream_result_default_pc)
            val pcUuid = intent.getStringExtra(DelosStreamActivity.EXTRA_PC_UUID)
            val appId = intent.getIntExtra(DelosStreamActivity.EXTRA_APP_ID, 0)
            val cover = if (Build.VERSION.SDK_INT < 33) pcUuid?.let { loadCover(activity, it, appId) } else null
            val logo = LaunchHandoff.logo
            val hue = ((title.hashCode() and 0x7fffffff) % 360).toFloat()
            val info = parameters(activity.resources, config, 0, config.width, config.height)
            val card = LaunchCard(0, title, greeting(activity.resources, pcName),
                GameArt("$pcUuid/$appId", hue, (hue + 35f) % 360f, Symbols.SPORTS_ESPORTS, cover),
                LaunchHandoff.background, logo, info)
            val view = ComposeView(activity)
            val appearance = AppearanceStore(activity) { ProfileStore.currentSettings(activity) }.appearance.value
            val loader = StreamLoader(activity, view, pcName, info, appearance.reducedMotion)
            val colors = delosColors(appearance, systemAccentHue(activity))
            view.setContent {
                CompositionLocalProvider(LocalDelosColors provides colors) {
                    Stage(textScale = appearance.uiScale / 100f) { LoaderScene(loader, card, appearance.reducedMotion) }
                }
            }
            activity.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return loader
        }

        @Composable private fun LoaderScene(loader: StreamLoader, card: LaunchCard, reducedMotion: Boolean) {
            val progress = remember { Animatable(LaunchHandoff.progress) }
            val opening = remember { Animatable(0f) }
            val start = remember { LaunchHandoff.startedAt.takeIf { it != 0L } ?: System.nanoTime() }
            val clock = if (reducedMotion) null else rememberLaunchClock(start)
            val target = loader.target.floatValue
            var line by remember { mutableStateOf(loader.stages.currentLine) }
            var drained by remember { mutableStateOf(false) }
            val logo by rememberLogo(loader.activity, loader.activity.intent.getStringExtra(DelosStreamActivity.EXTRA_PC_UUID),
                loader.activity.intent.getIntExtra(DelosStreamActivity.EXTRA_APP_ID, 0), card.logo)
            DisposableEffect(loader) { onDispose { loader.dispose() } }
            LaunchedEffect(target) { progress.animateTo(target, tween(600, easing = EaseOut)) }
            LaunchedEffect(loader) {
                while (isActive) {
                    val now = System.nanoTime()
                    loader.stages.advance(now)
                    line = loader.stages.currentLine
                    // The game opens as soon as it really runs: the lines queued behind the current one are simply not told
                    if (loader.streaming.value) {
                        drained = true
                        break
                    }
                    val remaining = loader.stages.remainingMillis(now)
                    val signal = if (remaining > 0L) withTimeoutOrNull(remaining) { loader.changes.receiveCatching() }
                        else loader.changes.receiveCatching()
                    if (signal?.isClosed == true) break
                }
            }
            val streaming = loader.streaming.value
            LaunchedEffect(streaming, drained) {
                if (!streaming || !drained) return@LaunchedEffect
                // Bounded: a decoder that never reports a rendered frame must not leave the tunnel up for ever
                withTimeoutOrNull(FRAME_WAIT_MS) {
                    while (!loader.activity.isFinishing && !loader.arrival.isLaunchFrameReady) delay(16)
                }
                if (loader.activity.isFinishing) return@LaunchedEffect
                loader.parameters.value = loader.activity.launchStreamParameters
                progress.animateTo(1f, tween(250, easing = EaseOut))
                var effectAvailable = !reducedMotion
                val firstLegMs = (HyperspaceConfig.OPENING_MS * LaunchArrival.READY_CHECK_AT).toInt()
                opening.animateTo(LaunchArrival.READY_CHECK_AT,
                    tween(if (reducedMotion) (HyperspaceConfig.REDUCED_OPENING_MS * LaunchArrival.READY_CHECK_AT).toInt() else firstLegMs,
                        easing = LinearEasing)) {
                    if (effectAvailable && LaunchArrival.shouldActivate(value)) loader.arrival.setLaunchArrivalProgress(value)
                }
                if (effectAvailable) {
                    val ready = withTimeoutOrNull(LaunchArrival.READY_WAIT_MS) {
                        while (!loader.activity.isFinishing && !loader.arrival.isLaunchArrivalReady &&
                            loader.arrival.launchArrivalError == null) delay(16)
                        !loader.activity.isFinishing && loader.arrival.isLaunchArrivalReady
                    } == true
                    if (loader.activity.isFinishing) return@LaunchedEffect
                    if (!ready) {
                        loader.arrival.reportLaunchArrivalError(loader.arrival.launchArrivalError ?: "Arrival preparation timed out") // l10n-ignore
                        effectAvailable = false
                    }
                }
                opening.animateTo(1f,
                    tween(if (reducedMotion) HyperspaceConfig.REDUCED_OPENING_MS -
                        (HyperspaceConfig.REDUCED_OPENING_MS * LaunchArrival.READY_CHECK_AT).toInt()
                        else HyperspaceConfig.OPENING_MS - firstLegMs, easing = LinearEasing)) {
                    if (effectAvailable) loader.arrival.setLaunchArrivalProgress(value)
                }
                if (effectAvailable) {
                    loader.arrival.setLaunchArrivalProgress(1f)
                    val complete = withTimeoutOrNull(LaunchArrival.COMPLETE_WAIT_MS) {
                        while (!loader.activity.isFinishing && !loader.arrival.isLaunchArrivalComplete &&
                            loader.arrival.launchArrivalError == null) delay(16)
                        !loader.activity.isFinishing && loader.arrival.isLaunchArrivalComplete
                    } == true
                    if (!complete && !loader.activity.isFinishing) {
                        loader.arrival.reportLaunchArrivalError(loader.arrival.launchArrivalError ?: "Arrival handover timed out") // l10n-ignore
                    }
                }
                loader.remove()
            }
            LaunchScene(card.copy(logo = logo, streamParameters = loader.parameters.value), line,
                progress = { progress.value }, opening = { opening.value }, clock = clock)
        }

        /** Decode existing artwork off the UI/decoder initialization path; the title remains usable. */
        @Composable fun rememberLogo(context: Context, pcUuid: String?, appId: Int, initial: ImageBitmap?): State<ImageBitmap?> {
            val image = remember(context, pcUuid, appId) { mutableStateOf(initial) }
            LaunchedEffect(context, pcUuid, appId) {
                if (image.value == null && pcUuid != null && appId >= 0) {
                    image.value = withContext(Dispatchers.IO) { loadLogo(context, pcUuid, appId) }
                }
            }
            return image
        }

        /** Existing stored logo only; no downloader or network operation during launch. */
        fun loadLogo(context: Context, pcUuid: String, appId: Int): ImageBitmap? = runCatching {
            val file = File(context.filesDir, "artwork/$pcUuid/$appId/logo")
            if (!file.isFile || file.length() == 0L) return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1000) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()

        fun loadCover(context: Context, pcUuid: String, appId: Int): ImageBitmap? = runCatching {
            val sgdb = File(context.filesDir, "artwork/$pcUuid/$appId/cover")
            val file = if (sgdb.exists() && sgdb.length() > 0) sgdb else CacheHelper.openPath(false, context.cacheDir, "boxart", pcUuid, "$appId.png")
            if (file.exists()) BitmapFactory.decodeFile(file.path)?.asImageBitmap() else null
        }.getOrNull()
    }
}
