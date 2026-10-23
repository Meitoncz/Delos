package com.limelight.shell

import android.content.Intent
import android.graphics.BitmapFactory
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import com.limelight.Game
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.AppearanceStore
import com.limelight.shell.ui.EaseOut
import com.limelight.shell.ui.GameArt
import com.limelight.shell.ui.LaunchCard
import com.limelight.shell.ui.LaunchHandoff
import com.limelight.shell.ui.LaunchScene
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.Stage
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.delosColors
import com.limelight.shell.ui.diveEffect
import com.limelight.shell.ui.rememberLaunchClock
import com.limelight.shell.ui.systemAccentHue
import com.limelight.utils.CacheHelper
import com.limelight.utils.ConnectionProgress
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * The stream's half of starting a game from the new UI (instead of the classic "Starting RTSP handshake"
 * dialog): it continues the launch scene the new UI showed – same background, ring and cover – fills the
 * ring while connecting, with the connection stage under the title, and when the stream runs the cover
 * gives way to the stream inside the ring and the ring opens past the screen edges: you fly through it
 * into the game.
 */
class StreamLoader private constructor(private val activity: ComponentActivity, private val view: ComposeView, private val pcName: String) : ConnectionProgress {
    private val stage = mutableStateOf<String?>(null)
    private val target = mutableFloatStateOf(LaunchHandoff.progress)
    private val streaming = mutableStateOf(false)

    /** How many lines each stage has told already (a stream is first set up, then started). */
    private val told = HashMap<String, Int>()

    override fun setMessage(message: String) {
        activity.runOnUiThread {
            stage.value = friendlyStage(message)
            // Each stage fills the ring a little more; the last part is left for the stream itself
            target.floatValue = (target.floatValue + .08f).coerceAtMost(.92f)
        }
    }

    /** Called when the stream runs (or failed): the ring opens into the game. */
    override fun dismiss() {
        activity.runOnUiThread { streaming.value = true }
    }

    private fun remove() {
        (view.parent as? ViewGroup)?.removeView(view)
        LaunchHandoff.background = null
    }

    /**
     * Moonlight's stage names ("Starting RTSP handshake"…) as something a player understands, with a wink
     * (like the loading lines of The Sims). Each stage starts at a random line and tells the next one when
     * it comes again.
     */
    private fun friendlyStage(message: String): String {
        val m = message.lowercase()
        val key = STAGE_LINES.keys.firstOrNull { it in m } ?: return message
        val lines = STAGE_LINES.getValue(key)
        val n = told[key] ?: Random.nextInt(lines.size)
        told[key] = n + 1
        return lines[n % lines.size].replace("%pc", pcName)
    }

