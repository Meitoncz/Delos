package com.limelight.binding.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Test;

public class ControllerSendLogTest {
    private final ControllerSendLog log = new ControllerSendLog();

    private String send(int pad, int buttons, int lt, int rt, int lx, int ly, int rx, int ry, long nowMs) {
        return log.onSend(pad, buttons, (byte) lt, (byte) rt, (short) lx, (short) ly, (short) rx, (short) ry, nowMs);
    }

    @Test
    public void theFirstPacketOfAPadIsLoggedInTheDocumentedFormat() {
        assertEquals("ControllerSend #1 buttons=0x0200 lt=0 rt=0 lx=0 ly=0 rx=0 ry=0 pad 0",
                send(0, ControllerPacket.RB_FLAG, 0, 0, 0, 0, 0, 0, 1000));
    }

    @Test
    public void triggersAreLoggedAsTheUnsignedByteTheHostReceives() {
        assertEquals("ControllerSend #1 buttons=0x0000 lt=255 rt=200 lx=-32768 ly=32767 rx=-1 ry=1 pad 3",
                send(3, 0, 255, 200, -32768, 32767, -1, 1, 1000));
    }

    @Test
    public void aReleaseIsAlwaysLoggedEvenRightAfterAPress() {
        assertNotNull(send(0, ControllerPacket.RB_FLAG, 0, 0, 0, 0, 0, 0, 1000));
        String release = send(0, 0, 0, 0, 0, 0, 0, 0, 1001);
        assertNotNull(release);
        assertTrue(release, release.startsWith("ControllerSend #2 buttons=0x0000 "));
    }

    @Test
    public void aTriggerChangeIsLikeAButtonChange() {
        send(0, 0, 0, 200, 0, 0, 0, 0, 1000);
        assertNotNull(send(0, 0, 0, 0, 0, 0, 0, 0, 1001));
        assertNotNull(send(0, 0, 90, 0, 0, 0, 0, 0, 1002));
    }

    @Test
    public void identicalPacketsAreCountedAndEveryFiftiethIsLogged() {
        assertNotNull(send(0, ControllerPacket.A_FLAG, 0, 0, 0, 0, 0, 0, 1000));
        for (int i = 1; i < ControllerSendLog.IDENTICAL_LINE_INTERVAL; i++) {
            assertNull("identical call " + i, send(0, ControllerPacket.A_FLAG, 0, 0, 0, 0, 0, 0, 1000 + i));
        }
        assertEquals("ControllerSend #51 buttons=0x1000 lt=0 rt=0 lx=0 ly=0 rx=0 ry=0 pad 0 identical=50",
                send(0, ControllerPacket.A_FLAG, 0, 0, 0, 0, 0, 0, 2000));
        for (int i = 1; i < ControllerSendLog.IDENTICAL_LINE_INTERVAL; i++) {
            assertNull(send(0, ControllerPacket.A_FLAG, 0, 0, 0, 0, 0, 0, 2000 + i));
        }
        String second = send(0, ControllerPacket.A_FLAG, 0, 0, 0, 0, 0, 0, 3000);
        assertTrue(second, second.endsWith(" identical=100"));
    }

    @Test
    public void aChangeRestartsTheIdenticalCount() {
        send(0, 0, 0, 0, 0, 0, 0, 0, 1000);
        for (int i = 0; i < 30; i++) {
            send(0, 0, 0, 0, 0, 0, 0, 0, 1000 + i);
        }
        send(0, ControllerPacket.B_FLAG, 0, 0, 0, 0, 0, 0, 2000);
        for (int i = 1; i < ControllerSendLog.IDENTICAL_LINE_INTERVAL; i++) {
            assertNull(send(0, ControllerPacket.B_FLAG, 0, 0, 0, 0, 0, 0, 2000 + i));
        }
        assertTrue(send(0, ControllerPacket.B_FLAG, 0, 0, 0, 0, 0, 0, 3000).endsWith(" identical=50"));
    }

