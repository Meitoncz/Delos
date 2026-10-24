package com.limelight.shell

import android.app.ActivityOptions
import android.content.Intent
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.limelight.Game
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.AppearanceStore
import com.limelight.shell.ui.Cue
import com.limelight.shell.ui.Feedback
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.PortalOverlay
import com.limelight.shell.ui.Stage
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.delosColors
import com.limelight.shell.ui.systemAccentHue

/**
 * Leaving a stream started from the new UI (Disconnect / Quit game in the stream menu), with the
 * prototype's portal back into home: the portal opens over the stream ("Closing <game>" or "Back to
 * Delos", the PC below) and bursts after the same 2.1 s as the portal into a PC. Then the new UI comes to
 * the front with a fade the system honours between tasks (finishing the stream would slide it out),
 * the stream ends behind it, and the new UI finishes the burst with the focus still on the game.
 */
object StreamExit {
    /** The burst the new UI finishes when it resumes; taken once. */
    class Arrival(val label: String, val sub: String)

    private var pending: Arrival? = null

    /** The burst to finish, if the stream just ended through the portal. */
    fun take(): Arrival? = pending.also { pending = null }

    /** Whether [game] was started from the new UI, so leaving it goes back there through the portal. */
    @JvmStatic
    fun applies(game: Game): Boolean = StreamLoader.requested(game.intent)

    /** Plays the portal over [game], then brings the new UI back and runs [end] (disconnect or quit). */
    @JvmStatic
    fun play(game: Game, quit: Boolean, end: Runnable) {
        val title = game.intent.getStringExtra(Game.EXTRA_APP_NAME) ?: "the game"
        val pcName = game.intent.getStringExtra(Game.EXTRA_PC_NAME) ?: ""
        val label = if (quit) "Closing $title" else "Back to Delos"
        val appearance = AppearanceStore(game) { ProfileStore.currentSettings(game) }.appearance.value
        val colors = delosColors(appearance, systemAccentHue(game))
        val portal = Portal((System.nanoTime() and 0x7fffffff).toInt(), Symbols.HOME, label, pcName, cancellable = false)
        val shown = mutableStateOf<Portal?>(null)
        val burst = mutableStateOf(false)
        val feedback = Feedback(game)
        val view = ComposeView(game).apply {
            setContent {
                CompositionLocalProvider(LocalDelosColors provides colors) {
                    // No blur source: the stream's picture cannot be blurred, the portal dims it instead
                    Stage(textScale = appearance.uiScale / 100f) { PortalOverlay(shown.value, burst.value, null, appearance.reducedMotion) }
                }
            }
        }
        game.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        feedback.view = view
        shown.value = portal
        feedback.play(Cue.OPEN)
        view.postDelayed({
            burst.value = true
            feedback.play(Cue.SELECT)
            view.postDelayed({
                if (game.isFinishing) return@postDelayed
                pending = Arrival(label, pcName)
                val root = game.window.decorView
                val home = Intent().setClassName(game, "com.limelight.shell.ShellActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                // From the whole screen a scale-up only fades the new UI in over the stream (like starting one)
                game.startActivity(home, ActivityOptions.makeScaleUpAnimation(root, 0, 0, root.width, root.height).toBundle())
                end.run()
                feedback.release()
            }, BURST_MS)
        }, if (appearance.reducedMotion) 600L else 2100L)
    }

    /** How long the burst plays over the stream before the new UI takes over (the prototype's 380 ms). */
    const val BURST_MS = 380L
}
