package com.limelight.shell.companion

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.limelight.binding.input.virtual_controller.keyboard.KeyBoardLayoutController
import com.limelight.shell.StreamMenu
import com.limelight.shell.StreamStats
import androidx.preference.PreferenceManager
import com.limelight.diag.DiagLog
import com.limelight.display.DualScreen
import com.limelight.display.MainScreenChoice
import com.limelight.display.SecondDisplayOverride
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.Appearance
import com.limelight.shell.settings.AppearanceStore
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps the companion screen on the second panel of a dual-screen device while its owner, the running shell or stream, is
 * in front (docs/DUAL_SCREEN.md). The screen is a [CompanionActivity] started on the panel that is not the owner's, in a task
 * of its own: a `Presentation` would be simpler, but WindowManager refuses one on any display without `FLAG_PRESENTATION`
 * (never the default display, and by the AOSP rule not an ordinary built-in panel).
 *
 * The owner calls [start] from `onResume` and [stop] from `onStop` (and `onDestroy`). Between the two the host follows the
 * displays and the two display settings: it starts the window when a companion display exists, finishes it when that display goes
 * away, starts it again when it returns or the choice changes. The window is kept until `onStop`, not `onPause`, and handed from
 * one owner to the next without closing ([CompanionSession]): when the shell hands over to the stream the stream resumes first.
 *
 * A stream's host also carries the stream's live data ([onStats], [bindStream]); [forStream] builds it. [state] is read inside composition; hold the state in Compose snapshot state and the window follows it. [appearance] is the
 * profile's appearance (colors, interface scale), read live.
 */
internal class CompanionHost(
    private val activity: Activity,
    appearance: StateFlow<Appearance>,
    state: () -> CompanionState,
) {
    private val content = CompanionSession.Content(appearance, state)
    private val handler = Handler(Looper.getMainLooper())
    private val displays = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity.applicationContext)
    private var started = false

    /** The live data of a stream's companion; null for the shell's host. */
    private var stream: StreamFeed? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = reconcile()
        override fun onDisplayRemoved(displayId: Int) = reconcile()
        override fun onDisplayChanged(displayId: Int) = reconcile()
    }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SecondDisplayOverride.KEY || key == MainScreenChoice.KEY) reconcile()
    }

    /**
     * From the decoder's thread: a statistics snapshot of the running stream. It is dropped here, before it can cost the main thread
     * anything, while no companion window shows and when it comes sooner than 2 Hz after the last one ([StatsThrottle]).
     */
    fun onStats(stats: StreamStats) {
        stream?.onStats(stats)
    }

    /** From the stream activity once its menu exists: the companion's tiles run the menu's own handlers from now on. */
    fun bindStream(menu: StreamMenu) {
        stream?.commands = StreamCommands(menu.companionMenu(), ::sendKey)
    }

    /** From `onResume`: take over the companion and start its window if there is a companion display. Safe to call again. */
    fun start() {
        if (!started) {
            started = true
            CompanionSession.forgetRefusal()
            displays.registerDisplayListener(displayListener, handler)
            prefs.registerOnSharedPreferenceChangeListener(prefListener)
        }
        CompanionSession.attach(this, content, activity)
        reconcile()
    }

    /** From `onStop` and `onDestroy`: stop following; close the window unless the next owner has already taken it over. Safe to call again. */
    fun stop() {
        if (started) {
            started = false
            displays.unregisterDisplayListener(displayListener)
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        }
        if (CompanionSession.detach(this)) CompanionSession.window?.finish()
    }

    private fun reconcile() {
        if (!started || activity.isFinishing || activity.isDestroyed) return
        val target = DualScreen.plan(activity)?.otherThan(DualScreen.displayIdOf(activity))?.id
        val window = CompanionSession.window
        if (target == null) {
            window?.finish()
            return
        }
        if (window != null && window.targetDisplay == target && !window.isFinishing) return
        // Another panel than before (a swap): the old window goes first, so there are never two
        window?.finish()
        val now = System.nanoTime()
        if (target == CompanionSession.refused || CompanionSession.isLaunching(target, now)) return
        launch(target, now)
    }

    private fun launch(displayId: Int, now: Long) {
        val intent = Intent(activity, CompanionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(CompanionActivity.EXTRA_DISPLAY, displayId)
        try {
            activity.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
            CompanionSession.launched(displayId, now)
            DiagLog.i(TAG, "Companion started on display $displayId")
        } catch (e: RuntimeException) {
            // The display vanished between the plan and the launch, or the system refuses the launch there
            CompanionSession.refuse(displayId)
            DiagLog.w(TAG, "Companion could not be started on display $displayId: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        internal const val TAG = "Companion"

        /**
         * The host of a stream activity (Java): the PC and game it streams are shown, the colors are the current profile's. Call
         * [bindStream] once the stream menu exists and [onStats] with every statistics snapshot.
         */
        @JvmStatic
        fun forStream(activity: Activity, pcName: String?, gameName: String?): CompanionHost {
            val feed = StreamFeed()
            val appearance = AppearanceStore(activity) { ProfileStore.currentSettings(activity) }.appearance
            return CompanionHost(activity, appearance) { CompanionState.Stream(pcName, gameName, feed.stats, feed.commands) }.also { it.stream = feed }
        }

        /** A key of the companion keyboard into the stream: the path of the Full keyboard (`KeyBoardLayoutController.sendKey`). */
        private fun sendKey(code: Int, down: Boolean) {
            val event = KeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code)
            event.source = 0
            KeyBoardLayoutController.sendKeyEvent(event)
        }
    }
}

/** The stream data the companion shows, held in snapshot state so the window follows it. */
private class StreamFeed {
    var stats by mutableStateOf<StreamStats?>(null)
    var commands by mutableStateOf<StreamCommands?>(null)
    private val throttle = StatsThrottle()
    private val handler = Handler(Looper.getMainLooper())

    /** Decoder thread. */
    fun onStats(next: StreamStats) {
        if (CompanionSession.window == null || !throttle.accept(SystemClock.elapsedRealtime())) return
        handler.post { stats = next }
    }
}
