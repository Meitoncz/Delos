package com.limelight.binding.input;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.InputDevice;
import android.view.MotionEvent;

import com.limelight.binding.input.capture.InputCaptureProvider;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.StreamContainer;
import com.limelight.utils.PanZoomHandler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import org.robolectric.annotation.Config;

/** Characterisation tests: the touch event mapping, the contact-area maths and the touch sensitivity Game used before the extraction. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class StreamPointerHandlerTest {
    private static final float EPSILON = 1e-4f;

    private final Context context = RuntimeEnvironment.getApplication();
    private final PreferenceConfiguration prefs = new PreferenceConfiguration();
    private final StreamContainer container = mock(StreamContainer.class);
    private StreamPointerHandler handler;

    @Before
    public void setUp() {
        when(container.getWidth()).thenReturn(1000);
        when(container.getHeight()).thenReturn(500);
        handler = new StreamPointerHandler(context, prefs, container, mock(PanZoomHandler.class), new StreamPointerHandler.Host() {
            @Override public NvConnection conn() { return mock(NvConnection.class); }
            @Override public InputCaptureProvider inputCaptureProvider() { return null; }
            @Override public boolean isConfiguringOnscreenControls() { return false; }
            @Override public void toggleKeyboard() { }
            @Override public void toggleFullKeyboard() { }
            @Override public void showGameMenu() { }
        });
    }

    private static MotionEvent event(int action, float x, float y) {
        return MotionEvent.obtain(0, 0, action, x, y, 0);
    }

    private static MotionEvent cancelledEvent(int action) {
        MotionEvent.PointerProperties[] properties = { new MotionEvent.PointerProperties() };
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] coords = { new MotionEvent.PointerCoords() };
        return MotionEvent.obtain(0, 0, action, 1, properties, coords, 0, 0, 1, 1, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.FLAG_CANCELED);
    }

    private static byte touchType(int action) {
        return StreamPointerHandler.getLiTouchTypeFromEvent(event(action, 0, 0));
    }

    @Test
    public void touchActionsMapToTheHostTouchEvents() {
        assertEquals(MoonBridge.LI_TOUCH_EVENT_DOWN, touchType(MotionEvent.ACTION_DOWN));
        assertEquals(MoonBridge.LI_TOUCH_EVENT_DOWN, touchType(MotionEvent.ACTION_POINTER_DOWN));
        assertEquals(MoonBridge.LI_TOUCH_EVENT_UP, touchType(MotionEvent.ACTION_UP));
        assertEquals(MoonBridge.LI_TOUCH_EVENT_UP, touchType(MotionEvent.ACTION_POINTER_UP));
        assertEquals(MoonBridge.LI_TOUCH_EVENT_MOVE, touchType(MotionEvent.ACTION_MOVE));
        assertEquals(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, touchType(MotionEvent.ACTION_CANCEL));
        assertEquals(MoonBridge.LI_TOUCH_EVENT_HOVER, touchType(MotionEvent.ACTION_HOVER_ENTER));
        assertEquals(MoonBridge.LI_TOUCH_EVENT_HOVER, touchType(MotionEvent.ACTION_HOVER_MOVE));
        assertEquals(MoonBridge.LI_TOUCH_EVENT_HOVER_LEAVE, touchType(MotionEvent.ACTION_HOVER_EXIT));
        assertEquals(MoonBridge.LI_TOUCH_EVENT_BUTTON_ONLY, touchType(MotionEvent.ACTION_BUTTON_PRESS));
        assertEquals(MoonBridge.LI_TOUCH_EVENT_BUTTON_ONLY, touchType(MotionEvent.ACTION_BUTTON_RELEASE));
        assertEquals(-1, touchType(MotionEvent.ACTION_SCROLL));
    }

    @Test
    public void aCancelledPointerUpIsASinglePointerCancel() {
        assertEquals(MoonBridge.LI_TOUCH_EVENT_CANCEL,
                StreamPointerHandler.getLiTouchTypeFromEvent(cancelledEvent(MotionEvent.ACTION_UP)));
        assertEquals(MoonBridge.LI_TOUCH_EVENT_CANCEL,
                StreamPointerHandler.getLiTouchTypeFromEvent(cancelledEvent(MotionEvent.ACTION_POINTER_UP)));
    }

    @Test
    public void valuesAreNormalizedToTheirAxisRange() {
        // MotionRange is final and has no public constructor: (axis, source, min, max, flat, fuzz, resolution)
        InputDevice.MotionRange range = ReflectionHelpers.callConstructor(InputDevice.MotionRange.class,
                ClassParameter.from(int.class, MotionEvent.AXIS_DISTANCE),
                ClassParameter.from(int.class, InputDevice.SOURCE_STYLUS),
                ClassParameter.from(float.class, -10f),
                ClassParameter.from(float.class, 30f),
                ClassParameter.from(float.class, 0f),
                ClassParameter.from(float.class, 0f),
                ClassParameter.from(float.class, 0f));

        assertEquals(0f, StreamPointerHandler.normalizeValueInRange(-10f, range), EPSILON);
        assertEquals(0.25f, StreamPointerHandler.normalizeValueInRange(0f, range), EPSILON);
        assertEquals(1f, StreamPointerHandler.normalizeValueInRange(30f, range), EPSILON);
    }

    @Test
    public void contactAreaAxesConvertBetweenPolarAndCartesian() {
        assertArrayEquals(new float[] { 2f, 0f }, StreamPointerHandler.polarToCartesian(2f, 0f), EPSILON);
        assertArrayEquals(new float[] { 0f, 3f }, StreamPointerHandler.polarToCartesian(3f, (float) (Math.PI / 2)), EPSILON);
        assertEquals(5f, StreamPointerHandler.cartesianToR(new float[] { 3f, 4f }), EPSILON);
        assertEquals(7f, StreamPointerHandler.cartesianToR(StreamPointerHandler.polarToCartesian(7f, 1.234f)), EPSILON);
    }

    @Test
    public void sensitivityScalesTheMovementOfEachFinger() {
        prefs.touchSensitivityGlobal = true;
        prefs.touchSensitivityX = 200;
        prefs.touchSensitivityY = 50;

        // The first move starts the finger where it is
        assertArrayEquals(new float[] { 100f, 100f }, move(100f, 100f), EPSILON);
        // Then every movement is scaled: x twice as far, y half as far
        assertArrayEquals(new float[] { 120f, 110f }, move(110f, 120f), EPSILON);
        assertArrayEquals(new float[] { 140f, 120f }, move(120f, 140f), EPSILON);

        // Lifting the finger forgets it
        handler.getStreamViewRelativeSensitivityXY(event(MotionEvent.ACTION_UP, 120f, 140f), 120f, 140f, 0);
        assertArrayEquals(new float[] { 300f, 300f }, move(300f, 300f), EPSILON);
    }

    @Test
    public void sensitivityOnlyAppliesToTheRightHalfUnlessGlobal() {
        prefs.touchSensitivityGlobal = false;
        prefs.touchSensitivityX = 200;
        prefs.touchSensitivityY = 200;
        float left = context.getResources().getDisplayMetrics().widthPixels / 2f - 1;

        move(left, 10f);
        assertArrayEquals("the left half is left alone", new float[] { left - 5, 20f }, move(left - 5, 20f), EPSILON);
    }

    @Test
    public void automaticRotationRecentresAFingerThatLeavesTheStream() {
        prefs.touchSensitivityGlobal = true;
        prefs.touchSensitivityRotationAuto = true;
        prefs.touchSensitivityX = 400;
        prefs.touchSensitivityY = 400;

        move(900f, 400f);
        // Scaled four times, the finger would end at x 1100 and y 600, past the 1000 x 500 stream
        assertArrayEquals(new float[] { 500f, 250f }, move(950f, 450f), EPSILON);
    }

    private float[] move(float x, float y) {
        return handler.getStreamViewRelativeSensitivityXY(event(MotionEvent.ACTION_MOVE, x, y), x, y, 0);
    }
}
