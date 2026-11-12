package com.limelight.binding.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Test;

import java.util.List;

public class MotionSendRateTest {
    private final MotionSendRate rate = new MotionSendRate();

    private void events(int pad, byte type, int callbacks, int accepted, int refused) {
        for (int i = 0; i < callbacks; i++) {
            rate.onSensorCallback(pad, type);
        }
        for (int i = 0; i < accepted; i++) {
            rate.onHandedOver(pad, type, 0);
        }
        for (int i = 0; i < refused; i++) {
            rate.onHandedOver(pad, type, MoonBridge.LI_ERR_UNSUPPORTED);
        }
    }

    @Test
    public void theLineHasTheDocumentedFormat() {
        events(0, MoonBridge.LI_MOTION_TYPE_GYRO, 200, 198, 0);
        events(0, MoonBridge.LI_MOTION_TYPE_ACCEL, 199, 199, 0);
        List<String> lines = rate.drainLines(1000, 1, 1);
        assertEquals(1, lines.size());
        assertEquals("MotionSend gyro 198/s accel 199/s pad 0 (sensor callbacks gyro 200/s accel 199/s, refused gyro 0 accel 0)", lines.get(0));
    }

    @Test
    public void ratesFollowTheRealLengthOfTheWindow() {
        events(0, MoonBridge.LI_MOTION_TYPE_GYRO, 100, 100, 0);
        String line = rate.drainLines(500, 1, 0).get(0);
        assertTrue(line, line.startsWith("MotionSend gyro 200/s accel 0/s pad 0 (sensor callbacks gyro 200/s"));
    }

    @Test
    public void eventsTheConnectionRefusesAreCountedSeparately() {
        events(0, MoonBridge.LI_MOTION_TYPE_GYRO, 50, 10, 40);
        String line = rate.drainLines(1000, 1, 0).get(0);
        assertEquals("MotionSend gyro 10/s accel 0/s pad 0 (sensor callbacks gyro 50/s accel 0/s, refused gyro 40 accel 0)", line);
    }

    @Test
    public void aDrainResetsTheCounters() {
        events(0, MoonBridge.LI_MOTION_TYPE_GYRO, 20, 20, 0);
        rate.drainLines(1000, 1, 0);
        assertEquals("MotionSend gyro 0/s accel 0/s pad 0 (sensor callbacks gyro 0/s accel 0/s, refused gyro 0 accel 0)",
                rate.drainLines(1000, 1, 0).get(0));
    }

    @Test
    public void aListeningPadThatWentQuietShowsZeroAndAnIdlePadShowsNothing() {
        assertTrue(rate.drainLines(1000, 0, 0).isEmpty());
        List<String> lines = rate.drainLines(1000, 0, 1 << 2);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0), lines.get(0).contains(" pad 2 "));
    }

    @Test
    public void everyPadWithEventsGetsItsOwnLine() {
        events(0, MoonBridge.LI_MOTION_TYPE_GYRO, 5, 5, 0);
        events(3, MoonBridge.LI_MOTION_TYPE_ACCEL, 7, 7, 0);
        List<String> lines = rate.drainLines(1000, 0, 0);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0), lines.get(0).startsWith("MotionSend gyro 5/s accel 0/s pad 0 "));
        assertTrue(lines.get(1), lines.get(1).startsWith("MotionSend gyro 0/s accel 7/s pad 3 "));
    }

    @Test
    public void aZeroLengthWindowCannotDivideByZero() {
        events(0, MoonBridge.LI_MOTION_TYPE_GYRO, 1, 1, 0);
        assertEquals(1, rate.drainLines(0, 1, 0).size());
    }
}
