package com.limelight.shell

import androidx.compose.animation.core.snap
import android.app.ActivityOptions
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.limelight.R
import com.limelight.display.DualScreen
import com.limelight.shell.ui.textResources
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import com.limelight.shell.settings.BackgroundKind
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import com.limelight.shell.data.LocalGameMetadata
import com.limelight.shell.data.MetadataFilters
import com.limelight.shell.screens.HomeScreen
import com.limelight.shell.screens.openMetadataMenu
import com.limelight.shell.screens.BootScreen
import com.limelight.shell.screens.CreateProfileScreen
import com.limelight.shell.screens.DevicesScreen
import com.limelight.shell.screens.PairPcScreen
import com.limelight.shell.screens.PinScreen
import com.limelight.shell.screens.ProfileScreen
import com.limelight.shell.screens.StartScreen
import com.limelight.shell.screens.SettingsScreen
import com.limelight.shell.screens.GameSettingsScreen
import com.limelight.shell.screens.appArt
import com.limelight.shell.screens.step
import com.limelight.shell.settings.Appearance
import com.limelight.shell.settings.StartupStore
import com.limelight.shell.system.rememberBatteryStatus
import com.limelight.shell.system.rememberClock
import com.limelight.shell.system.rememberWifiStatus
import com.limelight.shell.ui.Backdrop
import com.limelight.shell.ui.DelosColors
import com.limelight.shell.ui.ContextMenuLayer
import com.limelight.shell.ui.Cue
import com.limelight.shell.ui.EaseOut
import com.limelight.shell.ui.Feedback
import com.limelight.shell.ui.Glyph
import com.limelight.shell.ui.Hint
import com.limelight.shell.ui.HintBar
import com.limelight.shell.ui.HeroBitmaps
import com.limelight.shell.ui.HeroMotion
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
import com.limelight.shell.ui.screenTransition
import com.limelight.shell.ui.SettingRow
import com.limelight.shell.ui.FocusedListNavigation
import com.limelight.shell.ui.focusedListRow
import com.limelight.shell.ui.rememberFocusedListNavigation
import com.limelight.shell.ui.SideMenuHeader
import com.limelight.shell.ui.SideMenuLayer
import com.limelight.shell.ui.SideMenuScroll
import com.limelight.shell.ui.px
import com.limelight.shell.ui.StatusBar
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.ToastHost
import com.limelight.utils.ToastKind
import com.limelight.shell.ui.ZoomedBar
import com.limelight.shell.ui.AvatarBadge
import com.limelight.shell.ui.ButtonCaptureLayer
import com.limelight.shell.ui.DialogLayer
import com.limelight.shell.ui.PortalOverlay
import com.limelight.shell.ui.StreamResultLayer
import com.limelight.shell.ui.ProblemOfferCard
import com.limelight.diag.ProblemMonitor
import com.limelight.diag.ProblemReporter
import com.limelight.shell.ui.LocalImeAvoidance
import com.limelight.shell.ui.rememberImeAvoidance
import com.limelight.shell.data.LocalArtwork
import com.limelight.shell.data.downloadAll
import com.limelight.shell.data.BundledArtwork
import com.limelight.shell.data.ArtKind
import com.limelight.shell.data.rememberArtwork
import com.limelight.shell.ui.ArtworkProgressBar
import com.limelight.shell.screens.ArtworkScreen
import com.limelight.shell.screens.DebugInfoScreen
import com.limelight.shell.screens.OscPreviewScreen
import com.limelight.shell.screens.FilterModalLayer
import com.limelight.shell.screens.filterHintLabel
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
    results: MutableStateFlow<StreamResult?>,
    shell: ShellState,
    /** Debug screen previews only: the profiles the screens show instead of the store's (the store is never given any, see `ScreenPreviews`). */
    previewProfiles: com.limelight.shell.data.Profiles? = null,
) {
    val res = textResources()
    val colors = LocalDelosColors.current
    val feedback = LocalFeedback.current
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
    // The connection line the launch screen tells under the title (null: the greeting)
    val launchLine = remember { mutableStateOf<String?>(null) }
    // Set when the launch overlay was cleared as the stream window took over; the next resume does the return work
    val returningFromStream = remember { mutableStateOf(false) }
    // Stays set until the next launch: the overlay it cleared leaves without a fade (a fade would run after the return)
    val launchExitInstant = remember { mutableStateOf(false) }
    val activity = LocalActivity.current
    // What the system shows while this window comes back to the front is NOT drawn by us: it is a snapshot starting window. Recorded on
    // the Odin (the SurfaceFlinger layer list showed "SnapshotStartingWindow" for this task during the flash): it held the last frame
    // from before the stream, the launch tunnel. The window no longer lets the system snapshot it (ShellActivity), and the system then
    // shows a plain starting window in the task's background color: the palette's own, so it blends into the exit scene.
    val taskBackground = colors.bg.toArgb()
    LaunchedEffect(taskBackground) {
        // Android 13+ (the same versions that can switch the snapshot off, see ShellActivity)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            activity?.setTaskDescription(android.app.ActivityManager.TaskDescription.Builder().setBackgroundColor(taskBackground).build())
        }
    }
    val actions = remember(launcher, res) { ShellGameActions(res, shell, launcher, feedback, scope) }
    val reducedMotion by rememberUpdatedState(appearance.reducedMotion)
    val activityContext = androidx.compose.ui.platform.LocalContext.current
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val linkTests = remember { com.limelight.shell.link.LinkTestStore(appContext) }
    val linkTester = remember { com.limelight.shell.link.LinkTester(appContext, repo) }
    val devices = remember { ShellDeviceActions(activityContext, shell, tracker, repo, setup, startupStore, feedback, scope, linkTester, linkTests) { reducedMotion } }
    val storedProfiles by profileStore.profiles.collectAsStateWithLifecycle()
    val profiles = previewProfiles ?: storedProfiles
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
                        DialogSpec(res.getString(R.string.shell_link_pair_title, who), res.getString(R.string.shell_link_pair_message), emptyList(), res.getString(R.string.shell_link_pair_action)) {
                            devices.addAndPair(l.address, l.otp, Screen.Start)
                            true
                        }
                    ),
                    tracker,
                )
            }
            is ShellLink.Launch -> {
                feedback.play(Cue.OPEN)
                val game = l.appName?.takeIf { it.isNotEmpty() }
                val host = l.hostName?.takeIf { it.isNotEmpty() }
                val title = if (game != null) res.getString(R.string.shell_link_start_title, game) else res.getString(R.string.shell_link_start_title_unnamed)
                val message = when {
                    game != null && host != null -> res.getString(R.string.shell_link_start_message, game, host)
                    game != null -> res.getString(R.string.shell_link_start_message_game, game)
                    host != null -> res.getString(R.string.shell_link_start_message_host, host)
                    else -> res.getString(R.string.shell_link_start_message_unnamed)
                }
                shell.openLayer(
                    Layer.Dialog(
                        DialogSpec(title, message, emptyList(), res.getString(R.string.shell_link_start_action)) {
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

    // A stream that ended on its own (it never started, the connection broke, the PC closed it): Home of
    // that PC first, then a toast or the result overlay. Shown once; the activity only held it until here.
    val streamResult by results.collectAsStateWithLifecycle()
    LaunchedEffect(streamResult) {
        val r = streamResult ?: return@LaunchedEffect
        // Cleared only once it has been taken over below (the change restarts this effect)
        if (r.pcUuid.isNotEmpty()) repo.selectPc(r.pcUuid)
        if (profiles.all.isNotEmpty() && shell.screen !is Screen.Boot && shell.screen !is Screen.CreateProfile) shell.navigate(Screen.Home)
        if (r.kind == StreamResultKind.HOST_CLOSED) {
            results.value = null
            shell.showToast(r.title, Symbols.INFO)
            return@LaunchedEffect
        }
        // The launch scene that was waiting for the stream goes away when the shell resumes; the overlay follows it
        withTimeoutOrNull(3_000) { snapshotFlow { shell.launch == null }.first { it } }
        feedback.play(Cue.OPEN)
        results.value = null
        shell.result = r
        // A failed stream is also a problem the user may report: the card follows once the result has been answered
        ProblemMonitor.streamFailed(appContext, r.title, r.details)?.let { if (shell.problem == null) shell.problem = it }
    }

    // A crash, ANR or unclean exit of the last run: one card offers the report, once per problem (docs/DIAGNOSTICS.md)
    LaunchedEffect(Unit) {
        val found = withContext(Dispatchers.IO) { ProblemMonitor.detectAtStart(appContext) }
        if (found != null && shell.problem == null) shell.problem = found
    }
    fun dismissProblem() {
        val p = shell.problem ?: return
        shell.problem = null
        ProblemMonitor.resolve(p)
        scope.launch {
            delay(400)
            feedback.silenceNextFocus()
            runCatching { shell.restoreScreenFocus?.invoke() }
        }
    }
    // Report: save the cleaned log to Downloads, then open the prefilled GitHub issue in the browser; the user attaches the file
    fun reportProblem() {
        val p = shell.problem ?: return
        shell.problem = null
        scope.launch {
            val prepared = ProblemReporter.prepare(appContext, p)
            val saved = ProblemReporter.saveToDownloads(appContext, prepared.report, prepared.fileName)
            ProblemMonitor.resolve(p)
            if (saved != null) shell.showToast(res.getString(R.string.shell_report_saved, prepared.fileName), Symbols.CHECK_CIRCLE, durationMs = 6_500)
            else shell.showToast(res.getString(R.string.shell_report_save_failed), Symbols.WARNING, durationMs = 6_500)
            delay(900)
            if (!ProblemReporter.openIssue(appContext, prepared.url)) {
                feedback.play(Cue.ERROR)
                shell.showToast(res.getString(R.string.shell_report_no_browser), Symbols.ERROR)
            }
        }
    }
    fun dismissResult() {
        shell.result = null
        scope.launch {
            delay(400)
            feedback.silenceNextFocus()
            runCatching { shell.restoreScreenFocus?.invoke() }
        }
    }
    // Try again: the same game on the same PC, started the way Home starts it (the per-game settings are read again)
    fun retryStream() {
        val r = shell.result ?: return
        shell.result = null
        scope.launch {
            val target = if (r.pcUuid.isEmpty()) null else repo.awaitPc(r.pcUuid, 4_000) { it.paired && it.online }
            if (target == null) {
                feedback.play(Cue.ERROR)
                shell.showToast(res.getString(R.string.shell_retry_unreachable, r.pcName), Symbols.WIFI_OFF)
                return@launch
            }
            repo.selectPc(target.uuid)
            val ready = withTimeoutOrNull(5_000) { repo.library.first { it is Library.Ready && it.pc.uuid == target.uuid } } as? Library.Ready
            val app = ready?.apps?.let { apps -> apps.firstOrNull { it.id == r.appId } ?: apps.firstOrNull { it.name == r.appName } }
            if (app == null) {
                feedback.play(Cue.ERROR)
                shell.showToast(
                    if (r.appName.isEmpty()) res.getString(R.string.shell_retry_game_missing_unnamed, target.name) else res.getString(R.string.shell_retry_game_missing, r.appName, target.name),
                    Symbols.ERROR,
                )
                return@launch
            }
            val cover = activity?.let { withContext(Dispatchers.IO) { StreamLoader.loadCover(it, target.uuid, app.id) } }
            actions.start(target, app, cover)
        }
    }

    // The stream window inherits the last drawn tunnel progress, scene origin and game identity.
    LaunchedEffect(shell.launch?.id) {
        val launch = shell.launch ?: return@LaunchedEffect
        launchExitInstant.value = false
        LaunchHandoff.background = launch.background
        LaunchHandoff.heroTurn = if (appearance.reducedMotion || activity?.let(HeroMotion::animatorsOff) == true) Float.NaN else HeroMotion.turnAt(System.nanoTime())
        LaunchHandoff.progress = 0f
        LaunchHandoff.travel = 0f
        LaunchHandoff.speedProgress = 0f
        // The first witty connection line is told here already; the stream window goes on with the next ones
        StreamLoader.resetLines()
        launchLine.value = null
        delay(if (appearance.reducedMotion) 150 else 350)
        launchLine.value = StreamLoader.firstLine(res, launch.pc.name)
        delay(if (appearance.reducedMotion) 150 else 350)
        shell.diving = true
        // Only a game that really opens counts as last played (not one cancelled in the loader)
        repo.setLastPlayed(launch.pc.uuid, launch.app.id)
        // The stream (DelosStreamActivity, launchMode singleTask) opens in its own task, where the system ignores custom
        // activity animations and slides the new window in. A scale-up is honoured for new tasks; from the
        // whole screen it only fades the stream window in over this one (a clip reveal also shifts it up a
        // little), and that starts with the same scene.
        val options = activity?.window?.decorView?.let { root ->
            DualScreen.placeOnMain(root.context, ActivityOptions.makeScaleUpAnimation(root, 0, 0, root.width, root.height)).toBundle()
        }
        activity?.startActivity(launch.intent, options)
    }
    // Back from the stream: the overlay goes away, the focus stays on the game, the PC's state is refreshed.
    // Left through the stream menu, the portal's burst that began over the stream ends here.
    // The stream window fully covers this one: the launch overlay (and its tunnel) goes away now, not at the next resume,
    // where it would flash for a few frames when the stream is left. The resume work is remembered for that moment.
    // The system keeps a snapshot of this window from the moment it stops and shows it while the window comes back to the
    // front (recorded on the Odin: the tunnel flashed for 0.3 s on leaving a stream, and the log proved the overlay itself was
    // already gone: it was the snapshot). The snapshot is taken at stop, about 0.45 s after the pause, so the overlay is swapped
    // for the opaque background of the exit scene early enough for a frame of it to be drawn before that.
    fun handOverToStream() {
        if (shell.launch != null && shell.diving) {
            shell.exitCover = true
            shell.exitLights = false
            shell.launch = null
            shell.diving = false
            returningFromStream.value = true
            launchExitInstant.value = true
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        if (shell.launch != null && shell.diving) scope.launch {
            delay(HAND_OVER_DELAY_MS)
            handOverToStream()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { handOverToStream() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val arrival = StreamExit.take()
        // Not coming back through the exit portal (a cancelled or failed launch): the cover of the hand-over goes away
        if (arrival == null && shell.exitCover) {
            shell.exitCover = false
            shell.exitLights = false
        }
        arrival?.let { exit ->
            scope.launch {
                // Quit game: the portal waits, with its text and pulses, until the PC has really quit the game (at most 25 s),
                // and only then bursts into the dashboard. Disconnect bursts at once.
                val waitForQuit = StreamExit.quitting
                // The chosen animated background fades in over the dashboard first (the dashboard's own background still
                // settles after the return), and only then does the portal burst into it
                shell.exitCover = true
                // The flat background first (what the system window and the end of the stream window's scene showed), then the lights
                shell.exitLights = true
                shell.portalBurst = false
                shell.portal = Portal(-1, if (waitForQuit) Symbols.POWER_SETTINGS_NEW else Symbols.HOME, exit.label, exit.sub, cancellable = false, arriving = true)
                delay(if (waitForQuit) 400 else 600)
                if (waitForQuit) {
                    var waited = 0
                    while (StreamExit.quitting && waited < 25_000) {
                        delay(100)
                        waited += 100
                    }
                }
                // The loader just ends: the background and the portal fade away together into the dashboard, no second scene
                feedback.play(Cue.SELECT)
                shell.exitCover = false
                shell.exitLights = false
                shell.portal = null
            }
        }
        if (returningFromStream.value || (shell.launch != null && shell.diving)) {
            returningFromStream.value = false
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
    val metadata = LocalGameMetadata.current
    val openMetadata: (Pc, App) -> Unit = remember(scope, shell, tracker, metadata) {
        { selectedPc, app -> openMetadataMenu(res, scope, shell, tracker, metadata, selectedPc, app) }
    }
    LaunchedEffect(metadata, profiles.current?.id, (library as? Library.Ready)?.let { it.pc.uuid to it.apps.map { app -> app.id to app.name } }) {
        val ready = library as? Library.Ready ?: return@LaunchedEffect
        metadata.ensureLibrary(ready.pc, ready.apps)
    }
    // SteamGridDB: games never looked up get their best-rated artwork in the background
    val artwork = LocalArtwork.current
    LaunchedEffect((library as? Library.Ready)?.let { it.pc.uuid to it.apps.map { a -> a.id } }, artwork.enabled, artwork.automatic) {
        val ready = library as? Library.Ready ?: return@LaunchedEffect
        if (!artwork.enabled || !artwork.automatic) return@LaunchedEffect
        artwork.downloadAll(ready.pc, ready.apps, refresh = false) { shell.showToast(it.text(res), Symbols.ERROR) }
    }
    val showHidden by repo.showHidden.collectAsStateWithLifecycle()
    val uiScale = appearance.uiScale / 100f
    val pc = when (val l = library) {
        is Library.Ready -> l.pc
        is Library.Offline -> l.pc
        else -> null
    }
    // The companion screen of a dual-screen device shows the same PC as the status bar
    val companionPc = pc?.name.takeIf { shell.screen.main }
    LaunchedEffect(companionPc) { shell.pcName = companionPc }

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

    fun toggleFilters() {
        when (shell.layer) {
            Layer.Filters -> {
                feedback.play(Cue.BACK)
                shell.closeLayer()
            }
            null -> {
                feedback.play(Cue.OPEN)
                shell.openLayer(Layer.Filters, tracker)
            }
            else -> Unit
        }
    }

    fun toggleContext() {
        when (shell.layer) {
            is Layer.Context -> {
                feedback.play(Cue.BACK)
                shell.closeLayer()
            }
            null -> {
                val menu = tracker.contextMenu ?: return
                feedback.play(Cue.OPEN)
                shell.openLayer(Layer.Context(menu, tracker.bounds()), tracker)
            }
            else -> Unit
        }
    }

    DisposableEffect(keys) {
        keys.canUndo = { shell.toast?.action != null && shell.portal == null && shell.launch == null && shell.result == null }
        keys.onUndo = { shell.runToastAction() }
        // Y: settings search, or the artwork page's filter sheet
        keys.canSearch = {
            shell.layer == null && shell.portal == null && shell.launch == null &&
                (shell.screen == Screen.Settings || (shell.screen is Screen.Artwork && shell.onArtworkFilter != null))
        }
        keys.onSearch = { if (shell.screen is Screen.Artwork) shell.onArtworkFilter?.invoke() else shell.onSettingsSearch?.invoke() }
        // Y opens the filters over a library with games (and closes them again); nothing else may react to it
        keys.canFilter = {
            shell.screen == Screen.Home && shell.portal == null && shell.launch == null && shell.result == null && (
                shell.layer == Layer.Filters || (shell.layer == null && (library as? Library.Ready)?.apps?.isNotEmpty() == true)
            )
        }
        keys.onFilter = { toggleFilters() }
        keys.listener = listener@{ code ->
            if (shell.launch != null || shell.portal != null || shell.result != null) return@listener
            when (code) {
                KeyEvent.KEYCODE_BUTTON_SELECT -> if (sideMenu) toggleSide()
                KeyEvent.KEYCODE_BUTTON_START -> toggleContext()
                KeyEvent.KEYCODE_BUTTON_X -> if (shell.layer == Layer.Filters) {
                    if (metadata.filters.active) {
                        feedback.play(Cue.SELECT)
                        metadata.updateFilters(MetadataFilters())
                    }
                } else if (shell.screen is Screen.Artwork && shell.layer == null && shell.onArtworkSearch != null) shell.onArtworkSearch?.invoke()
                else if (shell.screen is Screen.GameSettings && shell.layer == null && shell.onGameReset != null) shell.onGameReset?.invoke()
                else toggleContext()
                KeyEvent.KEYCODE_BUTTON_L1 -> if (shell.layer == null) shell.onShoulder?.invoke(-1)
                KeyEvent.KEYCODE_BUTTON_R1 -> if (shell.layer == null) shell.onShoulder?.invoke(1)
            }
        }
        onDispose {
            keys.listener = null
            keys.onUndo = null
            keys.canUndo = { false }
            keys.onSearch = null
            keys.canSearch = { false }
            keys.onFilter = null
            keys.canFilter = { false }
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
    // B on the result overlay is Back to library (registered last, so it wins while the overlay is shown)
    BackHandler(enabled = shell.result != null) {
        feedback.play(Cue.BACK)
        dismissResult()
    }
    // B on the report card is Dismiss, wherever the focus is while the card shows
    BackHandler(enabled = shell.offerShown) {
        feedback.play(Cue.BACK)
        dismissProblem()
    }

    // The filter modal belongs to a ready library: a PC that goes offline takes it down with it
    LaunchedEffect(shell.layer, library is Library.Ready) {
        if (shell.layer == Layer.Filters && library !is Library.Ready) shell.closeLayer()
    }

    // When a layer has closed, the focus returns to where it was (silently, like the prototype)
    LaunchedEffect(shell.layer) {
        if (shell.layer != null) return@LaunchedEffect
        val previous = shell.takeReturnFocus() ?: return@LaunchedEffect
        feedback.silenceNextFocus()
        val restored = runCatching { previous.requestFocus() }.getOrDefault(false)
        if (!restored) runCatching { shell.restoreScreenFocus?.invoke() }
        // The held element has the focus again by now (it let go of its hold then); otherwise the hold must not outlive the menu
        delay(150)
        tracker.release()
    }

    val profile = profiles.current
    val configuration = LocalConfiguration.current
    val nav = remember(shell, shell.screen, configuration) {
        listOf(
            NavButton(Symbols.SETTINGS, res.getString(R.string.shell_nav_settings), shell.screen == Screen.Settings) { shell.navigate(Screen.Settings) },
            NavButton(Symbols.ACCOUNT_CIRCLE, res.getString(R.string.shell_nav_profile), shell.screen == Screen.Profile) { shell.navigate(Screen.Profile) },
            NavButton(Symbols.DEVICES, res.getString(R.string.shell_nav_devices), shell.screen == Screen.Devices) { shell.navigate(Screen.Devices) },
        )
    }

    // The keyboard never covers the text field being edited: the dialog or the page content that holds it moves up (docs/GUI.md, "Text input")
    val imeAvoidance = rememberImeAvoidance()
    // Where the status bar's Wi-Fi, battery and clock start: the start screen's profile chip keeps clear of them on a compact stage
    val statusEdges = remember { com.limelight.shell.ui.StatusBarEdges() }
    CompositionLocalProvider(
        LocalShell provides shell, LocalFocusTracker provides tracker, LocalGameActions provides actions, LocalDeviceActions provides devices,
        LocalBackdropHaze provides backdropHaze, LocalLibrary provides library, LocalImeAvoidance provides imeAvoidance,
        com.limelight.shell.screens.LocalLinkTests provides linkTests, com.limelight.shell.ui.LocalStatusBarEdges provides statusEdges,
    ) {
        Box(Modifier.fillMaxSize()) {
            // The blur source re-records the whole screen every frame, so it is only active while an overlay
            // is shown (and while it fades out)
            // On in the very frame an overlay appears (its blur would otherwise start empty and clipped)
            Box(Modifier.fillMaxSize().then(if (blurSource || shell.layer != null || shell.portal != null || shell.result != null) Modifier.hazeSource(haze) else Modifier)) {
                Box(Modifier.fillMaxSize().hazeSource(backdropHaze)) {
                    ShellBackdrop(shell, appearance, colors, heroShown = shell.screen == Screen.Home, animate = shell.launch == null && shell.portal == null)
                }
                AnimatedContent(
                    shell.screen,
                    // `.screen` (docs/GUI.md, "Motion"): the shared transition, only fades with reduced motion
                    transitionSpec = { screenTransition(appearance.reducedMotion) },
                    label = "screen",
                ) { screen ->
                    when (screen) {
                        Screen.Home -> HomeScreen(library, appearance, repo, profileStore, openMetadata)
                        Screen.Settings -> SettingsScreen(appearance, update, startup, startupStore::update, pcs, profileStore)
                        Screen.SettingsSearch -> SettingsScreen(appearance, update, startup, startupStore::update, pcs, profileStore, search = true)
                        Screen.Profile -> ProfileScreen(profiles, profileStore, pcs.firstOrNull { it.uuid == startup.defaultPc }?.name)
                        Screen.Devices -> DevicesScreen(pcs, pc?.uuid, startup.defaultPc)
                        Screen.Start -> StartScreen(profiles, profileStore, pcs, startup)
                        is Screen.Boot -> BootScreen(
                            onBegin = { shell.navigate(Screen.CreateProfile(editing = false, back = screen)) },
                            // PCs paired in the classic UI already: a default profile, then straight into home
                            onSkip = pcs.filter { it.paired }.let { paired ->
                                if (paired.isEmpty()) null else {
                                    {
                                        profileStore.create(res.getString(R.string.shell_default_profile_name), AVATARS.indexOfFirst { it.name == "Player" }.coerceAtLeast(0)) // l10n-ignore
                                        devices.connect(paired.firstOrNull { it.online } ?: paired.first())
                                    }
                                }
                            },
                        )
                        is Screen.CreateProfile -> CreateProfileScreen(screen.editing, profile.takeIf { screen.editing }) { name, avatar ->
                            if (screen.editing) {
                                profile?.let { p -> profileStore.update(p.id) { it.copy(name = name, avatar = avatar) } }
                                shell.showToast(res.getString(R.string.shell_profile_updated), Symbols.CHECK_CIRCLE)
                                shell.navigate(screen.back ?: Screen.Profile)
                            } else {
                                profileStore.create(name, avatar)
                                shell.showToast(res.getString(R.string.shell_welcome, name), Symbols.WAVING_HAND, kind = ToastKind.SUCCESS)
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
                        is Screen.PairPc -> PairPcScreen(screen, pcs, startup.defaultPc)
                        is Screen.Artwork -> ArtworkScreen(screen)
                        is Screen.GameSettings -> GameSettingsScreen(screen, profileStore, appearance)
                        Screen.DebugInfo -> DebugInfoScreen()
                        Screen.OscPreview -> OscPreviewScreen()
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
                // The on-screen controls preview is full screen like a stream
                if (shell.screen != Screen.OscPreview) ZoomedBar(uiScale) {
                    StatusBar(
                        nav.takeIf { shell.screen.main }, pcName = pc?.name.takeIf { shell.screen.main }, pcOnline = pc?.online == true,
                        wifi = wifi, battery = battery, clock = clock, brand = shell.screen != Screen.Start,
                    )
                }
            }

            // Overlays sit on their own glass: controls in them must not blur the background through it
            CompositionLocalProvider(LocalBackdropHaze provides null) {
                Scrim(shell.layer != null, light = shell.layer is Layer.Context, haze) {
                    feedback.play(Cue.BACK)
                    shell.closeLayer()
                }
                val context = shell.layer as? Layer.Context
                ContextMenuLayer(context?.menu, context?.anchor, shell.menuPath, { shell.menuPath = it }, haze) { shell.closeLayer() }
                SideMenuLayer(
                    shell.layer == Layer.Side,
                    haze,
                    header = {
                        SideMenuHeader(
                            avatar = { profile?.let { AvatarBadge(it.look, 72f, 40f) } },
                            name = profile?.name ?: "Delos", // l10n-ignore
                            status = when {
                                pc == null -> res.getString(R.string.shell_side_not_connected)
                                pc.online -> res.getString(R.string.shell_side_connected, pc.name)
                                else -> res.getString(R.string.shell_side_offline, pc.name)
                            },
                        )
                    },
                ) { first ->
                    val hiddenCount = (library as? Library.Ready)?.hiddenCount ?: 0
                    val hiddenRow = hiddenCount > 0 || showHidden
                    val rowKeys = remember(hiddenRow) {
                        listOf("theme", "material-you", "background", "sounds", "haptics", "settings") +
                            if (hiddenRow) listOf("show-hidden") else emptyList()
                    }
                    val navigation = rememberFocusedListNavigation(rowKeys, allowUpExit = false)
                    SideMenuScroll(navigation) {
                        SideMenuRows(appearance, update, navigation, Modifier.focusRequester(first)) { shell.navigate(Screen.Settings) }
                        if (hiddenRow) {
                            SettingRow(
                                Symbols.VISIBILITY, res.getString(R.string.shell_side_show_hidden), RowValue.Toggle(showHidden) { repo.setShowHidden(it) },
                                Modifier.focusedListRow(navigation, "show-hidden"),
                                description = pc?.let { res.getString(R.string.shell_side_hidden_on, hiddenCount, it.name) } ?: res.getString(R.string.shell_side_hidden_on_this_pc, hiddenCount), compact = true,
                            )
                        }
                    }
                }

                DialogLayer((shell.layer as? Layer.Dialog)?.dialog, haze) {
                    feedback.play(Cue.BACK)
                    shell.closeLayer()
                }

                FilterModalLayer(shell.layer == Layer.Filters, library as? Library.Ready, haze, appearance.reducedMotion) {
                    feedback.play(Cue.BACK)
                    shell.closeLayer()
                }

                // Fully there from the first frame: the stream window's portal already ended on this very background
                val cover by animateFloatAsState(if (shell.exitCover) 1f else 0f, if (shell.exitCover) snap() else tween(350, easing = EaseOut), label = "exitCover")
                val lights by animateFloatAsState(if (shell.exitLights) 1f else 0f, tween(if (shell.exitLights) 400 else 350, easing = EaseOut), label = "exitLights")
                if (cover > 0f) {
                    // Opaque flat background at once (it hides the dashboard and is the color the system window showed), the lights fade in over it
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = cover }.background(colors.bg)) {
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = lights }) {
                            ShellBackdrop(shell, appearance, colors, heroShown = appearance.background == BackgroundKind.STILL, animate = false)
                        }
                    }
                }
                // The portal of a stream exit sits on the opaque cover: it must not blur the dashboard behind (that was the third scene)
                PortalOverlay(shell.portal, shell.portalBurst, if (shell.portal?.arriving == true) null else haze, appearance.reducedMotion)

                StreamResultLayer(shell.result, haze, appearance.reducedMotion, onRetry = ::retryStream, onBack = ::dismissResult)

                ButtonCaptureLayer(shell.buttonCapture != null, keys) { buttons ->
                    val save = shell.buttonCapture
                    shell.buttonCapture = null
                    if (buttons != null) save?.invoke(buttons)
                }
            }

            val artworkProgress = artwork.progress.value
            ArtworkProgressBar(artworkProgress ?: metadata.progress.value,
                label = res.getString(if (artworkProgress != null) R.string.shell_progress_artwork else R.string.shell_progress_metadata))
            val note = (library as? Library.Ready)?.takeIf { shell.screen == Screen.Home && shell.layer == null }?.let { ready ->
                val shown = if (metadata.filters.active) ready.apps.count { metadata.filters.matches(it, metadata.entry(ready.pc, it).data) } else ready.apps.size
                if (shown == ready.apps.size) res.getQuantityString(R.plurals.shell_home_apps_on_pc, shown, shown, ready.pc.name)
                else res.getQuantityString(R.plurals.shell_home_apps_filtered, ready.apps.size, shown, ready.apps.size, ready.pc.name)
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                val filterHint = (library as? Library.Ready)?.takeIf { it.apps.isNotEmpty() }?.let { filterHintLabel(res, metadata.filters) }
                val hintEntries = hints(res, shell, tracker.adjustable, tracker.hasContextMenu, filterHint)
                com.limelight.shell.companion.PublishCompanionContext(hintEntries, library, pcs)
                ZoomedBar(uiScale) { HintBar(hintEntries, note = note, noteLabel = if (note != null) res.getString(R.string.shell_hint_library_label) else null) }
            }
            val launch = shell.launch
            val launchLogo = launch?.let { rememberArtwork(artwork, it.pc, it.app, ArtKind.LOGO).value }
            // Read off the main thread: profile and per-game settings; the card gets them when they are ready
            val launchParameters by produceState("", launch?.id) {
                value = launch?.let { item -> activity?.let { withContext(Dispatchers.IO) { StreamLoader.parametersFor(it, item.pc.uuid, item.app.id) } } }.orEmpty()
            }
            LaunchOverlay(
                launch?.let { LaunchCard(it.id, it.app.name, launchLine.value ?: StreamLoader.greeting(res, it.pc.name),
                    appArt(it.pc, it.app, it.image), it.background, launchLogo, launchParameters) },
                appearance.reducedMotion,
                instantExit = launchExitInstant.value,
            )
            ProblemOfferCard(if (shell.offerShown) shell.problem else null, appearance.reducedMotion, onReport = ::reportProblem, onDismiss = ::dismissProblem)
            ToastHost(shell.toast, onHidden = { shell.hideToast(it) }, onAction = { shell.runToastAction() }, actionPress = shell.toastPress)
        }
    }
}

/** Quick settings of the side menu. */
@Composable
private fun SideMenuRows(
    appearance: Appearance,
    update: ((Appearance) -> Appearance) -> Unit,
    navigation: FocusedListNavigation<String>,
    firstModifier: Modifier,
    openSettings: () -> Unit,
) {
    val res = textResources()
    SettingRow(Symbols.DARK_MODE, stringResource(R.string.shell_side_theme), RowValue.Choice(stringResource(if (appearance.dark) R.string.shell_side_theme_dark else R.string.shell_side_theme_light), { update { it.copy(dark = !it.dark) } }), firstModifier.focusedListRow(navigation, "theme"), compact = true)
    SettingRow(Symbols.SYSTEM_COLORS, stringResource(R.string.shell_side_material_you), RowValue.Toggle(appearance.materialYou) { on -> update { it.copy(materialYou = on) } }, Modifier.focusedListRow(navigation, "material-you"), compact = true)
    SettingRow(Symbols.ANIMATION, stringResource(R.string.shell_side_background), RowValue.Choice(appearance.background.label(res), { dir -> update { it.copy(background = it.background.step(dir)) } }), Modifier.focusedListRow(navigation, "background"), compact = true)
    SettingRow(Symbols.MUSIC_NOTE, stringResource(R.string.shell_side_sounds), RowValue.Toggle(appearance.sounds) { on -> update { it.copy(sounds = on) } }, Modifier.focusedListRow(navigation, "sounds"), compact = true)
    SettingRow(Symbols.HAPTICS, stringResource(R.string.shell_side_haptics), RowValue.Toggle(appearance.haptics) { on -> update { it.copy(haptics = on) } }, Modifier.focusedListRow(navigation, "haptics"), compact = true)
    SettingRow(Symbols.SETTINGS, stringResource(R.string.shell_side_all_settings), RowValue.Action(openSettings), Modifier.focusedListRow(navigation, "settings"), compact = true)
}

/** The game actions of the shell: start with the launch animation, quit with toasts, no dialogs. */
private class ShellGameActions(
    private val res: Resources,
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
            shell.showToast(res.getString(R.string.shell_game_unreachable, pc.name), Symbols.WIFI_OFF)
            return
        }
        if (start.virtualDisplayUnavailable) {
            shell.showToast(res.getString(R.string.shell_game_virtual_display_missing, pc.name), Symbols.MONITOR, kind = ToastKind.WARNING)
        }
        shell.diving = false
        shell.launch = LaunchRequest(++launches, pc, app, start.intent, image, HeroBitmaps.latest("${pc.uuid}/${app.id}"))
    }

    override fun quit(pc: Pc, app: App) {
        scope.launch {
            shell.showToast(res.getString(R.string.shell_game_quitting, app.name), Symbols.POWER_SETTINGS_NEW)
            report(launcher.quit(pc, app), pc, app)
        }
    }

    override fun quitAndStart(pc: Pc, running: App, app: App, image: ImageBitmap?) {
        scope.launch {
            shell.showToast(res.getString(R.string.shell_game_quitting, running.name), Symbols.POWER_SETTINGS_NEW)
            val result = launcher.quit(pc, running)
            if (result.success) start(pc, app, image) else report(result, pc, running)
        }
    }

    private fun report(result: GameLauncher.QuitResult, pc: Pc, app: App) {
        if (!result.success) feedback.play(Cue.ERROR)
        shell.showToast(result.text(res, pc.name, app.name), if (result.success) Symbols.CHECK_CIRCLE else Symbols.ERROR)
    }
}

/**
 * The animated background with the focused game's hero. It reads [ShellState.hero] itself, so a new hero (every focus move on the home
 * screen) recomposes just this, not the whole shell.
 */
@Composable
private fun ShellBackdrop(shell: ShellState, appearance: Appearance, colors: DelosColors, heroShown: Boolean, animate: Boolean) {
    Backdrop(appearance, colors, hero = if (heroShown) shell.hero else null, animate = animate)
}

/** Button hints for the current screen and layer (the prototype's updateHints). */
private fun hints(res: Resources, shell: ShellState, adjustable: Boolean, options: Boolean, filterHint: String?): List<Hint> {
    // [short]: an existing, shorter hint word a cramped (compact) bar tries before it drops the label
    fun hint(glyph: Glyph, @StringRes label: Int, @StringRes short: Int? = null) = Hint(glyph, res.getString(label), short?.let { res.getString(it) })
    val optionsHint = hint(Glyph.MENU_RIGHT, R.string.shell_hint_options).takeIf { options }
    val adjust = if (adjustable) listOf(hint(Glyph.ADJUST, R.string.shell_hint_adjust)) else emptyList()
    shell.portal?.let { return if (it.cancellable) listOf(hint(Glyph.B, R.string.shell_hint_cancel)) else emptyList() }
    if (shell.result != null) return listOf(hint(Glyph.A, R.string.shell_hint_select), hint(Glyph.B, R.string.shell_hint_back_to_library, R.string.shell_hint_back))
    return when (shell.layer) {
        is Layer.Context -> listOf(hint(Glyph.A, R.string.shell_hint_select), hint(Glyph.B, if (shell.menuPath.isEmpty()) R.string.shell_hint_close else R.string.shell_hint_back))
        Layer.Side -> adjust + listOf(hint(Glyph.A, R.string.shell_hint_change), hint(Glyph.B, R.string.shell_hint_close))
        Layer.Filters -> listOf(hint(Glyph.A, R.string.shell_hint_select), hint(Glyph.X, R.string.shell_hint_reset), hint(Glyph.Y, R.string.shell_hint_close), hint(Glyph.B, R.string.shell_hint_close))
        is Layer.Dialog -> listOf(hint(Glyph.A, R.string.shell_hint_select), hint(Glyph.B, R.string.shell_hint_cancel))
        null -> when (val screen = shell.screen) {
            Screen.Home -> listOfNotNull(hint(Glyph.A, R.string.shell_hint_start), filterHint?.let { Hint(Glyph.Y, it) }, hint(Glyph.MENU_LEFT, R.string.shell_hint_menu), optionsHint)
            Screen.Settings -> adjust + listOf(hint(Glyph.L1, R.string.shell_hint_previous_tab), hint(Glyph.R1, R.string.shell_hint_next_tab), hint(Glyph.Y, R.string.shell_hint_search), hint(Glyph.A, R.string.shell_hint_change), hint(Glyph.B, R.string.shell_hint_back))
            Screen.SettingsSearch -> listOf(hint(Glyph.A, R.string.shell_hint_select), hint(Glyph.B, R.string.shell_hint_back))
            Screen.Start -> listOfNotNull(hint(Glyph.A, R.string.shell_hint_connect), hint(Glyph.MENU_LEFT, R.string.shell_hint_menu), optionsHint)
            is Screen.Boot -> listOfNotNull(hint(Glyph.A, R.string.shell_hint_select), hint(Glyph.B, R.string.shell_hint_back).takeIf { screen.back != null })
            is Screen.CreateProfile -> listOf(hint(Glyph.A, R.string.shell_hint_select), hint(Glyph.B, R.string.shell_hint_back))
            is Screen.PairPc -> listOfNotNull(hint(Glyph.A, R.string.shell_hint_select), hint(Glyph.B, R.string.shell_hint_back), optionsHint)
            is Screen.Pin -> listOf(hint(Glyph.B, R.string.shell_hint_cancel))
            Screen.DebugInfo -> adjust + listOf(hint(Glyph.A, R.string.shell_hint_select), hint(Glyph.B, R.string.shell_hint_back))
            // Like a stream: nothing over the controls
            Screen.OscPreview -> emptyList()
            is Screen.Artwork -> if (BundledArtwork.kind(screen.app) != null) listOf(hint(Glyph.A, R.string.shell_hint_back), hint(Glyph.B, R.string.shell_hint_back))
                else if (shell.artworkSheetOpen) listOf(hint(Glyph.A, R.string.shell_hint_choose), hint(Glyph.X, R.string.shell_hint_reset_filters, R.string.shell_hint_reset), hint(Glyph.Y, R.string.shell_hint_done), hint(Glyph.B, R.string.shell_hint_close))
                else listOf(hint(Glyph.L1, R.string.shell_hint_previous_tab), hint(Glyph.R1, R.string.shell_hint_next_tab), hint(Glyph.A, R.string.shell_hint_use_image, R.string.shell_hint_choose), hint(Glyph.X, R.string.shell_hint_search_name, R.string.shell_hint_search), hint(Glyph.Y, R.string.shell_hint_filter), hint(Glyph.B, R.string.shell_hint_back))
            is Screen.GameSettings -> adjust + listOfNotNull(hint(Glyph.L1, R.string.shell_hint_previous_tab), hint(Glyph.R1, R.string.shell_hint_next_tab), hint(Glyph.A, R.string.shell_hint_change),
                hint(Glyph.X, R.string.shell_hint_reset_to_profile, R.string.shell_hint_reset).takeIf { shell.onGameReset != null }, optionsHint, hint(Glyph.B, R.string.shell_hint_back))
            Screen.Profile, Screen.Devices -> listOf(hint(Glyph.A, R.string.shell_hint_select), hint(Glyph.B, R.string.shell_hint_back), hint(Glyph.MENU_LEFT, R.string.shell_hint_menu))
        }
    }
}

/** How long after the stream window took over the launch overlay is swapped for the exit background (before the system snapshots the stopped window). */
private const val HAND_OVER_DELAY_MS = 200L