    @Test
    public void stickOnlyChangesAreLoggedAtMostEveryIntervalAndCountTheOnesLeftOut() {
        assertNotNull(send(0, 0, 0, 0, 0, 0, 0, 0, 1000));
        assertNull(send(0, 0, 0, 0, 100, 0, 0, 0, 1010));
        assertNull(send(0, 0, 0, 0, 200, 0, 0, 0, 1020));
        String line = send(0, 0, 0, 0, 300, 0, 0, 0, 1000 + ControllerSendLog.STICK_LINE_INTERVAL_MS);
        assertEquals("ControllerSend #4 buttons=0x0000 lt=0 rt=0 lx=300 ly=0 rx=0 ry=0 pad 0 (+2 stick-only packets not logged)", line);
        // the interval counts from the line, not from the packet before it
        assertNull(send(0, 0, 0, 0, 400, 0, 0, 0, 1000 + ControllerSendLog.STICK_LINE_INTERVAL_MS + 1));
    }

    @Test
    public void aButtonChangeBetweenStickPacketsIsNeverLeftOutAndReportsTheStateOfThatPacket() {
        send(0, 0, 0, 0, 0, 0, 0, 0, 1000);
        assertNull(send(0, 0, 0, 0, 0, 0, 5000, 0, 1005));
        String release = send(0, ControllerPacket.RB_FLAG, 0, 0, 0, 0, 5000, 0, 1010);
        assertEquals("ControllerSend #3 buttons=0x0200 lt=0 rt=0 lx=0 ly=0 rx=5000 ry=0 pad 0 (+1 stick-only packets not logged)", release);
    }

    @Test
    public void padsAreTrackedSeparatelyAndTheNumberCountsEveryCallOfAllPads() {
        assertEquals("ControllerSend #1 buttons=0x0000 lt=0 rt=0 lx=0 ly=0 rx=0 ry=0 pad 0", send(0, 0, 0, 0, 0, 0, 0, 0, 1000));
        assertNull(send(0, 0, 0, 0, 0, 0, 0, 0, 1001));
        assertEquals("ControllerSend #3 buttons=0x0000 lt=0 rt=0 lx=0 ly=0 rx=0 ry=0 pad 1", send(1, 0, 0, 0, 0, 0, 0, 0, 1002));
    }

    @Test
    public void describeButtonsNamesTheFlags() {
        assertEquals("none", ControllerSendLog.describeButtons(0));
        assertEquals("A+RB", ControllerSendLog.describeButtons(ControllerPacket.A_FLAG | ControllerPacket.RB_FLAG));
        assertEquals("LB+BACK", ControllerSendLog.describeButtons(ControllerPacket.BACK_FLAG | ControllerPacket.LB_FLAG));
        assertEquals("TOUCHPAD", ControllerSendLog.describeButtons(ControllerPacket.TOUCHPAD_FLAG));
        assertEquals("0x80000000", ControllerSendLog.describeButtons(0x80000000));
    }

    @Test
    public void theArrivalNamesTheTypeAndTheCapabilities() {
        assertEquals("XBOX", ControllerSendLog.controllerType(MoonBridge.LI_CTYPE_XBOX));
        assertEquals("UNKNOWN", ControllerSendLog.controllerType(MoonBridge.LI_CTYPE_UNKNOWN));
        assertEquals("PS", ControllerSendLog.controllerType(MoonBridge.LI_CTYPE_PS));
        assertEquals("type 9", ControllerSendLog.controllerType((byte) 9));
        assertEquals("none", ControllerSendLog.capabilities((short) 0));
        assertEquals("ANALOG_TRIGGERS+RUMBLE+ACCEL+GYRO", ControllerSendLog.capabilities(
                (short) (MoonBridge.LI_CCAP_ANALOG_TRIGGERS | MoonBridge.LI_CCAP_RUMBLE | MoonBridge.LI_CCAP_ACCEL | MoonBridge.LI_CCAP_GYRO)));
    }
}
