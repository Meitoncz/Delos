package com.limelight.binding.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** The decision logic of the "Fix gyro handling and vibrations" setting, on a fake clock and a fake handler. */
public class ControllerStateRefreshTest {
    private static final class Scheduled {
        final Runnable task;
        final long due;

        Scheduled(Runnable task, long due) {
            this.task = task;
            this.due = due;
        }
    }

    private static final class FakeHost implements ControllerStateRefresh.Host {
        boolean enabled;
        int active;
        long now;
        final List<Integer> resent = new ArrayList<>();
        final List<Scheduled> pending = new ArrayList<>();

        @Override public boolean enabled() { return enabled; }

        @Override public int activeControllers() { return active; }

        @Override public void resend(int controllerNumber) { resent.add(controllerNumber); }

        @Override public void schedule(Runnable task, long delayMs) { pending.add(new Scheduled(task, now + delayMs)); }

        @Override public void cancel(Runnable task) { pending.removeIf(s -> s.task == task); }

        void advance(long ms) {
            long target = now + ms;
            for (;;) {
                Scheduled next = pending.stream().filter(s -> s.due <= target).min(Comparator.comparingLong(s -> s.due)).orElse(null);
                if (next == null) {
                    break;
                }
                pending.remove(next);
                now = next.due;
                next.task.run();
            }
            now = target;
        }
    }

    private final FakeHost host = new FakeHost();
    private final ControllerStateRefresh refresh = new ControllerStateRefresh(host);

    @Test
    public void withTheSettingOffNothingIsEverScheduledOrSent() {
        host.enabled = false;
        host.active = 1;
        refresh.ensureRunning();
        host.advance(10_000);
        assertTrue(host.pending.isEmpty());
        assertTrue(host.resent.isEmpty());
    }

    @Test
    public void withoutMotionEventsNothingIsScheduledOrSent() {
        host.enabled = true;
        host.active = 0;
        refresh.ensureRunning();
        host.advance(10_000);
        assertTrue(host.pending.isEmpty());
        assertTrue(host.resent.isEmpty());
    }

    @Test
    public void theCurrentStateOfEveryActiveControllerIsSentEveryInterval() {
        host.enabled = true;
        host.active = (1 << 0) | (1 << 2);
        refresh.ensureRunning();
        assertTrue("nothing is sent before the first interval", host.resent.isEmpty());

        host.advance(ControllerStateRefresh.INTERVAL_MS);
        assertEquals(Arrays.asList(0, 2), host.resent);

        host.advance(3L * ControllerStateRefresh.INTERVAL_MS);
        assertEquals(Arrays.asList(0, 2, 0, 2, 0, 2, 0, 2), host.resent);
    }

    @Test
    public void callingEnsureRunningAgainDoesNotDoubleTheRate() {
        host.enabled = true;
        host.active = 1;
        refresh.ensureRunning();
        refresh.ensureRunning();
        refresh.ensureRunning();
        assertEquals(1, host.pending.size());
        host.advance(4L * ControllerStateRefresh.INTERVAL_MS);
        assertEquals(Collections.nCopies(4, 0), host.resent);
    }

    @Test
    public void itStopsByItselfWhenTheHostSwitchesMotionEventsOff() {
        host.enabled = true;
        host.active = 1;
        refresh.ensureRunning();
        host.advance(2L * ControllerStateRefresh.INTERVAL_MS);
        assertEquals(2, host.resent.size());

        host.active = 0;
        host.advance(ControllerStateRefresh.INTERVAL_MS);
        assertEquals("the tick that finds no motion sends nothing", 2, host.resent.size());
        assertTrue("and does not schedule another one", host.pending.isEmpty());

        // Motion events come back: it starts again
        host.active = 1;
        refresh.ensureRunning();
        host.advance(ControllerStateRefresh.INTERVAL_MS);
        assertEquals(3, host.resent.size());
    }

    @Test
    public void switchingTheSettingOffWhileItRunsStopsItAtTheNextTick() {
        host.enabled = true;
        host.active = 1;
        refresh.ensureRunning();
        host.advance(ControllerStateRefresh.INTERVAL_MS);
        assertEquals(1, host.resent.size());

        host.enabled = false;
        host.advance(10_000);
        assertEquals(1, host.resent.size());
        assertTrue(host.pending.isEmpty());

        host.enabled = true;
        refresh.ensureRunning();
        host.advance(ControllerStateRefresh.INTERVAL_MS);
        assertEquals(2, host.resent.size());
    }

    @Test
    public void aControllerThatJoinsLaterIsPickedUpAtTheNextTick() {
        host.enabled = true;
        host.active = 1;
        refresh.ensureRunning();
        host.advance(ControllerStateRefresh.INTERVAL_MS);
        host.active = 1 | (1 << 1);
        host.advance(ControllerStateRefresh.INTERVAL_MS);
        assertEquals(Arrays.asList(0, 0, 1), host.resent);
    }

    @Test
    public void stopCancelsTheTickAndAStoppedHandlerCannotRestartIt() {
        host.enabled = true;
        host.active = 1;
        refresh.ensureRunning();
        refresh.stop();
        assertTrue(host.pending.isEmpty());
        host.advance(10_000);
        assertTrue(host.resent.isEmpty());

        // The handler reports itself disabled once it is stopped
        host.enabled = false;
        refresh.ensureRunning();
        assertTrue(host.pending.isEmpty());
    }

    @Test
    public void aTickThatStillRunsAfterStopSendsNothing() {
        host.enabled = true;
        host.active = 1;
        refresh.ensureRunning();
        Runnable tick = host.pending.get(0).task;
        refresh.stop();
        tick.run();
        assertTrue(host.resent.isEmpty());
        assertTrue(host.pending.isEmpty());
    }
}
