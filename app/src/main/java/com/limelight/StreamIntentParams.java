package com.limelight;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.Nullable;

import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;

/** The stream launch parameters carried by the {@link Game} intent (the {@code Game.EXTRA_*} extras). */
public final class StreamIntentParams {
    public final String host;
    public final int port;
    /** 0 means unknown. */
    public final int httpsPort;
    public final int appId;
    @Nullable public final String appUUID;
    @Nullable public final String uniqueId;
    /** Null when the intent carries no certificate or it cannot be decoded. */
    @Nullable public final X509Certificate serverCert;
    public final boolean vDisplay;
    @Nullable public final ArrayList<String> serverCommands;
    /** Null when the stream was started from "Resume Session". */
    @Nullable public final String appName;
    @Nullable public final String pcName;
    public final NvApp app;

    private StreamIntentParams(Intent intent) {
        appName = intent.getStringExtra(Game.EXTRA_APP_NAME);
        pcName = intent.getStringExtra(Game.EXTRA_PC_NAME);

        host = intent.getStringExtra(Game.EXTRA_HOST);
        port = intent.getIntExtra(Game.EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT);
        httpsPort = intent.getIntExtra(Game.EXTRA_HTTPS_PORT, 0); // 0 is treated as unknown
        appUUID = intent.getStringExtra(Game.EXTRA_APP_UUID);
        appId = intent.getIntExtra(Game.EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID);
        uniqueId = intent.getStringExtra(Game.EXTRA_UNIQUEID);
        vDisplay = intent.getBooleanExtra(Game.EXTRA_VDISPLAY, false);
        serverCommands = intent.getStringArrayListExtra(Game.EXTRA_SERVER_COMMANDS);
        boolean appSupportsHdr = intent.getBooleanExtra(Game.EXTRA_APP_HDR, false);
        serverCert = decodeCert(intent.getByteArrayExtra(Game.EXTRA_SERVER_CERT));

        app = new NvApp(appName != null ? appName : "app", appUUID, appId, appSupportsHdr);
    }

    public static StreamIntentParams fromIntent(Intent intent) {
        return new StreamIntentParams(intent);
    }

    /** False when the intent names no app; the stream cannot start then. */
    public boolean hasValidApp() {
        return appId != StreamConfiguration.INVALID_APP_ID;
    }

    /** The host's HTTP client, or null without a server certificate or when it cannot be created. */
    @Nullable
    public NvHTTP createHttp(Context context) {
        if (serverCert == null) {
            return null;
        }
        try {
            return new NvHTTP(new ComputerDetails.AddressTuple(host, port), httpsPort, uniqueId, serverCert, PlatformBinding.getCryptoProvider(context));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Nullable
    private static X509Certificate decodeCert(@Nullable byte[] derCertData) {
        if (derCertData == null) {
            return null;
        }
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(derCertData));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
