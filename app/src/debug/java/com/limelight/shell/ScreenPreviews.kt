package com.limelight.shell

import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.limelight.R
import com.limelight.binding.video.PyroWaveRenderer
import com.limelight.diag.Problem
import com.limelight.diag.ProblemKind
import com.limelight.shell.data.LocalArtwork
import com.limelight.shell.data.LocalBoxArt
import com.limelight.shell.data.LocalGameMetadata
import com.limelight.shell.screens.appArt
import com.limelight.shell.settings.Appearance
import com.limelight.shell.ui.Backdrop
import com.limelight.shell.ui.DelosColors
import com.limelight.shell.ui.HANDOFF_PROGRESS
import com.limelight.shell.ui.LaunchCard
import com.limelight.shell.ui.LaunchScene
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.StreamResultLayer
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.delosColors
import com.limelight.shell.ui.textResources
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Debug builds only: the app's screens with fixed fake data (no network, no paired PC, nothing written to the user's profile data), for the
 * layout script that screenshots every screen in many window shapes. Started with
 * `adb shell am start -n io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity --es preview_screen <name> [--es preview_state <variant>]`
 * where `<name>` is one of:
 * - `home`: the real shell on Home, with 12 fake games of GAMING-PC (one running, one last played, two more hidden) whose covers are the placeholder art.
 * - `artwork`: the Artwork screen of Hades, with fixed choices for every kind.
 * - `settings`, `settings_search`, `debug_info`, `profile`: the real Settings (first tab), its search, Debug info and Profile screens.
 * - `game_options`: the real per-game settings screen of Hades (`game_settings` is the older component preview).
 * - `devices`: the Devices screen with GAMING-PC (online, connected), LIVING-ROOM-PC (asleep) and a PC with a very long name (online); the
 *   fourth fake PC, STUDY-LAPTOP, is unpaired and therefore not in this list.
 * - `start`: the start screen with three profiles and GAMING-PC as the default PC.
 *   `--ei preview_pcs <n>` gives `start`, `devices` and `pair_pc` n paired PCs instead of three (the circle rows that scroll).
 * - `pair`: the pairing screen of STUDY-LAPTOP showing the PIN 4826 and waiting for it to be entered.
 * - `filter`: the filter modal open over Home, with the genres and themes of the fake library.
 * - `dialog`: a dialog layer (title, message, Pair and Cancel) over Home.
 * - `loader`: the stream launch scene of Hades at a fixed progress and a fixed scene time (no launch).
 * - `result`: the stream result card of a stream that could not start, over the plain background.
 * - `side_menu`: the side menu open over Home. `home_context`: Home with the game menu of the focused game, opened by the
 *   Start key 3 s after the start. `problem`: Home with the problem report card. `stream_exit`: Home under the exit portal ("Closing Elden Ring").
 * - `boot`, `create_profile`, `pair_pc`: the first-launch screens, and the PC pairing list.
 * - `stream_stats` (`preview_state` full|compact, `preview_position` TOP_LEFT|TOP_RIGHT|BOTTOM_LEFT|BOTTOM_RIGHT): the real statistics panel over the game scene, with fixed numbers.
 * - `stream_osc`, `stream_video`: the stream's own geometry (the real on-screen controls, the video rectangle), see [StreamShapePreview].
 * All of them but `loader`, `result`, `stream_stats`, `stream_osc` and `stream_video` run the real `Shell` in a [PreviewWorld] (docs/GUI.md, "Screen previews"); `loader`,
 * `result` and `stream_stats` draw `LaunchScene`, `StreamResultLayer` and `StreamStatsPanel` alone. Any other name is not handled here.
 */
