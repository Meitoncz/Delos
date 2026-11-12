package com.limelight.shell

import android.content.Intent
import android.content.Context
import android.content.SharedPreferences
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.limelight.shell.companion.CompanionHost
import com.limelight.shell.companion.CompanionState
import com.limelight.shell.data.ArtworkStore
import com.limelight.shell.data.GameMetadataStore
import com.limelight.shell.data.LocalGameMetadata
import com.limelight.shell.data.BoxArtCache
import com.limelight.shell.data.LocalArtwork
import com.limelight.shell.data.GameLauncher
import com.limelight.shell.data.LocalBoxArt
import com.limelight.shell.data.PcRepository
import com.limelight.shell.data.PcSetup
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.Appearance
import com.limelight.shell.settings.AppearanceStore
import com.limelight.shell.settings.AppLanguage
import com.limelight.shell.settings.SettingsTab
import com.limelight.shell.settings.StartupStore
import com.limelight.shell.ui.Feedback
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.Stage
import com.limelight.shell.ui.animateDelosColors
import com.limelight.shell.ui.delosColors
import com.limelight.shell.ui.systemAccentHue
import kotlinx.coroutines.flow.MutableStateFlow
import com.limelight.utils.DelosToast

/**
 * Entry point of the Delos UI (see docs/GUI.md): the launcher activity on phones, TVs and
 * in multi-window, and the target of art:// links and PC shortcuts.
 */
class ShellActivity : ComponentActivity() {
    private lateinit var feedback: Feedback
    private lateinit var appearance: AppearanceStore
    private lateinit var repo: PcRepository
    private lateinit var boxArt: BoxArtCache
    private lateinit var artwork: ArtworkStore
    private lateinit var metadata: GameMetadataStore
    private lateinit var profiles: ProfileStore
    private val keys = GlobalKeys()

    /** The second panel of a dual-screen device (docs/DUAL_SCREEN.md): shown from `onResume` to `onStop` while a companion display exists. */
    private var companion: CompanionHost? = null
    private var placement: ShellPlacement? = null

    /** This instance started the shell again on the main panel and ends: nothing in `onCreate` after its first lines was set up. */
    private var movedToMain = false

