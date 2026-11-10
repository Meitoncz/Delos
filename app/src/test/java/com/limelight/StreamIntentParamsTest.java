package com.limelight;

import android.content.Intent;
import android.util.Base64;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.NvHTTP;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class StreamIntentParamsTest {
    // Self-signed test certificate (CN=Delos Test), DER, base64
    private static final String TEST_CERT_DER_BASE64 =
            "MIIBYDCCAQagAwIBAgIUTaBLanJLPXrv/ZCol+o978YLfvswCgYIKoZIzj0EAwIwFTETMBEGA1UEAwwKRGVsb3MgVGVzdDAgFw0y"
            + "NjExMDgxMTE4MzBaGA8yMTI2MTAxNTExMTgzMFowFTETMBEGA1UEAwwKRGVsb3MgVGVzdDBZMBMGByqGSM49AgEGCCqGSM49Aw"
            + "EHA0IABFrNAqPb+ia81B5jdeqL5XZRlC+rtp/RcYEoif0boiwN73HNFAzNBKB34jh3bQZ3ZJ404dn2Thq05zpcGXk1h5ijMjAwMB0G"
            + "A1UdDgQWBBTlobtVPx7gDFiUt96c2H8OwaG0OjAPBgNVHRMBAf8EBTADAQH/MAoGCCqGSM49BAMCA0gAMEUCIC/gxDXZhIvUiVc9"
            + "LBBahhd0rjAOcahagayJ/a31IbvMAiEA1+v9mZfq7SmqcEl2M6aNmsWyDPrUEMITybwcu74QR5g=";

    private static Intent launchIntent() {
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), DelosStreamActivity.class);
        intent.putExtra(DelosStreamActivity.EXTRA_HOST, "192.0.2.10");
        intent.putExtra(DelosStreamActivity.EXTRA_PORT, 47999);
        intent.putExtra(DelosStreamActivity.EXTRA_HTTPS_PORT, 47984);
        intent.putExtra(DelosStreamActivity.EXTRA_APP_ID, 12345);
        intent.putExtra(DelosStreamActivity.EXTRA_APP_UUID, "APP-UUID");
        intent.putExtra(DelosStreamActivity.EXTRA_APP_NAME, "Test Game");
        intent.putExtra(DelosStreamActivity.EXTRA_PC_NAME, "Test PC");
        intent.putExtra(DelosStreamActivity.EXTRA_UNIQUEID, "0123456789ABCDEF");
        intent.putExtra(DelosStreamActivity.EXTRA_VDISPLAY, true);
        intent.putExtra(DelosStreamActivity.EXTRA_APP_HDR, true);
        intent.putStringArrayListExtra(DelosStreamActivity.EXTRA_SERVER_COMMANDS, new ArrayList<>(List.of("cmd1", "cmd2")));
        return intent;
    }

    @Test
    public void normalLaunch_readsEveryExtra() {
        StreamIntentParams p = StreamIntentParams.fromIntent(launchIntent());
        assertEquals("192.0.2.10", p.host);
        assertEquals(47999, p.port);
        assertEquals(47984, p.httpsPort);
        assertEquals(12345, p.appId);
        assertEquals("APP-UUID", p.appUUID);
        assertEquals("Test Game", p.appName);
        assertEquals("Test PC", p.pcName);
        assertEquals("0123456789ABCDEF", p.uniqueId);
        assertTrue(p.vDisplay);
        assertEquals(List.of("cmd1", "cmd2"), p.serverCommands);
        assertTrue(p.hasValidApp());

        assertEquals("Test Game", p.app.getAppName());
        assertEquals("APP-UUID", p.app.getAppUUID());
        assertEquals(12345, p.app.getAppId());
        assertTrue(p.app.isHdrSupported());
    }

    @Test
    public void missingOptionalExtras_useTheDefaults() {
        Intent intent = new Intent();
        intent.putExtra(DelosStreamActivity.EXTRA_APP_ID, 1);
        StreamIntentParams p = StreamIntentParams.fromIntent(intent);
        assertNull(p.host);
        assertEquals(NvHTTP.DEFAULT_HTTP_PORT, p.port);
        assertEquals(0, p.httpsPort);
        assertFalse(p.vDisplay);
        assertNull(p.serverCommands);
        assertNull(p.serverCert);
        assertFalse(p.app.isHdrSupported());
    }

    @Test
    public void resumeSession_withoutAppName_namesTheAppGenerically() {
        Intent intent = launchIntent();
        intent.removeExtra(DelosStreamActivity.EXTRA_APP_NAME);
        StreamIntentParams p = StreamIntentParams.fromIntent(intent);
        assertNull(p.appName);
        assertEquals("app", p.app.getAppName());
        assertTrue(p.hasValidApp());
    }

    @Test
    public void missingAppId_isInvalid() {
        Intent intent = launchIntent();
        intent.removeExtra(DelosStreamActivity.EXTRA_APP_ID);
        StreamIntentParams p = StreamIntentParams.fromIntent(intent);
        assertEquals(StreamConfiguration.INVALID_APP_ID, p.appId);
        assertFalse(p.hasValidApp());
    }

    @Test
    public void explicitInvalidAppId_isInvalid() {
        Intent intent = launchIntent();
        intent.putExtra(DelosStreamActivity.EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID);
        assertFalse(StreamIntentParams.fromIntent(intent).hasValidApp());
    }

    @Test
    public void serverCert_isDecoded() {
        Intent intent = launchIntent();
        intent.putExtra(DelosStreamActivity.EXTRA_SERVER_CERT, Base64.decode(TEST_CERT_DER_BASE64, Base64.DEFAULT));
        StreamIntentParams p = StreamIntentParams.fromIntent(intent);
        assertNotNull(p.serverCert);
        assertTrue(p.serverCert.getSubjectX500Principal().getName().contains("CN=Delos Test"));
    }

    @Test
    public void brokenServerCert_isIgnored() {
        Intent intent = launchIntent();
        intent.putExtra(DelosStreamActivity.EXTRA_SERVER_CERT, new byte[]{1, 2, 3, 4});
        StreamIntentParams p = StreamIntentParams.fromIntent(intent);
        assertNull(p.serverCert);
        assertNull(p.createHttp(ApplicationProvider.getApplicationContext()));
    }

    @Test
    public void noServerCert_meansNoHttpClient() {
        StreamIntentParams p = StreamIntentParams.fromIntent(launchIntent());
        assertNull(p.serverCert);
        assertNull(p.createHttp(ApplicationProvider.getApplicationContext()));
    }
}
