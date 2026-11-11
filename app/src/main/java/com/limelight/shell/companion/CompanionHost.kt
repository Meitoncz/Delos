package com.limelight.shell.companion

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
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
 * [state] is read inside composition; hold the state in Compose snapshot state and the window follows it. [appearance] is the
 * profile's appearance (colors, interface scale), read live.
 */
class CompanionHost(
    private val activity: Activity,
    appearance: StateFlow<Appearance>,
    state: () -> CompanionState,
) {
    private val content = CompanionSession.Content(appearance, state)
    private val handler = Handler(Looper.getMainLooper())
    private val displays = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity.applicationContext)
    private var started = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = reconcile()
        override fun onDisplayRemoved(displayId: Int) = reconcile()
        override fun onDisplayChanged(displayId: Int) = reconcile()
    }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SecondDisplayOverride.KEY || key == MainScreenChoice.KEY) reconcile()
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

        /** The host of a stream activity (Java): the PC it streams from is shown, the colors are the current profile's. */
        @JvmStatic
        fun forStream(activity: Activity, pcName: String?): CompanionHost =
            CompanionHost(activity, AppearanceStore(activity) { ProfileStore.currentSettings(activity) }.appearance) { CompanionState.Stream(pcName) }
    }
}
