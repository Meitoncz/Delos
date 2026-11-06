package com.limelight.utils

import java.lang.ref.WeakReference

/** Android's duration hints, kept independent of Android for the routing tests. */
internal fun toastDurationMs(duration: Int): Long = if (duration == 1) 3500L else 2000L

/** Main-thread-only routing. Registration must not keep an Activity or its host alive. */
internal class ToastRouting<H : Any> {
    private class Entry<H : Any>(host: H) {
        val host = WeakReference(host)
        var visible = true // Includes onCreate: messages wait for the first visible frame.
    }

    private val entries = mutableListOf<Entry<H>>()
    private var resumed = WeakReference<H>(null)

    fun register(host: H) {
        prune()
        if (entries.none { it.host.get() === host }) entries.add(Entry(host))
    }

    fun start(host: H) {
        entries.find { it.host.get() === host }?.visible = true
    }

    fun resume(host: H) {
        start(host)
        resumed = WeakReference(host)
    }

    fun pause(host: H) {
        if (resumed.get() === host) resumed.clear()
    }

    fun stop(host: H) {
        pause(host)
        entries.find { it.host.get() === host }?.visible = false
    }

    fun remove(host: H) {
        pause(host)
        entries.removeAll { it.host.get() == null || it.host.get() === host }
    }

    /** Explicit visible Activity wins across displays; application/service contexts use foreground UI. */
    fun select(matchesContext: (H) -> Boolean): H? {
        prune()
        entries.asReversed().forEach { entry ->
            val host = entry.host.get()
            if (entry.visible && host != null && matchesContext(host)) return host
        }
        val current = resumed.get()
        if (current != null && entries.any { it.visible && it.host.get() === current }) return current
        entries.asReversed().forEach { entry ->
            if (entry.visible) entry.host.get()?.let { return it }
        }
        return null
    }

    private fun prune() {
        entries.removeAll { it.host.get() == null }
    }
}
