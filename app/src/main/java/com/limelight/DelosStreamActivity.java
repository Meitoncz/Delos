package com.limelight;


import static com.limelight.utils.ExternalDisplayFocus.requestFocusToExternalDisplayControl;
import static com.limelight.utils.ExternalDisplayControlActivity.closeExternalDisplayControl;
import static com.limelight.utils.ServerHelper.getActiveDisplay;
import static com.limelight.utils.ServerHelper.getSecondaryDisplay;

import com.limelight.binding.PlatformBinding;
import com.limelight.binding.audio.AndroidAudioRenderer;
import com.limelight.binding.input.ActiveKeyboardInput;
import com.limelight.binding.input.CommitTextSender;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.binding.input.MouseModeController;
import com.limelight.binding.input.StreamKeyHandler;
import com.limelight.binding.input.StreamPointerHandler;
import com.limelight.binding.input.capture.InputCaptureManager;
import com.limelight.binding.input.capture.InputCaptureProvider;
import com.limelight.binding.input.driver.UsbDriverService;
import com.limelight.binding.input.evdev.EvdevListener;
import com.limelight.binding.input.virtual_controller.VirtualController;
import com.limelight.binding.input.virtual_controller.keyboard.KeyBoardController;
import com.limelight.binding.input.virtual_controller.keyboard.KeyBoardLayoutController;
import com.limelight.binding.video.CrashListener;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.binding.video.PerfOverlayListener;
import com.limelight.binding.video.PyroWaveFormats;
import com.limelight.binding.video.PyroWaveRenderer;
import com.limelight.binding.video.StreamDisplayPlanner;
import com.limelight.binding.video.StreamSurfaceController;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.ExternalControllerView;
import com.limelight.ui.GameGestures;
import com.limelight.ui.StreamContainer;
import com.limelight.ui.StreamWindowController;
import com.limelight.utils.ExternalDisplayControlActivity;
import com.limelight.utils.PanZoomHandler;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.shell.StreamLoader;
import com.limelight.shell.StreamOutcome;
import com.limelight.shell.StreamResultKind;
import com.limelight.shell.StreamResultKt;
import com.limelight.utils.UiHelper;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.View.OnGenericMotionListener;
import android.view.View.OnTouchListener;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import com.limelight.utils.DelosToast;
import com.limelight.utils.StreamClipboardSync;
import com.limelight.utils.ToastKind;
import com.limelight.utils.WifiLockHolder;
import androidx.appcompat.app.AppCompatActivity;

import android.os.Looper;
import java.util.Objects;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import android.view.SurfaceView;


