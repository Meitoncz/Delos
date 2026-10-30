package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.media.MediaCodec;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class NanoPacerLateFrameTest {
    private static final long PERIOD_NS = 16_666_666L;

    private static final NanoPacer.OutputCallbacks NO_MORE_OUTPUT = new NanoPacer.OutputCallbacks() {
        @Override public int nextOutputIndex(MediaCodec.BufferInfo info, int timeoutUs) { return -1; }
        @Override public void releaseOutputBuffer(int index, boolean render) { }
    };

    // The clock never moves while waiting, so a frame that is made to wait would block forever.
    @Test(timeout = 5000)
    public void aFrameThatIsAFramePeriodLateIsPresentedAtOnceAfterAHitch() {
        long[] now = {1_000_000_000L};
        NanoPacer pacer = new NanoPacer(() -> now[0]);
        for (int i = 0; i < 20; i++) {
            now[0] += PERIOD_NS;
            pacer.updatePacingMode(true, 60, 60f);
        }
        assertTrue(pacer.isLocked());

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        NanoPacer.LatestOutput latest = new NanoPacer.LatestOutput();

        // A frame on time starts the PTS to clock mapping
        now[0] += 2 * PERIOD_NS;
        latest.index = 1;
        latest.ptsUs = 0;
        pacer.waitAndDrainLatest(info, latest, NO_MORE_OUTPUT);

        // The next frame comes after a hitch of several periods
        now[0] += 6 * PERIOD_NS;
        latest.index = 2;
        latest.ptsUs = PERIOD_NS / 1000;
        assertEquals(0, pacer.waitAndDrainLatest(info, latest, NO_MORE_OUTPUT));
        assertEquals(2, latest.index);
    }
}
