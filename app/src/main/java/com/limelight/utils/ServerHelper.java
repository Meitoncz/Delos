package com.limelight.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;
import com.limelight.utils.DelosToast;

import androidx.annotation.RequiresApi;

import com.limelight.ShortcutTrampoline;
import com.limelight.DelosStreamActivity;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.HostHttpResponseException;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;

public class ServerHelper {
    public static final String CONNECTION_TEST_SERVER = "android.conntest.moonlight-stream.org";

    public static ComputerDetails.AddressTuple getCurrentAddressFromComputer(ComputerDetails computer) throws IOException {
        if (computer.activeAddress == null) {
            throw new IOException("No active address for "+computer.name);
        }
        return computer.activeAddress;
    }

    public static Intent createPcShortcutIntent(Activity parent, ComputerDetails computer) {
        Intent i = new Intent(parent, ShortcutTrampoline.class);
        i.putExtra(ShortcutTrampoline.NAME_EXTRA, computer.name);
        i.putExtra(ShortcutTrampoline.UUID_EXTRA, computer.uuid);
        i.setAction(Intent.ACTION_DEFAULT);
        return i;
    }

    public static Intent createAppShortcutIntent(Activity parent, ComputerDetails computer, NvApp app) {
        Intent i = new Intent(parent, ShortcutTrampoline.class);
        i.putExtra(ShortcutTrampoline.NAME_EXTRA, computer.name);
        i.putExtra(ShortcutTrampoline.UUID_EXTRA, computer.uuid);
        i.putExtra(DelosStreamActivity.EXTRA_APP_NAME, app.getAppName());
        i.putExtra(DelosStreamActivity.EXTRA_APP_UUID, app.getAppUUID());
        i.putExtra(DelosStreamActivity.EXTRA_APP_ID, ""+app.getAppId());
        i.putExtra(DelosStreamActivity.EXTRA_APP_HDR, app.isHdrSupported());
        i.setAction(Intent.ACTION_DEFAULT);
        return i;
    }
    public static Display getActiveDisplay(Context context, PreferenceConfiguration prefs) {
        Display secondary = getSecondaryDisplay(context);
        if (secondary != null && (prefs.enableFullExDisplay)) {
            return secondary;
        } else {
            return ((DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE)).getDisplay(Display.DEFAULT_DISPLAY);
        }
    }

    public static Display getSecondaryDisplay(Context context) {
        DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display display = null;
        Display[] displays = displayManager.getDisplays();
        int mainDisplayId = Display.DEFAULT_DISPLAY;
        int secondaryDisplayId = -1;
        for (Display displayVariant : displays) {
            LimeLog.info(displayVariant.toString());
            if (displayVariant.getDisplayId() != mainDisplayId) {
                secondaryDisplayId = displayVariant.getDisplayId();
                break;
            }
        }

        if (secondaryDisplayId != -1) {
            display = displayManager.getDisplay(secondaryDisplayId);
        }
        return display;
    }

