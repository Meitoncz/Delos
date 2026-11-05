package com.limelight.binding.input;

import android.view.KeyEvent;
import android.view.View;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class ActiveKeyboardInputTest {
    private final InputTarget first = new InputTarget();
    private final InputTarget second = new InputTarget();

    @After
    public void clearOwners() {
        ActiveKeyboardInput.unregister(first);
        ActiveKeyboardInput.unregister(second);
    }

    @Test
    public void connectionStopAndActivityDestructionDisableKeyboardInput() {
        ActiveKeyboardInput.register(first);
        assertNull(ActiveKeyboardInput.connectedTarget());
        first.connected = true;
        assertSame(first, ActiveKeyboardInput.connectedTarget());
        first.connected = false;
        assertNull(ActiveKeyboardInput.connectedTarget());
        first.connected = true;
        assertSame(first, ActiveKeyboardInput.connectedTarget());
        ActiveKeyboardInput.unregister(first);
        assertNull(ActiveKeyboardInput.connectedTarget());
    }

    @Test
    public void replacementNeverFallsBackToOldStreamOrLosesInputToItsLateDestruction() {
        first.connected = true;
        ActiveKeyboardInput.register(first);
        ActiveKeyboardInput.register(second);
        assertNull(ActiveKeyboardInput.connectedTarget());
        second.connected = true;
        ActiveKeyboardInput.unregister(first);
        assertSame(second, ActiveKeyboardInput.connectedTarget());
        second.connected = false;
        assertNull(ActiveKeyboardInput.connectedTarget());
        ActiveKeyboardInput.unregister(second);
        assertNull(ActiveKeyboardInput.connectedTarget());
    }

    private static final class InputTarget implements ActiveKeyboardInput.Target {
        boolean connected;

        @Override
        public boolean isKeyboardInputConnected() { return connected; }

        @Override
        public boolean onKey(View view, int keyCode, KeyEvent event) {
            throw new AssertionError("Lifecycle lookup must not dispatch input");
        }

        @Override
        public void mouseButtonEvent(int buttonId, boolean down) {
            throw new AssertionError("Lifecycle lookup must not dispatch input");
        }

        @Override
        public void mouseMove(int deltaX, int deltaY) {
            throw new AssertionError("Lifecycle lookup must not dispatch input");
        }
    }
}
