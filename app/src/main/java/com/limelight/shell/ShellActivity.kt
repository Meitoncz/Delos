package com.limelight.shell

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
import com.limelight.shell.data.BoxArtCache
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

/**
 * Entry point of the new native Delos UI (Phase 6, see docs/UI_PLAN.md).
 *
 * During development it is a second launcher entry in debug builds only ("Delos Next",
 * declared in src/debug/AndroidManifest.xml); the classic UI (PcView) stays the main entry
 * until the switch-over in step 6.9.
 */
class ShellActivity : ComponentActivity() {
    private lateinit var feedback: Feedback
    private lateinit var appearance: AppearanceStore
    private lateinit var repo: PcRepository
    private lateinit var boxArt: BoxArtCache
    private val keys = GlobalKeys()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hideSystemBars()
        feedback = Feedback(this)
        appearance = AppearanceStore(this)
        repo = PcRepository(this)
        lifecycle.addObserver(repo)
        repo.bind()
        boxArt = BoxArtCache(this) { repo.binder?.uniqueId }
        val launcher = GameLauncher(this, repo)
        val profiles = ProfileStore(this)
        val startup = StartupStore(this)
        val setup = PcSetup(this, repo)
        setContent { ShellRoot(appearance, feedback, keys, repo, boxArt, launcher, profiles, startup, setup) }
        feedback.view = window.decorView
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = keys.dispatch(event) || super.dispatchKeyEvent(event)

    override fun onDestroy() {
        repo.unbind()
        feedback.release()
        super.onDestroy()
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
    launcher: GameLauncher,
    profiles: ProfileStore,
    startup: StartupStore,
    setup: PcSetup,
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
    CompositionLocalProvider(LocalDelosColors provides colors, LocalFeedback provides feedback, LocalBoxArt provides boxArt) {
        Stage(textScale = appearance.uiScale / 100f) {
            Shell(appearance, store::update, keys, repo, launcher, profiles, startup, setup)
        }
    }
}
