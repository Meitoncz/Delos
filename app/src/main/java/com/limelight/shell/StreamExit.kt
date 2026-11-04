package com.limelight.shell

import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.activity.ComponentDialog
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Leaving a stream started from the new UI (Disconnect / Quit game in the stream menu), with the
 * prototype's portal back into home: the portal opens over the stream ("Closing <game>" or "Back to
 * Delos", the PC below) in a window of its own, so the system can blur the stream behind it (the stream's
 * picture is a surface no view can blur; without cross-window blur it is dimmed), and bursts after the same 2.1 s as the portal into a PC. Then the new UI comes to
 * the front with a fade the system honours between tasks (finishing the stream would slide it out),
 * the stream ends behind it, and the new UI finishes the burst with the focus still on the game.
 */
object StreamExit {
    /** The burst the new UI finishes when it resumes; taken once. */
    class Arrival(val label: String, val sub: String)

    /**
     * True from the moment Quit game ends the stream until the PC has really quit the game (or gave up): the new UI keeps
     * the portal on screen for that long, so the dashboard never shows up while the game is still running.
     */
    @Volatile
    var quitting = false

    private var pending: Arrival? = null

    /** The burst to finish, if the stream just ended through the portal. */
    fun take(): Arrival? = pending.also { pending = null }

    /** Plays the portal over [game], then brings the new UI back and runs [end] (disconnect or quit). */
    @JvmStatic
    fun play(game: Game, quit: Boolean, end: Runnable) {
        val title = game.intent.getStringExtra(Game.EXTRA_APP_NAME) ?: "the game"
        val pcName = game.intent.getStringExtra(Game.EXTRA_PC_NAME) ?: ""
        val label = if (quit) "Quitting $title" else "Disconnecting"
        val appearance = AppearanceStore(game) { ProfileStore.currentSettings(game) }.appearance.value
        val colors = delosColors(appearance, systemAccentHue(game))
        val portal = Portal((System.nanoTime() and 0x7fffffff).toInt(), Symbols.HOME, label, pcName, cancellable = false)
        val shown = mutableStateOf<Portal?>(null)
        val burst = mutableStateOf(false)
        val feedback = Feedback(game).also { it.hapticsEnabled = appearance.haptics }
        val blur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && game.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled
        val dialog = ComponentDialog(game, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        dialog.setCancelable(false)
        val view = ComposeView(dialog.context).apply {
            setContent {
                CompositionLocalProvider(LocalDelosColors provides colors) {
                    Stage(textScale = appearance.uiScale / 100f) { PortalOverlay(shown.value, burst.value, null, appearance.reducedMotion, dim = !blur) }
                }
            }
        }
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN)
            attributes = attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                windowAnimations = 0
                dimAmount = 0f
            }
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            if (blur) addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
        dialog.show()
        dialog.window?.let { w ->
            WindowCompat.getInsetsController(w, w.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            if (blur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Stronger than the portal's backdrop blur in the new UI (22 stage px): the stream is busier.
                // Eases in with the portal's .45 s fade.
                val metrics = game.resources.displayMetrics
                val radius = 50f * minOf(metrics.widthPixels / 1920f, metrics.heightPixels / 1080f)
                ValueAnimator.ofFloat(0f, radius).apply {
                    duration = 450
                    addUpdateListener { a -> w.attributes = w.attributes.apply { blurBehindRadius = (a.animatedValue as Float).toInt() } }
                    start()
                }
            }
        }
        feedback.view = view
        shown.value = portal
        feedback.play(Cue.OPEN)
        // The stream behind zooms in a little, fast at first and then easing out
        if (!appearance.reducedMotion) game.streamSurface?.let { s ->
            s.pivotX = s.width / 2f
            s.pivotY = s.height / 2f
            s.animate().scaleX(s.scaleX * ZOOM).scaleY(s.scaleY * ZOOM).setDuration(2400).setInterpolator(PathInterpolator(.16f, 1f, .3f, 1f)).start()
        }
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
                // Gone with the stream window anyway; closed once the new UI has faded in over it
                view.postDelayed({ runCatching { dialog.dismiss() } }, 600)
            }, BURST_MS)
        }, if (appearance.reducedMotion) 600L else 2100L)
    }

    /** How long the burst plays over the stream before the new UI takes over (the prototype's 380 ms). */
    const val BURST_MS = 380L

    /** How far the stream behind the portal zooms in. */
    private const val ZOOM = 1.12f
}
