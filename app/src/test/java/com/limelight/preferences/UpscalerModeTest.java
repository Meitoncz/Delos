package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpscalerModeTest {
    @Test
    public void storedValuesOfOlderBuildsStillMapToTheSameModes() {
        assertEquals(UpscalerMode.STANDARD, UpscalerMode.fromValue("standard"));
        assertEquals(UpscalerMode.HIGH, UpscalerMode.fromValue("high"));
        assertEquals(UpscalerMode.VIDEO, UpscalerMode.fromValue("temporal"));
        assertEquals(UpscalerMode.VIDEO_QUALITY, UpscalerMode.fromValue("temporal-quality"));
        assertEquals(UpscalerMode.SHARPEN_ONLY, UpscalerMode.fromValue("sharpen"));
    }

    @Test
    public void unknownOrMissingValuesReadAsStandard() {
        assertEquals(UpscalerMode.STANDARD, UpscalerMode.fromValue(null));
        assertEquals(UpscalerMode.STANDARD, UpscalerMode.fromValue(""));
        assertEquals(UpscalerMode.STANDARD, UpscalerMode.fromValue("Sharpen"));
        assertEquals(UpscalerMode.STANDARD, UpscalerMode.fromValue("unknown-future-mode"));
    }

    @Test
    public void indicesAreTheStreamMenuPositionsAndNewModesAreAppended() {
        UpscalerMode[] expected = {UpscalerMode.STANDARD, UpscalerMode.HIGH, UpscalerMode.VIDEO,
                UpscalerMode.VIDEO_QUALITY, UpscalerMode.SHARPEN_ONLY};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], UpscalerMode.values()[i]);
            assertEquals(i, expected[i].index());
            assertEquals(expected[i], UpscalerMode.fromIndex(i));
        }
        assertEquals(expected.length, UpscalerMode.values().length);
    }

    @Test
    public void indexIsClampedIntoTheList() {
        assertEquals(UpscalerMode.STANDARD, UpscalerMode.fromIndex(-4));
        assertEquals(UpscalerMode.SHARPEN_ONLY, UpscalerMode.fromIndex(99));
    }

    @Test
    public void valueRoundTripsAndValuesAreDistinct() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (UpscalerMode mode : UpscalerMode.values()) {
            assertEquals(mode, UpscalerMode.fromValue(mode.value()));
            assertTrue(seen.add(mode.value()));
        }
    }

    @Test
    public void onlySharpenOnlyKeepsTheStreamSize() {
        for (UpscalerMode mode : UpscalerMode.values()) {
            assertEquals(mode != UpscalerMode.SHARPEN_ONLY, mode.scales());
        }
        assertFalse(UpscalerMode.SHARPEN_ONLY.isTemporal());
        assertFalse(UpscalerMode.STANDARD.isTemporal());
        assertTrue(UpscalerMode.VIDEO.isTemporal());
        assertTrue(UpscalerMode.VIDEO_QUALITY.isTemporal());
        assertEquals("Sharpen only", UpscalerMode.SHARPEN_ONLY.label());
    }
}