    companion object {
        /** Intent extra set by the new UI when it starts a stream. */
        const val EXTRA = "DelosLoader"

        /** How long a connection line stays at least, so it can be read (later stages skip the ones in between). */
        private const val LINE_MS = 700L

        /** Lines per connection stage (keys: parts of Moonlight's stage names); %pc is the PC's name. */
        private val STAGE_LINES = linkedMapOf(
            "platform" to listOf("Reticulating splines", "Rolling up the sleeves", "Stretching before the game"),
            "name resolution" to listOf("Asking the router where %pc lives", "Looking for %pc on the network", "Checking under the couch for %pc"),
            "rtsp" to listOf("Haggling with %pc over pixels and frame rate", "Signing the stream contract with %pc", "Agreeing with %pc on how sharp it should look"),
            "control stream" to listOf("Shaking hands with %pc", "Running an invisible cable to %pc", "Exchanging secret handshakes"),
            "video stream" to listOf("Warming up the pixels", "Polishing the pixels", "Lining the pixels up"),
            "audio stream" to listOf("Tuning the speakers", "Untangling the sound waves", "Clearing the throat"),
            "input stream" to listOf("Wiring up the buttons", "Teaching the buttons who is the boss", "Plugging in an invisible controller"),
            "unlocking" to listOf("%pc is still waking up, hang on", "%pc is rubbing its eyes, one moment"),
        )

        /** The line under the title until the first stage (the same in the new UI and in the stream). */
        @JvmStatic
        fun greeting(pcName: String): String = "Calling up $pcName"

        @JvmStatic
        fun requested(intent: Intent): Boolean = intent.getBooleanExtra(EXTRA, false)

        @JvmStatic
        fun show(activity: ComponentActivity): StreamLoader {
            val intent = activity.intent
            val title = intent.getStringExtra(Game.EXTRA_APP_NAME) ?: ""
            val pcName = intent.getStringExtra(Game.EXTRA_PC_NAME) ?: "the PC"
            val pcUuid = intent.getStringExtra(Game.EXTRA_PC_UUID)
            val appId = intent.getIntExtra(Game.EXTRA_APP_ID, 0)
            val cover = pcUuid?.let { loadCover(activity, it, appId) }
            val hue = ((title.hashCode() and 0x7fffffff) % 360).toFloat()
            val card = LaunchCard(
                0, title, greeting(pcName),
                GameArt("$pcUuid/$appId", hue, (hue + 35f) % 360f, Symbols.SPORTS_ESPORTS, cover),
                LaunchHandoff.background,
            )
            val view = ComposeView(activity)
            val loader = StreamLoader(activity, view, pcName)
            // The colors and scale of the profile that started the stream
            val appearance = AppearanceStore(activity, ProfileStore(activity)::settings).appearance.value
            val colors = delosColors(appearance, systemAccentHue(activity))
            view.setContent {
                CompositionLocalProvider(LocalDelosColors provides colors) {
                    Stage(textScale = appearance.uiScale / 100f) {
                        LoaderScene(loader, card, appearance.reducedMotion)
                    }
                }
            }
            activity.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return loader
        }

        @Composable
        private fun LoaderScene(loader: StreamLoader, card: LaunchCard, reducedMotion: Boolean) {
            val colors = LocalDelosColors.current
            val progress = remember { Animatable(LaunchHandoff.progress) }
            val opening = remember { Animatable(0f) }
            // The new UI's clock, so the pulses and the moon go on where they were
            val clock = rememberLaunchClock(remember { LaunchHandoff.startedAt.takeIf { it != 0L } ?: System.nanoTime() })
            val target = loader.target.floatValue
            LaunchedEffect(target) { progress.animateTo(target, tween(600, easing = EaseOut)) }
            // The latest stage, but each line stays a moment (the flow skips what came in between)
            var line by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                snapshotFlow { loader.stage.value }.collect {
                    line = it
                    if (it != null) delay(LINE_MS)
                }
            }
            val streaming = loader.streaming.value
            LaunchedEffect(streaming) {
                if (!streaming) return@LaunchedEffect
                progress.animateTo(1f, tween(250, easing = EaseOut))
                // A moment for the first frames to arrive before the ring opens on them
                delay(300)
                // Linear on purpose: the ring's own growth starts slowly and rushes past at the end
                opening.animateTo(1f, tween(if (reducedMotion) 500 else 1600, easing = LinearEasing))
                loader.remove()
            }
            LaunchScene(
                card, line, progress = { progress.value }, opening = { opening.value }, clock = clock.takeUnless { reducedMotion },
                // While the ring opens, everything around it rushes past (zoom blur, wind, chromatic aberration)
                modifier = if (reducedMotion) Modifier else Modifier.diveEffect({ ((opening.value - .35f) / .65f).coerceIn(0f, 1f) }, colors.accent),
            )
        }

        private fun loadCover(activity: ComponentActivity, pcUuid: String, appId: Int): ImageBitmap? = runCatching {
            val file = CacheHelper.openPath(false, activity.cacheDir, "boxart", pcUuid, "$appId.png")
            if (file.exists()) BitmapFactory.decodeFile(file.path)?.asImageBitmap() else null
        }.getOrNull()
    }
}