    /** The Language row's preference (the current profile's). The app locale is the truth; the row mirrors it ([AppLanguage]). */
    private var languageSettings: SharedPreferences? = null
    private val languageListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        AppLanguage.onPreferenceChanged(this, prefs, key)
    }

    /** A link or shortcut the shell still has to handle (from the intent that started or reached it). */
    private val links = MutableStateFlow<ShellLink?>(null)

    /** The result of a stream that ended on its own, which the shell still has to show (once). */
    private val results = MutableStateFlow<StreamResult?>(null)

    // Android 10-12: puts the saved app language on this activity's resources (a no-op on Android 13+, where the system does it)
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        placement = ShellPlacement(this)
        if (savedInstanceState == null && placement?.moveOnCreate() == true) {
            movedToMain = true
            return
        }
        hideSystemBars()
        // No system snapshot of this window (Android 13+): coming back from a stream it would show the last frame from before, the launch
        // tunnel, for a moment
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        feedback = Feedback(this)
        // Everything below reads the current profile's settings
        profiles = ProfileStore(this)
        bindLanguage()
        appearance = AppearanceStore(this, profiles::settings)
        repo = PcRepository(this, profiles::settings)
        val settingsAgain = intent.getBooleanExtra(ShellPlacement.EXTRA_RESTORE_SETTINGS, false) && profiles.profiles.value.all.isNotEmpty()
        val shell = ShellState(if (profiles.profiles.value.all.isEmpty()) Screen.Boot() else if (settingsAgain) Screen.Settings else Screen.Start)
        if (settingsAgain) shell.settingsTab = SettingsTab.DISPLAY
        DelosToast.register(this, show = { text, durationMs, kind -> shell.showToast(text, durationMs = durationMs, kind = kind) })
        metadata = GameMetadataStore(this, profiles::settings)
        val startup = StartupStore(profiles::settings)
        profiles.onSettingsChanged {
            appearance.reload()
            bindLanguage()
            startup.reload()
            metadata.reloadFilters()
            repo.profileChanged()
        }
        lifecycle.addObserver(repo)
        repo.bind()
        boxArt = BoxArtCache(this) { repo.binder?.uniqueId }
        artwork = ArtworkStore(this)
        if (com.limelight.BuildConfig.DEBUG && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            com.limelight.shell.ui.BackgroundShaders.verify()
        }
        val launcher = GameLauncher(this, repo)
        val setup = PcSetup(this, repo, profiles)
        if (savedInstanceState == null) {
            links.value = ShellLink.from(this, intent)
            results.value = StreamResult.take(intent)
        }
        // Debug builds only: the stream menu, the Full keyboard, a toast or a screen with fake state instead of the shell (docs/GUI.md, "Stream menu preview", "Full keyboard", "Toasts", "Screen shapes")
        val preview = StreamMenuPreview.from(intent) ?: FullKeyboardPreview.from(intent) ?: ToastPreview.from(intent, shell) ?: SettingsTabsPreview.from(intent) ?: SettingsListPreview.from(intent) ?: GameSettingsPreview.from(intent) ?: TopMenuPreview.from(intent) ?: ContextMenuPreview.from(intent) ?: CompanionPreview.from(intent) ?: ScreenPreviews.from(intent, shell)
        // Debug builds only: draws all of it in a box of another panel's size and density (`preview_shape`)
        val simulator = ShapeSimulator.from(intent)
        setContent {
            val screen: @Composable () -> Unit = {
                if (preview != null) ShellTheme(appearance, feedback) {
                    preview()
                    com.limelight.shell.ui.ToastHost(shell.toast, shell::hideToast, shell::runToastAction, shell.toastPress)
                }
                else ShellRoot(appearance, feedback, keys, repo, boxArt, artwork, metadata, launcher, profiles, startup, setup, links, results, shell)
            }
            if (simulator != null) simulator.Host(screen) else screen()
        }
        feedback.view = window.decorView
        val fakeCompanion = CompanionPreview.fakeState(intent) // debug builds only: made-up state for the real second display
        companion = CompanionHost(this, appearance.appearance) {
            // A screen preview (debug builds) runs its own shell state: the window shows what that one publishes
            fakeCompanion ?: (CompanionPreview.liveShell ?: shell).let { CompanionState.Shell(it.pcName, it.companionGame, it.companionHelp) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ShellLink.from(this, intent)?.let { links.value = it }
        StreamResult.take(intent)?.let { results.value = it }
    }

    // Activity's public key dispatcher must still run after the shell's global controller keys.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = keys.dispatch(event) || super.dispatchKeyEvent(event)

    /** Follows the current profile's settings for the Language row: it shows the app language, and choosing there sets it. */
    private fun bindLanguage() {
        languageSettings?.unregisterOnSharedPreferenceChangeListener(languageListener)
        val settings = profiles.settings()
        languageSettings = settings
        AppLanguage.mirror(this, settings)
        settings.registerOnSharedPreferenceChangeListener(languageListener)
    }

    override fun onDestroy() {
        companion?.stop()
        if (movedToMain) {
            super.onDestroy()
            return
        }
        languageSettings?.unregisterOnSharedPreferenceChangeListener(languageListener)
        profiles.streamProfiles.close()
        repo.unbind()
        feedback.release()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (movedToMain) return
        languageSettings?.let { AppLanguage.mirror(this, it) }
        profiles.streamProfiles.resume()
        placement?.start()
        companion?.start()
    }

    override fun onStop() {
        placement?.stop()
        companion?.stop()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The system shows the bars again after the notification shade or a dialog
        if (hasFocus) hideSystemBars()
    }

    // Delos draws its own status bar; the system bars only appear temporarily after an edge swipe
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun ShellRoot(
    store: AppearanceStore,
    feedback: Feedback,
    keys: GlobalKeys,
    repo: PcRepository,
    boxArt: BoxArtCache,
    artwork: ArtworkStore,
    metadata: GameMetadataStore,
    launcher: GameLauncher,
    profiles: ProfileStore,
    startup: StartupStore,
    setup: PcSetup,
    links: MutableStateFlow<ShellLink?>,
    results: MutableStateFlow<StreamResult?>,
    shell: ShellState,
) {
    ShellTheme(store, feedback) { appearance ->
        CompositionLocalProvider(LocalBoxArt provides boxArt, LocalArtwork provides artwork, LocalGameMetadata provides metadata) {
            Shell(appearance, store::update, keys, repo, launcher, profiles, startup, setup, links, results, shell)
        }
    }
}

/** The palette, feedback, reduced motion and the 1920 x 1080 stage every screen of Delos is drawn in. */
@Composable
private fun ShellTheme(store: AppearanceStore, feedback: Feedback, content: @Composable (Appearance) -> Unit) {
    val appearance by store.appearance.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val systemHue = remember { systemAccentHue(context) }
    val colors = animateDelosColors(delosColors(appearance, systemHue))
    val view = LocalView.current
    DisposableEffect(view) {
        // Compose plays the system click on every focus move; Delos has its own UI sounds
        view.isSoundEffectsEnabled = false
        onDispose { }
    }
    SideEffect {
        feedback.soundsEnabled = appearance.sounds
        feedback.hapticsEnabled = appearance.haptics
    }
    CompositionLocalProvider(LocalDelosColors provides colors, LocalFeedback provides feedback, LocalReducedMotion provides appearance.reducedMotion) {
        Stage(textScale = appearance.uiScale / 100f) { content(appearance) }
    }
}
