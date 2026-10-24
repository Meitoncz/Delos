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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.limelight.shell.data.App
import com.limelight.shell.data.GameLauncher
import com.limelight.shell.data.Library
import com.limelight.shell.data.Pc
import com.limelight.shell.data.AVATARS
import com.limelight.shell.data.PcRepository
import com.limelight.shell.data.PcSetup
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.screens.HomeScreen
import com.limelight.shell.screens.BootScreen
import com.limelight.shell.screens.CreateProfileScreen
import com.limelight.shell.screens.DevicesScreen
import com.limelight.shell.screens.PairPcScreen
import com.limelight.shell.screens.PinScreen
import com.limelight.shell.screens.ProfileScreen
import com.limelight.shell.screens.StartScreen
import com.limelight.shell.screens.SettingsScreen
import com.limelight.shell.screens.appArt
import com.limelight.shell.screens.step
import com.limelight.shell.settings.Appearance
import com.limelight.shell.settings.StartupStore
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
import com.limelight.shell.ui.LocalBackdropHaze
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.NavButton
import com.limelight.shell.ui.RowValue
import com.limelight.shell.ui.Scrim
import com.limelight.shell.ui.SettingRow
import com.limelight.shell.ui.SideMenuHeader
import com.limelight.shell.ui.SideMenuLayer
import com.limelight.shell.ui.StatusBar
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.ToastHost
import com.limelight.shell.ui.ZoomedBar
import com.limelight.shell.ui.AvatarBadge
import com.limelight.shell.ui.ButtonCaptureLayer
import com.limelight.shell.ui.DialogLayer
import com.limelight.shell.ui.PortalOverlay
import com.limelight.shell.data.LocalArtwork
import com.limelight.shell.data.downloadAll
import com.limelight.shell.ui.ArtworkProgressBar
import com.limelight.shell.screens.ArtworkScreen
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * The new UI (Phase 6, step 6.3): background, screens with transitions, status bar, hint bar, the
 * side menu (left menu button), context menus (right menu button / Start / X) and toasts.
 */
