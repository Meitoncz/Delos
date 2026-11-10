package com.limelight.binding.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.view.KeyEvent;

import com.limelight.binding.input.virtual_controller.keyboard.KeyBoardLayoutController;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The modifier state and the client-side Ctrl+Alt+Shift combos as DelosStreamActivity handled them before the
 * extraction (characterisation tests), and the keys released on the host when focus is lost.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class StreamKeyHandlerTest {
    private static final byte CTRL = KeyboardPacket.MODIFIER_CTRL;
    private static final byte SHIFT = KeyboardPacket.MODIFIER_SHIFT;
    private static final byte ALT = KeyboardPacket.MODIFIER_ALT;
    private static final byte META = KeyboardPacket.MODIFIER_META;
    private static final byte UP = KeyboardPacket.KEY_UP;
    private static final byte NONE = 0;
    // KeyEvents built in a test come from no keyboard the translator has a layout for
    private static final byte RAW = MoonBridge.SS_KBE_FLAG_NON_NORMALIZED;

    private final PreferenceConfiguration prefs = new PreferenceConfiguration();
    private final NvConnection conn = mock(NvConnection.class);
    private final KeyboardTranslator translator = new KeyboardTranslator(prefs);
    private final ControllerHandler controllers = mock(ControllerHandler.class);
    private final FakeHost host = new FakeHost();
    private final StreamKeyHandler handler = new StreamKeyHandler(prefs, host);

    private final class FakeHost implements StreamKeyHandler.Host {
        final List<String> actions = new ArrayList<>();

        @Override public NvConnection conn() { return conn; }
        @Override public KeyboardTranslator keyboardTranslator() { return translator; }
        @Override public ControllerHandler controllerHandler() { return controllers; }
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

    private static KeyEvent key(int action, int keyCode) {
        return new KeyEvent(action, keyCode);
    }

    /** The host's virtual key for an Android key code. */
    private short vk(int keyCode) {
        return translator.translate(keyCode, 0, -1);
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
    public void focusGainClearsTheModifiers() {
        down(KeyEvent.KEYCODE_CTRL_LEFT);
        down(KeyEvent.KEYCODE_ALT_LEFT);

        handler.clearModifierState();

        assertEquals(0, handler.getModifierState());
    }

    @Test
    public void focusLossReleasesEveryHeldKeyOnTheHostLastPressedFirst() {
        assertTrue(handler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT)));
        assertTrue(handler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT)));
        assertTrue(handler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB)));
        // Pressed and lifted before focus is lost: not held
        assertTrue(handler.handleKeyDown(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B)));
        assertTrue(handler.handleKeyUp(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_B)));
        assertEquals(CTRL | ALT, handler.getModifierState());
        clearInvocations(conn);

        handler.releaseHeldKeys();

        InOrder order = inOrder(conn);
        order.verify(conn).sendKeyboardInput(vk(KeyEvent.KEYCODE_TAB), UP, NONE, RAW);
        order.verify(conn).sendKeyboardInput(vk(KeyEvent.KEYCODE_ALT_LEFT), UP, NONE, RAW);
        order.verify(conn).sendKeyboardInput(vk(KeyEvent.KEYCODE_CTRL_LEFT), UP, NONE, RAW);
        verifyNoMoreInteractions(conn);
        assertEquals(0, handler.getModifierState());

        // Everything is up now: a second focus loss sends nothing
        handler.releaseHeldKeys();
        verifyNoMoreInteractions(conn);
    }

    @Test
    public void focusLossReleasesEvdevKeysAndTheRemappedRightAlt() {
        prefs.rightAltAsMeta = true;
        handler.keyboardEvent(true, (short) KeyEvent.KEYCODE_ALT_RIGHT);
        handler.keyboardEvent(true, (short) KeyEvent.KEYCODE_S);
        clearInvocations(conn);

        handler.releaseHeldKeys();

        InOrder order = inOrder(conn);
        order.verify(conn).sendKeyboardInput(vk(KeyEvent.KEYCODE_S), UP, NONE, NONE);
        order.verify(conn).sendKeyboardInput((short) KeyboardTranslator.VK_LWIN, UP, NONE, NONE);
        verifyNoMoreInteractions(conn);
        assertEquals(0, handler.getModifierState());
    }

    @Test
    public void focusLossWithNothingHeldSendsNothing() {
        handler.releaseHeldKeys();
        verifyNoMoreInteractions(conn);
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