    /** Returns the DelosStreamActivity intent, unwrapping the external-display controller when present. */
    public static Intent getStreamIntent(Intent launchIntent) {
        if (launchIntent == null) {
            return null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent nested = launchIntent.getParcelableExtra(
                    ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT, Intent.class);
            return nested != null ? nested : launchIntent;
        }

        @SuppressWarnings("deprecation")
        Intent nested = launchIntent.getParcelableExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT);
        return nested != null ? nested : launchIntent;
    }

    public static Intent createStartIntent(Activity parent, NvApp app, ComputerDetails computer,
                                           ComputerManagerService.ComputerManagerBinder managerBinder,
                                           boolean withVDisplay) {
        Intent gameIntent = null;
        PreferenceConfiguration prefConfig = PreferenceConfiguration.readPreferences(parent);
        // Try to add secondary DisplayContext if supported and connected
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && prefConfig.enableFullExDisplay && getSecondaryDisplay(parent) != null) {
            Context displayContext = parent.createDisplayContext(getSecondaryDisplay(parent)); // use secondary display
            gameIntent = new Intent(displayContext, DelosStreamActivity.class);
            gameIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        if(gameIntent == null) gameIntent = new Intent(parent, DelosStreamActivity.class);
        gameIntent.putExtra(DelosStreamActivity.EXTRA_HOST, computer.activeAddress.address);
        gameIntent.putExtra(DelosStreamActivity.EXTRA_PORT, computer.activeAddress.port);
        gameIntent.putExtra(DelosStreamActivity.EXTRA_HTTPS_PORT, computer.httpsPort);
        gameIntent.putExtra(DelosStreamActivity.EXTRA_APP_NAME, app.getAppName());
        gameIntent.putExtra(DelosStreamActivity.EXTRA_APP_UUID, app.getAppUUID());
        gameIntent.putExtra(DelosStreamActivity.EXTRA_APP_ID, app.getAppId());
        gameIntent.putExtra(DelosStreamActivity.EXTRA_APP_HDR, app.isHdrSupported());
        gameIntent.putExtra(DelosStreamActivity.EXTRA_UNIQUEID, managerBinder.getUniqueId());
        gameIntent.putExtra(DelosStreamActivity.EXTRA_PC_UUID, computer.uuid);
        gameIntent.putExtra(DelosStreamActivity.EXTRA_PC_NAME, computer.name);
        gameIntent.putExtra(DelosStreamActivity.EXTRA_VDISPLAY, withVDisplay);
        gameIntent.putExtra(DelosStreamActivity.EXTRA_SERVER_COMMANDS, (ArrayList<String>) computer.serverCommands);

        try {
            if (computer.serverCert != null) {
                gameIntent.putExtra(DelosStreamActivity.EXTRA_SERVER_CERT, computer.serverCert.getEncoded());
            }
        } catch (CertificateEncodingException e) {
            e.printStackTrace();
        }

        if (prefConfig.enableFullExDisplay) {
            Display secondaryDisplay = getSecondaryDisplay(parent);
            if (secondaryDisplay != null) {
                int secondaryDisplayId = secondaryDisplay.getDisplayId();
                gameIntent.putExtra(DelosStreamActivity.EXTRA_DISPLAY_ID, secondaryDisplayId);
                Intent touchpadIntent = new Intent(parent, ExternalDisplayControlActivity.class);
                touchpadIntent.putExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT, gameIntent);
                return touchpadIntent;
            }
        }

        return gameIntent;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void doStart(
            Activity parent,
            NvApp app,
            ComputerDetails computer,
            ComputerManagerService.ComputerManagerBinder managerBinder,
            boolean withVDisplay
    ) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            DelosToast.show(parent, parent.getString(R.string.pair_pc_offline), DelosToast.SHORT, ToastKind.ERROR);
            return;
        }

        Intent intent = createStartIntent(parent, app, computer, managerBinder, withVDisplay);
        parent.startActivity(intent);
    }

    public static void doNetworkTest(final Activity parent) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                SpinnerDialog spinnerDialog = SpinnerDialog.displayDialog(parent,
                        parent.getResources().getString(R.string.nettest_title_waiting),
                        parent.getResources().getString(R.string.nettest_text_waiting),
                        false);

                int ret = MoonBridge.testClientConnectivity(CONNECTION_TEST_SERVER, 443, MoonBridge.ML_PORT_FLAG_ALL);
                spinnerDialog.dismiss();

                String dialogSummary;
                if (ret == MoonBridge.ML_TEST_RESULT_INCONCLUSIVE) {
                    dialogSummary = parent.getResources().getString(R.string.nettest_text_inconclusive);
                }
                else if (ret == 0) {
                    dialogSummary = parent.getResources().getString(R.string.nettest_text_success);
                }
                else {
                    dialogSummary = parent.getResources().getString(R.string.nettest_text_failure);
                    dialogSummary += MoonBridge.stringifyPortFlags(ret, "\n");
                }

                Dialog.displayDialog(parent,
                        parent.getResources().getString(R.string.nettest_title_done),
                        dialogSummary,
                        false);
            }
        }).start();
    }

    public static void doQuit(final Activity parent,
                              final NvHTTP httpConn,
                              final String appName,
                              final Runnable onComplete,
                              final Runnable onFail
    ) {
        parent.runOnUiThread(() -> DelosToast.show(parent, parent.getResources().getString(R.string.applist_quit_app) + " " + appName + "...", DelosToast.SHORT));
        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                boolean failed = false;
                boolean quitOk = false;
                try {
                    if (httpConn.quitApp()) {
                        quitOk = true;
                        message = parent.getResources().getString(R.string.applist_quit_success) + " " + appName;
                    } else {
                        message = parent.getResources().getString(R.string.applist_quit_fail) + " " + appName;
                    }
                } catch (HostHttpResponseException e) {
                    failed = true;
                    if (e.getErrorCode() == 599) {
                        message = "This session wasn't started by this device," +
                                " so it cannot be quit. End streaming on the original " +
                                "device or the PC itself. (Error code: "+e.getErrorCode()+")";
                    }
                    else {
                        message = e.getMessage();
                    }
                } catch (UnknownHostException e) {
                    failed = true;
                    message = parent.getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    failed = true;
                    message = parent.getResources().getString(R.string.error_404);
                } catch (IOException | XmlPullParserException e) {
                    failed = true;
                    message = e.getMessage();
                    e.printStackTrace();
                } finally {
                    if (failed) {
                        if (onFail != null) {
                            onFail.run();
                        }
                    } else {
                        if (onComplete != null) {
                            onComplete.run();
                        }
                    }
                }

                final String toastMessage = message;
                final ToastKind toastKind = quitOk ? ToastKind.SUCCESS : ToastKind.ERROR;
                parent.runOnUiThread(() -> DelosToast.show(parent, toastMessage, DelosToast.LONG, toastKind));
            }
        }).start();

    }

    public static void doQuit(final Activity parent,
                              final ComputerDetails computer,
                              final NvApp app,
                              final ComputerManagerService.ComputerManagerBinder managerBinder,
                              final Runnable onComplete
    ) {
        try {
            NvHTTP httpConn = new NvHTTP(
                    ServerHelper.getCurrentAddressFromComputer(computer),
                    computer.httpsPort,
                    managerBinder.getUniqueId(),
                    computer.serverCert,
                    PlatformBinding.getCryptoProvider(parent)
            );
            doQuit(
                    parent,
                    httpConn,
                    app.getAppName(),
                    onComplete,
                    null
            );
        } catch (Exception e) {
            e.printStackTrace();

            final String toastMessage = e.getMessage();
            parent.runOnUiThread(() -> DelosToast.show(parent, toastMessage, DelosToast.LONG, ToastKind.ERROR));
        }
    }
}