@Composable
fun Shell(
    appearance: Appearance,
    update: ((Appearance) -> Appearance) -> Unit,
    keys: GlobalKeys,
    repo: PcRepository,
    launcher: GameLauncher,
    profileStore: ProfileStore,
    startupStore: StartupStore,
    setup: PcSetup,
    links: MutableStateFlow<ShellLink?>,
) {
    val colors = LocalDelosColors.current
    val feedback = LocalFeedback.current
    // The first launch starts with the logo and a new profile; every later one with the start screen
    val shell = remember { ShellState(if (profileStore.profiles.value.all.isEmpty()) Screen.Boot() else Screen.Start) }
    val tracker = remember { FocusTracker() }
    val haze = rememberHazeState()
    // Only the animated background, for glass that is always on (the focused row, the top bar)
    val backdropHaze = rememberHazeState()
    var blurSource by remember { mutableStateOf(false) }
    LaunchedEffect(shell.layer != null || shell.portal != null) {
        if (shell.layer != null || shell.portal != null) {
            blurSource = true
        } else {
            delay(600)
            blurSource = false
        }
    }
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current
    val actions = remember(launcher) { ShellGameActions(shell, launcher, feedback, scope) }
    val reducedMotion by rememberUpdatedState(appearance.reducedMotion)
    val devices = remember { ShellDeviceActions(shell, tracker, repo, setup, startupStore, feedback, scope) { reducedMotion } }
    val profiles by profileStore.profiles.collectAsStateWithLifecycle()
    val startup by startupStore.startup.collectAsStateWithLifecycle()
    val pcs by repo.pcs.collectAsStateWithLifecycle()

    // "Skip the start screen": straight into the default device
    LaunchedEffect(Unit) {
        val start = startupStore.startup.value
        val default = start.defaultPc
        if (shell.screen == Screen.Start && start.skipStart && default != null) {
            repo.awaitPc(default, 4_000) { it.paired }?.let { devices.connect(it) }
        }
    }

    // Links and shortcuts, once there is a profile (the first launch keeps them until then). Links can
    // come from any web page or app, so pairing and starting a game ask first (a security prompt)
    val link by links.collectAsStateWithLifecycle()
    LaunchedEffect(link, profiles.all.isEmpty()) {
        val l = link ?: return@LaunchedEffect
        if (profiles.all.isEmpty()) return@LaunchedEffect
        links.value = null
        when (l) {
            is ShellLink.Pair -> {
                feedback.play(Cue.OPEN)
                val who = l.name?.let { "$it (${l.address})" } ?: l.address
                shell.openLayer(
                    Layer.Dialog(
                        DialogSpec("Pair with $who?", "A link asks Delos to pair with this PC. Continue only if you opened it yourself.", emptyList(), "Pair") {
                            devices.addAndPair(l.address, l.otp, Screen.Start)
                            true
                        }
                    ),
                    tracker,
                )
            }
            is ShellLink.Launch -> {
                feedback.play(Cue.OPEN)
                val game = l.appName?.takeIf { it.isNotEmpty() } ?: "a game"
                val host = l.hostName?.takeIf { it.isNotEmpty() } ?: "a PC"
                shell.openLayer(
                    Layer.Dialog(
                        DialogSpec("Start $game?", "A link asks Delos to start $game on $host. Continue only if you opened it yourself.", emptyList(), "Start") {
                            activity?.startActivity(l.intent)
                            true
                        }
                    ),
                    tracker,
                )
            }
            is ShellLink.OpenPc -> repo.awaitPc(l.uuid, 4_000) { it.paired }?.let { devices.connect(it) }
        }
    }

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
    // Back from the stream: the overlay goes away, the focus stays on the game, the PC's state is refreshed.
    // Left through the stream menu, the portal's burst that began over the stream ends here.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        StreamExit.take()?.let { exit ->
            scope.launch {
                shell.portalBurst = true
                shell.portal = Portal(-1, Symbols.HOME, exit.label, exit.sub, cancellable = false, arriving = true)
                delay(500)
                shell.portal = null
                delay(400)
                shell.portalBurst = false
            }
        }
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
    val clock by rememberClock(appearance.clock)
    val library by repo.library.collectAsStateWithLifecycle()
    // SteamGridDB: games never looked up get their best-rated artwork in the background
    val artwork = LocalArtwork.current
    LaunchedEffect((library as? Library.Ready)?.let { it.pc.uuid to it.apps.map { a -> a.id } }, artwork.enabled, artwork.automatic) {
        val ready = library as? Library.Ready ?: return@LaunchedEffect
        if (!artwork.enabled || !artwork.automatic) return@LaunchedEffect
        artwork.downloadAll(ready.pc, ready.apps, refresh = false) { shell.showToast(it, Symbols.ERROR) }
    }
    val showHidden by repo.showHidden.collectAsStateWithLifecycle()
    val uiScale = appearance.uiScale / 100f
    val pc = when (val l = library) {
        is Library.Ready -> l.pc
        is Library.Offline -> l.pc
        else -> null
    }

    // The side menu belongs to the main screens and the start screen, not to the first launch
    val sideMenu = shell.screen.main || shell.screen == Screen.Start

    fun toggleSide() {
        if (shell.layer != null && shell.layer != Layer.Side) return
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
                MenuItem(Symbols.SETTINGS, "All settings") { shell.navigate(Screen.Settings) },
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
                val menu = tracker.contextMenu ?: defaultMenu.takeIf { sideMenu } ?: return
                feedback.play(Cue.OPEN)
                shell.openLayer(Layer.Context(menu, tracker.bounds()), tracker)
            }
            else -> Unit
        }
    }

    DisposableEffect(keys) {
        keys.canSearch = { shell.screen == Screen.Settings && shell.layer == null && shell.portal == null && shell.launch == null }
        keys.onSearch = { shell.onSettingsSearch?.invoke() }
        keys.listener = listener@{ code ->
            if (shell.launch != null || shell.portal != null) return@listener
            when (code) {
                KeyEvent.KEYCODE_BUTTON_SELECT -> if (sideMenu) toggleSide()
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_X -> toggleContext()
                KeyEvent.KEYCODE_BUTTON_L1 -> if (shell.layer == null) shell.onShoulder?.invoke(-1)
                KeyEvent.KEYCODE_BUTTON_R1 -> if (shell.layer == null) shell.onShoulder?.invoke(1)
            }
        }
        onDispose {
            keys.listener = null
            keys.onSearch = null
            keys.canSearch = { false }
        }
    }

    BackHandler(enabled = shell.layer != null || shell.screen.back != null) {
        feedback.play(Cue.BACK)
        shell.back()
    }
    // B in the portal only cancels waiting for a PC to wake up; nothing underneath moves meanwhile
    BackHandler(enabled = shell.portal != null) { devices.cancelPortal() }
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

    val profile = profiles.current
    val nav = listOf(
        NavButton(Symbols.SETTINGS, "Settings", shell.screen == Screen.Settings) { shell.navigate(Screen.Settings) },
        NavButton(Symbols.ACCOUNT_CIRCLE, "Profile", shell.screen == Screen.Profile) { shell.navigate(Screen.Profile) },
        NavButton(Symbols.DEVICES, "Devices", shell.screen == Screen.Devices) { shell.navigate(Screen.Devices) },
    )

    CompositionLocalProvider(
        LocalShell provides shell, LocalFocusTracker provides tracker, LocalGameActions provides actions, LocalDeviceActions provides devices,
        LocalBackdropHaze provides backdropHaze, LocalLibrary provides library,
    ) {
        Box(Modifier.fillMaxSize()) {
            // The blur source re-records the whole screen every frame, so it is only active while an overlay
            // is shown (and while it fades out)
            // On in the very frame an overlay appears (its blur would otherwise start empty and clipped)
            Box(Modifier.fillMaxSize().then(if (blurSource || shell.layer != null || shell.portal != null) Modifier.hazeSource(haze) else Modifier)) {
                Box(Modifier.fillMaxSize().hazeSource(backdropHaze)) {
                    Backdrop(appearance, colors, hero = if (shell.screen == Screen.Home) shell.hero else null)
                }
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
                        Screen.Home -> HomeScreen(library, appearance, repo)
                        Screen.Settings -> SettingsScreen(appearance, update, startup, startupStore::update, pcs, profileStore)
                        Screen.SettingsSearch -> SettingsScreen(appearance, update, startup, startupStore::update, pcs, profileStore, search = true)
                        Screen.Profile -> ProfileScreen(profiles, profileStore)
                        Screen.Devices -> DevicesScreen(pcs, pc?.uuid)
                        Screen.Start -> StartScreen(profiles, profileStore, pcs, startup)
                        is Screen.Boot -> BootScreen(
                            onBegin = { shell.navigate(Screen.CreateProfile(editing = false, back = screen)) },
                            // PCs paired in the classic UI already: a default profile, then straight into home
                            onSkip = pcs.filter { it.paired }.let { paired ->
                                if (paired.isEmpty()) null else {
                                    {
                                        profileStore.create("Player", AVATARS.indexOfFirst { it.name == "Player" }.coerceAtLeast(0))
                                        devices.connect(paired.firstOrNull { it.online } ?: paired.first())
                                    }
                                }
                            },
                        )
                        is Screen.CreateProfile -> CreateProfileScreen(screen.editing, profile.takeIf { screen.editing }) { name, avatar ->
                            if (screen.editing) {
                                profile?.let { p -> profileStore.update(p.id) { it.copy(name = name, avatar = avatar) } }
                                shell.showToast("Profile updated", Symbols.CHECK_CIRCLE)
                                shell.navigate(screen.back ?: Screen.Profile)
                            } else {
                                profileStore.create(name, avatar)
                                shell.showToast("Welcome, $name", Symbols.WAVING_HAND)
                                // The first launch goes on to pairing, unless PCs are paired already
                                shell.navigate(
                                    when {
                                        screen.back !is Screen.Boot -> screen.back ?: Screen.Start
                                        pcs.none { it.paired } -> Screen.PairPc(back = Screen.Start)
                                        else -> Screen.Start
                                    }
                                )
                            }
                        }
                        is Screen.PairPc -> PairPcScreen(screen, pcs)
                        is Screen.Artwork -> ArtworkScreen(screen)
                        is Screen.Pin -> PinScreen(
                            screen, repo, setup,
                            onFailed = { message ->
                                feedback.play(Cue.ERROR)
                                shell.showToast(message, Symbols.ERROR)
                                shell.navigate(screen.back)
                            },
                            onCancel = { shell.navigate(screen.back) },
                        )
                    }
                }
                ZoomedBar(uiScale) {
                    StatusBar(
                        nav.takeIf { shell.screen.main }, pcName = pc?.name.takeIf { shell.screen.main }, pcOnline = pc?.online == true,
                        wifi = wifi, battery = battery, clock = clock, brand = shell.screen != Screen.Start,
                    )
                }
            }

            // Overlays sit on their own glass: controls in them must not blur the background through it
            CompositionLocalProvider(LocalBackdropHaze provides null) {
                Scrim(shell.layer != null, haze) {
                    feedback.play(Cue.BACK)
                    shell.closeLayer()
                }
                val context = shell.layer as? Layer.Context
                ContextMenuLayer(context?.menu, context?.anchor, context?.below == true, haze) { shell.closeLayer() }
                SideMenuLayer(
                    shell.layer == Layer.Side,
                    haze,
                    header = {
                        SideMenuHeader(
                            avatar = { profile?.let { AvatarBadge(it.look, 72f, 40f) } },
                            name = profile?.name ?: "Delos",
                            status = when {
                                pc == null -> "Not connected"
                                pc.online -> "Connected to ${pc.name}"
                                else -> "${pc.name} is offline"
                            },
                        )
                    },
                ) { first ->
                    SideMenuRows(appearance, update, Modifier.focusRequester(first)) { shell.navigate(Screen.Settings) }
                    val hiddenCount = (library as? Library.Ready)?.hiddenCount ?: 0
                    if (hiddenCount > 0 || showHidden) {
                        SettingRow(
                            Symbols.VISIBILITY, "Show hidden games", RowValue.Toggle(showHidden) { repo.setShowHidden(it) },
                            description = "$hiddenCount hidden on ${pc?.name ?: "this PC"}", compact = true,
                        )
                    }
                }

                DialogLayer((shell.layer as? Layer.Dialog)?.dialog, haze) {
                    feedback.play(Cue.BACK)
                    shell.closeLayer()
                }

                PortalOverlay(shell.portal, shell.portalBurst, haze, appearance.reducedMotion)

                ButtonCaptureLayer(shell.buttonCapture != null, keys) { buttons ->
                    val save = shell.buttonCapture
                    shell.buttonCapture = null
                    if (buttons != null) save?.invoke(buttons)
                }
            }

            ArtworkProgressBar(artwork.progress.value)
            val note = (library as? Library.Ready)?.takeIf { shell.screen == Screen.Home && shell.layer == null }?.let { "${it.apps.size} apps on ${it.pc.name}" }
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
    shell.portal?.let { return if (it.cancellable) listOf(Hint(Glyph.B, "Cancel")) else emptyList() }
    return when (shell.layer) {
        is Layer.Context -> listOf(Hint(Glyph.A, "Select"), Hint(Glyph.B, "Close"))
        Layer.Side -> adjust + listOf(Hint(Glyph.A, "Change"), Hint(Glyph.B, "Close"))
        is Layer.Dialog -> listOf(Hint(Glyph.A, "Select"), Hint(Glyph.B, "Cancel"))
        null -> when (val screen = shell.screen) {
            Screen.Home -> listOf(Hint(Glyph.A, "Start"), Hint(Glyph.MENU_LEFT, "Menu"), Hint(Glyph.MENU_RIGHT, "Options"))
            Screen.Settings -> adjust + listOf(Hint(Glyph.L1, "Previous tab"), Hint(Glyph.R1, "Next tab"), Hint(Glyph.Y, "Search"), Hint(Glyph.A, "Change"), Hint(Glyph.B, "Back"))
            Screen.SettingsSearch -> listOf(Hint(Glyph.A, "Select"), Hint(Glyph.B, "Back"))
            Screen.Start -> listOf(Hint(Glyph.A, "Connect"), Hint(Glyph.MENU_LEFT, "Menu"), Hint(Glyph.MENU_RIGHT, "Options"))
            is Screen.Boot -> listOfNotNull(Hint(Glyph.A, "Select"), Hint(Glyph.B, "Back").takeIf { screen.back != null })
            is Screen.CreateProfile -> listOf(Hint(Glyph.A, "Select"), Hint(Glyph.B, "Back"))
            is Screen.PairPc -> listOf(Hint(Glyph.A, "Select"), Hint(Glyph.B, "Back"), Hint(Glyph.MENU_RIGHT, "Options"))
            is Screen.Pin -> listOf(Hint(Glyph.B, "Cancel"))
            is Screen.Artwork -> listOf(Hint(Glyph.L1, "Previous tab"), Hint(Glyph.R1, "Next tab"), Hint(Glyph.A, "Use"), Hint(Glyph.B, "Back"))
            Screen.Profile, Screen.Devices -> listOf(Hint(Glyph.A, "Select"), Hint(Glyph.B, "Back"), Hint(Glyph.MENU_LEFT, "Menu"))
        }
    }
}