public class DelosStreamActivity extends AppCompatActivity implements SurfaceHolder.Callback,
        OnGenericMotionListener, OnTouchListener, NvConnectionListener, EvdevListener,
        GameGestures, StreamContainer.InputCallbacks,
        ExternalControllerView.InputCallbacks,
        PerfOverlayListener, UsbDriverService.UsbDriverStateListener, ActiveKeyboardInput.Target {
    public static DelosStreamActivity instance;

    private Handler timerHandler;

    private ControllerHandler controllerHandler;
    private KeyboardTranslator keyboardTranslator;
    private VirtualController virtualController;

    private KeyBoardController keyBoardController;

    private KeyBoardLayoutController keyBoardLayoutController;

    private PreferenceConfiguration prefConfig;
    private SharedPreferences tombstonePrefs;

    private int displayWidth;
    private int displayHeight;

    public NvConnection conn;
    private StreamLoader spinner;
    private boolean endedWithResult = false;
    private boolean connecting = false;
    public boolean connected = false;
    private float desiredRefreshRate;

    private InputCaptureProvider inputCaptureProvider;
    private boolean grabbedInput = true;
    private boolean cursorVisible = false;
    private StreamContainer streamContainer;

    private boolean quitOnStop = false;
    private TextView notificationOverlayView;
    private View performanceOverlayView;

    private TextView performanceOverlayLite;

    private TextView performanceOverlayBig;
    // Delos: the statistics panel of streams started from the new UI (replaces the two texts above)
    private com.limelight.shell.StreamStatsPanel statsPanel;

    private MediaCodecDecoderRenderer decoderRenderer;
    private boolean reportedCrash;

    private final WifiLockHolder wifiLocks = new WifiLockHolder();

    private boolean connectedToUsbDriverService = false;
    private ServiceConnection usbDriverServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            UsbDriverService.UsbDriverBinder binder = (UsbDriverService.UsbDriverBinder) iBinder;
            binder.setListener(controllerHandler);
            binder.setStateListener(DelosStreamActivity.this);
            binder.start();
            connectedToUsbDriverService = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            connectedToUsbDriverService = false;
        }
    };

    public static final String EXTRA_HOST = "Host";
    public static final String EXTRA_PORT = "Port";
    public static final String EXTRA_HTTPS_PORT = "HttpsPort";
    public static final String EXTRA_APP_NAME = "AppName";
    public static final String EXTRA_APP_UUID = "AppUUID";
    public static final String EXTRA_APP_ID = "AppId";
    public static final String EXTRA_UNIQUEID = "UniqueId";
    public static final String EXTRA_PC_UUID = "UUID";
    public static final String EXTRA_PC_NAME = "PcName";
    public static final String EXTRA_APP_HDR = "HDR";
    public static final String EXTRA_SERVER_CERT = "ServerCert";
    public static final String EXTRA_VDISPLAY = "VirtualDisplay";
    public static final String EXTRA_SERVER_COMMANDS = "ServerCommands";
    public static final String EXTRA_DISPLAY_ID = "DisplayID";

    private StreamIntentParams launch;
    private ViewParent rootView;
    private StreamClipboardSync clipboardSync;
    private StreamWindowController windowController;
    private StreamSurfaceController surfaceController;
    private StreamKeyHandler keyHandler;
    private StreamPointerHandler pointerHandler;
    private MouseModeController mouseModeController;

    private NvHTTP httpConn;

    public GameMenu gameMenuCallbacks;
    // Delos: the stream menu of the new UI
    private com.limelight.shell.StreamMenu streamMenu;

    private boolean onExternalDisplay = false;

    // IME commit text, sent in UTF-8 chunks on the main thread
    private final CommitTextSender commitTextSender = new CommitTextSender(
            new Handler(Looper.getMainLooper()),
            text -> {
                if (conn != null) {
                    conn.sendUtf8Text(text);
                }
            },
            (keyCode, keyDirection) -> conn.sendKeyboardInput(keyCode, keyDirection, (byte) 0, (byte) 0));

    private final Runnable backgroundPing = () -> {
        if (connected) {
            timerHandler.postDelayed(DelosStreamActivity.this.backgroundPing, 20);
            MoonBridge.sendEmptyPayload();
        }
    };

    @SuppressLint({"MissingInflatedId", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.limelight.shell.DelosToastOverlay.attach(this);

        // External-display DelosStreamActivity runs as a separate task. Android otherwise applies its default
        // side-slide even though the Delos loader is a continuation of the shell's launch scene.
        if (getIntent().getIntExtra(EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY) != Display.DEFAULT_DISPLAY) {
            // Keep the external window on Delos's near-black stage color until the first Compose
            // loader frame. A transparent stream window otherwise exposes the TV task background.
            getWindow().setBackgroundDrawableResource(R.color.shell_window_background);
            getWindow().getAttributes().windowAnimations = 0;
            overridePendingTransition(0, 0);
        }

        instance = this;
        ActiveKeyboardInput.register(this);
        timerHandler = new Handler(Looper.getMainLooper());

        UiHelper.setLocale(this);

        // We don't want a title bar
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Read the stream preferences
        prefConfig = PreferenceConfiguration.readPreferences(this);
        tombstonePrefs = DelosStreamActivity.this.getSharedPreferences("DecoderTombstone", 0);
        windowController = new StreamWindowController(this, prefConfig, new WindowHost());
        surfaceController = new StreamSurfaceController(this::runOnUiThread, prefConfig, new SurfaceHost());
        keyHandler = new StreamKeyHandler(prefConfig, new KeyHost());

        // Delos: always full screen (immersive), the option is gone
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // If we're going to use immersive mode, we want to have
        // the entire screen
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);

        // Listen for UI visibility events
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(windowController);

        // Change volume button behavior
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Inflate the content
        setContentView(R.layout.activity_game);

        clipboardSync = new StreamClipboardSync(this, prefConfig, new StreamClipboardSync.Host() {
            @Override
            public NvHTTP httpConn() {
                return httpConn;
            }

            @Override
            public boolean isConnected() {
                return connected;
            }

            @Override
            public void showToast(String text, ToastKind kind) {
                runOnUiThread(() -> DelosToast.show(DelosStreamActivity.this, text, DelosToast.SHORT, kind));
            }
        });

        // The loader of the new UI (the stream's half of the launch scene), with the system bars hidden from
        // the first frame, as in the new UI
        windowController.hideSystemUiNow();
        spinner = StreamLoader.show(this, prefConfig);

        Display currentDisplay = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int displayId = getIntent().getIntExtra(EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY);
            currentDisplay = getSystemService(DisplayManager.class).getDisplay(displayId);
        }

        if (currentDisplay == null) {
            currentDisplay = getWindowManager().getDefaultDisplay();
        }

        onExternalDisplay = currentDisplay.getDisplayId() != Display.DEFAULT_DISPLAY;

        boolean shouldInvertDecoderResolution = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && onExternalDisplay
        ) {
            // StreamConfiguration and the SurfaceView aspect ratio use these fields. They must stay
            // at the profile/per-game stream size; the Display object already carries the TV canvas.
            displayWidth = prefConfig.width;
            displayHeight = prefConfig.height;
            prefConfig.enablePip = false;
            windowController.lockLandscape();
        } else {
            boolean portraitMode = windowController.chooseOrientation() == Configuration.ORIENTATION_PORTRAIT;
            shouldInvertDecoderResolution = portraitMode && prefConfig.autoInvertVideoResolution;

            displayWidth = shouldInvertDecoderResolution ? prefConfig.height : prefConfig.width;
            displayHeight = shouldInvertDecoderResolution ? prefConfig.width : prefConfig.height;

            // Enter landscape unless we're on a square screen
            windowController.applyPreferredOrientation();
        }


        if (
                prefConfig.videoScaleMode == PreferenceConfiguration.ScaleMode.STRETCH ||
                        shouldIgnoreInsetsForResolution(displayWidth, displayHeight)
        ) {
            // Allow the activity to layout under notches if the fill-screen option
            // was turned on by the user or it's a full-screen native resolution
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                getWindow().getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        }

        // Whether the local cursor is shown
        cursorVisible = prefConfig.enableMouseLocalCursor;

        // Listen for non-touch events on the game surface
        streamContainer = findViewById(R.id.streamContainer);
        streamContainer.init(this, prefConfig);
        streamContainer.setOnGenericMotionListener(this);
        streamContainer.setOnKeyListener(this);
        streamContainer.setInputCallbacks(this);
        streamContainer.setCommitTextEnabled(prefConfig.enableCommitText);

        rootView = streamContainer.getParent();

        // Show the stream at the top center of the screen
        if(prefConfig.alignDisplayTopCenter){
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) streamContainer.getLayoutParams();
            params.gravity = Gravity.CENTER_HORIZONTAL|Gravity.TOP;
        }
        // Listen for touch events on the background touch view to enable trackpad mode
        // to work on areas outside of the StreamView itself. We use a separate View
        // for this rather than just handling it at the Activity level, because that
        // allows proper touch splitting, which the OSC relies upon.
        View backgroundTouchView = findViewById(R.id.backgroundTouchView);
        backgroundTouchView.setOnTouchListener(this);


        pointerHandler = new StreamPointerHandler(this, prefConfig, streamContainer,
                new PanZoomHandler(
                        getApplicationContext(),
                        windowController::updatePipAutoEnter,
                        streamContainer.getSurfaceView(),
                        streamContainer,
                        prefConfig
                ),
                new PointerHost());

        // Restore previous zoom & pan if enabled and saved
        pointerHandler.restoreZoomAndPan();
        mouseModeController = new MouseModeController(this, prefConfig, streamContainer, pointerHandler, new MouseModeHost());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Request unbuffered input event dispatching for all input classes we handle here.
            // Without this, input events are buffered to be delivered in lock-step with VBlank,
            // artificially increasing input latency while streaming.
            streamContainer.requestUnbufferedDispatch(
                    InputDevice.SOURCE_CLASS_BUTTON | // Keyboards
                            InputDevice.SOURCE_CLASS_JOYSTICK | // Gamepads
                            InputDevice.SOURCE_CLASS_POINTER | // Touchscreens and mice (w/o pointer capture)
                            InputDevice.SOURCE_CLASS_POSITION | // Touchpads
                            InputDevice.SOURCE_CLASS_TRACKBALL // Mice (pointer capture)
            );
            backgroundTouchView.requestUnbufferedDispatch(
                    InputDevice.SOURCE_CLASS_BUTTON | // Keyboards
                            InputDevice.SOURCE_CLASS_JOYSTICK | // Gamepads
                            InputDevice.SOURCE_CLASS_POINTER | // Touchscreens and mice (w/o pointer capture)
                            InputDevice.SOURCE_CLASS_POSITION | // Touchpads
                            InputDevice.SOURCE_CLASS_TRACKBALL // Mice (pointer capture)
            );
        }

        notificationOverlayView = findViewById(R.id.notificationOverlay);

        performanceOverlayView = findViewById(R.id.performanceOverlay);

        performanceOverlayLite = findViewById(R.id.performanceOverlayLite);

        performanceOverlayBig = findViewById(R.id.performanceOverlayBig);
        statsPanel = com.limelight.shell.StreamStatsPanel.attach(this, (android.view.ViewGroup) performanceOverlayView, prefConfig.enablePerfOverlayLite);

        inputCaptureProvider = InputCaptureManager.getInputCaptureProvider(this, this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            streamContainer.setOnCapturedPointerListener(new View.OnCapturedPointerListener() {
                @Override
                public boolean onCapturedPointer(View view, MotionEvent motionEvent) {
//                    LimeLog.info("onCapturedPointer="+motionEvent.toString());
//                    LimeLog.info("onCapturedPointer-Device="+motionEvent.getDevice().toString());
                    return handleMotionEvent(view, motionEvent);
                }
            });
        }

        // Warn the user if they're on a metered connection
        ConnectivityManager connMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean isMetered = connMgr.isActiveNetworkMetered();
        if (isMetered) {
            displayTransientMessage(getResources().getString(R.string.conn_metered));
        }

        // Make sure Wi-Fi is fully powered up
        wifiLocks.acquire(this);

        launch = StreamIntentParams.fromIntent(getIntent());
        httpConn = launch.createHttp(this);

        if (!launch.hasValidApp()) {
            finish();
            return;
        }

        // Initialize the MediaCodec helper before creating the decoder
        GlPreferences glPrefs = GlPreferences.readPreferences(this);
        MediaCodecHelper.initialize(this, glPrefs.glRenderer);

        // Check if the user has enabled HDR
        boolean willStreamHdr = false;
        if (prefConfig.enableHdr) {
            if (onExternalDisplay) {
                // Enforce HDR on unsupported hardware can still enable 10bit streaming for better quality
                willStreamHdr = true;
            } else {
                // Start our HDR checklist
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Display.HdrCapabilities hdrCaps = currentDisplay.getHdrCapabilities();

                    // We must now ensure our display is compatible with HDR10
                    if (hdrCaps != null) {
                        // getHdrCapabilities() returns null on Lenovo Lenovo Mirage Solo (vega), Android 8.0
                        for (int hdrType : hdrCaps.getSupportedHdrTypes()) {
                            if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                                willStreamHdr = true;
                                break;
                            }
                        }
                    }

                    if (!willStreamHdr) {
                        // Nope, no HDR for us :(
                        DelosToast.show(this, "Display does not support HDR10", DelosToast.LONG, ToastKind.WARNING);
                    }
                }
                else {
                    DelosToast.show(this, "HDR requires Android 7.0 or later", DelosToast.LONG, ToastKind.WARNING);
                }
            }
        }

        // Delos: PyroWave when chosen and this device can decode it (Vulkan); the host falls back to the
        // usual codecs without it. With HDR on it is offered in 10-bit (HDR10).
        boolean offerPyroWave = false;
        if (prefConfig.videoFormat == PreferenceConfiguration.FormatOption.PYROWAVE) {
            if (PyroWaveRenderer.isAvailable()) {
                offerPyroWave = true;
            } else {
                DelosToast.show(this, "This device cannot decode PyroWave", DelosToast.LONG, ToastKind.WARNING);
            }
        }

        // Check if the user has enabled performance stats overlay
        if (prefConfig.enablePerfOverlay) {
            performanceOverlayView.setVisibility(View.VISIBLE);
            if (statsPanel != null) {
                // Delos: the statistics panel instead
            } else if (prefConfig.enablePerfOverlayLite) {
                performanceOverlayLite.setVisibility(View.VISIBLE);
            } else {
                performanceOverlayBig.setVisibility(View.VISIBLE);
            }
        }

        decoderRenderer = new MediaCodecDecoderRenderer(
                this,
                surfaceController,
                prefConfig,
                new CrashListener() {
                    @Override
                    public void notifyCrash(Exception e) {
                        // The MediaCodec instance is going down due to a crash
                        // let's tell the user something when they open the app again

                        // We must use commit because the app will crash when we return from this function
                        tombstonePrefs.edit().putInt("CrashCount", tombstonePrefs.getInt("CrashCount", 0) + 1).commit();
                        reportedCrash = true;
                    }
                },
                tombstonePrefs.getInt("CrashCount", 0),
                connMgr.isActiveNetworkMetered(),
                willStreamHdr,
                shouldInvertDecoderResolution,
                glPrefs.glRenderer,
                this);
        surfaceController.setDecoder(decoderRenderer);
        // Warm arrival resources without taking the video surface away from the decoder yet.
        decoderRenderer.prepareLaunchArrival(spinner == null || spinner.getReducedMotion());

        // LFR (low latency frame balance) is a variant of Balanced frame pacing that presents
        // queued frames at the next vsync and polls the decoder more often
        // Delos: Fast V-Sync owns pacing when selected; LFR is a separate Balanced variant.
        boolean useLfr = prefConfig.preferLowerDelays && prefConfig.framePacing != PreferenceConfiguration.FRAME_PACING_FAST_VSYNC;
        decoderRenderer.setPreferLowerDelays(useLfr);
        if (useLfr) {
            decoderRenderer.setPreferLowerDelaysTimeoutUs(500);
            prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
        } else {
            decoderRenderer.setPreferLowerDelaysTimeoutUs(2000);
        }
        LimeLog.info("Frame pacing: " + prefConfig.framePacing + ", LFR: " + useLfr);


        // Don't stream HDR if the decoder can't support it
        if (willStreamHdr && !offerPyroWave && !decoderRenderer.isHevcMain10Hdr10Supported() && !decoderRenderer.isAv1Main10Supported()) {
            willStreamHdr = false;
            DelosToast.show(this, "Decoder does not support HDR10 profile", DelosToast.LONG, ToastKind.WARNING);
        }
        // Display a message to the user if HEVC was forced on but we still didn't find a decoder
        if (prefConfig.videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC && !decoderRenderer.isHevcSupported()) {
            DelosToast.show(this, "No HEVC decoder found", DelosToast.LONG, ToastKind.WARNING);
        }

        // Display a message to the user if AV1 was forced on but we still didn't find a decoder
        if (prefConfig.videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1 && !decoderRenderer.isAv1Supported()) {
            DelosToast.show(this, "No AV1 decoder found", DelosToast.LONG, ToastKind.WARNING);
        }

        // H.264 is always supported
        int supportedVideoFormats = MoonBridge.VIDEO_FORMAT_H264;
        if (decoderRenderer.isHevcSupported()) {
            supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_H265;
            if (willStreamHdr && decoderRenderer.isHevcMain10Hdr10Supported()) {
                supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_H265_MAIN10;
            }
        }
        if (decoderRenderer.isAv1Supported()) {
            supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_AV1_MAIN8;
            if (willStreamHdr && decoderRenderer.isAv1Main10Supported()) {
                supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_AV1_MAIN10;
            }
        }
        if (offerPyroWave) {
            supportedVideoFormats |= PyroWaveFormats.offered(willStreamHdr, prefConfig.pyroWaveChroma444);
            if (willStreamHdr && prefConfig.pyroWaveChroma444) {
                DelosToast.show(this, "PyroWave 4:4:4 is not offered with HDR; using 4:2:0", DelosToast.LONG, ToastKind.WARNING);
            }
        }

        int gamepadMask = ControllerHandler.getAttachedControllerMask(this);
        if (!prefConfig.multiController) {
            // Always set gamepad 1 present for when multi-controller is
            // disabled for games that don't properly support detection
            // of gamepads removed and replugged at runtime.
            gamepadMask = 1;
        }
        if (prefConfig.onscreenController) {
            // If we're using OSC, always set at least gamepad 1.
            gamepadMask |= 1;
        }

        // Set to the optimal mode for streaming, and fit the frame pacing to the refresh rate
        StreamDisplayPlanner.Plan displayPlan = prepareDisplayForRendering(currentDisplay);
        float displayRefreshRate = displayPlan.displayRefreshRate;
        float chosenFrameRate = displayPlan.streamFrameRate;

        StreamConfiguration config = new StreamConfiguration.Builder()
                .setResolution(
                        displayWidth,
                        displayHeight
                )
                .setLaunchRefreshRate(prefConfig.fps)
                .setRefreshRate(chosenFrameRate)
                .setVirtualDisplay(launch.vDisplay)
                .setResolutionScaleFactor(prefConfig.resolutionScaleFactor)
                .setApp(launch.app)
                .setEnableUltraLowLatency(prefConfig.enableUltraLowLatency)
                .setBitrate(isMetered ? prefConfig.meteredBitrate: prefConfig.bitrate)
                .setEnableSops(false) // Delos: GFE only, Vibepollo / Apollo / Sunshine ignore it
                .enableLocalAudioPlayback(prefConfig.playHostAudio)
                .setMaxPacketSize(1392)
                .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO) // NvConnection will perform LAN and VPN detection
                .setSupportedVideoFormats(supportedVideoFormats)
                .setAttachedGamepadMask(gamepadMask)
                .setClientRefreshRateX100((int)(displayRefreshRate * 100))
                .setAudioConfiguration(prefConfig.audioConfiguration)
                .setColorSpace(decoderRenderer.getPreferredColorSpace())
                .setColorRange(decoderRenderer.getPreferredColorRange())
                .setPersistGamepadsAfterDisconnect(!prefConfig.multiController)
                .build();

        decoderRenderer.setStreamDiagnostics(config.getBitrate(), isMetered, prefConfig.pyroWaveChroma444);

        // Initialize the connection
        conn = new NvConnection(getApplicationContext(),
                new ComputerDetails.AddressTuple(launch.host, launch.port),
                launch.httpsPort, launch.uniqueId, config,
                PlatformBinding.getCryptoProvider(this), launch.serverCert);
        controllerHandler = new ControllerHandler(this, conn, this, prefConfig);
        keyboardTranslator = new KeyboardTranslator(prefConfig);

        InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        inputManager.registerInputDeviceListener(keyboardTranslator, null);

        // Initialize trackpad contexts
        pointerHandler.createTrackpadContexts();

        if (Objects.equals(launch.appUUID, NvApp.REMOTE_INPUT_UUID)) {
            // Force trackpad mode since we won't see anything on the screen
            mouseModeController.forceTrackpad();
        } else {
            if (prefConfig.enableFullExDisplay && onExternalDisplay) {
                requestFocusToExternalDisplayControl(this);
                listenForExternalDisplayRemoval();
            }

            // Initialize touch contexts based on preferences
            // The mouse mode preference is also read in PreferenceConfiguration to set the boolean flags
            mouseModeController.initMouseMode();
        }

        if (prefConfig.onscreenController) {
            // create virtual onscreen controller
            if (prefConfig.hideOSCWhenHasGamepad) {
                if (!controllerHandler.hasController()) {
                    initVirtualController();
                }
            } else {
                initVirtualController();
            }
        }

        // On-screen special keys layout
        if(prefConfig.enableKeyboard){
            initKeyboardController();
        }

        if (!decoderRenderer.isAvcSupported()) {
            // If we can't find an AVC decoder, we can't proceed
            StreamOutcome.failed(this, StreamResultKind.FAILED_TO_START, StreamResultKt.describeNoH264Decoder());
            return;
        }

        // The connection will be started when the surface gets created
        //streamContainer.getHolder().addCallback(this);

        streamContainer.setOnSurfaceAvailable(surfaceController::onSurfaceAvailable);

        gameMenuCallbacks = new GameMenu(this);
        streamMenu = new com.limelight.shell.StreamMenu(this);

        surfaceController.configureStreamSurface(findViewById(android.R.id.content));
    }

    private DisplayManager.DisplayListener externalDisplayListener;

    private void listenForExternalDisplayRemoval() {
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        externalDisplayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                if (getSecondaryDisplay(getBaseContext()) == null) {
                    if (streamMenu != null) {
                        streamMenu.closeForDisplayRemoval();
                    }
                    closeExternalDisplayControl();
                    finish();
                }
            }

            @Override
            public void onDisplayChanged(int displayId) {
            }
        };
        displayManager.registerDisplayListener(externalDisplayListener, null);
    }


    private void initKeyboardController(){
        keyBoardController = new KeyBoardController(conn,(FrameLayout)rootView, this);
        keyBoardController.refreshLayout();
        keyBoardController.show();
    }

    public Boolean isKeyboardLayoutVisible() {
        return keyBoardLayoutController != null && keyBoardLayoutController.shown;
    }

    private void initVirtualController(){
        virtualController = new VirtualController(controllerHandler, (FrameLayout)rootView, this);
        virtualController.refreshLayout();
        virtualController.show();
    }

    /** Delos: the Full keyboard that is up, on this display or on the external control display; null when there is none. */
    private KeyBoardLayoutController visibleFullKeyboard() {
        KeyBoardLayoutController keyboard = isOnExternalDisplay() ? ExternalDisplayControlActivity.getFullKeyboard() : keyBoardLayoutController;
        return keyboard != null && keyboard.isKeyboardVisible() ? keyboard : null;
    }

    /** Delos: the Full keyboard went up or down: it takes the controller (everything held is let go) or gives it back. */
    public void onFullKeyboardVisibilityChanged() {
        if (controllerHandler != null) {
            controllerHandler.setInputSuspended(visibleFullKeyboard() != null);
        }
    }

    private void initkeyBoardLayoutController(){
        keyBoardLayoutController = new KeyBoardLayoutController((FrameLayout)rootView, this, prefConfig);
        keyBoardLayoutController.setViewCallbacks(visible -> onFullKeyboardVisibilityChanged());
        keyBoardLayoutController.refreshLayout();
        keyBoardLayoutController.show();
    }

    public void toggleFullKeyboard() {
        if (isOnExternalDisplay()) {
            ExternalDisplayControlActivity.toggleFullKeyboard();
            return;
        }
        if (keyBoardLayoutController == null) {
            initkeyBoardLayoutController();
            return;
        }
        keyBoardLayoutController.toggleVisibility();
    }

    /** Whether the full keyboard overlay is up, on this display or on the external control display. */
    public boolean isFullKeyboardShown() {
        return isOnExternalDisplay() ? ExternalDisplayControlActivity.isFullKeyboardShown() : isKeyboardLayoutVisible();
    }

    /** Whether the on-screen gamepad is really up (not the stored preference). */
    public boolean isVirtualControllerShown() {
        return virtualController != null && virtualController.shown;
    }

    // Show/hide the virtual gamepad controller
    public void toggleVirtualController(){
        if (virtualController==null) {
            initVirtualController();
            prefConfig.onscreenController=true;
            return;
        }
        prefConfig.onscreenController= virtualController.switchShowHide() != 0;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        windowController.onConfigurationChanged();
    }

    /** What StreamWindowController needs from the stream: sizes, views, and the controls to hide in PiP. */
    private final class WindowHost implements StreamWindowController.Host {
        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public boolean isOnExternalDisplay() {
            return DelosStreamActivity.this.isOnExternalDisplay();
        }

        @Override
        public int videoWidth() {
            return displayWidth;
        }

        @Override
        public int videoHeight() {
            return displayHeight;
        }

        @Override
        public StreamContainer streamContainer() {
            return streamContainer;
        }

        @Override
        public View streamRoot() {
            return (View) rootView;
        }

        @Override
        public View performanceOverlay() {
            return performanceOverlayView;
        }

        @Override
        public View notificationOverlay() {
            return notificationOverlayView;
        }

        @Override
        public String appName() {
            return launch.appName;
        }

        @Override
        public String pcName() {
            return launch.pcName;
        }

        @Override
        public void refreshControlLayouts() {
            if (virtualController != null) {
                // Refresh layout of OSC for possible new screen size
                virtualController.refreshLayout();
            }

            if(keyBoardController != null){
                keyBoardController.refreshLayout();
            }

            if(keyBoardLayoutController != null){
                keyBoardLayoutController.refreshLayout();
            }
        }

        @Override
        public void hideControlsForPip() {
            if (virtualController != null) {
                virtualController.hide(true);
            }

            if (keyBoardController != null && keyBoardController.shown) {
                keyBoardController.hide(true);
            }

            if (keyBoardLayoutController!=null && keyBoardLayoutController.shown) {
                keyBoardLayoutController.hide(true);
            }

            hideGameMenu();
        }

        @Override
        public void restoreControlsAfterPip() {
            if (virtualController != null && virtualController.shown) {
                virtualController.show();
            }

            if (keyBoardController != null && keyBoardController.shown) {
                keyBoardController.show();
            }

            if(keyBoardLayoutController!=null && keyBoardLayoutController.shown){
                keyBoardLayoutController.show();
            }
        }

        @Override
        public void setControllerSensorsEnabled(boolean enabled) {
            if (enabled) {
                controllerHandler.enableSensors();
            } else {
                controllerHandler.disableSensors();
            }
        }

        @Override
        public void setVideoBackground(boolean background) {
            if (background) {
                decoderRenderer.notifyVideoBackground();
            } else {
                decoderRenderer.notifyVideoForeground();
            }
        }
    }

    /** What StreamSurfaceController needs from the stream: the surface, the display size and the connection. */
    private final class SurfaceHost implements StreamSurfaceController.Host {
        @Override
        public SurfaceView streamSurfaceView() {
            return streamContainer != null ? streamContainer.getSurfaceView() : null;
        }

        @Override
        public Surface activeSurface() {
            return streamContainer.getSurface();
        }

        @Override
        public int displayWidth() {
            return displayWidth;
        }

        @Override
        public int displayHeight() {
            return displayHeight;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void startConnection() {
            conn.start(new AndroidAudioRenderer(DelosStreamActivity.this, prefConfig.playHostAudio),
                    decoderRenderer, DelosStreamActivity.this);
        }

        @Override
        public void stopConnection() {
            DelosStreamActivity.this.stopConnection();
        }
    }

    /** What StreamKeyHandler needs from the stream: the connection, the grab state, the menu and the Full keyboard. */
    private final class KeyHost implements StreamKeyHandler.Host {
        @Override
        public NvConnection conn() {
            return conn;
        }

        @Override
        public KeyboardTranslator keyboardTranslator() {
            return keyboardTranslator;
        }

        @Override
        public ControllerHandler controllerHandler() {
            return controllerHandler;
        }

        @Override
        public boolean isInputGrabbed() {
            return grabbedInput;
        }

        @Override
        public boolean streamMenuTakesKey(KeyEvent event) {
            return DelosStreamActivity.this.streamMenuTakesKey(event);
        }

        @Override
        public KeyBoardLayoutController visibleFullKeyboard() {
            return DelosStreamActivity.this.visibleFullKeyboard();
        }

        @Override
        public void toggleInputGrabLater() {
            Handler h = getWindow().getDecorView().getHandler();
            if (h != null) {
                h.postDelayed(toggleGrab, 250);
            }
        }

        @Override
        public void quit() {
            finish();
        }

        @Override
        public void toggleLocalCursor() {
            toggleMouseLocalCursor();
        }
    }

    /** What StreamPointerHandler needs from the stream: the connection, the mouse capture and the gesture actions. */
    private final class PointerHost implements StreamPointerHandler.Host {
        @Override
        public NvConnection conn() {
            return conn;
        }

        @Override
        public InputCaptureProvider inputCaptureProvider() {
            return inputCaptureProvider;
        }

        @Override
        public boolean isConfiguringOnscreenControls() {
            return virtualController != null &&
                    (virtualController.getControllerMode() == VirtualController.ControllerMode.MoveButtons ||
                            virtualController.getControllerMode() == VirtualController.ControllerMode.ResizeButtons);
        }

        @Override
        public void toggleKeyboard() {
            DelosStreamActivity.this.toggleKeyboard();
        }

        @Override
        public void toggleFullKeyboard() {
            DelosStreamActivity.this.toggleFullKeyboard();
        }

        @Override
        public void showGameMenu() {
            DelosStreamActivity.this.showGameMenu();
        }
    }

    /** What MouseModeController needs from the stream: the connection the touch contexts send to. */
    private final class MouseModeHost implements MouseModeController.Host {
        @Override
        public NvConnection conn() {
            return conn;
        }

        @Override
        public boolean isOnExternalDisplay() {
            return DelosStreamActivity.this.isOnExternalDisplay();
        }
    }

    /** The stream surface and the live upscaler controls (the stream menu's and the launch arrival's facade). */
    public StreamSurfaceController getSurfaceController() {
        return surfaceController;
    }

    /** The keyboard input: key routing, modifiers and the special-key combinations sent by the menus. */
    public StreamKeyHandler getKeyHandler() {
        return keyHandler;
    }

    /** The pointer input: mouse, stylus, touchpad and touchscreen, the touch sensitivity and the pan and zoom mode. */
    public StreamPointerHandler getPointerHandler() {
        return pointerHandler;
    }

    /** The touchscreen's mouse mode, which the stream menu reads and switches. */
    public MouseModeController getMouseModeController() {
        return mouseModeController;
    }

    public void setMetaKeyCaptureState(boolean enabled) {
        // Android has native keyboard capture support starting in API 36.1
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
            WindowManager.LayoutParams windowLayoutParams = getWindow().getAttributes();
            windowLayoutParams.setKeyboardCaptureEnabled(enabled);
            getWindow().setAttributes(windowLayoutParams);
        }
        else {
            // This uses custom APIs present on some Samsung devices to allow capture of
            // meta key events while streaming.
            try {
                Class<?> semWindowManager = Class.forName("com.samsung.android.view.SemWindowManager");
                Method getInstanceMethod = semWindowManager.getMethod("getInstance");
                Object manager = getInstanceMethod.invoke(null);

                if (manager != null) {
                    Class<?>[] parameterTypes = new Class<?>[2];
                    parameterTypes[0] = ComponentName.class;
                    parameterTypes[1] = boolean.class;
                    Method requestMetaKeyEventMethod = semWindowManager.getDeclaredMethod("requestMetaKeyEvent", parameterTypes);
                    requestMetaKeyEventMethod.invoke(manager, this.getComponentName(), enabled);
                }
                else {
                    LimeLog.warning("SemWindowManager.getInstance() returned null");
                }
            } catch (ClassNotFoundException ignored) {
            } catch (NoSuchMethodException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        windowController.onUserLeaveHint();
    }

    @Override
    @TargetApi(Build.VERSION_CODES.R)
    public boolean onPictureInPictureRequested() {
        windowController.onPictureInPictureRequested();
        return true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        // We can't guarantee the state of modifiers keys which may have
        // lifted while focus was not on us. Clear the modifier state.
        keyHandler.clearModifierState();

        // With Android native pointer capture, capture is lost when focus is lost,
        // so it must be requested again when focus is regained.
        inputCaptureProvider.onWindowFocusChanged(hasFocus);
    }

    private boolean shouldIgnoreInsetsForResolution(int width, int height) {
        return StreamDisplayPlanner.shouldIgnoreInsets(width, height,
                StreamDisplayPlanner.Mode.of(getActiveDisplay(DelosStreamActivity.this, prefConfig).getSupportedModes()));
    }

    public boolean isOnExternalDisplay() {
        return onExternalDisplay;
    }

    // Applies the display plan: the display mode, the stream container's aspect ratio and the frame pacing.
    private StreamDisplayPlanner.Plan prepareDisplayForRendering(Display currentDisplay) {
        List<StreamDisplayPlanner.Mode> supportedModes = StreamDisplayPlanner.Mode.of(currentDisplay.getSupportedModes());
        StreamDisplayPlanner.Plan plan = StreamDisplayPlanner.plan(new StreamDisplayPlanner.Input()
                .display(StreamDisplayPlanner.Mode.of(currentDisplay.getMode()), supportedModes, currentDisplay.getRefreshRate())
                .stream(prefConfig.width, prefConfig.height, prefConfig.fps)
                .video(displayWidth, displayHeight, prefConfig.videoScaleMode)
                .pacing(prefConfig.framePacing, prefConfig.reduceRefreshRate, prefConfig.framePacingWarpFactor)
                .enforceDisplayMode(prefConfig.enforceDisplayMode)
                .sdkInt(Build.VERSION.SDK_INT)
                .television(getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
                        getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK))
                .externalDisplay(isOnExternalDisplay()));

        LimeLog.info("Current display mode: "+plan.currentMode);
        for (StreamDisplayPlanner.Mode candidate : supportedModes) {
            LimeLog.info("Examining display mode: "+candidate);
        }
        LimeLog.info("Best display mode: "+plan.bestMode);

        switch (plan.modeSwitch) {
            case PREFERRED_MODE_ID:
                // Apply the display mode change
                WindowManager.LayoutParams windowLayoutParams = getWindow().getAttributes();
                windowLayoutParams.preferredDisplayModeId = plan.bestMode.id;
                getWindow().setAttributes(windowLayoutParams);
                break;
            case SET_FRAME_RATE:
                LimeLog.info("Using setFrameRate() instead of preferredDisplayModeId due to matching resolution");
                break;
            case NONE:
                LimeLog.info("Current display mode is already the best display mode");
                break;
        }

        if (plan.applyAspectRatio) {
            // Set the surface to scale based on the aspect ratio of the stream
            streamContainer.setDesiredAspectRatio(plan.aspectRatio);
            streamContainer.setFillDisplay(plan.fillDisplay);
            LimeLog.info("surfaceChanged-->"+plan.aspectRatio);
            LimeLog.info("scaleMode-->"+prefConfig.videoScaleMode);
        }

        // Set the desired refresh rate that will get passed into setFrameRate() later
        desiredRefreshRate = plan.desiredRefreshRate;

        LimeLog.info("Display refresh rate: "+plan.displayRefreshRate);

        prefConfig.framePacing = plan.framePacing;
        switch (plan.pacingChange) {
            case DROP_FRAMES_ABOVE_REFRESH_RATE:
                LimeLog.info("Using drop mode for FPS > Hz");
                break;
            case BOGUS_REFRESH_RATE:
                LimeLog.info("Bogus refresh rate: " + plan.roundedRefreshRate);
                break;
            case CAPPED_BELOW_REFRESH_RATE:
                LimeLog.info("Adjusting FPS target for screen to " + plan.pacedFrameRate);
                break;
            case NONE:
                break;
        }
        return plan;
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
        super.onMultiWindowModeChanged(isInMultiWindowMode);
        windowController.onMultiWindowModeChanged(isInMultiWindowMode);
    }

    @Override
    protected void onDestroy() {
        if (externalDisplayListener != null) {
            ((DisplayManager) getSystemService(Context.DISPLAY_SERVICE)).unregisterDisplayListener(externalDisplayListener);
            externalDisplayListener = null;
        }
        if (streamMenu != null) {
            streamMenu.closeForDisplayRemoval();
            streamMenu.flushAutoSave();
        }
        super.onDestroy();

        instance = null;
        ActiveKeyboardInput.unregister(this);
        timerHandler.removeCallbacksAndMessages(null);

        if (prefConfig.enableFullExDisplay) closeExternalDisplayControl();

        if (controllerHandler != null) {
            controllerHandler.destroy();
        }
        if (keyboardTranslator != null) {
            InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
            inputManager.unregisterInputDeviceListener(keyboardTranslator);
        }

        wifiLocks.release();

        // Save zoom/pan before other cleanup
        if (prefConfig != null && pointerHandler != null) {
            pointerHandler.saveZoomAndPan();
        }

        if (connectedToUsbDriverService) {
            // Unbind from the discovery service
            unbindService(usbDriverServiceConnection);
        }

        // Destroy the capture provider
        inputCaptureProvider.destroy();
    }

    @Override
    protected void onPause() {
        if (isFinishing()) {
            // Stop any further input device notifications before we lose focus (and pointer capture)
            if (controllerHandler != null) {
                controllerHandler.stop();
            }

            // Ungrab input to prevent further input device notifications
            setInputGrabState(false);
        }

        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (virtualController != null) {
            virtualController.hide();
        }
        if (keyBoardController != null) {
            keyBoardController.hide();
        }

        if(keyBoardLayoutController!=null){
            keyBoardLayoutController.hide();
        }

        if (conn != null) {
            endedWithResult = true;
            stopConnection();

            // Clear the tombstone count if we terminated normally
            if (!reportedCrash && tombstonePrefs.getInt("CrashCount", 0) != 0) {
                tombstonePrefs.edit()
                        .putInt("CrashCount", 0)
                        .putInt("LastNotifiedCrashCount", 0)
                        .apply();
            }

        }

        finish();
    }

    private void setInputGrabState(boolean grab) {
        // Grab/ungrab the mouse cursor
        if (grab) {
            inputCaptureProvider.enableCapture();

            // Enabling capture may hide the cursor again, so
            // we will need to show it again.
            if (cursorVisible) {
                inputCaptureProvider.showCursor();
            }
        }
        else {
            inputCaptureProvider.disableCapture();
        }

        // Grab/ungrab system keyboard shortcuts
        setMetaKeyCaptureState(grab);

        grabbedInput = grab;
    }

    private final Runnable toggleGrab = new Runnable() {
        @Override
        public void run() {
            setInputGrabState(!grabbedInput);
        }
    };

    // Delos: the stream menu shortcut, or the open stream menu, on this display
    private boolean streamMenuTakesKey(KeyEvent event) {
        return streamMenu != null && !isOnExternalDisplay() && streamMenu.onKey(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return keyHandler.handleKeyDown(event) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean handleKeyDown(KeyEvent event) {
        return keyHandler.handleKeyDown(event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return keyHandler.handleKeyUp(event) || super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean handleKeyUp(KeyEvent event) {
        return keyHandler.handleKeyUp(event);
    }

    @Override
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        return keyHandler.handleKeyMultiple(event) || super.onKeyMultiple(keyCode, repeatCount, event);
    }

    public boolean handleFocusChange(boolean hasFocus) {
        return clipboardSync.handleFocusChange(hasFocus);
    }

    public boolean sendClipboard(boolean force) {
        return clipboardSync.sendClipboard(force);
    }

    public boolean getClipboard(int delay) {
        return clipboardSync.getClipboard(delay);
    }

    @Override
    public void toggleKeyboard() {
        if (isOnExternalDisplay()) {
            ExternalDisplayControlActivity.toggleKeyboard();
        } else {
            LimeLog.info("Toggling keyboard overlay");
            InputMethodManager inputManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            inputManager.toggleSoftInput(0, 0);
        }
    }

    // Returns true if the event was consumed
    // NB: View is only present if called from a view callback
    public boolean handleMotionEvent(View view, MotionEvent event) {
        // Pass through mouse/touch/joystick input if we're not grabbing
        if (!grabbedInput) {
            return false;
        }

        int deviceId = event.getDeviceId();
        if (prefConfig.ignoreSynthEvents && deviceId <= 0) {
            return false;
        }

        int eventSource = event.getSource();
        int deviceSources = event.getDevice() != null ? event.getDevice().getSources() : 0;
        if ((eventSource & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
            return handleControllerMotionEvent(event);
        }
        else if ((deviceSources & InputDevice.SOURCE_CLASS_JOYSTICK) != 0 && controllerHandler.tryHandleTouchpadEvent(event)) {
            return true;
        }

        // Mice, styluses, touchpads and the touchscreen; false for an unknown class
        return pointerHandler.handlePointerEvent(view, event);
    }

    // Gamepad sticks and triggers: the Full keyboard while it is up, otherwise the controller handler
    private boolean handleControllerMotionEvent(MotionEvent event) {
        // Delos: while the Full keyboard is up it owns the controller (see StreamKeyHandler.handleKeyDown())
        KeyBoardLayoutController fullKeyboard = visibleFullKeyboard();
        if (fullKeyboard != null) {
            return fullKeyboard.onControllerMotion(event);
        }
        return controllerHandler.handleMotionEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        return handleMotionEvent(null, event) || super.onGenericMotionEvent(event);

    }

    @Override
    public boolean onGenericMotion(View view, MotionEvent event) {
        return handleMotionEvent(view, event);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            // Tell the OS not to buffer input events for us
            //
            // NB: This is still needed even when we call the newer requestUnbufferedDispatch()!
            view.requestUnbufferedDispatch(event);
        }

        return handleMotionEvent(view, event);
    }

    @Override
    public void stageStarting(final String stage) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (spinner != null) {
                    spinner.setMessage(getResources().getString(R.string.conn_starting) + " " + stage);
                }
            }
        });
    }

    @Override
    public void stageComplete(String stage) {
    }

    private void stopConnection() {
        if (connecting || connected) {
            connecting = connected = false;
            windowController.updatePipAutoEnter();

            controllerHandler.stop();

            // Update GameManager state to indicate we're no longer in game
            UiHelper.notifyStreamEnded(this);

            // Stop may take a few hundred ms to do some network I/O to tell
            // the server we're going away and clean up. Let it run in a separate
            // thread to keep things smooth for the UI. Inside moonlight-common,
            // we prevent another thread from starting a connection before and
            // during the process of stopping this one.
            new Thread() {
                public void run() {
                    conn.stop();
                    if (httpConn != null && quitOnStop) {
                        // Delos: the host is still tearing the stream down for a while and then refuses the quit request
                        // ("Another stream operation is still running", cancel = 0), so it is repeated until it is accepted.
                        // The toasts use the application context: this activity is already gone.
                        final android.content.Context app = getApplicationContext();
                        final String quitName = launch.appName;
                        String failure = null;
                        boolean quit = false;
                        for (int attempt = 0; attempt < 12 && !quit; attempt++) {
                            try {
                                sleep(attempt == 0 ? 1000 : 1500);
                                quit = httpConn.quitApp();
                                failure = null;
                            } catch (Exception e) {
                                failure = e.getMessage();
                            }
                        }
                        final ToastKind messageKind = quit ? ToastKind.SUCCESS : ToastKind.ERROR;
                        final String message = quit
                                ? app.getResources().getString(R.string.applist_quit_success) + " " + quitName
                                : (failure != null && !failure.isEmpty() ? failure : "The PC did not quit " + quitName);
                        com.limelight.shell.StreamExit.INSTANCE.setQuitting(false);
                        new Handler(Looper.getMainLooper()).post(() -> DelosToast.show(app, message, DelosToast.LONG, messageKind));
                    } else {
                        com.limelight.shell.StreamExit.INSTANCE.setQuitting(false);
                    }
                }
            }.start();
        }
    }

    @Override
    public boolean stageFailed(final String stage, final int portFlags, final int errorCode) {
        // Perform a connection test if the failure could be due to a blocked port
        // This does network I/O, so don't do it on the main thread.
        final int portTestResult = MoonBridge.testClientConnectivity(ServerHelper.CONNECTION_TEST_SERVER, 443, portFlags);

        if (errorCode == 0 && portFlags != 0 && (portTestResult == MoonBridge.ML_TEST_RESULT_INCONCLUSIVE || portTestResult == 0)) {
            spinner.setMessage(getResources().getString(R.string.unlocking_or_starting));
            return true;
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!endedWithResult) {
                    endedWithResult = true;
                    LimeLog.severe(stage + " failed: " + errorCode);

                    // If video initialization failed while the surface is still valid, the decoder is to blame
                    Surface currentSurface = streamContainer.getSurface();
                    boolean decoderFailed = stage.contains("video") && currentSurface != null && currentSurface.isValid();
                    String ports = portFlags != 0 ? MoonBridge.stringifyPortFlags(portFlags, "\n") : null;
                    boolean portsBlocked = portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0;

                    // Delos: the shell shows what happened (docs/GUI.md, "Stream exits")
                    StreamOutcome.failed(DelosStreamActivity.this, StreamResultKind.FAILED_TO_START,
                            StreamResultKt.describeStreamFailure(stage, errorCode, ports, portsBlocked, decoderFailed));
                }
            }
        });

        return false;
    }

    @Override
    public void connectionTerminated(final int errorCode) {
        // Perform a connection test if the failure could be due to a blocked port
        // This does network I/O, so don't do it on the main thread.
        final int portFlags = MoonBridge.getPortFlagsFromTerminationErrorCode(errorCode);
        final int portTestResult = MoonBridge.testClientConnectivity(ServerHelper.CONNECTION_TEST_SERVER,443, portFlags);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Let the display go to sleep now
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                // Stop processing controller input
                controllerHandler.stop();
                timerHandler.removeCallbacksAndMessages(null);

                // Ungrab input
                setInputGrabState(false);

                if (!endedWithResult) {
                    endedWithResult = true;
                    LimeLog.severe("Connection terminated: " + errorCode);
                    stopConnection();

                    // Delos: the shell shows what happened (docs/GUI.md, "Stream exits"): an unexpected
                    // termination as the result overlay, the host ending the session normally as a toast.
                    if (errorCode != MoonBridge.ML_ERROR_GRACEFUL_TERMINATION) {
                        String ports = portFlags != 0 ? MoonBridge.stringifyPortFlags(portFlags, "\n") : null;
                        boolean portsBlocked = portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0;
                        StreamOutcome.failed(DelosStreamActivity.this, StreamResultKind.CONNECTION_LOST,
                                StreamResultKt.describeStreamFailure(null, errorCode, ports, portsBlocked, false));
                    }
                    else {
                        StreamOutcome.hostClosed(DelosStreamActivity.this);
                    }
                }
            }
        });
    }

    @Override
    public void connectionStatusUpdate(final int connectionStatus) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (prefConfig.disableWarnings) {
                    return;
                }

                if (connectionStatus == MoonBridge.CONN_STATUS_POOR) {
                    if (prefConfig.bitrate > 5000) {
                        notificationOverlayView.setText(getResources().getString(R.string.slow_connection_msg));
                    }
                    else {
                        notificationOverlayView.setText(getResources().getString(R.string.poor_connection_msg));
                    }

                    windowController.requestNotificationOverlayVisibility(View.VISIBLE);
                }
                else if (connectionStatus == MoonBridge.CONN_STATUS_OKAY) {
                    windowController.requestNotificationOverlayVisibility(View.GONE);
                }

                windowController.applyNotificationOverlayVisibility();
            }
        });
    }

    @Override
    public void connectionStarted() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (spinner != null) {
                    spinner.dismiss();
                    spinner = null;
                }

                connected = true;
                connecting = false;
                windowController.updatePipAutoEnter();

                // Hide the mouse cursor now after a short delay.
                // Doing it before dismissing the spinner seems to be undone
                // when the spinner gets displayed. On Android Q, even now
                // is too early to capture. We will delay a second to allow
                // the spinner to dismiss before capturing.
                timerHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        setInputGrabState(true);
                    }
                }, 500);

                // Keep the display on
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                // Update GameManager state to indicate we're in game
                UiHelper.notifyStreamConnected(DelosStreamActivity.this);

                // Sync local clipboard to host
                handleFocusChange(true);

                windowController.hideSystemUi(1000);

                if (prefConfig.preventPacketLoss) {
                    timerHandler.postDelayed(backgroundPing, 1000);
                }
            }
        });

        if (prefConfig.usbDriver) {
            // Start the USB driver
            bindService(new Intent(this, UsbDriverService.class),
                    usbDriverServiceConnection, Service.BIND_AUTO_CREATE);
        }

        // Add the launched game to the Android TV channel
        ComputerDetails computer = new ComputerDetails();
        computer.name = launch.pcName;
        computer.uuid = DelosStreamActivity.this.getIntent().getStringExtra(EXTRA_PC_UUID);
        if (launch.appName != null) {
            // This may be null if launched from the "Resume Session" PC context menu item
            new ShortcutHelper(this).reportGameLaunched(computer, launch.app);
        }
    }

    @Override
    public void displayMessage(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                DelosToast.show(DelosStreamActivity.this, message, DelosToast.LONG);
            }
        });
    }

    @Override
    public void displayTransientMessage(final String message) {
        if (!prefConfig.disableWarnings) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    DelosToast.show(DelosStreamActivity.this, message, DelosToast.LONG, ToastKind.WARNING);
                }
            });
        }
    }

    @Override
    public void rumble(short controllerNumber, short lowFreqMotor, short highFreqMotor) {
        if (prefConfig.enableRumble) {
            LimeLog.info(String.format((Locale)null, "Rumble on gamepad %d: %04x %04x", controllerNumber, lowFreqMotor, highFreqMotor));
            controllerHandler.handleRumble(controllerNumber, lowFreqMotor, highFreqMotor);
        }
    }

    @Override
    public void rumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger) {
        LimeLog.info(String.format((Locale)null, "Rumble on gamepad triggers %d: %04x %04x", controllerNumber, leftTrigger, rightTrigger));

        controllerHandler.handleRumbleTriggers(controllerNumber, leftTrigger, rightTrigger);
    }

    @Override
    public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
        surfaceController.setHdrMode(enabled, hdrMetadata);
    }

    @Override
    public void setMotionEventState(short controllerNumber, byte motionType, short reportRateHz) {
        controllerHandler.handleSetMotionEventState(controllerNumber, motionType, reportRateHz);
    }

    @Override
    public void setControllerLED(short controllerNumber, byte r, byte g, byte b) {
        controllerHandler.handleSetControllerLED(controllerNumber, r, g, b);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surfaceController.onSurfaceChanged(width, height);

        pointerHandler.onSurfaceChanged();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!isInPictureInPictureMode()) {
                windowController.updatePipAutoEnter();
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceController.onSurfaceCreated(holder, StreamDisplayPlanner.surfaceFrameRate(desiredRefreshRate,
                prefConfig.fps, prefConfig.framePacing, prefConfig.reduceRefreshRate));
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceController.onSurfaceDestroyed();
    }

    @Override
    public boolean isKeyboardInputConnected() {
        return connected;
    }

    @Override
    public void mouseMove(int deltaX, int deltaY) {
        conn.sendMouseMove((short) deltaX, (short) deltaY);
    }

    @Override
    public void mouseButtonEvent(int buttonId, boolean down) {
        byte buttonIndex;

        switch (buttonId)
        {
            case EvdevListener.BUTTON_LEFT:
                buttonIndex = MouseButtonPacket.BUTTON_LEFT;
                break;
            case EvdevListener.BUTTON_MIDDLE:
                buttonIndex = MouseButtonPacket.BUTTON_MIDDLE;
                break;
            case EvdevListener.BUTTON_RIGHT:
                buttonIndex = MouseButtonPacket.BUTTON_RIGHT;
                break;
            case EvdevListener.BUTTON_X1:
                buttonIndex = MouseButtonPacket.BUTTON_X1;
                break;
            case EvdevListener.BUTTON_X2:
                buttonIndex = MouseButtonPacket.BUTTON_X2;
                break;
            default:
                LimeLog.warning("Unhandled button: "+buttonId);
                return;
        }

        if (down) {
            conn.sendMouseButtonDown(buttonIndex);
        }
        else {
            conn.sendMouseButtonUp(buttonIndex);
        }
    }

    @Override
    public void mouseVScroll(byte amount) {
        conn.sendMouseScroll(amount);
    }

    @Override
    public void mouseHScroll(byte amount) {
        conn.sendMouseHScroll(amount);
    }

    @Override
    public void keyboardEvent(boolean buttonDown, short keyCode) {
        keyHandler.keyboardEvent(buttonDown, keyCode);
    }

    @Override
    public void onPerfStats(com.limelight.shell.StreamStats stats) {
        if (statsPanel != null) {
            statsPanel.update(stats);
        }
    }

    @Override
    public void onPerfUpdate(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(prefConfig.enablePerfOverlayLite){
                    performanceOverlayLite.setText(text);
                }else{
                    performanceOverlayBig.setText(text);
                }
            }
        });
    }

    @Override
    public void onUsbPermissionPromptStarting() {
        windowController.beginPipSuppression();
    }

    @Override
    public void onUsbPermissionPromptCompleted() {
        windowController.endPipSuppression();
    }

    @Override
    public boolean onKey(View view, int keyCode, KeyEvent keyEvent) {
        return keyHandler.handleKeyEvent(keyEvent);
    }

    @Override
    @SuppressLint("MissingSuperCall") // Delos: consume Back; the superclass would finish the stream.
    public void onBackPressed() {
        // Delos: Back always opens the stream menu
        showGameMenu();
    }

    public void sendExecServerCmd(int cmdId) {
        conn.sendExecServerCmd(cmdId);
    }

    public ArrayList<String> getServerCmds() {
        return launch.serverCommands;
    }

    // Toggle the local mouse cursor
    public void toggleMouseLocalCursor(){
        if (!grabbedInput) {
            inputCaptureProvider.enableCapture();
            grabbedInput = true;
        }
        cursorVisible = !cursorVisible;
        if (cursorVisible) {
            inputCaptureProvider.showCursor();
        } else {
            inputCaptureProvider.hideCursor();
        }
    }

    public boolean isLocalMouseCursorVisible() { return cursorVisible; }

    /** Why the running rumble preset could not use its technique, or null. */
    public String getRumbleProblem() { return controllerHandler == null ? null : controllerHandler.getRumbleProblem(); }

    /** Delos: the statistics overlay container, which the stream menu lifts above its blurred backdrop while it is open. */
    public android.view.ViewGroup getPerformanceOverlayContainer() {
        return (android.view.ViewGroup) performanceOverlayView;
    }

    public void toggleHUD() {
        if (statsPanel != null) {
            // Delos: the stream menu's Overlay goes round full → compact → off
            if (!prefConfig.enablePerfOverlay) {
                prefConfig.enablePerfOverlay = true;
                prefConfig.enablePerfOverlayLite = false;
            } else if (!prefConfig.enablePerfOverlayLite) {
                prefConfig.enablePerfOverlayLite = true;
            } else {
                prefConfig.enablePerfOverlay = false;
            }
            statsPanel.setCompact(prefConfig.enablePerfOverlayLite);
            performanceOverlayView.setVisibility(prefConfig.enablePerfOverlay ? View.VISIBLE : View.GONE);
            // Kept for the next streams: the new UI passes the profile's settings on to them
            com.limelight.shell.data.ProfileStore.Companion.currentSettings(this).edit()
                    .putBoolean("checkbox_enable_perf_overlay", prefConfig.enablePerfOverlay)
                    .putBoolean("checkbox_enable_perf_overlay_lite", prefConfig.enablePerfOverlayLite)
                    .apply();
            return;
        }
        prefConfig.enablePerfOverlay = !prefConfig.enablePerfOverlay;
        if (prefConfig.enablePerfOverlay) {
            performanceOverlayView.setVisibility(View.VISIBLE);
            if (statsPanel != null) {
                // Delos: the statistics panel instead
            } else if(prefConfig.enablePerfOverlayLite){
                performanceOverlayLite.setVisibility(View.VISIBLE);
            }else{
                performanceOverlayBig.setVisibility(View.VISIBLE);
            }
        } else {
            performanceOverlayView.setVisibility(View.GONE);
        }
    }

    public void disconnect() {
        if (prefConfig.smartClipboardSync) {
            getClipboard(-1);
        }
        finish();
    }


    // Delos: every way into the menu opens the Delos stream menu.
    // The shortcut (L3+R3 or the buttons chosen in Delos) and the swipe up are checked before the stream.
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (inputBlocked) {
            return true;
        }
        if (streamMenuTakesKey(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (inputBlocked) {
            return true;
        }
        if (streamMenu != null && !isOnExternalDisplay() && streamMenu.onTouch(event)) {
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return inputBlocked || super.dispatchGenericMotionEvent(event);
    }

    // Delos: while the portal back into the new UI plays, the game gets no more input
    private boolean inputBlocked = false;

    public void blockInput() {
        inputBlocked = true;
    }

    // Delos: the stream menu shows which of its toggles are on
    public PreferenceConfiguration getPrefConfig() {
        return prefConfig;
    }

    public String getLaunchStreamParameters() {
        int format = decoderRenderer != null ? decoderRenderer.getActiveVideoFormat() : 0;
        int width = decoderRenderer != null && decoderRenderer.getStreamWidth() > 0
                ? decoderRenderer.getStreamWidth() : prefConfig.width;
        int height = decoderRenderer != null && decoderRenderer.getStreamHeight() > 0
                ? decoderRenderer.getStreamHeight() : prefConfig.height;
        return StreamLoader.parameters(prefConfig, format, width, height);
    }

    public boolean isStreamMenuOpen() {
        return streamMenu != null && streamMenu.isOpen();
    }

    public void showStreamMenu() {
        if (streamMenu != null) streamMenu.show();
    }

    /** One input route for the controller Activity, including keys delivered before the IME. */
    public boolean handleExternalDisplayKeyEvent(KeyEvent event) {
        if (streamMenu != null && streamMenu.onExternalKey(event)) return true;
        return keyHandler.handleKeyEvent(event);
    }

    /** Keeps raw stick input on the normal streaming path unless the external menu owns it. */
    public boolean handleExternalDisplayMotionEvent(MotionEvent event) {
        return (streamMenu != null && streamMenu.onExternalMotion(event)) || onGenericMotionEvent(event);
    }

    // Delos: "Quit game" in the Delos stream menu is the confirmation (no dialog)
    public void quitWithoutAsking() {
        quitOnStop = true;
        com.limelight.shell.StreamExit.INSTANCE.setQuitting(true);
        finish();
    }

    @Override
    public void showGameMenu() {
        if (streamMenu != null) {
            streamMenu.show();
        }
    }

    public void hideGameMenu() {
        // Delos
        if (streamMenu != null) {
            streamMenu.hide();
        }
    }

    @Override
    public boolean handleCommitText(CharSequence text) {
        if (!prefConfig.enableCommitText || conn == null) {
            return false;
        }
        commitTextSender.enqueue(text.toString());
        return true;
    }

    @Override
    public boolean handleDeleteSurroundingText(int beforeLength, int afterLength) {
        if (!prefConfig.enableCommitText || conn == null) {
            return false;
        }
        // Send backspace events for deleted preceding characters
        if (beforeLength > 0) {
            commitTextSender.sendBackspaces(keyboardTranslator.translate(KeyEvent.KEYCODE_DEL, 0, -1), beforeLength);
        }
        return true;
    }

}
