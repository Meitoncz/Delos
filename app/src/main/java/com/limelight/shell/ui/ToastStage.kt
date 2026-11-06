package com.limelight.shell.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.limelight.shell.Toast

/**
 * What is on the toast stage and when it started: the one source of truth the toast hosts draw. A window that blurs what is behind it
 * (the stream menu, the exit) gets a host of its own, and only one host at a time is active (`ToastCovers`); they share this stage, so a toast
 * keeps its start time, its spring, glow and timer when the drawing moves from one window to another.
 */
@Stable
class ToastStage {
    private val logic = ToastStageLogic<Toast>()
    private var shownId: Int? = null
    private var seenPress: Int? = null

    /** The toasts on the stage (at most the leaving one and the entering one); replaced as a whole when it changes. */
    var slots by mutableStateOf(emptyList<ToastSlot<Toast>>())
        private set

    /** The clock (ms) the animation of every slot is a function of; ticks only while a host drives a non-empty stage. */
    val clock = mutableLongStateOf(0L)

    /** Counts the presses of the action: true when [count] is a new one (the chip squeezes before the toast is replaced). */
    fun pressed(count: Int, now: Long): Boolean {
        val seen = seenPress
        seenPress = count
        if (seen == null || seen == count) return false
        logic.press(now)
        slots = logic.slots()
        return true
    }

    /** Brings the stage in line with the state's [toast]: a new one replaces (or enters), none dismisses; the toast it already shows changes nothing. */
    fun sync(toast: Toast?, now: Long) {
        if (toast?.id == shownId) return
        shownId = toast?.id
        if (toast != null) logic.show(toast, toast.durationMs, now) else logic.dismiss(now)
        slots = logic.slots()
    }

    /** One frame at [now]: true when the stage is empty again; [onExpired] is told once for each toast whose time is up. */
    fun tick(now: Long, onExpired: (ToastSlot<Toast>) -> Unit): Boolean {
        clock.longValue = now
        if (logic.advance(now, onExpired)) slots = logic.slots()
        return slots.isEmpty()
    }
}
