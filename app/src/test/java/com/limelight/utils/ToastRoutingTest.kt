package com.limelight.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ToastRoutingTest {
    @Test
    fun durationHintsAreTheStreamsShortAndLongToastTimes() {
        assertEquals(4000L, toastDurationMs(0))
        assertEquals(6500L, toastDurationMs(1))
    }

    @Test
    fun aStartingActivityReceivesItsEarlyToastBeforeResume() {
        val routing = ToastRouting<Any>()
        val shell = Any()
        val game = Any()
        routing.register(shell)
        routing.resume(shell)
        routing.register(game)

        assertSame(game, routing.select { it === game })
    }

    @Test
    fun contextMatchedVisibleDisplayWinsEvenWhenAnotherDisplayResumedLast() {
        val routing = ToastRouting<Any>()
        val stream = Any()
        val controls = Any()
        routing.register(stream)
        routing.resume(stream)
        routing.register(controls)
        routing.resume(controls)

        assertSame(stream, routing.select { it === stream })
        assertSame(controls, routing.select { it === controls })
        assertSame(controls, routing.select { false }) // Application/service context.
    }

    @Test
    fun aLatePauseOrDestroyCannotClearTheNewForegroundOwner() {
        val routing = ToastRouting<Any>()
        val old = Any()
        val current = Any()
        routing.register(old)
        routing.resume(old)
        routing.register(current)
        routing.resume(current)
        routing.pause(old)

        assertSame(current, routing.select { false })
        routing.remove(old)
        assertSame(current, routing.select { false })
    }

    @Test
    fun pausedButVisibleActivityKeepsShutdownMessagesUntilStop() {
        val routing = ToastRouting<Any>()
        val game = Any()
        routing.register(game)
        routing.resume(game)
        routing.pause(game)

        assertSame(game, routing.select { it === game })
        assertSame(game, routing.select { false })
        routing.stop(game)
        assertNull(routing.select { it === game })
    }

    @Test
    fun stoppedContextUsesForegroundUiRatherThanItsHiddenHostOrSystemFallback() {
        val routing = ToastRouting<Any>()
        val old = Any()
        val current = Any()
        routing.register(old)
        routing.resume(old)
        routing.stop(old)
        routing.register(current)
        routing.resume(current)

        assertSame(current, routing.select { it === old })
        routing.remove(current)
        assertNull(routing.select { it === old })
        routing.start(old)
        assertSame(old, routing.select { it === old })
    }

    @Test
    fun fallbackIsSelectedOnlyWithoutAnyVisibleHost() {
        val routing = ToastRouting<Any>()
        assertNull(routing.select { false })
        val host = Any()
        routing.register(host)
        assertSame(host, routing.select { false })
        routing.stop(host)
        assertNull(routing.select { false })
        routing.resume(host)
        assertSame(host, routing.select { false })
        routing.remove(host)
        assertNull(routing.select { false })
    }
}
