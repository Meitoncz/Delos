package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VrrSupportTest {
    @Test
    public void resolveFollowsTheSettingAndOnlyAutoReadsTheDisplay() {
        // setting, reported, active
        Object[][] table = {
                {"auto", true, true}, {"auto", false, false},
                {"on", true, true}, {"on", false, true},
                {"off", true, false}, {"off", false, false},
                // an unknown or missing value behaves like the default, Auto
                {"bogus", true, true}, {"bogus", false, false}, {null, true, true}, {null, false, false},
        };
        for (Object[] row : table) {
            assertEquals(row[0] + "/" + row[1], row[2], VrrMode.resolve((String) row[0], (Boolean) row[1]));
        }
    }

    @Test
    public void notReportedBelowApi36WithoutAskingTheDisplay() {
        DisplayVrr.ArrQuery mustNotRun = () -> {
            throw new AssertionError("hasArrSupport() does not exist below API 36");
        };
        assertFalse(DisplayVrr.detect(mustNotRun, 35).reported);
        assertFalse(DisplayVrr.detect(mustNotRun, 29).reported);
    }

    @Test
    public void reportedFromApi36WhenTheDisplayHasArrSupport() {
        DisplayVrr.Result yes = DisplayVrr.detect(() -> true, 36);
        assertTrue(yes.reported);
        assertTrue(yes.source.contains("hasArrSupport"));
        assertFalse(DisplayVrr.detect(() -> false, 36).reported);
        assertTrue(DisplayVrr.detect(() -> true, 37).reported);
    }

    @Test
    public void aDisplayThatThrowsIsNotReported() {
        DisplayVrr.Result security = DisplayVrr.detect(() -> {
            throw new SecurityException("vendor");
        }, 36);
        assertFalse(security.reported);
        assertTrue(security.source.contains("failed"));
        assertFalse(DisplayVrr.detect(() -> {
            throw new NoSuchMethodError("hasArrSupport");
        }, 36).reported);
    }

    @Test
    public void resultReadsAsOneLine() {
        assertEquals("yes (Display.hasArrSupport(), Android API 36)", DisplayVrr.detect(() -> true, 36).toString());
    }
}
