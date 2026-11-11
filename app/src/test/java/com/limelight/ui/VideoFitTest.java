package com.limelight.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;

/** Where the video lands on every screen shape of docs/HANDHELD_DEVICES.md, for each scale mode and a few stream shapes. */
public class VideoFitTest {
    /** The measuring StreamContainer and StreamView did before VideoFit took it over. */
    private static int[] legacyMeasure(int widthSize, int heightSize, double desiredAspectRatio, boolean fillDisplay) {
        int measuredHeight, measuredWidth;
        if (fillDisplay) {
            if (widthSize < heightSize * desiredAspectRatio) {
                measuredHeight = heightSize;
                measuredWidth = (int) (heightSize * desiredAspectRatio);
            } else {
                measuredWidth = widthSize;
                measuredHeight = (int) (widthSize / desiredAspectRatio);
            }
        } else {
            if (widthSize > heightSize * desiredAspectRatio) {
                measuredHeight = heightSize;
                measuredWidth = (int) (measuredHeight * desiredAspectRatio);
            } else {
                measuredWidth = widthSize;
                measuredHeight = (int) (measuredWidth / desiredAspectRatio);
            }
        }
        return new int[] { measuredWidth, measuredHeight };
    }

    /** name, width, height of the screen */
    private static final Object[][] SCREENS = {
            {"1:1 RG Cube", 720, 720},
            {"5:4", 1280, 1024},
            {"31:27 Thor bottom", 1240, 1080},
            {"4:3 RG 406H", 960, 720},
            {"4:3 RG 476H", 1280, 960},
            {"3:2 Pocket Micro", 960, 640},
            {"16:10", 1920, 1200},
            {"16:9 1080p", 1920, 1080},
            {"16:9 Retroid 4 Pro", 1334, 750},
            {"20:9 Razer Edge", 2400, 1080},
            {"21:9", 2520, 1080},
    };

    /** name, width, height of the stream */
    private static final Object[][] STREAMS = {
            {"16:9 1080p", 1920, 1080},
            {"16:9 720p", 1280, 720},
            {"16:10", 1920, 1200},
            {"4:3", 1280, 960},
            {"21:9", 2560, 1080},
            {"1:1", 1080, 1080},
            {"9:16 (inverted)", 1080, 1920},
    };

    private static double aspect(int w, int h) {
        return (double) w / (double) h;
    }

    @Test
    public void sixteenByNineOnSixteenByNineFillsTheScreenInBothModes() {
        assertArrayEquals(new int[] { 1920, 1080 }, VideoFit.measure(1920, 1080, aspect(1920, 1080), false));
        assertArrayEquals(new int[] { 1920, 1080 }, VideoFit.measure(1920, 1080, aspect(1920, 1080), true));
        assertArrayEquals(new int[] { 1280, 720 }, VideoFit.measure(1280, 720, aspect(1920, 1080), false));
        assertArrayEquals(new int[] { 2560, 1440 }, VideoFit.measure(2560, 1440, aspect(1280, 720), true));
    }

    @Test
    public void everyScreenAndStreamMeasuresAsBefore() {
        for (Object[] screen : SCREENS) {
            for (Object[] stream : STREAMS) {
                double a = aspect((Integer) stream[1], (Integer) stream[2]);
                for (boolean fill : new boolean[] { false, true }) {
                    assertArrayEquals(screen[0] + " " + stream[0] + " fill=" + fill,
                            legacyMeasure((Integer) screen[1], (Integer) screen[2], a, fill),
                            VideoFit.measure((Integer) screen[1], (Integer) screen[2], a, fill));
                }
            }
        }
    }

    @Test
    public void fitShowsAllOfTheVideoInsideTheScreenWithBarsOnTheLongSide() {
        for (Object[] screen : SCREENS) {
            int w = (Integer) screen[1];
            int h = (Integer) screen[2];
            for (Object[] stream : STREAMS) {
                double a = aspect((Integer) stream[1], (Integer) stream[2]);
                int[] size = VideoFit.measure(w, h, a, false);
                String what = screen[0] + " showing " + stream[0];
                assertTrue(what, size[0] <= w && size[1] <= h);
                // touches the screen on one axis
                assertTrue(what, size[0] == w || size[1] == h || Math.abs(size[1] - h) <= 1);
                // keeps the stream's shape (truncation: one pixel)
                assertEquals(what, a, (double) size[0] / size[1], 1.5 / Math.min(size[0], size[1]) * a + 1e-9);
            }
        }
    }

