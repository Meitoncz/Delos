package com.limelight;


import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import com.limelight.utils.DelosToast;
import com.limelight.utils.ToastKind;

import androidx.appcompat.app.AppCompatActivity;

import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.computers.ShortcutPcPlan;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.shell.data.GameLauncher;
import com.limelight.utils.CacheHelper;
import com.limelight.shell.ResultText;
import com.limelight.shell.ShortcutFailure;
import com.limelight.shell.ShortcutSplash;
import com.limelight.shell.StreamOutcome;
import com.limelight.shell.StreamResultKind;
import com.limelight.shell.StreamResultKt;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.UiHelper;

import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class ShortcutTrampoline extends AppCompatActivity {
    private String uuidString;
    private NvApp app;
    // The PC of a shortcut (and of the stream started from it)
    public final static String NAME_EXTRA = "Name";
    public final static String UUID_EXTRA = "UUID";

    private ArrayList<Intent> intentStack = new ArrayList<>();

    // The PC is looked up through the service's poll-now call, not through startPolling: the polling and its
    // listener are shared with the Delos UI (which stops them when this activity covers it), so waiting on
    // them could wait for a round nobody runs any more.
    private static final long WAKE_RETRY_DELAY_MS = 3000;
    private static final long POLL_RETRY_DELAY_MS = 1000;

    private volatile ComputerManagerService.ComputerManagerBinder managerBinder;
    private volatile Thread lookupThread;
    // Only touched on the main thread
    private boolean serviceBound;

    private static final String TAG = "ShortcutTrampoline";

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            Thread thread = new Thread(() -> lookUpPc(localBinder), "ShortcutTrampoline PC lookup");
            lookupThread = thread;
            thread.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    /**
     * Brings the PC's state up to date and decides what the shortcut does (see {@link ShortcutPcPlan}): a state
     * the service polled a moment ago is used as it is, otherwise the PC is polled right away. An offline PC
     * is woken (Wake-on-LAN) and polled until it answers or the tries run out. Runs on its own thread.
     */
    private void lookUpPc(ComputerManagerService.ComputerManagerBinder binder) {
        boolean discovering = false;
        try {
            // Wait for the binder to be ready
            binder.waitForReady();
            if (Thread.currentThread().isInterrupted()) {
                return;
            }

            // Now make the binder visible
            managerBinder = binder;

            int failedPolls = 0;
            int wakesSent = 0;
            ComputerDetails details = binder.pollComputerNow(uuidString, ShortcutPcPlan.FRESH_STATE_MS);
            while (true) {
                if (details == null) {
                    reject(R.string.scut_pc_not_found);
                    return;
                }
                if (details.state != ComputerDetails.State.ONLINE) {
                    failedPolls++;
                }

                ShortcutPcPlan.Step step = ShortcutPcPlan.afterPoll(details.state, details.pairState,
                        failedPolls, details.macAddress != null, wakesSent);
                if (step != ShortcutPcPlan.Step.POLL && step != ShortcutPcPlan.Step.WAKE_THEN_POLL) {
                    complete(details, step);
                    return;
                }

                // Not online yet. The PC may have a new address: look for it on the network meanwhile.
                if (!discovering) {
                    binder.startDiscovery();
                    discovering = true;
                }

                if (step == ShortcutPcPlan.Step.WAKE_THEN_POLL) {
                    try {
                        // Make a best effort attempt to wake the target PC
                        WakeOnLanSender.sendWolPacket(details);
                        wakesSent++;
                    } catch (IOException e) {
                        // We couldn't send a single WoL packet, so this is the offline error path.
                        Log.w(TAG, "Wake-on-LAN failed", e);
                        complete(details, ShortcutPcPlan.Step.FAIL_OFFLINE);
                        return;
                    }
                }

                Thread.sleep(step == ShortcutPcPlan.Step.WAKE_THEN_POLL ? WAKE_RETRY_DELAY_MS : POLL_RETRY_DELAY_MS);
                details = binder.pollComputerNow(uuidString, -1);
            }
        } catch (InterruptedException e) {
            // The activity went away; nothing left to start
            Thread.currentThread().interrupt();
        } finally {
            if (discovering) {
                binder.stopDiscovery();
            }
        }
    }

    /** Acts on the PC's final state on the main thread: the game, the PC's games, or the failure of the start. */
    private void complete(ComputerDetails details, ShortcutPcPlan.Step step) {
        runOnUiThread(() -> {
            // If the activity was stopped before this, there is nothing left to start
            if (managerBinder == null || isFinishing()) {
                finish();
                return;
            }

            if (step == ShortcutPcPlan.Step.START) {
                // Launch game if provided app ID, otherwise launch app view
                if (app != null) {
                    if (details.runningGameId == 0 || details.runningGameId == app.getAppId() || Objects.equals(details.runningGameUUID, app.getAppUUID())) {
                        intentStack.add(GameLauncher.startIntent(ShortcutTrampoline.this, managerBinder, details, app).getIntent());

                        // Close this activity; the stream's loader goes on from the launch scene
                        finish();

                        // Now start the activities
                        startActivities(intentStack.toArray(new Intent[]{}));
                    } else {
                        // Another game is running on the PC. Delos never quits it without an explicit
                        // choice in its own menu ("Quit X and start"), and this activity cannot tell a
                        // pinned shortcut from another app's intent, so nothing is quit here and no
                        // dialog asks: open the PC's games in Delos, where the running game is marked
                        // and the menu of the chosen game offers to quit it and start.
                        DelosToast.show(getApplicationContext(), getRunningGameMessage(details), DelosToast.LONG);

                        openPcInDelos();
                    }
                } else {
                    openPcInDelos();
                }
            } else if (step == ShortcutPcPlan.Step.FAIL_OFFLINE) {
                failStart(details, ShortcutFailure.OFFLINE);
            } else {
                failStart(details, ShortcutFailure.NOT_PAIRED);
            }

            // Nothing more is needed from the service
            releaseService();
        });
    }

    /** Unbinds from the computer manager service (once). Main thread. */
    private void releaseService() {
        managerBinder = null;
        if (serviceBound) {
            serviceBound = false;
            unbindService(serviceConnection);
        }
    }

    /** A shortcut that names nothing Delos can start (no known PC to show a result for): a toast, then this activity ends. */
    private void reject(String message) {
        runOnUiThread(() -> {
            DelosToast.show(getApplicationContext(), message, DelosToast.LONG, ToastKind.ERROR);
            finish();
        });
    }

    private void reject(int message) {
        reject(getString(message));
    }

    /** The PC's games in the Delos UI (it connects to the PC from the UUID). */
    private void openPcInDelos() {
        // Close this activity
        finish();

        Intent i = new Intent(getIntent());
        i.setClass(ShortcutTrampoline.this, com.limelight.shell.ShellActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        intentStack.add(i);

        startActivities(intentStack.toArray(new Intent[]{}));
    }

    /**
     * The PC of the shortcut cannot start its game. Delos opens on that PC with the result overlay (Try again
     * starts the game again); a shortcut that asked for no game gets a toast and the PC's games.
     */
    private void failStart(ComputerDetails details, ShortcutFailure failure) {
        ResultText text = StreamResultKt.describeShortcutFailure(getResources(), details.name, failure);
        if (app == null) {
            DelosToast.show(getApplicationContext(), text.getSentence(), DelosToast.LONG, ToastKind.ERROR);
            openPcInDelos();
        } else {
            StreamOutcome.failed(this, StreamResultKind.FAILED_TO_START, text, details.uuid, details.name, app.getAppId(), app.getAppName());
        }
    }

    protected boolean validateHostInput(String hostUUID, String hostName) {
        // Validate PC UUID/Name
        if (hostUUID == null && hostName == null) {
            reject(R.string.scut_invalid_uuid);
            return false;
        }

        if (hostUUID != null && !hostUUID.isEmpty()) {
            try {
                UUID.fromString(hostUUID);
            } catch (IllegalArgumentException ex) {
                reject(R.string.scut_invalid_uuid);
                return false;
            }
        } else {
            // UUID is null, so fallback to Name
            if (hostName == null || hostName.isEmpty()) {
                reject(R.string.scut_invalid_uuid);
                return false;
            }
        }

        return true;
    }


    /** The toast for a shortcut whose game cannot start because another one runs: names it when the PC's app list does. */
    private String getRunningGameMessage(ComputerDetails details) {
        if (details.rawAppList != null) {
            try {
                for (NvApp running : NvHTTP.getAppListByReader(new StringReader(details.rawAppList))) {
                    boolean sameUuid = details.runningGameUUID != null && !details.runningGameUUID.isEmpty()
                            && details.runningGameUUID.equals(running.getAppUUID());
                    boolean sameId = details.runningGameId != 0 && running.getAppId() == details.runningGameId;
                    if ((sameUuid || sameId) && running.getAppName() != null) {
                        return getString(R.string.scut_game_running, running.getAppName());
                    }
                }
            } catch (IOException | XmlPullParserException e) {
                Log.w(TAG, "Unreadable app list of the PC", e);
            }
        }
        return getString(R.string.scut_other_game_running);
    }

    protected boolean validateAppInput(String appUUID, String appIDStr, String appName) {
        if (appUUID == null && appIDStr == null && appName == null) {
            // We're just going to the AppView
            return false;
        }

        if (appUUID != null && !appUUID.isEmpty()) {
            try {
                UUID.fromString(appUUID);
            } catch (IllegalArgumentException ex) {
                reject(R.string.scut_invalid_app_id);
                return false;
            }
        } else {
            // Validate App ID (if provided)
            if (appIDStr != null && !appIDStr.isEmpty()) {
                try {
                    Integer.parseInt(appIDStr);
                } catch (NumberFormatException ex) {
                    reject(R.string.scut_invalid_app_id);
                    return false;
                }
            }
        }

        return true;
    }

    private Map<String, String> parseArtFileData(Uri fileUri) {
        if (fileUri == null) {
            return null;
        }

        Map<String, String> artData = new HashMap<>();

        try (InputStream inputStream = getContentResolver().openInputStream(fileUri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) {
                    continue; // Skip comments and empty lines
                }

                if (!line.startsWith("[")) {
                    throw new IOException("Invalid .art file format");
                }

                int separatorIndex = line.indexOf(' ');
                if (separatorIndex > 0 && separatorIndex < line.length() - 1) {
                    String key = line.substring(0, separatorIndex).trim();
                    String value = line.substring(separatorIndex + 1).trim();
                    if (key.endsWith("]")) {
                        key = key.substring(1, key.length() - 1);
                        artData.put(key, value);
                    } else {
                        throw new IOException("Invalid .art file format");
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading .art file", e);
            reject("Error reading .art file: " + e.getMessage());
        }
        return artData;
    }

    // Android 10-12: puts the saved app language on this activity's resources (a no-op on Android 13+, where the system does it)
    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(com.limelight.shell.settings.AppLanguage.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        UiHelper.notifyNewRootView(this);
        ComputerDatabaseManager dbManager = new ComputerDatabaseManager(this);
        ComputerDetails _computer = null;

        Intent intent = getIntent();
        String action = intent.getAction();
        Uri dataUri = intent.getData();

        String hostUUID = null;
        String hostName = null;
        String appName = null;
        String appUUID = null;
        String appIDStr = null;

        if (Intent.ACTION_VIEW.equals(action) && dataUri != null) {
            Map<String, String> artData = parseArtFileData(dataUri);

            if (artData != null) {
                hostUUID = artData.get(ShortcutHelper.KEY_HOST_UUID);
                hostName = artData.get(ShortcutHelper.KEY_HOST_NAME);
                appName = artData.get(ShortcutHelper.KEY_APP_NAME);
                appUUID = artData.get(ShortcutHelper.KEY_APP_UUID);
                appIDStr = artData.get(ShortcutHelper.KEY_APP_ID);
            }
        }

        {
            // PC arguments, both are optional, but at least one must be provided
            if (hostUUID == null) {
                hostUUID = getIntent().getStringExtra(ShortcutTrampoline.UUID_EXTRA);
            }
            if (hostName == null) {
                hostName = getIntent().getStringExtra(ShortcutTrampoline.NAME_EXTRA);
            }

            // App arguments, all optional, but one must be provided in order to start an app
            if (appUUID == null) {
                appUUID = getIntent().getStringExtra(DelosStreamActivity.EXTRA_APP_UUID);
            }
            if (appIDStr == null) {
                appIDStr = getIntent().getStringExtra(DelosStreamActivity.EXTRA_APP_ID);
            }
            if (appName == null) {
                appName = getIntent().getStringExtra(DelosStreamActivity.EXTRA_APP_NAME);
            }
        }

        if (!validateHostInput(hostUUID, hostName)) {
            // Invalid input, so just return
//            finish();
            return;
        }

        if (hostUUID == null || hostUUID.isEmpty()) {
            // Use hostName to find the corresponding UUID
            _computer = dbManager.getComputerByName(hostName);

            if (_computer == null) {
                reject(R.string.scut_pc_not_found);
//                    finish();
                return;
            }

            hostUUID = _computer.uuid;
        }

        uuidString = hostUUID;

        // Set the AppView UUID intent
        setIntent(new Intent(getIntent()).putExtra(ShortcutTrampoline.UUID_EXTRA, uuidString));

        if (validateAppInput(appUUID, appIDStr, appName)) {
            // If app data came from .art file or was determined by appNameString from extras
            if (appUUID != null && !appUUID.isEmpty()) {
                app = new NvApp(appName, // appName can be null if only UUID is provided
                        appUUID,
                        -1, // App ID is not strictly needed if UUID is present
                        getIntent().getBooleanExtra(DelosStreamActivity.EXTRA_APP_HDR, false)); // HDR info still from intent
            } else if (appIDStr != null && !appIDStr.isEmpty()) {
                int appID = Integer.parseInt(appIDStr);
                app = new NvApp(appName, // appName can be null if only App ID is provided
                        null,
                        appID,
                        getIntent().getBooleanExtra(DelosStreamActivity.EXTRA_APP_HDR, false)); // HDR info still from intent
            } else if (appName != null && !appName.isEmpty()) {
                // Use appNameString (from .art file or intent extra) to find the corresponding AppId and AppUUID
                try {
                    int appID = -1;
                    String appUuidFromFile = null;
                    String rawAppList = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuidString));

                    if (rawAppList.isEmpty()) {
                        reject(getString(R.string.scut_invalid_app_id) + " (applist cache empty or unreadable)");
//                    finish();
                        return;
                    }
                    List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(rawAppList));

                    for (NvApp _app : applist) {
                        if (_app.getAppName().equalsIgnoreCase(appName)) {
                            appID = _app.getAppId();
                            appUuidFromFile = _app.getAppUUID();
                            break;
                        }
                    }
                    if (appID < 0 && appUuidFromFile == null) { // Need at least one
                        reject(getString(R.string.scut_invalid_app_id) + " (app not found in cache)");
//                    finish();
                        return;
                    }
                    // Update intent with found app ID and UUID if they weren't originally there
                    Intent currentIntent = getIntent();
                    if (currentIntent.getStringExtra(DelosStreamActivity.EXTRA_APP_ID) == null && appID != -1) {
                        currentIntent.putExtra(DelosStreamActivity.EXTRA_APP_ID, String.valueOf(appID));
                    }
                    if (currentIntent.getStringExtra(DelosStreamActivity.EXTRA_APP_UUID) == null && appUuidFromFile != null) {
                        currentIntent.putExtra(DelosStreamActivity.EXTRA_APP_UUID, appUuidFromFile);
                    }
                    app = new NvApp(
                            appName,
                            appUuidFromFile,
                            appID,
                            getIntent().getBooleanExtra(DelosStreamActivity.EXTRA_APP_HDR, false));
                } catch (IOException | XmlPullParserException e) {
                    Log.e(TAG, "Error processing app list from cache", e);
                    reject(getString(R.string.scut_invalid_app_id) + " (error parsing applist cache)");
//                finish();
                    return;
                }
            }
        }

        // Bind to the computer manager service
        serviceBound = bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        // Delos: the launch scene of the new UI while the PC is looked up (no classic spinner dialog); the
        // stream's loader goes on from it
        String shownPc = hostName;
        if (shownPc == null || shownPc.isEmpty()) {
            ComputerDetails known = _computer != null ? _computer : dbManager.getComputerByUUID(uuidString);
            shownPc = known != null ? known.name : null;
        }
        ShortcutSplash.show(this, appName, shownPc, uuidString, app != null ? app.getAppId() : -1);
    }

    @Override
    protected void onStop() {
        super.onStop();

        // The activity is gone for good (noHistory): end a lookup that is still waiting for the PC
        Thread thread = lookupThread;
        if (thread != null) {
            thread.interrupt();
        }
        releaseService();

        finish();
    }
}
