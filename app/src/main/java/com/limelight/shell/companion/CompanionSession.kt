package com.limelight.shell.companion

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.limelight.shell.settings.Appearance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the one companion window shows and who owns it (docs/DUAL_SCREEN.md). The shell and the stream are different activities,
 * but the panel should not blink when one hands over to the other: the new owner [attach]es before the old one [detach]es (the
 * stream resumes before the shell stops, and the shell before the stream is destroyed), the window stays and only [content]
 * changes. A [detach] from an activity that is no longer the owner does nothing; the owner's detach with nobody after it closes the
 * window. Main thread only.
 */
internal object CompanionSession {
    /** What an owner wants shown: [state] is read in composition, so snapshot state held in it updates the window live. */
    class Content(val appearance: StateFlow<Appearance>, val state: () -> CompanionState)

    private val shown = MutableStateFlow<Content?>(null)

    /** The owner's content; null when nobody owns the companion (the window then finishes). */
    val content: StateFlow<Content?> = shown.asStateFlow()

    private var owner: Any? = null
    private var ownerActivity: Activity? = null

    /** The running window, if any. */
    var window: CompanionActivity? = null

    /** The display a window was started on a moment ago and has not come up yet, so a second display event does not start another. */
    private var launching = -1
    private var launchedAt = 0L

    /** A display that took the launch but put the window elsewhere: not tried again until an owner attaches again. */
    var refused = -1
        private set

    fun attach(owner: Any, content: Content, ownerActivity: Activity) {
        this.owner = owner
        this.ownerActivity = ownerActivity
        shown.value = content
    }

    /** True when [owner] was the current owner, i.e. the window has to close now. */
    fun detach(owner: Any): Boolean {
        if (this.owner !== owner) return false
        this.owner = null
        ownerActivity = null
        shown.value = null
        return true
    }

    /**
     * Starting an activity on the other display makes that display the one the controller and keyboard keys go to, and a window
     * that takes no key focus leaves them with nowhere to go (observed: the main panel stops reacting and the system shows "isn't
     * responding"). So once the companion is up the owner is brought to the front again, which gives the key focus back to the main panel.
     */
    fun returnFocusToOwner() {
        val activity = ownerActivity ?: return
        // Not at once: the system settles the focus of the launch after the new activity has resumed
        handler.postDelayed({
            if (ownerActivity === activity && !activity.isFinishing && !activity.isDestroyed) {
                // Not moveTaskToFront: for a task that is already the front one of its display it changes nothing, and the focus stays
                // on the companion's display (observed). Starting the owner again with REORDER_TO_FRONT delivers an intent to the running
                // instance (no new activity) and makes its display the focused one.
                activity.startActivity(
                    Intent(activity, activity.javaClass)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            }
        }, FOCUS_DELAY_MS)
    }

    fun refuse(displayId: Int) {
        refused = displayId
    }

    fun forgetRefusal() {
        refused = -1
    }

    /** True when a window was started for [displayId] less than two seconds ago and is not up yet. */
    fun isLaunching(displayId: Int, now: Long): Boolean = window == null && launching == displayId && now - launchedAt < LAUNCH_NANOS

    fun launched(displayId: Int, now: Long) {
        launching = displayId
        launchedAt = now
    }

    private val handler = Handler(Looper.getMainLooper())
    private const val FOCUS_DELAY_MS = 250L
    private const val LAUNCH_NANOS = 2_000_000_000L
}
