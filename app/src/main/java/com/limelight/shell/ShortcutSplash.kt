package com.limelight.shell

import android.graphics.drawable.ColorDrawable
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.AppearanceStore
import com.limelight.shell.ui.GameArt
import com.limelight.shell.ui.HANDOFF_PROGRESS
import com.limelight.shell.ui.LaunchCard
import com.limelight.shell.ui.LaunchHandoff
import com.limelight.shell.ui.LaunchOverlay
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.Stage
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.delosColors
import com.limelight.shell.ui.systemAccentHue

/**
 * What a shortcut, `.art` file or link shows while it looks for its PC (ShortcutTrampoline cannot start
 * the stream before the PC answers): the same launch scene Delos shows when a game starts from Home, so
 * the stream's loader (StreamLoader) continues it exactly as it does there ([handOff]).
 */
object ShortcutSplash {
    /** Shows the launch scene for [appName] on [pcName] (either may be unknown) over the whole [activity]. */
    @JvmStatic
    fun show(activity: AppCompatActivity, appName: String?, pcName: String?, pcUuid: String?, appId: Int) {
        val pc = pcName?.takeIf { it.isNotEmpty() } ?: "the PC"
        val title = appName?.takeIf { it.isNotEmpty() } ?: pc
        // The colors and scale of the profile that the stream will use
        val appearance = AppearanceStore(activity) { ProfileStore.currentSettings(activity) }.appearance.value
        val colors = delosColors(appearance, systemAccentHue(activity))
        val hue = ((title.hashCode() and 0x7fffffff) % 360).toFloat()
        val cover = if (pcUuid != null && appId >= 0) StreamLoader.loadCover(activity, pcUuid, appId) else null
        val card = LaunchCard(
            0, title, StreamLoader.greeting(pc),
            GameArt("$pcUuid/$appId", hue, (hue + 35f) % 360f, Symbols.SPORTS_ESPORTS, cover),
            null,
        )
        LaunchHandoff.background = null
        // No white or grey flash before the first frame, and no bars, as in the new UI
        activity.window.setBackgroundDrawable(ColorDrawable(colors.bg.toArgb()))
        activity.supportActionBar?.hide()
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        activity.setContentView(
            ComposeView(activity).apply {
                setContent {
                    CompositionLocalProvider(LocalDelosColors provides colors) {
                        Stage(textScale = appearance.uiScale / 100f) { LaunchOverlay(card, appearance.reducedMotion) }
                    }
                }
            }
        )
    }

    /** The stream window is about to open: its loader goes on from where Home's launch scene hands over. */
    @JvmStatic
    fun handOff() {
        LaunchHandoff.progress = HANDOFF_PROGRESS
    }
}
