package com.limelight.binding.input;

import static com.limelight.binding.input.KeyboardTranslator.getModifier;

import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import androidx.annotation.Nullable;

import com.limelight.binding.input.virtual_controller.keyboard.KeyBoardLayoutController;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The stream's key input: routes key events (the stream menu, the Full keyboard and gamepad buttons come first, then
 * the physical or soft keyboard), tracks the modifier state and runs the client-side combos Ctrl+Alt+Shift+Z (toggle
 * the input grab), +Q (quit) and +C (toggle the local cursor), which fire once every modifier is up again. {@code DelosStreamActivity}
 * forwards the Activity's and the stream view's key callbacks to it; when focus is lost it releases the keys that
 * are down on the host.
 */
public final class StreamKeyHandler {
    /** How long {@link #sendKeys} holds a key combination before releasing it. */
    private static final long KEY_UP_DELAY = 25;

    /** What the key handler needs from the stream activity. */
    public interface Host {
        NvConnection conn();

        KeyboardTranslator keyboardTranslator();

        ControllerHandler controllerHandler();

        /** Whether the stream grabs input; keyboard keys pass through to Android otherwise. */
        boolean isInputGrabbed();

        /** Whether the stream menu takes the key (its shortcut, or the open menu on this display). */
        boolean streamMenuTakesKey(KeyEvent event);

        /** The Full keyboard that is up, on this display or on the external control display; null when there is none. */
        @Nullable
        KeyBoardLayoutController visibleFullKeyboard();

        /** Ctrl+Alt+Shift+Z: toggles the input grab after a short delay. */
        void toggleInputGrabLater();

        /** Ctrl+Alt+Shift+Q: ends the stream. */
        void quit();

        /** Ctrl+Alt+Shift+C: shows or hides the local mouse cursor. */
        void toggleLocalCursor();
    }

    private final PreferenceConfiguration prefConfig;
    private final Host host;

    private int modifierFlags = 0;
    private boolean waitingForAllModifiersUp = false;
    private int specialKeyCode = KeyEvent.KEYCODE_UNKNOWN;

    // The keys this handler pressed on the host and has not released yet, in press order: the host's virtual key
    // and the flags it was sent with. Guarded by itself: the evdev reader calls in from its own thread.
    private final Map<Short, Byte> heldKeys = new LinkedHashMap<>();

    public StreamKeyHandler(PreferenceConfiguration prefConfig, Host host) {
        this.prefConfig = prefConfig;
        this.host = host;
    }

    /**
     * We can't guarantee the state of modifiers keys which may have lifted while focus was not on us, so the
     * modifier state is cleared when the window regains focus; losing it goes through {@link #releaseHeldKeys}.
     */
    public void clearModifierState() {
        this.modifierFlags = 0;
    }

    /**
     * The window the keys come from lost focus. A key lifted while focus is elsewhere never reaches us, so every
     * key that is down on the host (modifiers included) is released there, last pressed first, and the modifier
     * state is cleared. Without this a held Ctrl or Alt stays pressed on the host.
     */
    public void releaseHeldKeys() {
        short[] keys;
        byte[] flags;
        synchronized (heldKeys) {
            keys = new short[heldKeys.size()];
            flags = new byte[keys.length];
            int next = 0;
            for (Map.Entry<Short, Byte> held : heldKeys.entrySet()) {
                keys[next] = held.getKey();
                flags[next++] = held.getValue();
            }
            heldKeys.clear();
        }

        modifierFlags = 0;
        for (int i = keys.length - 1; i >= 0; i--) {
            host.conn().sendKeyboardInput(keys[i], KeyboardPacket.KEY_UP, (byte) 0, flags[i]);
        }
    }

    /** Sends a key to the host and remembers it while it is down there. */
    private void sendKey(short key, boolean down, byte modifiers, byte flags) {
        synchronized (heldKeys) {
            if (down) {
                heldKeys.put(key, flags);
            } else {
                heldKeys.remove(key);
            }
        }
        host.conn().sendKeyboardInput(key, down ? KeyboardPacket.KEY_DOWN : KeyboardPacket.KEY_UP, modifiers, flags);
    }

