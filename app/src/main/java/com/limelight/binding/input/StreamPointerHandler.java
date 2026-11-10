package com.limelight.binding.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import androidx.preference.PreferenceManager;

import com.limelight.R;
import com.limelight.SensitivityBean;
import com.limelight.binding.input.capture.InputCaptureProvider;
import com.limelight.binding.input.touch.TouchContext;
import com.limelight.binding.input.touch.TrackpadContext;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.StreamContainer;
import com.limelight.utils.DelosToast;
import com.limelight.utils.ExternalDisplayControlActivity;
import com.limelight.utils.PanZoomHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * The stream's pointer input: mice (captured or not), styluses and erasers, touchpads and the touchscreen in every
 * mouse mode (direct touch, mouse, trackpad), the 3/4/5-finger taps, the touch sensitivity and the pan and zoom mode.
 * {@code DelosStreamActivity} sends it the pointer events left over after the controller passthrough; {@link MouseModeController}
 * fills its touch contexts.
 */
public final class StreamPointerHandler {
    private static final int STYLUS_DOWN_DEAD_ZONE_DELAY = 100;
    private static final int STYLUS_DOWN_DEAD_ZONE_RADIUS = 20;

    private static final int STYLUS_UP_DEAD_ZONE_DELAY = 150;
    private static final int STYLUS_UP_DEAD_ZONE_RADIUS = 50;

    private static final int THREE_FINGER_TAP_THRESHOLD = 300;
    private static final int FOUR_FINGER_TAP_THRESHOLD = 300;
    private static final int FIVE_FINGER_TAP_THRESHOLD = 300;

    /** What the pointer handler needs from the stream activity. */
    public interface Host {
        NvConnection conn();

        /** The mouse capture (pointer capture or the device-specific provider). */
        InputCaptureProvider inputCaptureProvider();

        /** Whether the on-screen controls are being moved or resized; touches then belong to them. */
        boolean isConfiguringOnscreenControls();

        /** 3-finger tap: the system keyboard. */
        void toggleKeyboard();

        /** 4-finger tap: the Full keyboard. */
        void toggleFullKeyboard();

        /** 5-finger tap: the stream menu. */
        void showGameMenu();
    }

    private final Context context;
    private final PreferenceConfiguration prefConfig;
    private final StreamContainer streamContainer;
    private final PanZoomHandler panZoomHandler;
    private final Host host;

    private int lastButtonState = 0;

    // Only 2 touches are supported
    private final TouchContext[] touchContextMap = new TouchContext[2];
    private final TouchContext[] trackpadContextMap = new TouchContext[2];
    private long threeFingerDownTime = 0;
    private long fourFingerDownTime = 0;
    private long fiveFingerDownTime = 0;

    private boolean isPanZoomMode = false;
    private boolean synthClickPending = false;
    private boolean pointerSwiping = false;
    private long synthTouchDownTime = 0;

    private boolean pendingDrag = false;
    private boolean isDragging = false;
    private float lastTouchDownX, lastTouchDownY;

    private long lastAbsTouchUpTime = 0;
    private long lastAbsTouchDownTime = 0;
    private float lastAbsTouchUpX, lastAbsTouchUpY;
    private float lastAbsTouchDownX, lastAbsTouchDownY;

    // Per-pointer sensitivity state, so multiple fingers are supported
    private Map<String,SensitivityBean> sensitivityMap=new HashMap<>();
    private final int[] windowLocation = new int[2];

    /** {@code context} is the stream activity (vibration, toasts and saved zoom and pan). */
    public StreamPointerHandler(Context context, PreferenceConfiguration prefConfig, StreamContainer streamContainer,
                                PanZoomHandler panZoomHandler, Host host) {
        this.context = context;
        this.prefConfig = prefConfig;
        this.streamContainer = streamContainer;
        this.panZoomHandler = panZoomHandler;
        this.host = host;
    }

    /** The touchscreen's contexts, one per supported pointer; the mouse mode replaces them. */
    TouchContext[] touchContexts() {
        return touchContextMap;
    }

    /** The trackpad contexts for touchpads and two-finger swipes; created once the connection exists. */
    public void createTrackpadContexts() {
        for (int i = 0; i < trackpadContextMap.length; i++) {
            trackpadContextMap[i] = new TrackpadContext(host.conn(), i, prefConfig.trackpadSwapAxis, prefConfig.trackpadSensitivityX, prefConfig.trackpadSensitivityY);
        }
    }

    /** Restores the zoom and pan of the last stream when the user keeps them. */
    public void restoreZoomAndPan() {
        if (prefConfig.rememberZoomPan) {
            streamContainer.post(() -> panZoomHandler.setInitialZoomAndPan(
                    prefConfig.zoomScale,
                    prefConfig.panOffsetX,
                    prefConfig.panOffsetY
            ));
        }
    }

    /** Saves the zoom and pan for the next stream when the user keeps them. */
    public void saveZoomAndPan() {
        if (prefConfig.rememberZoomPan) {
            SharedPreferences basePrefs = PreferenceManager.getDefaultSharedPreferences(context);
            basePrefs.edit()
                    .putFloat("number_zoom_scale", panZoomHandler.getScaleFactor())
                    .putFloat("number_pan_offset_x", panZoomHandler.getChildX())
                    .putFloat("number_pan_offset_y", panZoomHandler.getChildY())
                    .apply();
        }
    }

    /** The stream surface changed size: keep the zoomed view within bounds. */
    public void onSurfaceChanged() {
        panZoomHandler.handleSurfaceChange();
    }

    public boolean isZoomModeEnabled() {
        return isPanZoomMode;
    }

