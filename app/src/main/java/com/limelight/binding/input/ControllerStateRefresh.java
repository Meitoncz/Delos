package com.limelight.binding.input;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The optional "Fix gyro handling and vibrations" setting (docs/CONTROLLER_STATE.md).
 *
 * While it is on and the host has asked for motion events of a controller, the current state of that controller is
 * sent to the host again every {@link #INTERVAL_MS}. The packet carries exactly the state the user holds right now (the
 * same buttons, triggers and sticks the previous packet had, or the newer ones if something changed in between): nothing
 * is added, rounded or invented, and when the setting is off nothing runs and nothing is scheduled.
 *
 * It exists because, with the motion sensors on, the host was seen to keep a button as pressed after the client had
 * sent its release (the game kept vibrating and kept aiming with the gyro) until the next state packet arrived. A state
 * packet the host processes late or loses then corrects itself within one interval instead of waiting for a stick to move.
 *
 * Pure Java: the platform (the handler, the controller contexts, the preference) is behind {@link Host}.
 */
final class ControllerStateRefresh {
    static final int INTERVAL_MS = 50;

    interface Host {
        /** The setting is on and the handler is running. Read at every tick, so a live change takes effect at once. */
        boolean enabled();

        /** Bit n set: the host receives motion events of controller n and its state can be sent (no mouse emulation). */
        int activeControllers();

        /** Sends the current aggregated state of controller [controllerNumber]; unchanged input, no side effects. */
        void resend(int controllerNumber);

        /** Runs [task] on the thread that handles input events after [delayMs]. */
        void schedule(Runnable task, long delayMs);

        void cancel(Runnable task);
    }

    private final Host host;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final Runnable tick = this::tick;

    ControllerStateRefresh(Host host) {
        this.host = host;
    }

    /**
     * Starts the refresh when it is on and a controller has motion events active. Safe from any thread and cheap to
     * call again: call it whenever motion events are switched on or the setting changes.
     */
    void ensureRunning() {
        if (host.enabled() && host.activeControllers() != 0 && scheduled.compareAndSet(false, true)) {
            host.schedule(tick, INTERVAL_MS);
        }
    }

    /** Stops the refresh for good (stream teardown); a later [ensureRunning] starts it again. */
    void stop() {
        scheduled.set(false);
        host.cancel(tick);
    }

    private void tick() {
        if (!scheduled.get()) {
            return;
        }
        int active = host.enabled() ? host.activeControllers() : 0;
        if (active == 0) {
            // Let go first, then look again: motion switched on between the read above and here would find
            // the flag still set and not schedule anything.
            scheduled.set(false);
            ensureRunning();
            return;
        }
        for (int controller = 0; controller < Integer.SIZE; controller++) {
            if ((active & (1 << controller)) != 0) {
                host.resend(controller);
            }
        }
        host.schedule(tick, INTERVAL_MS);
    }
}