    // Returns true if the key stroke was consumed
    boolean handleSpecialKeys(int androidKeyCode, boolean down) {
        int modifierMask = 0;
        int nonModifierKeyCode = KeyEvent.KEYCODE_UNKNOWN;

        if (androidKeyCode == KeyEvent.KEYCODE_CTRL_LEFT ||
                androidKeyCode == KeyEvent.KEYCODE_CTRL_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_CTRL;
        }
        else if (androidKeyCode == KeyEvent.KEYCODE_SHIFT_LEFT ||
                androidKeyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_SHIFT;
        }
        else if (androidKeyCode == KeyEvent.KEYCODE_ALT_LEFT ||
                androidKeyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
            if (prefConfig.rightAltAsMeta && androidKeyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
                // Remap Right Alt → Command (VK_LWIN) for Mac streaming
                if (down) {
                    modifierFlags |= KeyboardPacket.MODIFIER_META;
                } else {
                    modifierFlags &= ~KeyboardPacket.MODIFIER_META;
                }
                sendKey((short) KeyboardTranslator.VK_LWIN, down, (byte) modifierFlags, (byte) 0);
                return true;
            }
            modifierMask = KeyboardPacket.MODIFIER_ALT;
        }
        else if (androidKeyCode == KeyEvent.KEYCODE_META_LEFT ||
                androidKeyCode == KeyEvent.KEYCODE_META_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_META;
        }
        else {
            nonModifierKeyCode = androidKeyCode;
        }

        if (down) {
            this.modifierFlags |= modifierMask;
        }
        else {
            this.modifierFlags &= ~modifierMask;
        }

        // Handle the special combos on the key up
        if (waitingForAllModifiersUp || specialKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
            if (specialKeyCode == androidKeyCode) {
                // If this is a key up for the special key itself, eat that because the host never saw the original key down
                return true;
            }
            else if (modifierFlags != 0) {
                // While we're waiting for modifiers to come up, eat all key downs and allow all key ups to pass
                return down;
            }
            else {
                // When all modifiers are up, perform the special action
                switch (specialKeyCode) {
                    // Toggle input grab
                    case KeyEvent.KEYCODE_Z:
                        host.toggleInputGrabLater();
                        break;

                    // Quit
                    case KeyEvent.KEYCODE_Q:
                        host.quit();
                        break;

                    // Toggle cursor visibility
                    case KeyEvent.KEYCODE_C:
                        host.toggleLocalCursor();
                        break;

                    default:
                        break;
                }

                // Reset special key state
                specialKeyCode = KeyEvent.KEYCODE_UNKNOWN;
                waitingForAllModifiersUp = false;
            }
        }
        // Check if Ctrl+Alt+Shift is down when a non-modifier key is pressed
        else if ((modifierFlags & (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_ALT | KeyboardPacket.MODIFIER_SHIFT)) ==
                (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_ALT | KeyboardPacket.MODIFIER_SHIFT) &&
                (down && nonModifierKeyCode != KeyEvent.KEYCODE_UNKNOWN)) {
            switch (androidKeyCode) {
                case KeyEvent.KEYCODE_Z:
                case KeyEvent.KEYCODE_Q:
                case KeyEvent.KEYCODE_C:
                    // Remember that a special key combo was activated, so we can consume all key
                    // events until the modifiers come up
                    specialKeyCode = androidKeyCode;
                    waitingForAllModifiersUp = true;
                    return true;

                default:
                    // This isn't a special combo that we consume on the client side
                    return false;
            }
        }

        // Not a special combo
        return false;
    }

    // We cannot simply use modifierFlags for all key event processing, because
    // some IMEs will not generate real key events for pressing Shift. Instead
    // they will simply send key events with isShiftPressed() returning true,
    // and we will need to send the modifier flag ourselves.
    byte getModifierState(KeyEvent event) {
        // Start with the global modifier state to ensure we cover the case
        // detailed in https://github.com/moonlight-stream/moonlight-android/issues/840
        byte modifier = getModifierState();
        if (event.isShiftPressed()) {
            modifier |= KeyboardPacket.MODIFIER_SHIFT;
        }
        if (event.isCtrlPressed()) {
            modifier |= KeyboardPacket.MODIFIER_CTRL;
        }
        if (event.isAltPressed()) {
            // When Right Alt is remapped to Meta, only treat Alt as held if Left Alt is specifically pressed
            if (!prefConfig.rightAltAsMeta || (event.getMetaState() & KeyEvent.META_ALT_LEFT_ON) != 0) {
                modifier |= KeyboardPacket.MODIFIER_ALT;
            }
        }
        if (event.isMetaPressed()) {
            modifier |= KeyboardPacket.MODIFIER_META;
        }
        return applyKeySpecificModifiers(event.getKeyCode(), modifier);
    }