internal object ScreenPreviews {
    fun from(intent: Intent, @Suppress("UNUSED_PARAMETER") shell: ShellState): (@Composable () -> Unit)? {
        PreviewFixtures.pairedPcs = intent.getIntExtra("preview_pcs", 0)
        return when (intent.getStringExtra("preview_screen")) {
            "home" -> shellPreview(ShellRoute { Screen.Home })
            "artwork" -> shellPreview(ShellRoute { world -> Screen.Artwork(world.library.pc, world.library.apps.first { it.name == ARTWORK_GAME }) })
            "devices" -> shellPreview(ShellRoute { Screen.Devices })
            "settings" -> shellPreview(ShellRoute { Screen.Settings })
            "settings_search" -> shellPreview(ShellRoute { Screen.SettingsSearch })
            "debug_info" -> shellPreview(ShellRoute { Screen.DebugInfo })
            "profile" -> shellPreview(ShellRoute { Screen.Profile })
            "game_options" -> shellPreview(ShellRoute { world -> Screen.GameSettings(world.library.pc, world.library.apps.first { it.name == ARTWORK_GAME }) })
            "start" -> shellPreview(ShellRoute { Screen.Start })
            "pair" -> shellPreview(ShellRoute { Screen.Pin(PreviewFixtures.STUDY_PC, PreviewFixtures.host(PreviewFixtures.STUDY_PC).name, null, Screen.Start) })
            "filter" -> shellPreview(ShellRoute(layer = Layer.Filters) { Screen.Home })
            "dialog" -> shellPreview(ShellRoute(layer = Layer.Dialog(DIALOG)) { Screen.Home })
            "side_menu" -> shellPreview(ShellRoute(layer = Layer.Side) { Screen.Home })
            "home_context" -> shellPreview(ShellRoute(press = KeyEvent.KEYCODE_BUTTON_START) { Screen.Home })
            "problem" -> shellPreview(ShellRoute(after = { it.shell.problem = PROBLEM }) { Screen.Home })
            "stream_exit" -> shellPreview(ShellRoute(after = { it.shell.portal = PORTAL }) { Screen.Home })
            "boot" -> shellPreview(ShellRoute { Screen.Boot() })
            "create_profile" -> shellPreview(ShellRoute { Screen.CreateProfile(editing = false, back = Screen.Boot()) })
            "pair_pc" -> shellPreview(ShellRoute { Screen.PairPc(back = Screen.Start) })
            "loader" -> loaderPreview
            "result" -> resultPreview
            "stream_stats" -> StatsPreview.from(intent)
            "stream_osc", "stream_video" -> StreamShapePreview.from(intent)
            else -> null
        }
    }

    private const val ARTWORK_GAME = "Hades"

    private val DIALOG = DialogSpec(
        "Pair with GAMING-PC?",
        "Delos will ask GAMING-PC to start pairing. Enter the PIN that Delos shows on that PC to finish.",
        emptyList(), "Pair",
    ) { true }

    private val PROBLEM = Problem("preview-problem", ProblemKind.NATIVE_CRASH, PreviewFixtures.NOW, "SIGSEGV (SEGV_MAPERR)", "", viaExitRecord = true)

    private val PORTAL = Portal(1, Symbols.POWER_SETTINGS_NEW, "Closing Elden Ring", "GAMING-PC", cancellable = false)

    private fun shellPreview(route: ShellRoute): @Composable () -> Unit = {
        // The native Vulkan probe behind Settings' capabilities dies with SIGILL on the layout emulator (SwiftShader): say "no PyroWave" up front
        remember {
            runCatching { PyroWaveRenderer::class.java.getDeclaredField("available").apply { isAccessible = true }.set(null, false) }
        }
        ShellPreview(route)
    }

    private val loaderPreview: @Composable () -> Unit = { LoaderPreview() }
    private val resultPreview: @Composable () -> Unit = { ResultPreview() }

    /** The scene time of the launch scene: where its tunnel has settled into the cruise. */
    private const val LOADER_SECONDS = 3f

