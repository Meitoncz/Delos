package com.limelight.binding.input;

import com.limelight.nvstream.jni.MoonBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Counts the motion events of every controller and turns them into one {@code MotionSend} line per second and pad
 * (docs/CONTROLLER_STATE.md): how many sensor callbacks arrived, and how many events were handed to the connection
 * (accepted, or refused by it: the host does not take motion events, or the input queue is full). The connection
 * merges events that wait in its queue, so what actually leaves the device is at most the handed-over rate.
 *
 * The sensor threads only increment, the logging thread reads and resets; no lock is needed.
 */
final class MotionSendRate {
    private static final int PADS = 16;
    private static final int TYPES = 2; // accelerometer, gyro: MoonBridge.LI_MOTION_TYPE_* minus one

    private final AtomicIntegerArray callbacks = new AtomicIntegerArray(PADS * TYPES);
    private final AtomicIntegerArray accepted = new AtomicIntegerArray(PADS * TYPES);
    private final AtomicIntegerArray refused = new AtomicIntegerArray(PADS * TYPES);

    private static int slot(int pad, byte motionType) {
        int type = motionType - 1;
        if (type < 0 || type >= TYPES) {
            type = 0;
        }
        return (pad & (PADS - 1)) * TYPES + type;
    }

    /** A sensor callback for [pad], before the duplicate and smoothing steps. */
    void onSensorCallback(int pad, byte motionType) {
        callbacks.incrementAndGet(slot(pad, motionType));
    }

    /** An event handed to the connection; [result] is what NvConnection.sendControllerMotionEvent returned (0 is accepted). */
    void onHandedOver(int pad, byte motionType, int result) {
        (result == 0 ? accepted : refused).incrementAndGet(slot(pad, motionType));
    }

    /**
     * The lines for the window that just ended, and a reset of the counters. A pad gets a line when it counted
     * anything or when a listener is registered for it (a sensor that went quiet shows as {@code 0/s}).
     *
     * @param windowMs the real length of the window
     * @param gyroActive bit n set: a gyro listener is registered for pad n
     * @param accelActive bit n set: an accelerometer listener is registered for pad n
     */
    List<String> drainLines(long windowMs, int gyroActive, int accelActive) {
        List<String> lines = new ArrayList<>();
        long window = Math.max(1, windowMs);
        for (int pad = 0; pad < PADS; pad++) {
            int gyro = slot(pad, (byte) MoonBridge.LI_MOTION_TYPE_GYRO);
            int accel = slot(pad, (byte) MoonBridge.LI_MOTION_TYPE_ACCEL);
            int gyroCallbacks = callbacks.getAndSet(gyro, 0), accelCallbacks = callbacks.getAndSet(accel, 0);
            int gyroAccepted = accepted.getAndSet(gyro, 0), accelAccepted = accepted.getAndSet(accel, 0);
            int gyroRefused = refused.getAndSet(gyro, 0), accelRefused = refused.getAndSet(accel, 0);
            boolean listening = ((gyroActive | accelActive) & (1 << pad)) != 0;
            if (!listening && gyroCallbacks + accelCallbacks + gyroAccepted + accelAccepted + gyroRefused + accelRefused == 0) {
                continue;
            }
            lines.add(String.format(Locale.ROOT,
                    "MotionSend gyro %d/s accel %d/s pad %d (sensor callbacks gyro %d/s accel %d/s, refused gyro %d accel %d)",
                    perSecond(gyroAccepted, window), perSecond(accelAccepted, window), pad,
                    perSecond(gyroCallbacks, window), perSecond(accelCallbacks, window), gyroRefused, accelRefused));
        }
        return lines;
    }

    private static long perSecond(int count, long windowMs) {
        return Math.round(count * 1000.0 / windowMs);
    }
}
