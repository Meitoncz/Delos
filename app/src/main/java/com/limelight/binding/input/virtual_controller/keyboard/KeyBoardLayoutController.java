/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller.keyboard;

import android.content.Context;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.compose.ui.platform.ComposeView;

import com.limelight.binding.input.ActiveKeyboardInput;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.shell.keyboard.FullKeyboardHost;
import com.limelight.shell.keyboard.FullKeyboardInputKt;
import com.limelight.shell.keyboard.FullKeyboardState;
import com.limelight.shell.keyboard.FullKeyboardView;
import com.limelight.shell.keyboard.KeyHaptic;

/**
 * The Full keyboard over the stream (Delos: drawn by Compose, see {@code shell/keyboard} and docs/GUI.md, "Full keyboard").
 * This class is its bridge to the stream: the visibility the activities toggle, the size settings, haptics, the controller
 * input the stream forwards while it is up, and the one path every key takes to the host ({@link #sendKeyEvent}).
 */
public class KeyBoardLayoutController implements FullKeyboardHost {
    private final PreferenceConfiguration prefConfig;
    private ViewCallbacks viewCallbacks;
    private final FrameLayout frame_layout;
    public boolean shown = false;
    private final FullKeyboardState state;
    private final ComposeView keyboardView;

    public KeyBoardLayoutController(FrameLayout layout, final Context context, PreferenceConfiguration prefConfig) {
        this.frame_layout = layout;
        this.prefConfig = prefConfig;
        this.state = new FullKeyboardState(this);
        this.keyboardView = FullKeyboardView.createFullKeyboardView(context, state);
    }

    public void setViewCallbacks(ViewCallbacks viewCallbacks) {
        this.viewCallbacks = viewCallbacks;
    }

    public boolean isKeyboardVisible() {
        return state.getOpen();
    }

    public void hide(boolean temporary) {
        if (prefConfig.enableKeyboardVibrate) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                keyboardView.performHapticFeedback(HapticFeedbackConstants.REJECT);
            } else {
                keyboardView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
        }
        state.hide();
        if (!temporary) {
            shown = false;
        }

        if (viewCallbacks != null) {
            viewCallbacks.onKeyboardControllerVisibilityChange(false);
        }
    }

    public void hide() {
        hide(false);
    }

    public void show() {
        state.show();
        shown = true;
        if (viewCallbacks != null) {
            viewCallbacks.onKeyboardControllerVisibilityChange(true);
        }
    }

    public void toggleVisibility() {
        if (isKeyboardVisible()) {
            hide();
        } else {
            show();
        }
    }

    /**
     * Applies the size and opacity settings and puts the keyboard on top of the layout. The keyboard sizes itself from
     * the room it is given (docked to the bottom, the width of the layout), so a new screen size needs no more than this.
     */
    public void refreshLayout() {
        state.setPrefs(prefConfig.onscreenKeyboardAutoFitDisabled, prefConfig.onscreenKeyboardHeight,
                prefConfig.onscreenKeyboardWidth, prefConfig.onscreenKeyboardAlignMode, prefConfig.oscKeyboardOpacity);
        if (keyboardView.getParent() == null) {
            frame_layout.addView(keyboardView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        } else {
            keyboardView.bringToFront();
        }
    }

    /** Whether [event] comes from a controller, which the open keyboard owns (the touch screen, a mouse and a physical keyboard keep their ways). */
    public boolean ownsInputFrom(InputEvent event) {
        return FullKeyboardInputKt.isControllerEvent(event);
    }

    /** A controller button while the keyboard is up: moves the focus, presses, closes. Always consumed. */
    public boolean onControllerKey(KeyEvent event) {
        return state.onControllerKey(event);
    }

    /** A joystick event while the keyboard is up: the hat (D-pad) and the left stick move the focus. Always consumed. */
    public boolean onControllerMotion(MotionEvent event) {
        return state.onControllerMotion(event);
    }

    public static void sendKeyEvent(KeyEvent keyEvent) {
        ActiveKeyboardInput.Target target = ActiveKeyboardInput.connectedTarget();
        if (target == null) {
            return;
        }
        // 1-Mouse 0-Buttons 2-Stick 3-DPad
        if (keyEvent.getSource() == 1) {
            target.mouseButtonEvent(keyEvent.getKeyCode(), KeyEvent.ACTION_DOWN == keyEvent.getAction());
        } else {
            target.onKey(null, keyEvent.getKeyCode(), keyEvent);
        }
    }

    // FullKeyboardHost

    @Override
    public boolean getStickyModifiers() {
        return prefConfig.stickyModifierKey;
    }

    @Override
    public void sendKey(int code, boolean down) {
        KeyEvent keyEvent = new KeyEvent(down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, code);
        keyEvent.setSource(0);
        sendKeyEvent(keyEvent);
    }

    @Override
    public void haptic(KeyHaptic haptic) {
        if (!prefConfig.enableKeyboardVibrate) {
            return;
        }
        switch (haptic) {
            case PRESS:
                keyboardView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
                break;
            case RELEASE:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    keyboardView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE);
                } else {
                    keyboardView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
                break;
            case LATCH:
                keyboardView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                break;
        }
    }

    @Override
    public void close() {
        hide();
    }

    public interface ViewCallbacks {
        void onKeyboardControllerVisibilityChange(boolean visible);
    }
}
