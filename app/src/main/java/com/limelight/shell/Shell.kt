package com.limelight.shell

import android.app.ActivityOptions
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.limelight.shell.screens.AppearanceRows
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.limelight.shell.data.App
import com.limelight.shell.data.GameLauncher
import com.limelight.shell.data.Library
import com.limelight.shell.data.Pc
import com.limelight.shell.data.PcRepository
import com.limelight.shell.screens.HomeScreen
import com.limelight.shell.screens.PlaceholderScreen
import com.limelight.shell.screens.SettingsScreen
import com.limelight.shell.screens.appArt
import com.limelight.shell.screens.step
import com.limelight.shell.settings.Appearance
import com.limelight.shell.system.rememberBatteryStatus
import com.limelight.shell.system.rememberClock
import com.limelight.shell.system.rememberWifiStatus
import com.limelight.shell.ui.Backdrop
import com.limelight.shell.ui.ContextMenuLayer
import com.limelight.shell.ui.Cue
import com.limelight.shell.ui.EaseOut
import com.limelight.shell.ui.Feedback
import com.limelight.shell.ui.Glyph
import com.limelight.shell.ui.Hint
import com.limelight.shell.ui.HintBar
import com.limelight.shell.ui.HeroBitmaps
import com.limelight.shell.ui.HANDOFF_PROGRESS
import com.limelight.shell.ui.LaunchCard
import com.limelight.shell.ui.LaunchHandoff
import com.limelight.shell.ui.LaunchOverlay
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.NavButton
import com.limelight.shell.ui.RowValue
import com.limelight.shell.ui.Scrim
import com.limelight.shell.ui.SettingRow
import com.limelight.shell.ui.SideMenuHeader
import com.limelight.shell.ui.SideMenuLayer
import com.limelight.shell.ui.StatusBar
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.ToastHost
import com.limelight.shell.ui.ZoomedBar
import com.limelight.shell.ui.avatarBrush
import com.limelight.shell.ui.tpx
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * The new UI (Phase 6, step 6.3): background, screens with transitions, status bar, hint bar, the
 * side menu (left menu button), context menus (right menu button / Start / X) and toasts.
 */