    public void toggleZoomMode() {
        this.isPanZoomMode = !this.isPanZoomMode;
        if (this.isPanZoomMode) {
            DelosToast.show(context, context.getString(R.string.pan_zoom_mode_enabled), DelosToast.SHORT);
        } else {
            DelosToast.show(context, context.getString(R.string.pan_zoom_mode_disabled), DelosToast.SHORT);
        }

        if (ExternalDisplayControlActivity.instance != null) {
            ExternalDisplayControlActivity.instance.toggleZoomMode(false);
        }
    }

    /** Leaves the pan and zoom mode without a toast (a new mouse mode always does). */
    void exitZoomMode() {
        isPanZoomMode = false;
    }

    // Toggle custom touch sensitivity
    public void switchTouchSensitivity(){
        prefConfig.enableTouchSensitivity = !prefConfig.enableTouchSensitivity;
    }

    private TouchContext getTouchContext(int actionIndex, TouchContext[] inputContextMap)
    {
        if (actionIndex < inputContextMap.length) {
            return inputContextMap[actionIndex];
        }
        else {
            return null;
        }
    }

    static byte getLiTouchTypeFromEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                return MoonBridge.LI_TOUCH_EVENT_DOWN;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if ((event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
                    return MoonBridge.LI_TOUCH_EVENT_CANCEL;
                }
                else {
                    return MoonBridge.LI_TOUCH_EVENT_UP;
                }

            case MotionEvent.ACTION_MOVE:
                return MoonBridge.LI_TOUCH_EVENT_MOVE;

            case MotionEvent.ACTION_CANCEL:
                // ACTION_CANCEL applies to *all* pointers in the gesture, so it maps to CANCEL_ALL
                // rather than CANCEL. For a single pointer cancellation, that's indicated via
                // FLAG_CANCELED on a ACTION_POINTER_UP.
                // https://developer.android.com/develop/ui/views/touch-and-input/gestures/multi
                return MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL;

            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                return MoonBridge.LI_TOUCH_EVENT_HOVER;

            case MotionEvent.ACTION_HOVER_EXIT:
                return MoonBridge.LI_TOUCH_EVENT_HOVER_LEAVE;

            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return MoonBridge.LI_TOUCH_EVENT_BUTTON_ONLY;

            default:
                return -1;
        }
    }

    // Adjust touch sensitivity by scaling the movement distance; by default only on the right half of the screen.
    // sensitiveSide: the pointer is where the sensitivity applies (anywhere, or on the right half; see the caller).
    float[] getStreamViewRelativeSensitivityXY(MotionEvent event,float normalizedX,float normalizedY,int pointerIndex,boolean sensitiveSide){
        float[] normalized=new float[2];
        normalized[0]=normalizedX;
        normalized[1]=normalizedY;

        // A pointer that just went down starts afresh, whatever an earlier touch with its id left behind
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN||event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
            sensitivityMap.remove(String.valueOf(event.getPointerId(pointerIndex)));
        }
        // Not in global mode and the touch is not on the right half: leave it unchanged. The pointer's offset is
        // forgotten, so that it starts afresh when it comes back instead of jumping by the way it went meanwhile.
        if(!sensitiveSide){
            if(!sensitivityMap.isEmpty()){
                sensitivityMap.remove(String.valueOf(event.getPointerId(pointerIndex)));
            }
            return normalized;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            SensitivityBean bean=sensitivityMap.get(String.valueOf(event.getPointerId(pointerIndex)));
            if(bean==null){
                bean=new SensitivityBean();
            }
            if(bean.getLastAbsoluteX() !=-1){
                float dx=normalizedX- bean.getLastAbsoluteX();
                float dy=normalizedY- bean.getLastAbsoluteY();
                dx*=0.01f*prefConfig.touchSensitivityX;// sensitivity
                dy*=0.01f*prefConfig.touchSensitivityY;
                normalizedX= bean.getLastRelativelyX() +dx;
                normalizedY= bean.getLastRelativelyY() +dy;
            }
            if(prefConfig.touchSensitivityRotationAuto){
                if(normalizedX>= streamContainer.getWidth()){
                    normalizedX= streamContainer.getWidth()/2.0f;
                }
                if(normalizedY>= streamContainer.getHeight()){
                    normalizedY= streamContainer.getHeight()/2.0f;
                }
            }
            bean.setLastAbsoluteX(event.getX(pointerIndex));
            bean.setLastAbsoluteY(event.getY(pointerIndex));
            bean.setLastRelativelyX(normalizedX);
            bean.setLastRelativelyY(normalizedY);
            sensitivityMap.put(String.valueOf(event.getPointerId(pointerIndex)),bean);
        }
        // On pointer up, reset to the initial state
        if (event.getActionMasked() == MotionEvent.ACTION_UP||event.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
            sensitivityMap.remove(String.valueOf(event.getPointerId(pointerIndex)));
        }
        normalized[0]=normalizedX;
        normalized[1]=normalizedY;
        return normalized;
    }

    /**
     * Whether a pointer at {@code screenX} is on the right half of the window it touches. Both are screen
     * coordinates, so the answer does not depend on which view got the event: a view's own coordinates start at its
     * left edge, which is not the screen's for a letterboxed, zoomed or panned stream.
     */
    static boolean isOnRightHalf(float screenX, int windowLeft, int windowWidth) {
        return screenX >= windowLeft + windowWidth / 2f;
    }

    private boolean isOnRightHalfOfScreen(View view, MotionEvent event, int pointerIndex) {
        // NB: view may be null; the stream container is in the same full-screen window then
        View window = (view != null ? view : streamContainer).getRootView();
        window.getLocationOnScreen(windowLocation);
        return isOnRightHalf(event.getRawX(pointerIndex), windowLocation[0], window.getWidth());
    }

    private float[] getStreamViewRelativeNormalizedXY(View view, MotionEvent event, int pointerIndex) {
        float normalizedX = event.getX(pointerIndex);
        float normalizedY = event.getY(pointerIndex);
        // Custom touch sensitivity enabled and not 100%
        if(prefConfig.enableTouchSensitivity&&(prefConfig.touchSensitivityX !=100||prefConfig.touchSensitivityY!=100)){
            float[] normalized=getStreamViewRelativeSensitivityXY(event,normalizedX,normalizedY,pointerIndex,
                    prefConfig.touchSensitivityGlobal||isOnRightHalfOfScreen(view,event,pointerIndex));
            normalizedX=normalized[0];
            normalizedY=normalized[1];
        }
        // For the containing background view, we must subtract the origin
        // of the StreamView to get video-relative coordinates.
        if (view != streamContainer) {
            float[] normalized = getNormalizedCoordinates(streamContainer, normalizedX, normalizedY);
            normalizedX = normalized[0];
            normalizedY = normalized[1];
        }

        normalizedX = Math.max(normalizedX, 0.0f);
        normalizedY = Math.max(normalizedY, 0.0f);

        normalizedX = Math.min(normalizedX, streamContainer.getWidth());
        normalizedY = Math.min(normalizedY, streamContainer.getHeight());

        normalizedX /= streamContainer.getWidth();
        normalizedY /= streamContainer.getHeight();

        return new float[] { normalizedX, normalizedY };
    }

    private float[] getNormalizedCoordinates(View streamView, float rawX, float rawY) {
        float scaleX = streamView.getScaleX();
        float scaleY = streamView.getScaleY();

        float normalizedX = (rawX - streamView.getX()) / scaleX;
        float normalizedY = (rawY - streamView.getY()) / scaleY;

        return new float[] { normalizedX, normalizedY };
    }

    static float normalizeValueInRange(float value, InputDevice.MotionRange range) {
        return (value - range.getMin()) / range.getRange();
    }

    private static float getPressureOrDistance(MotionEvent event, int pointerIndex) {
        InputDevice dev = event.getDevice();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_EXIT:
                // Hover events report distance
                if (dev != null) {
                    InputDevice.MotionRange distanceRange = dev.getMotionRange(MotionEvent.AXIS_DISTANCE, event.getSource());
                    if (distanceRange != null) {
                        return normalizeValueInRange(event.getAxisValue(MotionEvent.AXIS_DISTANCE, pointerIndex), distanceRange);
                    }
                }
                return 0.0f;

            default:
                // Other events report pressure
                return event.getPressure(pointerIndex);
        }
    }

    private static short getRotationDegrees(MotionEvent event, int pointerIndex) {
        InputDevice dev = event.getDevice();
        if (dev != null) {
            if (dev.getMotionRange(MotionEvent.AXIS_ORIENTATION, event.getSource()) != null) {
                short rotationDegrees = (short) Math.toDegrees(event.getOrientation(pointerIndex));
                if (rotationDegrees < 0) {
                    rotationDegrees += 360;
                }
                return rotationDegrees;
            }
        }
        return MoonBridge.LI_ROT_UNKNOWN;
    }

    static float[] polarToCartesian(float r, float theta) {
        return new float[] { (float)(r * Math.cos(theta)), (float)(r * Math.sin(theta)) };
    }

    static float cartesianToR(float[] point) {
        return (float)Math.sqrt(Math.pow(point[0], 2) + Math.pow(point[1], 2));
    }

    private float[] getStreamViewNormalizedContactArea(MotionEvent event, int pointerIndex) {
        float orientation;

        // If the orientation is unknown, we'll just assume it's at a 45 degree angle and scale it by
        // X and Y scaling factors evenly.
        if (event.getDevice() == null || event.getDevice().getMotionRange(MotionEvent.AXIS_ORIENTATION, event.getSource()) == null) {
            orientation = (float)(Math.PI / 4);
        }
        else {
            orientation = event.getOrientation(pointerIndex);
        }

        float contactAreaMajor, contactAreaMinor;
        switch (event.getActionMasked()) {
            // Hover events report the tool size
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_EXIT:
                contactAreaMajor = event.getToolMajor(pointerIndex);
                contactAreaMinor = event.getToolMinor(pointerIndex);
                break;

            // Other events report contact area
            default:
                contactAreaMajor = event.getTouchMajor(pointerIndex);
                contactAreaMinor = event.getTouchMinor(pointerIndex);
                break;
        }

        // The contact area major axis is parallel to the orientation, so we simply convert
        // polar to cartesian coordinates using the orientation as theta.
        float[] contactAreaMajorCartesian = polarToCartesian(contactAreaMajor, orientation);

        // The contact area minor axis is perpendicular to the contact area major axis (and thus
        // the orientation), so rotate the orientation angle by 90 degrees.
        float[] contactAreaMinorCartesian = polarToCartesian(contactAreaMinor, (float)(orientation + (Math.PI / 2)));

        // Normalize the contact area to the stream view size
        contactAreaMajorCartesian[0] = Math.min(Math.abs(contactAreaMajorCartesian[0]), streamContainer.getWidth()) / streamContainer.getWidth();
        contactAreaMinorCartesian[0] = Math.min(Math.abs(contactAreaMinorCartesian[0]), streamContainer.getWidth()) / streamContainer.getWidth();
        contactAreaMajorCartesian[1] = Math.min(Math.abs(contactAreaMajorCartesian[1]), streamContainer.getHeight()) / streamContainer.getHeight();
        contactAreaMinorCartesian[1] = Math.min(Math.abs(contactAreaMinorCartesian[1]), streamContainer.getHeight()) / streamContainer.getHeight();

        // Convert the normalized values back into polar coordinates
        return new float[] { cartesianToR(contactAreaMajorCartesian), cartesianToR(contactAreaMinorCartesian) };
    }

    private boolean sendPenEventForPointer(View view, MotionEvent event, byte eventType, byte toolType, int pointerIndex) {
        byte penButtons = 0;
        if ((event.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
            penButtons |= MoonBridge.LI_PEN_BUTTON_PRIMARY;
        }
        if ((event.getButtonState() & MotionEvent.BUTTON_STYLUS_SECONDARY) != 0) {
            penButtons |= MoonBridge.LI_PEN_BUTTON_SECONDARY;
        }

        byte tiltDegrees = MoonBridge.LI_TILT_UNKNOWN;
        InputDevice dev = event.getDevice();
        if (dev != null) {
            if (dev.getMotionRange(MotionEvent.AXIS_TILT, event.getSource()) != null) {
                tiltDegrees = (byte)Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex));
            }
        }

        float[] normalizedCoords = getStreamViewRelativeNormalizedXY(view, event, pointerIndex);
        float[] normalizedContactArea = getStreamViewNormalizedContactArea(event, pointerIndex);
        return host.conn().sendPenEvent(eventType, toolType, penButtons,
                normalizedCoords[0], normalizedCoords[1],
                getPressureOrDistance(event, pointerIndex),
                normalizedContactArea[0], normalizedContactArea[1],
                getRotationDegrees(event, pointerIndex), tiltDegrees) != MoonBridge.LI_ERR_UNSUPPORTED;
    }

    private static byte convertToolTypeToStylusToolType(MotionEvent event, int pointerIndex) {
        switch (event.getToolType(pointerIndex)) {
            case MotionEvent.TOOL_TYPE_ERASER:
                return MoonBridge.LI_TOOL_TYPE_ERASER;
            case MotionEvent.TOOL_TYPE_STYLUS:
                return MoonBridge.LI_TOOL_TYPE_PEN;
            default:
                return MoonBridge.LI_TOOL_TYPE_UNKNOWN;
        }
    }

    private boolean trySendPenEvent(View view, MotionEvent event) {
        byte eventType = getLiTouchTypeFromEvent(event);
        if (eventType < 0) {
            return false;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            // Move events may impact all active pointers
            boolean handledStylusEvent = false;
            for (int i = 0; i < event.getPointerCount(); i++) {
                byte toolType = convertToolTypeToStylusToolType(event, i);
                if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) {
                    // Not a stylus pointer, so skip it
                    continue;
                }
                else {
                    // This pointer is a stylus, so we'll report that we handled this event
                    handledStylusEvent = true;
                }

                if (!sendPenEventForPointer(view, event, eventType, toolType, i)) {
                    // Pen events aren't supported by the host
                    return false;
                }
            }
            return handledStylusEvent;
        }
        else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            // Cancel impacts all active pointers
            return host.conn().sendPenEvent(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, MoonBridge.LI_TOOL_TYPE_UNKNOWN, (byte)0,
                    0, 0, 0, 0, 0,
                    MoonBridge.LI_ROT_UNKNOWN, MoonBridge.LI_TILT_UNKNOWN) != MoonBridge.LI_ERR_UNSUPPORTED;
        }
        else {
            // Up, Down, and Hover events are specific to the action index
            byte toolType = convertToolTypeToStylusToolType(event, event.getActionIndex());
            if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) {
                // Not a stylus event
                return false;
            }
            return sendPenEventForPointer(view, event, eventType, toolType, event.getActionIndex());
        }
    }

    private boolean sendTouchEventForPointer(View view, MotionEvent event, byte eventType, int pointerIndex) {
        float[] normalizedCoords = getStreamViewRelativeNormalizedXY(view, event, pointerIndex);
        float[] normalizedContactArea = getStreamViewNormalizedContactArea(event, pointerIndex);
        return host.conn().sendTouchEvent(eventType, event.getPointerId(pointerIndex),
                normalizedCoords[0], normalizedCoords[1],
                getPressureOrDistance(event, pointerIndex),
                normalizedContactArea[0], normalizedContactArea[1],
                getRotationDegrees(event, pointerIndex)) != MoonBridge.LI_ERR_UNSUPPORTED;
    }

    private boolean trySendTouchEvent(View view, MotionEvent event) {
        byte eventType = getLiTouchTypeFromEvent(event);
        if (eventType < 0) {
            return false;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            // Move events may impact all active pointers
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (!sendTouchEventForPointer(view, event, eventType, i)) {
                    return false;
                }
            }
            return true;
        }
        else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            // Cancel impacts all active pointers
            return host.conn().sendTouchEvent(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, 0,
                    0, 0, 0, 0, 0,
                    MoonBridge.LI_ROT_UNKNOWN) != MoonBridge.LI_ERR_UNSUPPORTED;
        }
        else {
            // Up, Down, and Hover events are specific to the action index
            return sendTouchEventForPointer(view, event, eventType, event.getActionIndex());
        }
    }

    /**
     * A mouse, stylus, touchpad or touchscreen event (the pointer and position source classes); false for other
     * sources. Returns true if the event was consumed.
     * NB: View is only present if called from a view callback
     */
    public boolean handlePointerEvent(View view, MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            // The gesture is over for all of its pointers, and none of them gets an ACTION_UP any more
            sensitivityMap.clear();
        }

        int eventSource = event.getSource();
        if ((eventSource & InputDevice.SOURCE_CLASS_POINTER) != 0 ||
                (eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0 ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE)
        {
            if (isMouseLikeEvent(event, eventSource)) {
                // This case is for mice and non-finger touch devices
                return handleMouseEvent(view, event, eventSource);
            }
            // This case is for fingers
            return handleFingerEvent(view, event, eventSource);
        }

        // Unknown class
        return false;
    }

    // Mice, styluses, erasers and touchpads with buttons take the mouse path; fingers take the touch path
    private static boolean isMouseLikeEvent(MotionEvent event, int eventSource) {
        boolean hasActionButton = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || (event.getActionButton() != 0);
        return eventSource == InputDevice.SOURCE_MOUSE ||
                ((eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0 && hasActionButton) || // SOURCE_TOUCHPAD
                (eventSource == InputDevice.SOURCE_MOUSE_RELATIVE ||
                        (event.getPointerCount() >= 1 &&
                                (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE ||
                                        event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                                        event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER)) ||
                        eventSource == 12290); // 12290 = Samsung DeX mode desktop mouse
    }

    // Mice and non-finger touch devices
    private boolean handleMouseEvent(View view, MotionEvent event, int eventSource) {
        NvConnection conn = host.conn();
        InputCaptureProvider inputCaptureProvider = host.inputCaptureProvider();
        int buttonState = event.getButtonState();
        int changedButtons = buttonState ^ lastButtonState;

        // Two finger click
        if ((eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0 &&
                event.getPointerCount() == 2 &&
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && event.getActionButton() == MotionEvent.BUTTON_PRIMARY)) {
            if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS) {
                buttonState |= MotionEvent.BUTTON_SECONDARY;
            }
            else if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_RELEASE) {
                buttonState &= ~MotionEvent.BUTTON_SECONDARY;
            }
            // We may not pressing the primary button down from a previous event,
            // so be sure to clear that bit out the button state.
            buttonState &= ~MotionEvent.BUTTON_PRIMARY;
            buttonState |= (lastButtonState & MotionEvent.BUTTON_PRIMARY);

            changedButtons = buttonState ^ lastButtonState;
        }

        // Ignore mouse input if we're not capturing from our input source
        if (!inputCaptureProvider.isCapturingActive()) {
            // We return true here because otherwise the events may end up causing
            // Android to synthesize d-pad events.
            return true;
        }

        // Always update the position before sending any button events. If we're
        // dealing with a stylus without hover support, our position might be
        // significantly different than before.
        if (inputCaptureProvider.eventHasRelativeMouseAxes(event)) {
            // Send the deltas straight from the motion event
            short deltaX = (short)inputCaptureProvider.getRelativeAxisX(event);
            short deltaY = (short)inputCaptureProvider.getRelativeAxisY(event);

            if (deltaX != 0 || deltaY != 0) {
                if (prefConfig.absoluteMouseMode) {
                    // NB: view may be null, but we can unconditionally use streamView because we don't need to adjust
                    // relative axis deltas for the position of the streamView within the parent's coordinate system.
                    conn.sendMouseMoveAsMousePosition(deltaX, deltaY, (short) streamContainer.getWidth(), (short) streamContainer.getHeight());
                }
                else {
                    conn.sendMouseMove(deltaX, deltaY);
                }
            }
        }
        else if ((eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0) {
            // If this input device is not associated with the view itself (like a trackpad),
            // we'll convert the device-specific coordinates to use to send the cursor position.
            // This really isn't ideal but it's probably better than nothing.
            //
            // Trackpad on newer versions of Android (Oreo and later) should be caught by the
            // relative axes case above. If we get here, we're on an older version that doesn't
            // support pointer capture.
            InputDevice device = event.getDevice();
            if (device != null) {
                InputDevice.MotionRange xRange = device.getMotionRange(MotionEvent.AXIS_X, eventSource);
                InputDevice.MotionRange yRange = device.getMotionRange(MotionEvent.AXIS_Y, eventSource);

                // All touchpads coordinate planes should start at (0, 0)
                if (xRange != null && yRange != null && xRange.getMin() == 0 && yRange.getMin() == 0) {
                    int xMax = (int)xRange.getMax();
                    int yMax = (int)yRange.getMax();

                    // Touchpads must be smaller than (65535, 65535)
                    if (xMax <= Short.MAX_VALUE && yMax <= Short.MAX_VALUE) {
                        conn.sendMousePosition((short)event.getX(), (short)event.getY(),
                                (short)xMax, (short)yMax);
                    }
                }
            }
        }
        else if (view != null && trySendPenEvent(view, event)) {
            // If our host supports pen events, send it directly
            return true;
        }
        else if (view != null) {
            if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                // Handle trackpad two finger swipes when pointer is not captured by synthesizing a trackpad movement
                // Android emulates trackpad  two finger swipes as one finger swipe on the screen
                int eventAction = event.getActionMasked();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && event.getClassification() == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE) {
                    if (!pointerSwiping) {
                        pointerSwiping = true;
                        handleTouchInput(event, trackpadContextMap, false, prefConfig.trackpadSwapAxis, MotionEvent.ACTION_POINTER_DOWN, 1, 2);
                    }
                    return handleTouchInput(event, trackpadContextMap, false, prefConfig.trackpadSwapAxis, MotionEvent.ACTION_MOVE, 1, 2);
                } else if (pointerSwiping && eventAction == MotionEvent.ACTION_UP) {
                    pointerSwiping = false;
                    synthClickPending = false;
                    handleTouchInput(event, trackpadContextMap, false, prefConfig.trackpadSwapAxis, MotionEvent.ACTION_POINTER_UP, 1, 2);
                    return true;
                }

                // Press & Hold / Double-Tap & Hold for Selection or Drag & Drop
                double positionDelta = Math.sqrt(
                        Math.pow(event.getX() - lastTouchDownX, 2) +
                                Math.pow(event.getY() - lastTouchDownY, 2)
                );

                if (synthClickPending &&
                        event.getEventTime() - synthTouchDownTime >= prefConfig.trackpadDragDropThreshold) {
                    if (positionDelta > 50) {
                        pendingDrag = false;
                    } else if (pendingDrag) {
                        pendingDrag = false;
                        isDragging = true;
                        if (prefConfig.trackpadDragDropVibration) {
                            Vibrator vibrator = ((Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE));
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator.vibrate(VibrationEffect.createOneShot(20, 127));
                            } else {
                                vibrator.vibrate(20);
                            }
                        }
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                        return true;
                    }
                }

                switch (eventAction) {
                    case MotionEvent.ACTION_HOVER_MOVE:
                    case MotionEvent.ACTION_MOVE:
                        updateMousePosition(view, event);
                        return true;
                    case MotionEvent.ACTION_HOVER_EXIT:
                    case MotionEvent.ACTION_DOWN:
                        pendingDrag = true;
                        synthClickPending = true;
                        lastTouchDownX = event.getX();
                        lastTouchDownY = event.getY();
                        synthTouchDownTime = event.getEventTime();
                        return true;
                    case MotionEvent.ACTION_HOVER_ENTER:
                    case MotionEvent.ACTION_UP:
                        if (synthClickPending) {
                            long timeDiff = event.getEventTime() - synthTouchDownTime;

                            if (eventSource == 12290) {
                                // Special handle for DeX
                                // DeX reports button secondary when tapping with two fingers
                                // So there's no need to distinguish left/right click by time difference
                                if (timeDiff < 120) {
                                    conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                                }
                            } else {
                                if (timeDiff < 20) {
                                    conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                                } else if (timeDiff < 120) {
                                    conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                                }
                            }
                            if (isDragging) {
                                isDragging = false;
                                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                            }
                            pendingDrag = false;
                            synthClickPending = false;
                        }
                        return true;
                    case MotionEvent.ACTION_BUTTON_PRESS:
                    case MotionEvent.ACTION_BUTTON_RELEASE:
                        synthClickPending = false;
                    default:
                        break;
                }
            } else {
                updateMousePosition(view, event);
            }
        }

        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            // Send the vertical scroll packet
            conn.sendMouseHighResScroll((short)(event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120));
            conn.sendMouseHighResHScroll((short)(event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120));
        }

        sendMouseButtonChanges(buttonState, changedButtons);
        handleStylusPress(event);

        lastButtonState = buttonState;

        // Handled a known source
        return true;
    }

    private void sendMouseButtonChanges(int buttonState, int changedButtons) {
        NvConnection conn = host.conn();
        if ((changedButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
            if ((buttonState & MotionEvent.BUTTON_PRIMARY) != 0) {
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
            }
            else {
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
            }
        }

        // Mouse secondary or stylus primary is right click (stylus down is left click)
        if ((changedButtons & (MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
            if ((buttonState & (MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
            }
            else {
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
            }
        }

        // Mouse tertiary or stylus secondary is middle click
        if ((changedButtons & (MotionEvent.BUTTON_TERTIARY | MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
            if ((buttonState & (MotionEvent.BUTTON_TERTIARY | MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);
            }
            else {
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
            }
        }

        if (prefConfig.mouseNavButtons) {
            if ((changedButtons & MotionEvent.BUTTON_BACK) != 0) {
                if ((buttonState & MotionEvent.BUTTON_BACK) != 0) {
                    conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_X1);
                }
                else {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X1);
                }
            }

            if ((changedButtons & MotionEvent.BUTTON_FORWARD) != 0) {
                if ((buttonState & MotionEvent.BUTTON_FORWARD) != 0) {
                    conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_X2);
                }
                else {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X2);
                }
            }
        }
    }

    // Handle stylus presses: the stylus tip is a left click, the eraser a right click
    private void handleStylusPress(MotionEvent event) {
        if (event.getPointerCount() == 1 && event.getActionIndex() == 0) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                    lastAbsTouchDownTime = event.getEventTime();
                    lastAbsTouchDownX = event.getX(0);
                    lastAbsTouchDownY = event.getY(0);

                    // Stylus is left click
                    host.conn().sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                    lastAbsTouchDownTime = event.getEventTime();
                    lastAbsTouchDownX = event.getX(0);
                    lastAbsTouchDownY = event.getY(0);

                    // Eraser is right click
                    host.conn().sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                }
            }
            else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                    lastAbsTouchUpTime = event.getEventTime();
                    lastAbsTouchUpX = event.getX(0);
                    lastAbsTouchUpY = event.getY(0);

                    // Stylus is left click
                    host.conn().sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                    lastAbsTouchUpTime = event.getEventTime();
                    lastAbsTouchUpX = event.getX(0);
                    lastAbsTouchUpY = event.getY(0);

                    // Eraser is right click
                    host.conn().sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                }
            }
        }
    }

    // Fingers on the touchscreen, or on a touchpad without buttons
    private boolean handleFingerEvent(View view, MotionEvent event, int eventSource) {
        if (eventSource == InputDevice.SOURCE_TOUCHPAD) {
            return handleTouchInput(event, trackpadContextMap, false);
        }

        if (host.isConfiguringOnscreenControls()) {
            // Ignore presses when the virtual controller is being configured
            return true;
        }

        if (isPanZoomMode) {
            // panning the streamView
            panZoomHandler.handleTouchEvent(event);
            return true;
        }

        // If touch is disabled or not initialized, we'll try panning the streamView
        if (touchContextMap[0] == null) {
            return true;
        }

        if (prefConfig.enableMultiTouchGestures || !prefConfig.enableMultiTouchScreen) {
            int pointerCount = event.getPointerCount();
            if (pointerCount > 2) {
                int eventAction = event.getActionMasked();
                if (
                        (
                                eventAction == MotionEvent.ACTION_POINTER_DOWN
                                        || eventAction == MotionEvent.ACTION_POINTER_UP
                                        || eventAction == MotionEvent.ACTION_UP
                        )
                                && handleMultiTouchGesture(event, eventAction, pointerCount, view)
                ) {
                    return true;
                }
            }
        }

        if (prefConfig.enableMultiTouchScreen && !prefConfig.touchscreenTrackpad && trySendTouchEvent(view, event)) {
            // If this host supports touch events and absolute touch is enabled,
            // send it directly as a touch event.
            return true;
        }

        return handleTouchInput(event, touchContextMap, true);
    }

    private boolean handleTouchInput(MotionEvent event, TouchContext[] inputContextMap, boolean isTouchScreen) {
        // Actual invert logic is handled within the touch context
        return handleTouchInput(event, inputContextMap, isTouchScreen, false, event.getActionMasked(), event.getActionIndex(), event.getPointerCount());
    }

    private boolean handleTouchInput(MotionEvent event, TouchContext[] inputContextMap, boolean isTouchScreen, boolean invertAxis, int eventAction, int actionIndex, int pointerCount) {
        TouchContext context = getTouchContext(actionIndex, inputContextMap);
        if (context == null) {
            return false;
        }

        int actualActionIndex = event.getActionIndex();
        int actualPointerCount = event.getPointerCount();

        boolean shouldDuplicateMovement = actualPointerCount < pointerCount;

        if (eventAction == MotionEvent.ACTION_MOVE) {
            // ACTION_MOVE is special because it always has actionIndex == 0
            // We'll call the move handlers for all indexes manually

            // First process the historical events
            for (int i = 0; i < event.getHistorySize(); i++) {
                for (TouchContext aTouchContextMap : inputContextMap) {
                    if (aTouchContextMap.getActionIndex() < pointerCount)
                    {
                        int aActionIndex = shouldDuplicateMovement ? 0 : aTouchContextMap.getActionIndex();
                        int historicalX = (int)event.getHistoricalX(aActionIndex, i);
                        int historicalY = (int)event.getHistoricalY(aActionIndex, i);
                        if (isTouchScreen) {
                            float[] normalizedCoords = getNormalizedCoordinates(streamContainer, historicalX, historicalY);
                            historicalX = (int)normalizedCoords[0];
                            historicalY = (int)normalizedCoords[1];
                        }

                        // Invert axis again since synthetic events are not inverted
                        // Invert twice could correct the direction
                        // Blame Android for this problem
                        // some devices report inverted axis when trackpad pointer is captured
                        // but not when they're simulated as swipes on the screen
                        if (invertAxis) {
                            aTouchContextMap.touchMoveEvent(
                                    historicalY,
                                    historicalX,
                                    event.getHistoricalEventTime(i)
                            );
                        } else {
                            aTouchContextMap.touchMoveEvent(
                                    historicalX,
                                    historicalY,
                                    event.getHistoricalEventTime(i)
                            );
                        }
                    }
                }
            }

            // Now process the current values
            for (TouchContext aTouchContextMap : inputContextMap) {
                if (aTouchContextMap.getActionIndex() < pointerCount)
                {
                    int aActionIndex = shouldDuplicateMovement ? 0 : aTouchContextMap.getActionIndex();
                    int currentX = (int)event.getX(aActionIndex);
                    int currentY = (int)event.getY(aActionIndex);
                    if (isTouchScreen) {
                        float[] normalizedCoords = getNormalizedCoordinates(streamContainer, currentX, currentY);
                        currentX = (int)normalizedCoords[0];
                        currentY = (int)normalizedCoords[1];
                    }

                    // Invert axis again since synthetic events are not inverted
                    if (invertAxis) {
                        aTouchContextMap.touchMoveEvent(
                                currentY,
                                currentX,
                                event.getEventTime()
                        );
                    } else {
                        aTouchContextMap.touchMoveEvent(
                                currentX,
                                currentY,
                                event.getEventTime());
                    }
                }
            }

            return true;
        }

        int eventX = (int)event.getX(actualActionIndex);
        int eventY = (int)event.getY(actualActionIndex);

        // Handle view scaling
        if (isTouchScreen) {
            float[] normalizedCoords = getNormalizedCoordinates(streamContainer, eventX, eventY);
            eventX = (int)normalizedCoords[0];
            eventY = (int)normalizedCoords[1];
        }

        switch (eventAction)
        {
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_DOWN:
                for (TouchContext touchContext : inputContextMap) {
                    touchContext.setPointerCount(pointerCount);
                }
                context.touchDownEvent(eventX, eventY, event.getEventTime(), true);
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                if (prefConfig.touchscreenTrackpad) {
                    if (pointerCount == 1 &&
                            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || (event.getFlags() & MotionEvent.FLAG_CANCELED) == 0)) {
                        // All fingers up
                        long currentEventTime = event.getEventTime();
                        if (currentEventTime - threeFingerDownTime < THREE_FINGER_TAP_THRESHOLD) {
                            // This is a 3 finger tap to bring up the keyboard
                            host.toggleKeyboard();
                            return true;
                        } else if (currentEventTime - fourFingerDownTime < FOUR_FINGER_TAP_THRESHOLD) {
                            host.toggleFullKeyboard();
                            return true;
                        } else if (currentEventTime - fiveFingerDownTime < FIVE_FINGER_TAP_THRESHOLD) {
                            host.showGameMenu();
                            return true;
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
                    context.cancelTouch();
                }
                else {
                    context.touchUpEvent(eventX, eventY, event.getEventTime());
                }

                for (TouchContext touchContext : inputContextMap) {
                    touchContext.setPointerCount(pointerCount - 1);
                }
                if (actionIndex == 0 && pointerCount > 1 && !context.isCancelled()) {
                    // The original secondary touch now becomes primary
                    int pointer1X = (int)event.getX(1);
                    int pointer1Y = (int)event.getY(1);
                    if (isTouchScreen) {
                        float[] normalizedCoords = getNormalizedCoordinates(streamContainer, pointer1X, pointer1Y);
                        pointer1X = (int)normalizedCoords[0];
                        pointer1Y = (int)normalizedCoords[1];
                    }
                    context.touchDownEvent(
                            pointer1X,
                            pointer1Y,
                            event.getEventTime(), false);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                for (TouchContext aTouchContext : inputContextMap) {
                    aTouchContext.cancelTouch();
                    aTouchContext.setPointerCount(0);
                }
                break;
            default:
                return false;
        }

        return true;
    }

    private boolean handleMultiTouchGesture(MotionEvent event, int eventAction, int pointerCount, View view) {

        if (eventAction == MotionEvent.ACTION_POINTER_DOWN) {
            if (pointerCount == 3) {
                threeFingerDownTime = event.getEventTime();
            } else if (pointerCount == 4) {
                threeFingerDownTime = 0;
                fourFingerDownTime = event.getEventTime();
            } else if (pointerCount == 5) {
                threeFingerDownTime = 0;
                fourFingerDownTime = 0;
                fiveFingerDownTime = event.getEventTime();
            }
        }

        switch (eventAction) {
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                long currentEventTime = event.getEventTime();
                if (pointerCount >= 5 && fiveFingerDownTime > 0 && currentEventTime - fiveFingerDownTime < FIVE_FINGER_TAP_THRESHOLD) {
                    host.showGameMenu();
                    fiveFingerDownTime = 0;
                    break;
                } else if (pointerCount == 4 && fourFingerDownTime > 0 && currentEventTime - fourFingerDownTime < FOUR_FINGER_TAP_THRESHOLD) {
                    host.toggleFullKeyboard();
                    fourFingerDownTime = 0;
                    break;
                } else if (pointerCount == 3 && threeFingerDownTime > 0 && currentEventTime - threeFingerDownTime < THREE_FINGER_TAP_THRESHOLD) {
                    host.toggleKeyboard();
                    threeFingerDownTime = 0;
                    break;
                }
                threeFingerDownTime = 0;
                fourFingerDownTime = 0;
                fiveFingerDownTime = 0;

                cancelStaleTouchState(event, view);
                return false;
            default:
                return false;
        }

        cancelStaleTouchState(event, view);
        return true;
    }

    private void cancelStaleTouchState(MotionEvent event, View view) {
        MotionEvent cancelEvent = MotionEvent.obtain(event);
        cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
        view.dispatchTouchEvent(cancelEvent);
        cancelEvent.recycle();
        for (TouchContext aTouchContext : touchContextMap) {
            aTouchContext.cancelTouch();
            aTouchContext.setPointerCount(0);
        }
    }

    private void updateMousePosition(View touchedView, MotionEvent event) {
        // X and Y are already relative to the provided view object
        float eventX, eventY;
        // For our StreamView itself, we can use the coordinates unmodified.

        if (touchedView == streamContainer) {
            eventX = event.getX(0);
            eventY = event.getY(0);
        } else {
            // For the containing background view, we must subtract the origin
            // of the StreamView to get video-relative coordinates.
            eventX = event.getX(0) - streamContainer.getX();
            eventY = event.getY(0) - streamContainer.getY();
        }

        if (event.getPointerCount() == 1 && event.getActionIndex() == 0 &&
                (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
                        event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS))
        {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_EXIT:
                case MotionEvent.ACTION_HOVER_MOVE:
                    if (event.getEventTime() - lastAbsTouchUpTime <= STYLUS_UP_DEAD_ZONE_DELAY &&
                            Math.sqrt(Math.pow(eventX - lastAbsTouchUpX, 2) + Math.pow(eventY - lastAbsTouchUpY, 2)) <= STYLUS_UP_DEAD_ZONE_RADIUS) {
                        // Enforce a small deadzone between touch up and hover or touch down to allow more precise double-clicking
                        return;
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    if (event.getEventTime() - lastAbsTouchDownTime <= STYLUS_DOWN_DEAD_ZONE_DELAY &&
                            Math.sqrt(Math.pow(eventX - lastAbsTouchDownX, 2) + Math.pow(eventY - lastAbsTouchDownY, 2)) <= STYLUS_DOWN_DEAD_ZONE_RADIUS) {
                        // Enforce a small deadzone between touch down and move or touch up to allow more precise double-clicking
                        return;
                    }
                    break;
            }
        }

        // We may get values slightly outside our view region on ACTION_HOVER_ENTER and ACTION_HOVER_EXIT.
        // Normalize these to the view size. We can't just drop them because we won't always get an event
        // right at the boundary of the view, so dropping them would result in our cursor never really
        // reaching the sides of the screen.
        eventX = Math.min(Math.max(eventX, 0), streamContainer.getWidth());
        eventY = Math.min(Math.max(eventY, 0), streamContainer.getHeight());

        host.conn().sendMousePosition((short)eventX, (short)eventY, (short) streamContainer.getWidth(), (short) streamContainer.getHeight());
    }
}
