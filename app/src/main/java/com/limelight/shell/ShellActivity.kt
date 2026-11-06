package com.limelight.shell

import android.content.Intent
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

    /** A link or shortcut the shell still has to handle (from the intent that started or reached it). */
    private val links = MutableStateFlow<ShellLink?>(null)

    /** The result of a stream that ended on its own, which the shell still has to show (once). */
    private val results = MutableStateFlow<StreamResult?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hideSystemBars()
        // No system snapshot of this window (Android 13+): coming back from a stream it would show the last frame from before, the launch
        // tunnel, for a moment
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        feedback = Feedback(this)
        // Everything below reads the current profile's settings
        profiles = ProfileStore(this)
        appearance = AppearanceStore(this, profiles::settings)
        repo = PcRepository(this, profiles::settings)
        val shell = ShellState(if (profiles.profiles.value.all.isEmpty()) Screen.Boot() else Screen.Start)
        DelosToast.register(this, show = { text, durationMs, kind -> shell.showToast(text, durationMs = durationMs, kind = kind) })
        metadata = GameMetadataStore(this, profiles::settings)
        val startup = StartupStore(profiles::settings)
        profiles.onSettingsChanged {
            appearance.reload()
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
        // Debug builds only: the stream menu, the Full keyboard or a toast with fake state instead of the shell (docs/GUI.md, "Stream menu preview", "Full keyboard", "Toasts")
        val preview = StreamMenuPreview.from(intent) ?: FullKeyboardPreview.from(intent) ?: ToastPreview.from(intent, shell) ?: SettingsTabsPreview.from(intent) ?: SettingsListPreview.from(intent) ?: TopMenuPreview.from(intent)
        setContent {
            if (preview != null) ShellTheme(appearance, feedback) {
                preview()
                com.limelight.shell.ui.ToastHost(shell.toast, shell::hideToast, shell::runToastAction, shell.toastPress)
            }
            else ShellRoot(appearance, feedback, keys, repo, boxArt, artwork, metadata, launcher, profiles, startup, setup, links, results, shell)
        }
        feedback.view = window.decorView
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

    override fun onDestroy() {
        profiles.streamProfiles.close()
        repo.unbind()
        feedback.release()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        profiles.streamProfiles.resume()
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