@Composable
fun Shell(appearance: Appearance, update: ((Appearance) -> Appearance) -> Unit, keys: GlobalKeys, repo: PcRepository, launcher: GameLauncher) {
    val colors = LocalDelosColors.current
    val feedback = LocalFeedback.current
    val shell = remember { ShellState() }
    val tracker = remember { FocusTracker() }
    val haze = rememberHazeState()
    var blurSource by remember { mutableStateOf(false) }
    LaunchedEffect(shell.layer != null) {
        if (shell.layer != null) {
            blurSource = true
        } else {
            delay(600)
            blurSource = false
        }
    }
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current
    val actions = remember(launcher) { ShellGameActions(shell, launcher, feedback, scope) }

    // Starting a game: the launch scene appears and its ring starts filling, then the stream window takes
    // over the very same scene (StreamLoader), connects and opens the ring into the game
    LaunchedEffect(shell.launch?.id) {
        val launch = shell.launch ?: return@LaunchedEffect
        LaunchHandoff.background = launch.background
        LaunchHandoff.progress = HANDOFF_PROGRESS
        delay(if (appearance.reducedMotion) 300 else 700)
        shell.diving = true
        // Only a game that really opens counts as last played (not one cancelled in the loader)
        repo.setLastPlayed(launch.pc.uuid, launch.app.id)
        // The stream (Game, launchMode singleTask) opens in its own task, where the system ignores custom
        // activity animations and slides the new window in. A scale-up is honoured for new tasks; from the
        // whole screen it only fades the stream window in over this one (a clip reveal also shifts it up a
        // little), and that starts with the same scene.
        val options = activity?.window?.decorView?.let { root ->
            ActivityOptions.makeScaleUpAnimation(root, 0, 0, root.width, root.height).toBundle()
        }
        activity?.startActivity(launch.intent, options)
    }
    // Back from the stream: the overlay goes away, the focus stays on the game, the PC's state is refreshed
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (shell.launch != null && shell.diving) {
            shell.launch = null
            shell.diving = false
            repo.refresh()
            tracker.requester?.let {
                feedback.silenceNextFocus()
                runCatching { it.requestFocus() }
            }
        }
    }
    val wifi by rememberWifiStatus()
    val battery by rememberBatteryStatus()
    val clock by rememberClock()
    val library by repo.library.collectAsStateWithLifecycle()
    val showHidden by repo.showHidden.collectAsStateWithLifecycle()
    val uiScale = appearance.uiScale / 100f
    val pc = when (val l = library) {
        is Library.Ready -> l.pc
        is Library.Offline -> l.pc
        else -> null
    }

    fun toggleSide() {
        if (shell.layer == Layer.Side) {
            feedback.play(Cue.BACK)
            shell.closeLayer()
        } else {
            feedback.play(Cue.OPEN)
            shell.openLayer(Layer.Side, tracker)
        }
    }

    val defaultMenu = remember {
        ContextMenu(
            "Delos",
            listOf(
                MenuItem(Symbols.MENU, "Side menu") { shell.openLayer(Layer.Side, tracker) },
                MenuItem(Symbols.SETTINGS, "All settings") { shell.navigate(Screen.SETTINGS) },
            ),
        )
    }

    fun toggleContext() {
        when (shell.layer) {
            is Layer.Context -> {
                feedback.play(Cue.BACK)
                shell.closeLayer()
            }
            null -> {
                feedback.play(Cue.OPEN)
                shell.openLayer(Layer.Context(tracker.contextMenu ?: defaultMenu, tracker.bounds()), tracker)
            }
            else -> Unit
        }
    }

    DisposableEffect(keys) {
        keys.listener = listener@{ code ->
            if (shell.launch != null) return@listener
            when (code) {
                KeyEvent.KEYCODE_BUTTON_SELECT -> toggleSide()
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_X -> toggleContext()
                KeyEvent.KEYCODE_BUTTON_L1 -> if (shell.layer == null) shell.onShoulder?.invoke(-1)
                KeyEvent.KEYCODE_BUTTON_R1 -> if (shell.layer == null) shell.onShoulder?.invoke(1)
            }
        }
        onDispose { keys.listener = null }
    }

    BackHandler(enabled = shell.layer != null || shell.screen != Screen.HOME) {
        feedback.play(Cue.BACK)
        shell.back()
    }
    // B during the loader cancels the start (registered last, so it wins while a game is starting)
    BackHandler(enabled = shell.launch != null) {
        if (!shell.diving) {
            feedback.play(Cue.BACK)
            shell.launch = null
            tracker.requester?.let {
                feedback.silenceNextFocus()
                runCatching { it.requestFocus() }
            }
        }
    }

    // When a layer has closed, the focus returns to where it was (silently, like the prototype)
    LaunchedEffect(shell.layer) {
        if (shell.layer != null) return@LaunchedEffect
        val previous = shell.takeReturnFocus() ?: return@LaunchedEffect
        feedback.silenceNextFocus()
        val restored = runCatching { previous.requestFocus() }.getOrDefault(false)
        if (!restored) runCatching { shell.restoreScreenFocus?.invoke() }
    }

    val nav = listOf(
        NavButton(Symbols.SETTINGS, "Settings", shell.screen == Screen.SETTINGS) { shell.navigate(Screen.SETTINGS) },
        NavButton(Symbols.SAILING, "Profile", shell.screen == Screen.PROFILE) { shell.navigate(Screen.PROFILE) },
        NavButton(Symbols.DEVICES, "Devices", shell.screen == Screen.DEVICES) { shell.navigate(Screen.DEVICES) },
    )

    CompositionLocalProvider(LocalShell provides shell, LocalFocusTracker provides tracker, LocalGameActions provides actions) {
        Box(Modifier.fillMaxSize()) {
            // The blur source re-records the whole screen every frame, so it is only active while an overlay
            // is shown (and while it fades out)
            Box(Modifier.fillMaxSize().then(if (blurSource) Modifier.hazeSource(haze) else Modifier)) {
                Backdrop(appearance, colors, hero = if (shell.screen == Screen.HOME) shell.hero else null)
                AnimatedContent(
                    shell.screen,
                    transitionSpec = {
                        // `.screen`: fade in .5 s while settling from a slight zoom (.7 s)
                        (fadeIn(tween(500)) + scaleIn(tween(700, easing = EaseOut), initialScale = 1.035f)) togetherWith
                            (fadeOut(tween(500)) + scaleOut(tween(700, easing = EaseOut), targetScale = 1.035f))
                    },
                    label = "screen",
                ) { screen ->
                    when (screen) {
                        Screen.HOME -> HomeScreen(library, appearance, repo)
                        Screen.SETTINGS -> SettingsScreen(appearance, update)
                        Screen.PROFILE -> PlaceholderScreen(Symbols.ACCOUNT_CIRCLE, "Profile", "Profiles and avatars come in step 6.6.") { shell.navigate(Screen.HOME) }
                        Screen.DEVICES -> PlaceholderScreen(Symbols.DEVICES, "Devices", "Your PCs, pairing and renaming come in step 6.6.") { shell.navigate(Screen.HOME) }
                    }
                }
                ZoomedBar(uiScale) {
                    StatusBar(nav.takeIf { shell.screen.main }, pcName = pc?.name, pcOnline = pc?.online == true, wifi = wifi, battery = battery, clock = clock)
                }
            }

            Scrim(shell.layer != null, haze) {
                feedback.play(Cue.BACK)
                shell.closeLayer()
            }
            val context = shell.layer as? Layer.Context
            ContextMenuLayer(context?.menu, context?.anchor, haze) { shell.closeLayer() }
            SideMenuLayer(
                shell.layer == Layer.Side,
                haze,
                header = {
                    SideMenuHeader(
                        avatar = {
                            Box(Modifier.fillMaxSize().background(avatarBrush(200f)), contentAlignment = Alignment.Center) {
                                Symbol(Symbols.SAILING, 40.tpx, Color.White, filled = true)
                            }
                        },
                        name = "Player",
                        status = when {
                            pc == null -> "Not connected"
                            pc.online -> "Connected to ${pc.name}"
                            else -> "${pc.name} is offline"
                        },
                    )
                },
            ) { first ->
                SideMenuRows(appearance, update, Modifier.focusRequester(first)) { shell.navigate(Screen.SETTINGS) }
                val hiddenCount = (library as? Library.Ready)?.hiddenCount ?: 0
                if (hiddenCount > 0 || showHidden) {
                    SettingRow(
                        Symbols.VISIBILITY, "Show hidden games", RowValue.Toggle(showHidden) { repo.setShowHidden(it) },
                        description = "$hiddenCount hidden on ${pc?.name ?: "this PC"}", compact = true,
                    )
                }
            }

            val note = (library as? Library.Ready)?.takeIf { shell.screen == Screen.HOME && shell.layer == null }?.let { "${it.apps.size} apps on ${it.pc.name}" }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                ZoomedBar(uiScale) { HintBar(hints(shell, tracker.adjustable), note = note) }
            }
            val launch = shell.launch
            LaunchOverlay(
                launch?.let { LaunchCard(it.id, it.app.name, StreamLoader.greeting(it.pc.name), appArt(it.pc, it.app, it.image), it.background) },
                appearance.reducedMotion,
            )
            ToastHost(shell.toast) { shell.hideToast(it) }
        }
    }
}

