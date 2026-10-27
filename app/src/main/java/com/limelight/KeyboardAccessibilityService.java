package com.limelight;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import com.limelight.utils.ExternalDisplayControlActivity;

import java.util.Arrays;
import java.util.List;

/**
 * Captures keyboard shortcuts that the system would otherwise consume (e.g. Home, Alt+Tab)
 * and forwards them to the host while a stream is in the foreground.
 */
public class KeyboardAccessibilityService extends AccessibilityService {

    // Keys that are never captured, so the device keeps handling them
    private static final List<Integer> PASSTHROUGH_KEYS = Arrays.asList(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_POWER
    );

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        Game game = Game.instance;
        if (game == null || !game.connected || !isStreamFocused(game) ||
                PASSTHROUGH_KEYS.contains(event.getKeyCode())) {
            return super.onKeyEvent(event);
        }

        // Some tablets (e.g. Xiaomi Pad) report the Esc key as KEYCODE_BACK with scancode 1
        boolean isEscScanCode = event.getScanCode() == 1;
        KeyEvent routedEvent = isEscScanCode ? new KeyEvent(event.getAction(), KeyEvent.KEYCODE_ESCAPE) : event;
        ExternalDisplayControlActivity controller = ExternalDisplayControlActivity.instance;
        boolean externalDisplayFocused = controller != null && controller.hasWindowFocus();

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (externalDisplayFocused) game.handleExternalDisplayKeyEvent(routedEvent);
            else game.handleKeyDown(routedEvent);
            return true;
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            if (externalDisplayFocused) game.handleExternalDisplayKeyEvent(routedEvent);
            else game.handleKeyUp(routedEvent);
            return true;
        }

        return super.onKeyEvent(event);
    }

    /**
     * Keys are only captured while the stream window (or the external display controller)
     * has focus - never while Delos is in the background or in picture-in-picture,
     * so typing in other apps never reaches the host.
     */
    private static boolean isStreamFocused(Game game) {
        if (game.hasWindowFocus()) {
            return true;
        }

        ExternalDisplayControlActivity controller = ExternalDisplayControlActivity.instance;
        return controller != null && controller.hasWindowFocus();
    }

    @Override
    public void onServiceConnected() {
        LimeLog.info("Keyboard service is connected");
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        // We only filter key events; no accessibility events from other apps are needed
        info.packageNames = new String[] { BuildConfig.APPLICATION_ID };
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.notificationTimeout = 100;
        info.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {
    }

    @Override
    public void onInterrupt() {
    }
}
