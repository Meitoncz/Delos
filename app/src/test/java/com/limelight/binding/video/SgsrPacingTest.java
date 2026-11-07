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
public class SgsrPacingTest {
    private static final long MS = 1_000_000L;
    private static final long PERIOD_120_NS = 8_333_333L;

    @Test
    public void theLeadIsTheMeasuredTimePlusTheMargin() {
        assertEquals(4 * MS + SgsrPacing.MARGIN_NS, SgsrPacing.leadNs(4 * MS, 16_666_666L));
    }

    @Test
    public void anUnknownOrNonPositiveTimeGivesNoLead() {
        assertEquals(0L, SgsrPacing.leadNs(0L, PERIOD_120_NS));
        assertEquals(0L, SgsrPacing.leadNs(-3 * MS, PERIOD_120_NS));
        assertEquals(0L, SgsrPacing.leadNs(4 * MS, 0L));
    }

    @Test
    public void theLeadNeverExceedsHalfAFramePeriod() {
        assertEquals(PERIOD_120_NS / 2, SgsrPacing.leadNs(10 * MS, PERIOD_120_NS));
        assertEquals(PERIOD_120_NS / 2, SgsrPacing.leadNs(Long.MAX_VALUE / 2, PERIOD_120_NS));
    }

    @Test
    public void theReleaseMovesEarlierByTheLeadButNeverBeforeNow() {
        assertEquals(96 * MS, SgsrPacing.releaseTimeNs(100 * MS, 90 * MS, 4 * MS));
        assertEquals(98 * MS, SgsrPacing.releaseTimeNs(100 * MS, 98 * MS, 4 * MS));
        assertEquals(96 * MS, SgsrPacing.releaseTimeNs(100 * MS, 96 * MS, 4 * MS));
    }

    @Test
    public void aTargetThatIsAlreadyDueIsLeftAlone() {
        assertEquals(100 * MS, SgsrPacing.releaseTimeNs(100 * MS, 100 * MS, 4 * MS));
        assertEquals(90 * MS, SgsrPacing.releaseTimeNs(90 * MS, 100 * MS, 4 * MS));
    }

    @Test
    public void withoutALeadTheReleaseIsTheTarget() {
        assertEquals(100 * MS, SgsrPacing.releaseTimeNs(100 * MS, 90 * MS, 0L));
    }

    @Test
    public void smoothingTakesTheFirstSampleThenMovesAFifthOfTheWay() {
        assertEquals(5f, SgsrPacing.smooth(0f, 5f), 1e-6f);
        assertEquals(6f, SgsrPacing.smooth(5f, 10f), 1e-6f);
    }

    // The clock advances by half a millisecond on every read, so a wait ends when it reaches its release time
    private static long releaseTimeOfFirstFrame(long workNs) {
        long[] now = {1_000_000_000L};
        boolean[] ticking = {false};
        NanoPacer pacer = new NanoPacer(() -> {
            if (ticking[0]) now[0] += MS / 2;
            return now[0];
        });
        for (int i = 0; i < 40 && !pacer.isLocked(); i++) {
            now[0] += 16_666_666L;
            pacer.updatePacingMode(true, 60, 60f);
        }
        // The first frame is aimed one period after the lock, so it has to wait
        assertTrue(pacer.isLocked());
        pacer.setRenderWorkNs(workNs);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        NanoPacer.LatestOutput latest = new NanoPacer.LatestOutput();
        latest.index = 1;
        latest.ptsUs = 0;
        ticking[0] = true;
        pacer.waitAndDrainLatest(info, latest, new NanoPacer.OutputCallbacks() {
            @Override public int nextOutputIndex(MediaCodec.BufferInfo i, int timeoutUs) { return -1; }
            @Override public void releaseOutputBuffer(int index, boolean render) { }
        });
        return now[0];
    }

    @Test(timeout = 10000)
    public void fastVsyncReleasesAFrameEarlierByTheSgsrTimePlusMargin() {
        long plain = releaseTimeOfFirstFrame(0L);
        long withSgsr = releaseTimeOfFirstFrame(3 * MS);
        long earlier = plain - withSgsr;
        assertTrue("released " + earlier + " ns earlier", earlier >= 3 * MS && earlier <= 4 * MS);
    }
}