/** Quick settings of the side menu. */
@Composable
private fun SideMenuRows(appearance: Appearance, update: ((Appearance) -> Appearance) -> Unit, firstModifier: Modifier, openSettings: () -> Unit) {
    SettingRow(Symbols.DARK_MODE, "Theme", RowValue.Choice(if (appearance.dark) "Dark" else "Light", { update { it.copy(dark = !it.dark) } }), firstModifier, compact = true)
    SettingRow(Symbols.WALLPAPER, "Material You", RowValue.Toggle(appearance.materialYou) { on -> update { it.copy(materialYou = on) } }, compact = true)
    SettingRow(Symbols.ANIMATION, "Background", RowValue.Choice(appearance.background.label, { dir -> update { it.copy(background = it.background.step(dir)) } }), compact = true)
    SettingRow(Symbols.VIEW_CAROUSEL, "Home layout", RowValue.Choice(appearance.homeLayout.label, { dir -> update { it.copy(homeLayout = it.homeLayout.step(dir)) } }), compact = true)
    SettingRow(Symbols.MUSIC_NOTE, "Interface sounds", RowValue.Toggle(appearance.sounds) { on -> update { it.copy(sounds = on) } }, compact = true)
    SettingRow(Symbols.VIBRATION, "Haptics", RowValue.Toggle(appearance.haptics) { on -> update { it.copy(haptics = on) } }, compact = true)
    SettingRow(Symbols.SETTINGS, "All settings", RowValue.Action(openSettings), compact = true)
}

