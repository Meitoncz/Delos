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
import com.limelight.shell.data.BoxArtCache
import com.limelight.shell.data.LocalArtwork
import com.limelight.shell.data.GameLauncher
import com.limelight.shell.data.LocalBoxArt
import com.limelight.shell.data.PcRepository
import com.limelight.shell.data.PcSetup
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.AppearanceStore
import com.limelight.shell.settings.StartupStore
import com.limelight.shell.ui.Feedback
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.Stage
import com.limelight.shell.ui.animateDelosColors
import com.limelight.shell.ui.delosColors
import com.limelight.shell.ui.systemAccentHue
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Entry point of the Delos UI (Phase 6, see docs/UI_PLAN.md): the launcher activity on phones, TVs and
 * in multi-window, and the target of art:// links and PC shortcuts.
 */
class ShellActivity : ComponentActivity() {
    private lateinit var feedback: Feedback
    private lateinit var appearance: AppearanceStore
    private lateinit var repo: PcRepository
    private lateinit var boxArt: BoxArtCache
    private lateinit var artwork: ArtworkStore
    private lateinit var profiles: ProfileStore
    private val keys = GlobalKeys()

    /** A link or shortcut the shell still has to handle (from the intent that started or reached it). */
    private val links = MutableStateFlow<ShellLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hideSystemBars()
        feedback = Feedback(this)
        // Everything below reads the current profile's settings
        profiles = ProfileStore(this)
        appearance = AppearanceStore(this, profiles::settings)
        repo = PcRepository(this, profiles::settings)
        val startup = StartupStore(profiles::settings)
        profiles.onSettingsChanged {
            appearance.reload()
            startup.reload()
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
        val setup = PcSetup(this, repo)
        if (savedInstanceState == null) links.value = ShellLink.from(this, intent)
        setContent { ShellRoot(appearance, feedback, keys, repo, boxArt, artwork, launcher, profiles, startup, setup, links) }
        feedback.view = window.decorView
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ShellLink.from(this, intent)?.let { links.value = it }
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
    launcher: GameLauncher,
    profiles: ProfileStore,
    startup: StartupStore,
    setup: PcSetup,
    links: MutableStateFlow<ShellLink?>,
) {
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
    CompositionLocalProvider(LocalDelosColors provides colors, LocalFeedback provides feedback, LocalBoxArt provides boxArt, LocalArtwork provides artwork) {
        Stage(textScale = appearance.uiScale / 100f) {
            Shell(appearance, store::update, keys, repo, launcher, profiles, startup, setup, links)
        }
    }
}
