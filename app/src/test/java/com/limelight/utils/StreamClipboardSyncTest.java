package com.limelight.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.nvstream.http.NvHTTP;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
public class StreamClipboardSyncTest {
    private ClipboardManager clipboard;
    private PreferenceConfiguration prefs;
    private StreamClipboardSync sync;
    private boolean connected;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        clipboard = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        prefs = PreferenceConfiguration.readPreferences(ctx);
        sync = new StreamClipboardSync(ctx, prefs, new StreamClipboardSync.Host() {
            @Override
            public NvHTTP httpConn() {
                return null;
            }

            @Override
            public boolean isConnected() {
                return connected;
            }

            @Override
            public void showToast(String text, ToastKind kind) {
            }
        });
    }

    @Test
    public void emptyClipboard_hasNoContent() {
        assertNull(sync.getClipboardContent(false));
        assertNull(sync.getClipboardContent(true));
    }

    @Test
    public void plainText_isReturnedOnce_thenMarkedAsSeen() {
        clipboard.setPrimaryClip(ClipData.newPlainText("label", "hello"));
        assertEquals("hello", sync.getClipboardContent(false));

        // The clip is now marked as Delos's own, so smart sync does not send it again
        PersistableBundle extras = clipboard.getPrimaryClipDescription().getExtras();
        assertTrue(extras.getBoolean(StreamClipboardSync.CLIPBOARD_IDENTIFIER));
        assertNull(sync.getClipboardContent(false));

        // A forced send (stream menu) still gets it
        assertEquals("hello", sync.getClipboardContent(true));
    }

    @Test
    public void ownClip_isSkippedUnlessForced() {
        ClipData own = ClipData.newPlainText(StreamClipboardSync.CLIPBOARD_IDENTIFIER, "from host");
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(StreamClipboardSync.CLIPBOARD_IDENTIFIER, true);
        own.getDescription().setExtras(extras);
        clipboard.setPrimaryClip(own);

        assertNull(sync.getClipboardContent(false));
        assertEquals("from host", sync.getClipboardContent(true));
    }

    @Test
    public void nonTextClip_hasNoContent() {
        clipboard.setPrimaryClip(ClipData.newIntent("intent", new Intent(Intent.ACTION_VIEW)));
        assertNull(sync.getClipboardContent(false));
    }

    @Test
    public void focusChange_doesNothingWhenNotConnected() {
        prefs.smartClipboardSync = true;
        clipboard.setPrimaryClip(ClipData.newPlainText("label", "hello"));
        connected = false;
        assertFalse(sync.handleFocusChange(true));
        // Not read, so not marked either
        assertEquals("hello", sync.getClipboardContent(false));
    }

    @Test
    public void sendAndGet_failWithoutHttpClient() {
        connected = true;
        prefs.smartClipboardSync = true;
        clipboard.setPrimaryClip(ClipData.newPlainText("label", "hello"));
        assertFalse(sync.handleFocusChange(true));
        assertFalse(sync.handleFocusChange(false));
        assertFalse(sync.sendClipboard(true));
        assertFalse(sync.getClipboard(0));
    }
}