    byte getModifierState(int keyCode) {
        return applyKeySpecificModifiers(keyCode, getModifierState());
    }

    static byte applyKeySpecificModifiers(int keyCode, byte modifier) {
        if (keyCode == KeyEvent.KEYCODE_PLUS) {
            // The host protocol has a single US =/+ virtual key, so Android's semantic plus key needs Shift.
            modifier |= KeyboardPacket.MODIFIER_SHIFT;
        }
        return modifier;
    }

    byte getModifierState() {
        return (byte) modifierFlags;
    }

    /** A key event of any action (the stream view's and the external display's key listener). */
    @SuppressWarnings("deprecation") // KeyEvent.ACTION_MULTIPLE remains possible for physical keyboards
    public boolean handleKeyEvent(KeyEvent event) {
        switch (event.getAction()) {
            case KeyEvent.ACTION_DOWN:
                return handleKeyDown(event);
            case KeyEvent.ACTION_UP:
                return handleKeyUp(event);
            case KeyEvent.ACTION_MULTIPLE:
                return handleKeyMultiple(event);
            default:
                return false;
        }
    }

    public boolean handleKeyDown(KeyEvent event) {
        // Delos: the stream menu shortcut first. The focused stream view gets keys before the activity's
        // dispatchKeyEvent() (onKeyPreIme), and the controller handler below eats every gamepad button.
        if (host.streamMenuTakesKey(event)) {
            return true;
        }

        // Pass-through virtual navigation keys
        if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false;
        }

        int deviceId = event.getDeviceId();
        if (prefConfig.ignoreSynthEvents && deviceId <= 0) {
            return false;
        }

        // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click. This event WILL repeat if
        // the right mouse button is held down, so we ignore those.
        int eventSource = event.getSource();
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
                event.getKeyCode() == KeyEvent.KEYCODE_BACK) {

            // Send the right mouse button event if mouse back and forward
            // are disabled. If they are enabled, handleMotionEvent() will take
            // care of this.
            if (!prefConfig.mouseNavButtons) {
                host.conn().sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
            }

            // Always return true, otherwise the back press will be propagated
            // up to the parent and finish the activity.
            return true;
        }

        // Delos: while the Full keyboard is up it owns the controller (docs/GUI.md, "Full keyboard")
        KeyBoardLayoutController fullKeyboard = host.visibleFullKeyboard();
        if (fullKeyboard != null && fullKeyboard.ownsInputFrom(event)) {
            return fullKeyboard.onControllerKey(event);
        }

        boolean handled = false;

        if (ControllerHandler.isGameControllerDevice(event.getDevice())) {
            // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = host.controllerHandler().handleButtonDown(event);
        }

        // Try the keyboard handler if it wasn't handled as a game controller
        if (!handled) {
            // Let this method take duplicate key down events
            if (handleSpecialKeys(event.getKeyCode(), true)) {
                return true;
            }

            // Pass through keyboard input if we're not grabbing
            if (!host.isInputGrabbed()) {
                return false;
            }

            // We'll send it as a raw key event if we have a key mapping, otherwise we'll send it
            // as UTF-8 text (if it's a printable character).
            KeyboardTranslator keyboardTranslator = host.keyboardTranslator();
            short translated = keyboardTranslator.translate(event.getKeyCode(), event.getScanCode(), deviceId);
            if (translated == 0) {
                // Make sure it has a valid Unicode representation and it's not a dead character
                // (which we don't support). If those are true, we can send it as UTF-8 text.
                //
                // NB: We need to be sure this happens before the getRepeatCount() check because
                // UTF-8 events don't auto-repeat on the host side.
                int unicodeChar = event.getUnicodeChar();
                if ((unicodeChar & KeyCharacterMap.COMBINING_ACCENT) == 0 && (unicodeChar & KeyCharacterMap.COMBINING_ACCENT_MASK) != 0) {
                    host.conn().sendUtf8Text(""+(char)unicodeChar);
                    return true;
                }

                return false;
            }

            // Eat repeat down events
            if (event.getRepeatCount() > 0) {
                return true;
            }

            sendKey(translated, true, getModifierState(event),
                    keyboardTranslator.hasNormalizedMapping(event.getKeyCode(), deviceId) ? 0 : MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
        }

        return true;
    }

    public boolean handleKeyUp(KeyEvent event) {
        // Delos: see handleKeyDown()
        if (host.streamMenuTakesKey(event)) {
            return true;
        }

        // Pass-through virtual navigation keys
        if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false;
        }

        int deviceId = event.getDeviceId();
        if (prefConfig.ignoreSynthEvents && deviceId <= 0) {
            return false;
        }

        // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click.
        int eventSource = event.getSource();
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
                event.getKeyCode() == KeyEvent.KEYCODE_BACK) {

            // Send the right mouse button event if mouse back and forward
            // are disabled. If they are enabled, handleMotionEvent() will take
            // care of this.
            if (!prefConfig.mouseNavButtons) {
                host.conn().sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
            }

            // Always return true, otherwise the back press will be propagated
            // up to the parent and finish the activity.
            return true;
        }

        // Delos: see handleKeyDown()
        KeyBoardLayoutController fullKeyboard = host.visibleFullKeyboard();
        if (fullKeyboard != null && fullKeyboard.ownsInputFrom(event)) {
            return fullKeyboard.onControllerKey(event);
        }

        boolean handled = false;
        if (ControllerHandler.isGameControllerDevice(event.getDevice())) {
            // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = host.controllerHandler().handleButtonUp(event);
        }

        // Try the keyboard handler if it wasn't handled as a game controller
        if (!handled) {
            if (handleSpecialKeys(event.getKeyCode(), false)) {
                return true;
            }

            // Pass through keyboard input if we're not grabbing
            if (!host.isInputGrabbed()) {
                return false;
            }

            KeyboardTranslator keyboardTranslator = host.keyboardTranslator();
            short translated = keyboardTranslator.translate(event.getKeyCode(), event.getScanCode(), deviceId);
            if (translated == 0) {
                // If we sent this event as UTF-8 on key down, also report that it was handled
                // when we get the key up event for it.
                int unicodeChar = event.getUnicodeChar();
                return (unicodeChar & KeyCharacterMap.COMBINING_ACCENT) == 0 && (unicodeChar & KeyCharacterMap.COMBINING_ACCENT_MASK) != 0;
            }

            sendKey(translated, false, getModifierState(event),
                    keyboardTranslator.hasNormalizedMapping(event.getKeyCode(), deviceId) ? 0 : MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
        }

        return true;
    }

    public boolean handleKeyMultiple(KeyEvent event) {
        // We can receive keys from a software keyboard that don't correspond to any existing
        // KEYCODE value. Android will give those to us as an ACTION_MULTIPLE KeyEvent.
        //
        // Despite the fact that the Android docs say this is unused since API level 29, these
        // events are still sent as of Android 13 for the above case.
        //
        // For other cases of ACTION_MULTIPLE, we will not report those as handled so hopefully
        // they will be passed to us again as regular singular key events.
        if (event.getKeyCode() != KeyEvent.KEYCODE_UNKNOWN || event.getCharacters() == null) {
            return false;
        }

        host.conn().sendUtf8Text(event.getCharacters());
        return true;
    }

    /** A key from the evdev reader (an Android keycode). */
    public void keyboardEvent(boolean buttonDown, short keyCode) {
        short keyMap = host.keyboardTranslator().translate(keyCode, 0, -1);
        if (keyMap != 0) {
            // handleSpecialKeys() takes the Android keycode
            if (handleSpecialKeys(keyCode, buttonDown)) {
                return;
            }

            // Like handleKeyDown() and handleKeyUp(): the keyboard is not the host's while we're not grabbing
            if (!host.isInputGrabbed()) {
                return;
            }

            sendKey(keyMap, buttonDown, getModifierState(keyCode), (byte) 0);
        }
    }

    /** Presses the host virtual keys in order and releases them in reverse after a short delay (menu special keys). */
    public void sendKeys(short[] keys) {
        final byte[] modifier = {(byte) 0};

        for (short key : keys) {
            host.conn().sendKeyboardInput(key, KeyboardPacket.KEY_DOWN, modifier[0], (byte) 0);

            // Apply the modifier of the pressed key, e.g. CTRL first issues a CTRL event (without
            // modifier) and then sends the following keys with the CTRL modifier applied
            modifier[0] |= getModifier(key);
        }

        new Handler(Looper.getMainLooper()).postDelayed((() -> {
            for (int pos = keys.length - 1; pos >= 0; pos--) {
                short key = keys[pos];

                // Remove the keys modifier before releasing the key
                modifier[0] &= (byte) ~getModifier(key);

                host.conn().sendKeyboardInput(key, KeyboardPacket.KEY_UP, modifier[0], (byte) 0);
            }
        }), KEY_UP_DELAY);
    }
}
