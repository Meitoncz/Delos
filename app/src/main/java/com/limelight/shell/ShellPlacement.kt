package com.limelight.shell

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

/**
 * Keeps the shell on the main panel of a dual-screen device (docs/DUAL_SCREEN.md): the launcher starts it on whatever display the
 * user tapped it on, and the Main screen setting or a display that comes and goes can change which panel is main. A shell on
 * the companion panel is started again on the main one (a new task there) and ends itself. Without a built-in companion display
 * nothing here ever acts.
 */
internal class ShellPlacement(private val activity: ShellActivity) {
    private val handler = Handler(Looper.getMainLooper())
    private val displays = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity.applicationContext)
    private var started = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = moveIfMisplaced()
        override fun onDisplayRemoved(displayId: Int) = moveIfMisplaced()
        override fun onDisplayChanged(displayId: Int) = moveIfMisplaced()
    }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SecondDisplayOverride.KEY || key == MainScreenChoice.KEY) moveIfMisplaced()
    }

    /** From `onResume`: follow the displays and the two display settings. */
    fun start() {
        if (started) return
        started = true
        displays.registerDisplayListener(displayListener, handler)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    /** From `onStop`. */
    fun stop() {
        if (!started) return
        started = false
        displays.unregisterDisplayListener(displayListener)
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    /**
     * At creation: when this shell is on the companion panel, starts it on the main one with the same intent (a link or a stream
     * result it was started for travels along) and ends this one. Returns true when it did; the caller then does nothing else.
     * A shell that was itself started by this class is left where it is.
     */
    fun moveOnCreate(): Boolean {
        if (activity.intent.getBooleanExtra(EXTRA_PLACED, false)) return false
        val target = target() ?: return false
        return relocate(target, Intent(activity.intent), restoreSettings = false)
    }

    /** While running: a changed setting or display. The new shell opens on the Display tab of the settings, where the user was. */
    private fun moveIfMisplaced() {
        if (!started || activity.isFinishing || activity.isDestroyed) return
        val target = target() ?: return
        relocate(target, Intent(activity, ShellActivity::class.java), restoreSettings = true)
    }

    private fun target(): Int? = DualScreen.plan(activity)?.mainFor(DualScreen.displayIdOf(activity))

    private fun relocate(target: Int, intent: Intent, restoreSettings: Boolean): Boolean {
        val now = System.nanoTime()
        // A display that refuses the launch must not turn into a loop of launches
        if (target == lastTarget && now - lastAt < RETRY_NANOS) return false
        lastTarget = target
        lastAt = now
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .putExtra(EXTRA_PLACED, true)
            .putExtra(EXTRA_RESTORE_SETTINGS, restoreSettings)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(target)
        return try {
            activity.startActivity(intent, options.toBundle())
            DiagLog.i("Companion", "Shell moved to the main display $target")
            activity.finish()
            @Suppress("DEPRECATION") activity.overridePendingTransition(0, 0)
            true
        } catch (e: RuntimeException) {
            DiagLog.w("Companion", "Shell could not move to display $target: ${e.javaClass.simpleName}")
            false
        }
    }

    companion object {
        /** Set on the intent of a shell this class started: it stays where the system put it. */
        const val EXTRA_PLACED = "com.limelight.shell.PLACED"

        /** Set when the shell was moved by a setting: it opens on the Display tab of the settings. */
        const val EXTRA_RESTORE_SETTINGS = "com.limelight.shell.RESTORE_SETTINGS"

        private const val RETRY_NANOS = 3_000_000_000L
        private var lastTarget = -1
        private var lastAt = 0L
    }
}
