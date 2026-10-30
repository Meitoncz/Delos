package com.limelight.shell.data

import java.util.concurrent.TimeUnit

/**
 * Spaces blocking provider requests at least [intervalMillis] apart across all callers of one client.
 * The sleep is interruptible so a cancelled lookup does not wait out the pacing window.
 */
internal class RequestPacer(
    private val intervalMillis: Long,
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleep: (nanos: Long) -> Unit = { TimeUnit.NANOSECONDS.sleep(it) },
) {
    private var next = nanoTime()

    @Synchronized fun await() {
        val wait = next - nanoTime()
        if (wait > 0) sleep(wait)
        next = nanoTime() + TimeUnit.MILLISECONDS.toNanos(intervalMillis)
    }
}