    @Test
    public void fillCoversTheWholeScreen() {
        for (Object[] screen : SCREENS) {
            int w = (Integer) screen[1];
            int h = (Integer) screen[2];
            for (Object[] stream : STREAMS) {
                double a = aspect((Integer) stream[1], (Integer) stream[2]);
                int[] size = VideoFit.measure(w, h, a, true);
                String what = screen[0] + " showing " + stream[0];
                assertTrue(what, size[0] >= w - 1 && size[1] >= h - 1);
                int[] visible = VideoFit.visibleRect(w, h, a, true, false);
                assertArrayEquals(what, new int[] { 0, 0, w, h }, new int[] { visible[0], visible[1], visible[2], visible[3] });
            }
        }
    }

    @Test
    public void sixteenByNineStreamOnASquarePanelIsLetterboxedAndFillCropsTheSides() {
        // RG Cube, 720x720: 720x405 in the middle, 157 / 158 px bars; Fill shows the middle 405 px of the video's 720 high... of its 1280 wide
        assertArrayEquals(new int[] { 720, 405 }, VideoFit.measure(720, 720, aspect(1920, 1080), false));
        assertArrayEquals(new int[] { 0, 157, 720, 562 }, VideoFit.visibleRect(720, 720, aspect(1920, 1080), false, false));
        assertArrayEquals(new int[] { 1280, 720 }, VideoFit.measure(720, 720, aspect(1920, 1080), true));
        // The container is 1280 wide and the parent crops 280 px off each side: 720 of 1280 (56 %) of the picture stays
        assertArrayEquals(new int[] { 0, 0, 720, 720 }, VideoFit.visibleRect(720, 720, aspect(1920, 1080), true, false));
    }

    @Test
    public void sixteenByNineStreamOnFourByThreeIsLetterboxed() {
        assertArrayEquals(new int[] { 960, 540 }, VideoFit.measure(960, 720, aspect(1920, 1080), false));
        assertArrayEquals(new int[] { 0, 90, 960, 630 }, VideoFit.visibleRect(960, 720, aspect(1920, 1080), false, false));
        // Top center puts the picture against the top edge instead
        assertArrayEquals(new int[] { 0, 0, 960, 540 }, VideoFit.visibleRect(960, 720, aspect(1920, 1080), false, true));
        assertArrayEquals(new int[] { 960, 720 }, VideoFit.measure(960, 720, aspect(1280, 960), false));
    }

    @Test
    public void sixteenByNineStreamOnTwentyOneByNineIsPillarboxed() {
        assertArrayEquals(new int[] { 1920, 1080 }, VideoFit.measure(2520, 1080, aspect(1920, 1080), false));
        assertArrayEquals(new int[] { 300, 0, 2220, 1080 }, VideoFit.visibleRect(2520, 1080, aspect(1920, 1080), false, false));
        // Fill: 2520 x 1417, 168 px cropped off the top and the bottom
        assertArrayEquals(new int[] { 2520, 1417 }, VideoFit.measure(2520, 1080, aspect(1920, 1080), true));
    }

    @Test
    public void aPortraitStreamOnALandscapePanelIsPillarboxed() {
        // The inverted stream of an auto-orientation start: 1080x1920 on 1920x1080 gives a 607 wide strip
        assertArrayEquals(new int[] { 607, 1080 }, VideoFit.measure(1920, 1080, aspect(1080, 1920), false));
    }

    @Test
    public void squarishThresholdIsOnePointThree() {
        // 1:1, 5:4 (1.25), 31:27 (1.148) are squarish, in either orientation; 4:3 (1.333) and everything wider are not
        assertTrue(PreferenceConfiguration.isSquarishScreen(720, 720));
        assertTrue(PreferenceConfiguration.isSquarishScreen(1280, 1024));
        assertTrue(PreferenceConfiguration.isSquarishScreen(1024, 1280));
        assertTrue(PreferenceConfiguration.isSquarishScreen(1240, 1080));
        assertTrue(PreferenceConfiguration.isSquarishScreen(1080, 1240));
        assertTrue(!PreferenceConfiguration.isSquarishScreen(960, 720));
        assertTrue(!PreferenceConfiguration.isSquarishScreen(720, 960));
        assertTrue(!PreferenceConfiguration.isSquarishScreen(1280, 960));
        assertTrue(!PreferenceConfiguration.isSquarishScreen(1024, 768));
        assertTrue(!PreferenceConfiguration.isSquarishScreen(960, 640));
        assertTrue(!PreferenceConfiguration.isSquarishScreen(1920, 1200));
        assertTrue(!PreferenceConfiguration.isSquarishScreen(1920, 1080));
        assertTrue(!PreferenceConfiguration.isSquarishScreen(2520, 1080));
        // 1.3 itself is not squarish (the comparison is strict)
        assertTrue(!PreferenceConfiguration.isSquarishScreen(1300, 1000));
        assertTrue(PreferenceConfiguration.isSquarishScreen(1299, 1000));
    }
}
