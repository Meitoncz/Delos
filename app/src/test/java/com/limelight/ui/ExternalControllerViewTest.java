package com.limelight.ui;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ExternalControllerViewTest {
    @Test
    public void menuHandlerReceivesGamepadButtonBeforeIme() {
        Context context = ApplicationProvider.getApplicationContext();
        ExternalControllerView view = new ExternalControllerView(context);
        ExternalControllerView.PreImeKeyHandler menu = mock(ExternalControllerView.PreImeKeyHandler.class);
        ExternalControllerView.InputCallbacks stream = mock(ExternalControllerView.InputCallbacks.class);
        KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A);
        when(menu.handle(event)).thenReturn(true);
        view.setPreImeKeyHandler(menu);
        view.setInputCallbacks(stream);

        assertTrue(view.onKeyPreIme(event.getKeyCode(), event));
        verify(menu).handle(event);
        verify(stream, never()).handleKeyDown(event);
    }

    @Test
    public void commitTextRemainsAvailableWithPreImeRouting() {
        Context context = ApplicationProvider.getApplicationContext();
        ExternalControllerView view = new ExternalControllerView(context);
        ExternalControllerView.InputCallbacks stream = mock(ExternalControllerView.InputCallbacks.class);
        view.setInputCallbacks(stream);
        view.setPreImeKeyHandler(event -> false);
        view.setCommitTextEnabled(true);

        InputConnection connection = view.onCreateInputConnection(new EditorInfo());
        assertNotNull(connection);
        connection.commitText("hello", 1);

        verify(stream).handleCommitText("hello");
    }
}
