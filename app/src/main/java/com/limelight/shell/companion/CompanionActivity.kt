package com.limelight.shell.companion

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.limelight.diag.DiagLog
import com.limelight.display.DualScreen
import com.limelight.shell.settings.Appearance
import com.limelight.shell.settings.AppLanguage
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.Stage
import com.limelight.shell.ui.animateDelosColors
import com.limelight.shell.ui.delosColors
import com.limelight.shell.ui.systemAccentHue
import kotlinx.coroutines.flow.StateFlow

/**
 * The companion screen: an activity on the second panel of a dual-screen device (docs/DUAL_SCREEN.md), in a task of its own
 * that is not in Recents. It exists only for [CompanionHost], which starts it for the running shell or stream and finishes it
 * with them: it never starts while no owner has attached ([CompanionSession.content]), and it ends itself when it is not on
 * the display it was started for (the system puts a task on the default display when its display is removed, and a display that
 * refuses the launch may too: the window must never cover the main panel).
 *
 * Its window takes no key focus (`FLAG_NOT_FOCUSABLE`), so gamepad and keyboard keys stay with the main panel; touch works.
 */
class CompanionActivity : ComponentActivity() {
    /** The display this window was started for. */
    var targetDisplay = -1
        private set

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        targetDisplay = intent.getIntExtra(EXTRA_DISPLAY, -1)
        val own = DualScreen.displayIdOf(this)
        if (CompanionSession.content.value == null || own != targetDisplay) {
            // No owner (the process was restarted with this task, or the owner left): nothing to show. Or the launch landed elsewhere.
            if (CompanionSession.content.value != null) {
                CompanionSession.refuse(targetDisplay)
                DiagLog.w(CompanionHost.TAG, "Companion started for display $targetDisplay opened on display $own and closed")
            }
            finish()
            return
        }
        // Key focus stays with the main panel; the window still receives the touches on it
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        // Only the focused window can hide the system bars through the insets controller; on the default display (the companion of a
        // swapped device) the window never has focus, so the fullscreen flag above and the legacy visibility flags do it
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        CompanionSession.window = this
        setContent { CompanionRoot() }
    }

    // A task whose display is removed moves to the default display with a new configuration (this activity handles it in place)
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        closeUnlessOnTarget()
    }

    override fun onResume() {
        super.onResume()
        closeUnlessOnTarget()
        if (!isFinishing) CompanionSession.returnFocusToOwner()
    }

    private fun closeUnlessOnTarget() {
        if (!isFinishing && DualScreen.displayIdOf(this) != targetDisplay) finish()
    }

    override fun onDestroy() {
        if (CompanionSession.window === this) CompanionSession.window = null
        CompanionSession.helpGone(this)
        super.onDestroy()
    }

    companion object {
        /** The display this window is started for. */
        const val EXTRA_DISPLAY = "com.limelight.shell.companion.DISPLAY"
    }
}

@Composable
private fun CompanionRoot() {
    val content by CompanionSession.content.collectAsState()
    content?.let { CompanionTheme(it.appearance) { CompanionContent(it.state(), inWindow = true) } }
}

/** The palette, reduced motion and the stage of the companion's own display: what `ShellTheme` does for the shell. */
@Composable
private fun CompanionTheme(appearance: StateFlow<Appearance>, content: @Composable () -> Unit) {
    val current by appearance.collectAsState()
    val context = LocalContext.current
    val systemHue = remember { systemAccentHue(context) }
    val colors = animateDelosColors(delosColors(current, systemHue))
    CompositionLocalProvider(LocalDelosColors provides colors, LocalReducedMotion provides current.reducedMotion) {
        Stage(textScale = current.uiScale / 100f, content = content)
    }
}
