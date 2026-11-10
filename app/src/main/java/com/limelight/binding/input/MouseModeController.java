package com.limelight.binding.input;

import android.content.Context;

import com.limelight.R;
import com.limelight.binding.input.touch.AbsoluteTouchContext;
import com.limelight.binding.input.touch.RelativeTouchContext;
import com.limelight.binding.input.touch.TouchContext;
import com.limelight.binding.input.touch.TrackpadContext;
import com.limelight.nvstream.NvConnection;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;
import com.limelight.ui.StreamContainer;

/**
 * The touchscreen's mouse mode (0 multi-touch, 1 mouse, 2 trackpad natural, 3 trackpad gaming, 4 touch disabled,
 * 5 mouse with swapped buttons): the starting mode from the profile, the external display's trackpad override, the
 * stream menu's live switch with its optional persistence, and the touch contexts of {@link StreamPointerHandler}
 * each mode installs.
 */
public final class MouseModeController {
    private static final int REFERENCE_HORIZ_RES = 1280;
    private static final int REFERENCE_VERT_RES = 720;

    /** What the mouse mode needs from the stream activity. */
    public interface Host {
        NvConnection conn();

        boolean isOnExternalDisplay();
    }

    private final Context context;
    private final PreferenceConfiguration prefConfig;
    private final StreamContainer streamContainer;
    private final StreamPointerHandler pointerHandler;
    private final Host host;

    private int activeMouseMode;
    private boolean changeAllowed = true;

    /** {@code context} is the stream activity (the mode names and the profile's preferences). */
    public MouseModeController(Context context, PreferenceConfiguration prefConfig, StreamContainer streamContainer,
                               StreamPointerHandler pointerHandler, Host host) {
        this.context = context;
        this.prefConfig = prefConfig;
        this.streamContainer = streamContainer;
        this.pointerHandler = pointerHandler;
        this.host = host;
    }

    public int getActiveMouseMode() {
        return activeMouseMode;
    }

    /** False when the stream forces a mode (remote input), so the stream menu hides the choice. */
    public boolean isChangeAllowed() {
        return changeAllowed;
    }

    /** Remote input: force trackpad mode since we won't see anything on the screen. */
    public void forceTrackpad() {
        changeAllowed = false;
        applyMouseMode(2);
    }

    /**
     * Initializes and applies the appropriate mouse mode based on user preferences
     * and whether the app is running in secondary display mode (such as Samsung DeX).
     *
     * Behavior:
     * - If the app is in secondary display mode:
     *   - Applies the user's saved mouse mode if it's one of the supported modes:
     *     "Trackpad Natural", "Trackpad Gaming", or "Disabled".
     *   - Otherwise, defaults to applying the "Trackpad Natural" mode.
     *
     * - If the app is not in secondary display mode:
     *   - Applies the user's saved mouse mode as is.
     *
     * This ensures the correct input mode is applied depending on the environment,
     * improving compatibility with desktop-like multi-display modes.
     */
    public void initMouseMode() {
        String[] mouseModes = context.getResources().getStringArray(R.array.mouse_mode_names);

        String savedMouseModeIndexStr = ProfilesManager.getInstance()
                .getOverlayingSharedPreferences(context)
                .getString("mouse_mode_list", "0");

        int savedMouseModeIndex;
        try {
            savedMouseModeIndex = Integer.parseInt(savedMouseModeIndexStr);
        } catch (NumberFormatException e) {
            savedMouseModeIndex = 0;
        }

        String savedMouseModeString = (savedMouseModeIndex >= 0 && savedMouseModeIndex < mouseModes.length)
                ? mouseModes[savedMouseModeIndex]
                : null;

        String natural = context.getString(R.string.mouse_mode_track_pad_natural);
        String gaming = context.getString(R.string.mouse_mode_track_pad_gaming);
        String disabled = context.getString(R.string.mouse_mode_disabled);

        int naturalIndex = 2; //fallback natural mode for secondary screen
        for (int i = 0; i < mouseModes.length; i++) {
            if (mouseModes[i].equals(natural)) {
                naturalIndex = i;
                break;
            }
        }
        // We only want to temporary override the mouse mode to work with external, but not store it
        if (host.isOnExternalDisplay()) {
            if (savedMouseModeString != null &&
                    (savedMouseModeString.equals(natural) ||
                            savedMouseModeString.equals(gaming) ||
                            savedMouseModeString.equals(disabled))) {
                applyMouseMode(savedMouseModeIndex);
            } else {
                applyMouseMode(naturalIndex);
            }
        } else {
            applyMouseMode(savedMouseModeIndex);
        }
    }

    /** Delos: the native stream menu reuses the same mode and persistence rules as the old chooser. */
    public void setStreamMouseMode(int mode) {
        if (mode < 0 || mode > 5 || (host.isOnExternalDisplay() && mode != 2 && mode != 3 && mode != 4)) return;
        applyMouseMode(mode);
        if (prefConfig.rememberMouseMode) {
            ProfilesManager.getInstance().getOverlayingSharedPreferences(context).edit()
                    .putString("mouse_mode_list", String.valueOf(mode)).apply();
        }
    }

    void applyMouseMode(int mode) {
        activeMouseMode = mode;
        switch (mode) {
            case 0: // Multi-touch
                prefConfig.enableMultiTouchScreen = true;
                prefConfig.touchscreenTrackpad = false;
                break;
            case 1: // Normal mouse
            case 5: // Normal mouse with swapped buttons
                prefConfig.enableMultiTouchScreen = false;
                prefConfig.touchscreenTrackpad = false;
                break;
            case 2: // Trackpad (natural)
            case 3: // Trackpad (gaming)
                prefConfig.enableMultiTouchScreen = false;
                prefConfig.touchscreenTrackpad = true;
                break;
            case 4: // Touch mouse disabled
                break;
            default:
                break;
        }

        //Initialize touch contexts
        NvConnection conn = host.conn();
        TouchContext[] touchContextMap = pointerHandler.touchContexts();
        for (int i = 0; i < touchContextMap.length; i++) {
            if (touchContextMap[i] != null) touchContextMap[i].cancelTouch();
            if (mode == 4) {
                // Touch mouse disabled
                touchContextMap[i] = null;
            } else if (!prefConfig.touchscreenTrackpad) {
                touchContextMap[i] = new AbsoluteTouchContext(conn, i, streamContainer, mode == 5);
            } else if (mode == 3) {
                touchContextMap[i] = new RelativeTouchContext(conn, i, REFERENCE_HORIZ_RES, REFERENCE_VERT_RES, streamContainer, prefConfig);
            } else {
                touchContextMap[i] = new TrackpadContext(conn, i);
            }
        }

        // Always exit zoom mode if mouse mode has changed
        pointerHandler.exitZoomMode();
    }
}
