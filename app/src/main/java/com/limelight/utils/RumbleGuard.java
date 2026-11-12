package com.limelight.utils;

/**
 * Pure rules that keep a host rumble from outliving what the game meant. The host (Sunshine and its forks) sends a
 * rumble packet only when the game sets the motors and never repeats it, so nothing but these two rules ends a
 * value that the host did not clear:
 * <ul>
 * <li>a dead zone: a motor value the game left behind that is far too weak for a real pad's motor to spin at
 * (a decay tail such as 4 of 255) is silence, so it is never played, however a profile's floor would raise it;</li>
 * <li>a hold limit: a rumble plays at most {@link #HOLD_MS} after the last host event; the next event renews it.
 * The vibration effect itself is no longer than that, and the playback's watchdog cancels at the same moment (that
 * one is what ends a repeating waveform).</li>
 * </ul>
 */
public final class RumbleGuard {
    /**
     * A host motor value whose most significant byte (the only one the vibrators use) is below this is silence
     * (6 % of full strength). The evidence is a stuck vibration on the Odin from a final host value of 4 of 255.
     */
    public static final int DEAD_ZONE = 16;

    /**
     * The longest a rumble plays without a renewing host event. A game that holds one unchanging value for longer
     * without the host repeating it is cut off here (it was 60 s before): hosts do not repeat held rumble.
     */
    public static final long HOLD_MS = 5000;

    private RumbleGuard() {
    }

    /** The motor value the vibrators are driven with: [raw] (unsigned 16 bit), or 0 inside the dead zone. */
    public static short gate(short raw) {
        return ((raw >> 8) & 0xFF) < DEAD_ZONE ? 0 : raw;
    }

    /**
     * Milliseconds from [nowMs] until a rumble last renewed at [renewedAtMs] has to stop (0 when overdue), or
     * {@link Long#MAX_VALUE} while nothing vibrates ([active] false) and so nothing has to be watched.
     */
    public static long holdDelay(long nowMs, long renewedAtMs, boolean active) {
        if (!active) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, renewedAtMs + HOLD_MS - nowMs);
    }

    /** Whether a rumble last renewed at [renewedAtMs] has played its {@link #HOLD_MS} and must be cancelled. */
    public static boolean holdExpired(long nowMs, long renewedAtMs, boolean active) {
        return holdDelay(nowMs, renewedAtMs, active) == 0;
    }
}