/** The game actions of the shell: start with the launch animation, quit with toasts, no dialogs. */
private class ShellGameActions(
    private val shell: ShellState,
    private val launcher: GameLauncher,
    private val feedback: Feedback,
    private val scope: CoroutineScope,
) : GameActions {
    private var launches = 0

    override fun start(pc: Pc, app: App, image: ImageBitmap?) {
        if (shell.launch != null) return
        val start = launcher.prepare(pc, app)
        if (start == null) {
            feedback.play(Cue.ERROR)
            shell.showToast("${pc.name} cannot be reached", Symbols.WIFI_OFF)
            return
        }
        if (start.virtualDisplayUnavailable) {
            shell.showToast("The virtual display is not ready on ${pc.name} · streaming the main display", Symbols.MONITOR)
        }
        shell.diving = false
        shell.launch = LaunchRequest(++launches, pc, app, start.intent, image, HeroBitmaps.latest("${pc.uuid}/${app.id}"))
    }

    override fun quit(pc: Pc, app: App) {
        scope.launch {
            shell.showToast("Quitting ${app.name}…", Symbols.POWER_SETTINGS_NEW)
            report(launcher.quit(pc, app))
        }
    }

    override fun quitAndStart(pc: Pc, running: App, app: App, image: ImageBitmap?) {
        scope.launch {
            shell.showToast("Quitting ${running.name}…", Symbols.POWER_SETTINGS_NEW)
            val result = launcher.quit(pc, running)
            if (result.success) start(pc, app, image) else report(result)
        }
    }

    private fun report(result: GameLauncher.QuitResult) {
        if (!result.success) feedback.play(Cue.ERROR)
        shell.showToast(result.message, if (result.success) Symbols.CHECK_CIRCLE else Symbols.ERROR)
    }
}

/** Button hints for the current screen and layer (the prototype's updateHints). */
private fun hints(shell: ShellState, adjustable: Boolean): List<Hint> {
    val adjust = if (adjustable) listOf(Hint(Glyph.ADJUST, "Adjust")) else emptyList()
    return when (shell.layer) {
        is Layer.Context -> listOf(Hint(Glyph.A, "Select"), Hint(Glyph.B, "Close"))
        Layer.Side -> adjust + listOf(Hint(Glyph.A, "Change"), Hint(Glyph.B, "Close"))
        null -> when (shell.screen) {
            Screen.HOME -> listOf(Hint(Glyph.A, "Start"), Hint(Glyph.MENU_LEFT, "Menu"), Hint(Glyph.MENU_RIGHT, "Options"))
            Screen.SETTINGS -> adjust + listOf(Hint(Glyph.A, "Change"), Hint(Glyph.B, "Back"), Hint(Glyph.MENU_LEFT, "Menu"))
            else -> listOf(Hint(Glyph.A, "Select"), Hint(Glyph.B, "Back"), Hint(Glyph.MENU_LEFT, "Menu"))
        }
    }
}
