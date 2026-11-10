package com.limelight.binding.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.view.KeyEvent;

import com.limelight.binding.input.virtual_controller.keyboard.KeyBoardLayoutController;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/** Characterisation tests: the modifier state and the client-side Ctrl+Alt+Shift combos Game handled before the extraction. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class StreamKeyHandlerTest {
    private static final byte CTRL = KeyboardPacket.MODIFIER_CTRL;
    private static final byte SHIFT = KeyboardPacket.MODIFIER_SHIFT;
    private static final byte ALT = KeyboardPacket.MODIFIER_ALT;
    private static final byte META = KeyboardPacket.MODIFIER_META;

    private final PreferenceConfiguration prefs = new PreferenceConfiguration();
    private final NvConnection conn = mock(NvConnection.class);
    private final FakeHost host = new FakeHost();
    private final StreamKeyHandler handler = new StreamKeyHandler(prefs, host);

    private final class FakeHost implements StreamKeyHandler.Host {
        final List<String> actions = new ArrayList<>();

        @Override public NvConnection conn() { return conn; }
        @Override public KeyboardTranslator keyboardTranslator() { return null; }
        @Override public ControllerHandler controllerHandler() { return null; }
        @Override public boolean isInputGrabbed() { return true; }
        @Override public boolean streamMenuTakesKey(KeyEvent event) { return false; }
        @Override public KeyBoardLayoutController visibleFullKeyboard() { return null; }
        @Override public void toggleInputGrabLater() { actions.add("grab"); }
        @Override public void quit() { actions.add("quit"); }
        @Override public void toggleLocalCursor() { actions.add("cursor"); }
    }

    private boolean down(int keyCode) {
        return handler.handleSpecialKeys(keyCode, true);
    }

    private boolean up(int keyCode) {
        return handler.handleSpecialKeys(keyCode, false);
    }

    private void pressCtrlAltShift() {
        assertFalse(down(KeyEvent.KEYCODE_CTRL_LEFT));
        assertFalse(down(KeyEvent.KEYCODE_ALT_LEFT));
        assertFalse(down(KeyEvent.KEYCODE_SHIFT_LEFT));
    }

    @Test
    public void modifierKeysAreTrackedUntilReleased() {
        down(KeyEvent.KEYCODE_CTRL_LEFT);
        down(KeyEvent.KEYCODE_SHIFT_RIGHT);
        down(KeyEvent.KEYCODE_ALT_RIGHT);
        down(KeyEvent.KEYCODE_META_LEFT);
        assertEquals(CTRL | SHIFT | ALT | META, handler.getModifierState());

        up(KeyEvent.KEYCODE_SHIFT_RIGHT);
        assertEquals(CTRL | ALT | META, handler.getModifierState());
        up(KeyEvent.KEYCODE_CTRL_LEFT);
        up(KeyEvent.KEYCODE_ALT_RIGHT);
        up(KeyEvent.KEYCODE_META_LEFT);
        assertEquals(0, handler.getModifierState());
    }

    @Test
    public void focusLossClearsTheModifiers() {
        down(KeyEvent.KEYCODE_CTRL_LEFT);
        down(KeyEvent.KEYCODE_ALT_LEFT);

        handler.clearModifierState();

        assertEquals(0, handler.getModifierState());
    }

    @Test
    public void eventMetaStateAddsTheModifiersAnImeDoesNotSendAsKeys() {
        KeyEvent shifted = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0,
                KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON | KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON);
        assertEquals(SHIFT | CTRL, handler.getModifierState(shifted));

        KeyEvent alted = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0,
                KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON | KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON);
        assertEquals(ALT | META, handler.getModifierState(alted));

        // The tracked keys are kept as well
        down(KeyEvent.KEYCODE_CTRL_RIGHT);
        KeyEvent plain = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A);
        assertEquals(CTRL, handler.getModifierState(plain));
    }

    @Test
    public void rightAltAsMetaOnlyCountsLeftAltAsAlt() {
        prefs.rightAltAsMeta = true;

        KeyEvent rightAlt = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0,
                KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON);
        assertEquals(0, handler.getModifierState(rightAlt));

        KeyEvent leftAlt = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0,
                KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON);
        assertEquals(ALT, handler.getModifierState(leftAlt));
    }

    @Test
    public void rightAltAsMetaSendsTheWindowsKeyWithTheMetaModifier() {
        prefs.rightAltAsMeta = true;

        assertTrue(down(KeyEvent.KEYCODE_ALT_RIGHT));
        verify(conn).sendKeyboardInput((short) KeyboardTranslator.VK_LWIN, KeyboardPacket.KEY_DOWN, META, (byte) 0);
        assertEquals(META, handler.getModifierState());

        assertTrue(up(KeyEvent.KEYCODE_ALT_RIGHT));
        verify(conn).sendKeyboardInput((short) KeyboardTranslator.VK_LWIN, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
        assertEquals(0, handler.getModifierState());
    }

    @Test
    public void thePlusKeyNeedsShiftOnTheHost() {
        assertEquals(SHIFT, handler.getModifierState(KeyEvent.KEYCODE_PLUS));
        assertEquals(0, handler.getModifierState(KeyEvent.KEYCODE_EQUALS));
        assertEquals(SHIFT | CTRL, StreamKeyHandler.applyKeySpecificModifiers(KeyEvent.KEYCODE_PLUS, CTRL));
    }

    @Test
    public void ctrlAltShiftZTogglesTheGrabOnceEveryModifierIsUp() {
        pressCtrlAltShift();

        assertTrue("the combo key is eaten", down(KeyEvent.KEYCODE_Z));
        assertTrue("repeats of it too", down(KeyEvent.KEYCODE_Z));
        assertTrue("other key downs are eaten while waiting", down(KeyEvent.KEYCODE_A));
        assertFalse("key ups pass", up(KeyEvent.KEYCODE_A));
        assertTrue("its own key up is eaten", up(KeyEvent.KEYCODE_Z));

        assertFalse(up(KeyEvent.KEYCODE_SHIFT_LEFT));
        assertFalse(up(KeyEvent.KEYCODE_ALT_LEFT));
        assertTrue(host.actions.isEmpty());
        assertFalse("the last modifier up passes", up(KeyEvent.KEYCODE_CTRL_LEFT));
        assertEquals(List.of("grab"), host.actions);

        // The combo state is reset
        assertFalse(down(KeyEvent.KEYCODE_Z));
        assertFalse(up(KeyEvent.KEYCODE_Z));
        assertEquals(List.of("grab"), host.actions);
    }

    @Test
    public void ctrlAltShiftQQuitsAndCTogglesTheCursor() {
        pressCtrlAltShift();
        assertTrue(down(KeyEvent.KEYCODE_Q));
        up(KeyEvent.KEYCODE_Q);
        up(KeyEvent.KEYCODE_CTRL_LEFT);
        up(KeyEvent.KEYCODE_ALT_LEFT);
        up(KeyEvent.KEYCODE_SHIFT_LEFT);

        pressCtrlAltShift();
        assertTrue(down(KeyEvent.KEYCODE_C));
        up(KeyEvent.KEYCODE_SHIFT_LEFT);
        up(KeyEvent.KEYCODE_ALT_LEFT);
        up(KeyEvent.KEYCODE_CTRL_LEFT);
        up(KeyEvent.KEYCODE_C);

        assertEquals(List.of("quit", "cursor"), host.actions);
    }

    @Test
    public void otherKeysAndIncompleteCombosAreNotSpecial() {
        pressCtrlAltShift();
        assertFalse("not one of Z, Q and C", down(KeyEvent.KEYCODE_A));
        assertFalse(up(KeyEvent.KEYCODE_A));
        up(KeyEvent.KEYCODE_SHIFT_LEFT);

        assertFalse("Shift is missing", down(KeyEvent.KEYCODE_Z));
        up(KeyEvent.KEYCODE_Z);
        up(KeyEvent.KEYCODE_ALT_LEFT);
        up(KeyEvent.KEYCODE_CTRL_LEFT);

        assertTrue(host.actions.isEmpty());
    }
}
