package com.limelight.binding.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.content.Context;

import com.limelight.binding.input.capture.InputCaptureProvider;
import com.limelight.binding.input.touch.AbsoluteTouchContext;
import com.limelight.binding.input.touch.RelativeTouchContext;
import com.limelight.binding.input.touch.TouchContext;
import com.limelight.binding.input.touch.TrackpadContext;
import com.limelight.nvstream.NvConnection;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.StreamContainer;
import com.limelight.utils.PanZoomHandler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Characterisation tests: what each mouse mode sets and installs, as Game did before the extraction. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class MouseModeControllerTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private final PreferenceConfiguration prefs = new PreferenceConfiguration();
    private final NvConnection conn = mock(NvConnection.class);
    private final StreamContainer container = mock(StreamContainer.class);
    private boolean externalDisplay;
    private StreamPointerHandler pointer;
    private MouseModeController modes;

    @Before
    public void setUp() {
        pointer = new StreamPointerHandler(context, prefs, container, mock(PanZoomHandler.class), new StreamPointerHandler.Host() {
            @Override public NvConnection conn() { return conn; }
            @Override public InputCaptureProvider inputCaptureProvider() { return null; }
            @Override public boolean isConfiguringOnscreenControls() { return false; }
            @Override public void toggleKeyboard() { }
            @Override public void toggleFullKeyboard() { }
            @Override public void showGameMenu() { }
        });
        modes = new MouseModeController(context, prefs, container, pointer, new MouseModeController.Host() {
            @Override public NvConnection conn() { return conn; }
            @Override public boolean isOnExternalDisplay() { return externalDisplay; }
        });
    }

    private void assertContexts(Class<? extends TouchContext> type) {
        for (TouchContext context : pointer.touchContexts()) {
            assertSame(type, context.getClass());
        }
    }

    @Test
    public void multiTouchSendsDirectTouches() {
        modes.applyMouseMode(0);

        assertEquals(0, modes.getActiveMouseMode());
        assertTrue(prefs.enableMultiTouchScreen);
        assertFalse(prefs.touchscreenTrackpad);
        assertContexts(AbsoluteTouchContext.class);
    }

    @Test
    public void mouseModesUseAbsoluteTouchContexts() {
        for (int mode : new int[] { 1, 5 }) {
            prefs.enableMultiTouchScreen = true;
            prefs.touchscreenTrackpad = true;

            modes.applyMouseMode(mode);

            assertEquals(mode, modes.getActiveMouseMode());
            assertFalse(prefs.enableMultiTouchScreen);
            assertFalse(prefs.touchscreenTrackpad);
            assertContexts(AbsoluteTouchContext.class);
        }
    }

    @Test
    public void trackpadModesUseTrackpadOrRelativeContexts() {
        modes.applyMouseMode(2);
        assertFalse(prefs.enableMultiTouchScreen);
        assertTrue(prefs.touchscreenTrackpad);
        assertContexts(TrackpadContext.class);

        modes.applyMouseMode(3);
        assertTrue(prefs.touchscreenTrackpad);
        assertContexts(RelativeTouchContext.class);
    }

    @Test
    public void disabledTouchKeepsTheFlagsAndRemovesTheContexts() {
        modes.applyMouseMode(0);
        TouchContext[] before = pointer.touchContexts().clone();

        modes.applyMouseMode(4);

        assertEquals(4, modes.getActiveMouseMode());
        assertTrue(prefs.enableMultiTouchScreen);
        assertFalse(prefs.touchscreenTrackpad);
        for (int i = 0; i < before.length; i++) {
            assertNull(pointer.touchContexts()[i]);
            assertTrue("the replaced context is cancelled", before[i].isCancelled());
        }
    }

    @Test
    public void theStreamMenuOnlyChoosesKnownModes() {
        modes.applyMouseMode(1);

        modes.setStreamMouseMode(-1);
        modes.setStreamMouseMode(6);
        assertEquals(1, modes.getActiveMouseMode());

        modes.setStreamMouseMode(3);
        assertEquals(3, modes.getActiveMouseMode());
    }

    @Test
    public void theExternalDisplayOnlyAllowsTrackpadOrDisabledTouch() {
        externalDisplay = true;
        modes.applyMouseMode(2);

        for (int mode : new int[] { 0, 1, 5 }) {
            modes.setStreamMouseMode(mode);
            assertEquals(2, modes.getActiveMouseMode());
        }
        modes.setStreamMouseMode(4);
        assertEquals(4, modes.getActiveMouseMode());
    }

    @Test
    public void remoteInputForcesTheTrackpad() {
        assertTrue(modes.isChangeAllowed());

        modes.forceTrackpad();

        assertFalse(modes.isChangeAllowed());
        assertEquals(2, modes.getActiveMouseMode());
        assertContexts(TrackpadContext.class);
    }
}
