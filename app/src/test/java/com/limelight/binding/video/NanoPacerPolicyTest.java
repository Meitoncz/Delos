package com.limelight.binding.video;

import org.junit.Test;
import static org.junit.Assert.*;

public class NanoPacerPolicyTest {
    @Test public void locksOnMatchingHighRateAndResetsWhenDisabled() {
        long[] now={1_000_000_000L};
        NanoPacer pacer=new NanoPacer(() -> now[0]);
        for (int i=0;i<20;i++) {
            now[0]+=8_333_333;
            pacer.updatePacingMode(true,120,119.88f);
        }
        assertTrue(pacer.isLocked());
        pacer.updatePacingMode(false,120,120f);
        assertFalse(pacer.isLocked());
    }
    @Test public void lowerFpsStreamLocksAtStreamRateOnFasterDisplay() {
        long[] now={1_000_000_000L};
        NanoPacer pacer=new NanoPacer(() -> now[0]);
        for (int i=0;i<20;i++) {
            now[0]+=33_333_333;
            pacer.updatePacingMode(true,30,120f);
        }
        assertTrue(pacer.isLocked());
        pacer.updatePacingMode(true,30,Float.NaN);
        assertFalse(pacer.isLocked());
    }
    @Test public void fastIrregularArrivalDoesNotImmediatelyEnablePacing() {
        long[] now={1_000_000_000L};
        NanoPacer pacer=new NanoPacer(() -> now[0]);
        for (int i=0;i<20;i++) {
            now[0]+=7_000_000;
            pacer.updatePacingMode(true,30,120f);
        }
        assertFalse(pacer.isLocked());
    }
}