    /** The real shell, fed by a [PreviewWorld]: its repository, stores, appearance and profiles, and a [ShellState] that starts on the route. */
    @Composable
    private fun ShellPreview(route: ShellRoute) {
        val base = LocalContext.current
        val activity = LocalActivity.current ?: return
        val textScale = LocalStage.current.textScale
        val world = remember(route) { PreviewWorld(base, activity, route, textScale) }
        DisposableEffect(world) {
            CompanionPreview.liveShell = world.shell // the companion window of a real second display follows this shell
            onDispose {
                if (CompanionPreview.liveShell === world.shell) CompanionPreview.liveShell = null
                world.close()
            }
        }
        LaunchedEffect(world) {
            val key = route.press ?: return@LaunchedEffect
            delay(3000)
            world.keys.dispatch(KeyEvent(KeyEvent.ACTION_UP, key))
        }
        val appearance by world.appearanceStore.appearance.collectAsStateWithLifecycle()
        val colors = remember(appearance) { delosColors(appearance, null) }
        CompositionLocalProvider(
            LocalContext provides world.context, LocalDelosColors provides colors, LocalReducedMotion provides appearance.reducedMotion,
            LocalBoxArt provides world.boxArt, LocalArtwork provides world.artwork, LocalGameMetadata provides world.metadata,
        ) {
            // The activity's own GlobalKeys has no listener in a preview: the shell's global keys (L1 / R1, X, Y, Start, Select) reach the world's
            // here, so `adb shell input keyevent` (and a controller) can switch tabs and open the filter sheets of a previewed screen
            Box(Modifier.fillMaxSize().onPreviewKeyEvent { world.keys.dispatch(it.nativeKeyEvent) }) {
                Shell(
                    appearance, world.appearanceStore::update, world.keys, world.repo, world.launcher, world.profiles, world.startupStore,
                    world.setup, world.links, world.results, world.shell, previewProfiles = world.previewProfiles,
                )
            }
        }
    }

    /** The defaults, dark, at the interface scale of the window, and the palette they give: what the previews without a world draw in. */
    @Composable
    private fun PreviewTheme(content: @Composable (Appearance, DelosColors) -> Unit) {
        val textScale = LocalStage.current.textScale
        val appearance = remember(textScale) { Appearance(dark = true, uiScale = (textScale * 100f).roundToInt().coerceIn(100, 130)) }
        val colors = remember(appearance) { delosColors(appearance, null) }
        CompositionLocalProvider(
            LocalDelosColors provides colors, LocalReducedMotion provides false, LocalFocusTracker provides remember { FocusTracker() },
        ) { content(appearance, colors) }
    }

    @Composable
    private fun LoaderPreview() = PreviewTheme { _, colors ->
        val res = textResources()
        val pc = remember { PreviewFixtures.pc(PreviewFixtures.host(PreviewFixtures.ACTIVE_PC)) }
        val app = remember { PreviewFixtures.app(PreviewFixtures.game(ARTWORK_GAME)) }
        val card = remember(res) {
            LaunchCard(1, app.name, StreamLoader.greeting(res, pc.name), appArt(pc, app), null, null, "3840 × 2160 · 60 FPS · HEVC · HDR · 80 Mbps")
        }
        Box(Modifier.fillMaxSize().background(colors.bg)) {
            LaunchScene(card, stage = null, progress = { HANDOFF_PROGRESS }, opening = { 0f }, clock = { LOADER_SECONDS })
        }
    }

    @Composable
    private fun ResultPreview() = PreviewTheme { appearance, colors ->
        val res = textResources()
        val haze = rememberHazeState()
        val host = remember { PreviewFixtures.host(PreviewFixtures.ACTIVE_PC) }
        val result = remember(res) {
            StreamResult(
                StreamResultKind.FAILED_TO_START, res.getString(R.string.stream_result_start_title), res.getString(R.string.stream_result_timeout),
                res.getString(R.string.stream_result_failed_stage, "video stream", 5003) + "\n\n" + res.getString(R.string.stream_result_timeout_details),
                host.uuid, host.name, PreviewFixtures.game(ARTWORK_GAME).id, ARTWORK_GAME, id = 1L,
            )
        }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().hazeSource(haze)) { Backdrop(appearance, colors, hero = null, animate = false) }
            // The face's float is the one motion that never settles: the preview holds it still, as with reduced motion
            StreamResultLayer(result, haze, reducedMotion = true, onRetry = {}, onBack = {})
        }
    }
}
