package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;

import com.limelight.preferences.PreferenceConfiguration.ScaleMode;

import org.junit.Test;

/** The window decisions DelosStreamActivity made before StreamWindowController was extracted. */
public class StreamWindowControllerTest {
    private static final int LANDSCAPE = Configuration.ORIENTATION_LANDSCAPE;
    private static final int PORTRAIT = Configuration.ORIENTATION_PORTRAIT;

    @Test
    public void nonSquarishDisplayLocksToTheChosenOrientation() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
                StreamWindowController.requestedOrientation(false, false, false, 1920, 1080, LANDSCAPE));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
                StreamWindowController.requestedOrientation(false, false, false, 1080, 1920, PORTRAIT));
        // Anything but landscape locks to portrait
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
                StreamWindowController.requestedOrientation(false, false, false, 1920, 1080,
                        Configuration.ORIENTATION_UNDEFINED));
        // The on-screen controls and the native resolution only matter on squarish displays
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
                StreamWindowController.requestedOrientation(false, true, true, 2400, 1080, PORTRAIT));
    }

    @Test
    public void squarishDisplayAllowsAnyOrientationWithoutAReasonToLock() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
                StreamWindowController.requestedOrientation(true, false, false, 1920, 1080, LANDSCAPE));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
                StreamWindowController.requestedOrientation(true, false, false, 1920, 1080, PORTRAIT));
    }

    @Test
    public void squarishDisplayWithOnScreenControlsIsLandscape() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
                StreamWindowController.requestedOrientation(true, true, false, 1080, 1920, PORTRAIT));
    }

    @Test
    public void squarishDisplayWithANativeStreamFollowsTheVideoShape() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
                StreamWindowController.requestedOrientation(true, false, true, 2208, 1768, PORTRAIT));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
                StreamWindowController.requestedOrientation(true, false, true, 1768, 2208, LANDSCAPE));
        // A square video counts as portrait, and the native resolution wins over the on-screen controls
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
                StreamWindowController.requestedOrientation(true, true, true, 1800, 1800, LANDSCAPE));
    }

    @Test
    public void pipUsesTheStreamViewOnlyInFitModeUnzoomed() {
        assertTrue(StreamWindowController.pipFromStreamContainer(ScaleMode.FIT, 1f));
        assertFalse(StreamWindowController.pipFromStreamContainer(ScaleMode.FIT, 1.5f));
        assertFalse(StreamWindowController.pipFromStreamContainer(ScaleMode.FILL, 1f));
        assertFalse(StreamWindowController.pipFromStreamContainer(ScaleMode.STRETCH, 1f));
    }

    @Test
    public void pipTitleIsTheGameWithThePcBelowOrThePcAlone() {
        assertEquals("Hades", StreamWindowController.pipTitle("Hades", "Desktop PC"));
        assertEquals("Desktop PC", StreamWindowController.pipSubtitle("Hades", "Desktop PC"));

        assertEquals("Hades", StreamWindowController.pipTitle("Hades", null));
        assertNull(StreamWindowController.pipSubtitle("Hades", null));

        // "Resume Session" has no game name
        assertEquals("Desktop PC", StreamWindowController.pipTitle(null, "Desktop PC"));
        assertNull(StreamWindowController.pipSubtitle(null, "Desktop PC"));

        assertNull(StreamWindowController.pipTitle(null, null));
        assertNull(StreamWindowController.pipSubtitle(null, null));
    }

    @Test
    public void pipAutoEntersOnlyWhenConnectedAndNoUsbPromptIsOpen() {
        assertTrue(StreamWindowController.pipAutoEnter(true, 0));
        assertFalse(StreamWindowController.pipAutoEnter(false, 0));
        assertFalse(StreamWindowController.pipAutoEnter(true, 1));
        assertFalse(StreamWindowController.pipAutoEnter(true, 2));
    }
}
