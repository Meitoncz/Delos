package com.limelight.utils;

import static com.limelight.utils.ExternalDisplayFocus.requestFocusToGameActivity;
import static com.limelight.utils.ServerHelper.getSecondaryDisplay;

import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.IntentCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.input.virtual_controller.keyboard.KeyBoardLayoutController;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.ExternalControllerView;

/**
 * A standalone Activity providing a full-screen touchpad controller for the secondary display.
 * It creates its own UI programmatically and forwards menu input to the stream on the external display.
 */
public class ExternalDisplayControlActivity extends AppCompatActivity implements KeyBoardLayoutController.ViewCallbacks {

    public static String EXTRA_LAUNCH_INTENT = "launchIntent";

    @SuppressLint("StaticFieldLeak")
    public static ExternalDisplayControlActivity instance;

    private PreferenceConfiguration prefConfig;

    private ExternalControllerView rootLayout;
    private ImageButton zoomButton;
    private KeyBoardLayoutController keyBoardLayoutController;

    private boolean isKeyboardVisible = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int failCount = 0;
    private Runnable dimScreenRunnable;
    private float originalBrightness = -1f; // -1 = use system default
    private static final int INACTIVITY_TIMEOUT_MS = 10_000;


    // --- Static Methods for External Control ---

    public static void closeExternalDisplayControl() {
        if (instance != null) {
            instance.finish();
        }
    }

    public static void toggleKeyboard() {
        if (instance != null) {
            instance._toggleKeyboard();
        }
    }

    public static void toggleFullKeyboard() {
        if (instance != null) {
            instance._toggleFullKeyboard();
        }
    }

    public static void toggleGameMenu() {
        if (Game.instance != null) {
            Game.instance.showStreamMenu();
        }
    }

    /**
     * The stream menu's brightness slider. The stream's own window is on the external display,
     * which ignores window brightness, so the value goes to this device's screen, and it is what
     * the screen returns to after it dimmed from inactivity.
     */
    public static void setStreamBrightness(float brightness) {
        if (instance != null) {
            instance.applyStreamBrightness(brightness);
        }
    }

    // --- Activity Lifecycle ---

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack(this);
            }
        });

        instance = this;
        prefConfig = PreferenceConfiguration.readPreferences(this);

        if (!isGameInstanceAvailable()) {
            Intent gameIntent = IntentCompat.getParcelableExtra(getIntent(), EXTRA_LAUNCH_INTENT, Intent.class);
            if (gameIntent == null) {
                finish();
            } else {
                Display secondaryDisplay = getSecondaryDisplay(this);
                if (secondaryDisplay != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // The shell and loader are one continuous scene. This nested Game task must not
                    // get Android's default side-slide (custom animations are ignored for this task).
                    gameIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    ActivityOptions options = ActivityOptions.makeBasic();
                    options.setLaunchDisplayId(secondaryDisplay.getDisplayId());
                    Toast.makeText(this,
                            getString(R.string.external_display_info,
                                    secondaryDisplay.getMode().getPhysicalWidth(),
                                    secondaryDisplay.getMode().getPhysicalHeight(),
                                    secondaryDisplay.getMode().getRefreshRate()),
                            Toast.LENGTH_LONG).show();

                    startActivity(gameIntent, options.toBundle());
                    overridePendingTransition(0, 0);
                } else {
                    LimeLog.warning(getString(R.string.no_external_display));
                    startActivity(gameIntent);
                    finish();
                }
            }
        }

        initViews();
    }

    private void initViews() {
        if (Game.instance == null) {
            if (failCount > 10) {
                Toast.makeText(this, getString(R.string.no_game_instance), Toast.LENGTH_LONG).show();
                finish();
            }
            // Wait for the intent to get started
            handler.postDelayed(this::initViews, 500);
            failCount++;
            return;
        }

        WindowInsetsControllerCompat windowInsetsController = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());

        windowInsetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        );

        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
        windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (v, insets) -> {
                boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
                updateKeyboardVisibility(imeVisible || (keyBoardLayoutController != null && keyBoardLayoutController.isKeyboardVisible()));
                return androidx.core.view.ViewCompat.onApplyWindowInsets(v, insets);
            });
        }

        createProgrammaticUI();
        initTouchEventHandling();
        setupInactivityTimeoutForBrightness();
        requestFocusToGameActivity();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initTouchEventHandling() {
        // Intercept touch events on root layout
        rootLayout.setOnTouchListener((v, event) -> {
            handleUserActivity();
            if (Game.instance != null) {
                Game.instance.handleMotionEvent(v, event);
            }
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isGameInstanceAvailable() && rootLayout != null) {
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!isGameInstanceAvailable()) {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        instance = null;
    }

    @Override
    public void onKeyboardControllerVisibilityChange(boolean visible) {
        updateKeyboardVisibility(visible);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupInactivityTimeoutForBrightness() {
        // Save the original brightness
        WindowManager.LayoutParams layout = getWindow().getAttributes();
        originalBrightness = layout.screenBrightness;

        // Runnable to dim screen
        dimScreenRunnable = () -> {
            WindowManager.LayoutParams l = getWindow().getAttributes();
            l.screenBrightness = 0.0f;
            getWindow().setAttributes(l);
        };

        // Start the timer
        resetInactivityTimer();
    }

    private void updateKeyboardVisibility(boolean visible) {
        if (isKeyboardVisible != visible) {
            isKeyboardVisible = visible;
            if (isKeyboardVisible) {
                // Keyboard is visible, so prevent screen dimming
                handler.removeCallbacks(dimScreenRunnable);
                // and restore brightness
                restoreBrightnessIfNeeded();
            } else {
                // Keyboard is hidden, so resume inactivity timer
                resetInactivityTimer();
            }
        }
    }

    private void restoreBrightnessIfNeeded() {
        WindowManager.LayoutParams l = getWindow().getAttributes();
        if (l.screenBrightness == 0.0f) {
            l.screenBrightness = originalBrightness;
            getWindow().setAttributes(l);
        }
    }

    private void applyStreamBrightness(float brightness) {
        // The dimmed screen comes back to this value on the next touch
        originalBrightness = brightness;
        WindowManager.LayoutParams l = getWindow().getAttributes();
        if (l.screenBrightness != 0.0f) {
            l.screenBrightness = brightness;
            getWindow().setAttributes(l);
        }
    }

    private void handleUserActivity() {
        // Restore brightness if dimmed
        restoreBrightnessIfNeeded();
        resetInactivityTimer();
    }

    private void resetInactivityTimer() {
        handler.removeCallbacks(dimScreenRunnable);
        if (!isKeyboardVisible) {
            handler.postDelayed(dimScreenRunnable, INACTIVITY_TIMEOUT_MS);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (isGameInstanceAvailable()) {
            Game.instance.handleFocusChange(hasFocus);
        } else {
            finish();
        }
    }

    @SuppressWarnings("deprecation") // Game handles back itself via onBackPressed()
    private void handleBack(OnBackPressedCallback callback) {
        if (Game.instance != null && Game.instance.isKeyboardLayoutVisible()) {
            toggleFullKeyboard();
        } else if (Game.instance != null && Game.instance.isStreamMenuOpen()) {
            Game.instance.hideGameMenu();
        } else if (Game.instance != null) {
            Game.instance.onBackPressed();
        } else {
            // Default behavior: let the system finish this activity
            callback.setEnabled(false);
            getOnBackPressedDispatcher().onBackPressed();
            callback.setEnabled(true);
        }
    }

    // --- Initialization and UI Creation ---

    /**
     * Checks if the static Game.instance is alive. If not, finishes this Activity.
     */
    private boolean isGameInstanceAvailable() {
        return Game.instance != null;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        if (Game.instance != null) {
            Game.instance.onConfigurationChanged(newConfig);
        }
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (Game.instance != null) {
            return Game.instance.handleExternalDisplayMotionEvent(event);
        }
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return routeKeyEvent(event) || super.dispatchKeyEvent(event);
    }

    private boolean routeKeyEvent(KeyEvent event) {
        if (Game.instance == null) return false;
        return Game.instance.handleExternalDisplayKeyEvent(event);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void createProgrammaticUI() {
        rootLayout = new ExternalControllerView(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.setFocusable(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            rootLayout.setFocusedByDefault(true);
        }

        rootLayout.setInputCallbacks(Game.instance);
        rootLayout.setPreImeKeyHandler(this::routeKeyEvent);
        rootLayout.setCommitTextEnabled(prefConfig.enableCommitText);

        setContentView(rootLayout);

        // Top-left buttons
        LinearLayout topLeftButtons = createButtonContainer(Gravity.TOP | Gravity.START);
        topLeftButtons.setFocusable(false);
        zoomButton = createImageButton(R.drawable.ic_zoom_toggle, v -> toggleZoomMode(true));
        if (Game.instance != null && Game.instance.isZoomModeEnabled()) {
            zoomButton.setAlpha(1.0f);
        } else {
            zoomButton.setAlpha(0.5f);
        }
        topLeftButtons.addView(zoomButton);
        rootLayout.addView(topLeftButtons);

        // Top-center buttons
//        LinearLayout topCenterButtons = createButtonContainer(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
//        topCenterButtons.setFocusable(false);
//        rootLayout.addView(topCenterButtons);

        // Top-right buttons
        LinearLayout topRightButtons = createButtonContainer(Gravity.TOP | Gravity.END);
        topRightButtons.setFocusable(false);
        topRightButtons.addView(createImageButton(R.drawable.ic_menu_external, v -> showGameMenu()));
        topRightButtons.addView(createImageButton(R.drawable.ic_close_external, v -> finish()));
        rootLayout.addView(topRightButtons);

        // Bottom-left button: Android keyboard toggle
        LinearLayout bottomLeftButton = createButtonContainer(Gravity.BOTTOM | Gravity.START);
        bottomLeftButton.setFocusable(false);
        bottomLeftButton.addView(createImageButton(R.drawable.ic_android_keyboard, v -> _toggleKeyboard()));
        rootLayout.addView(bottomLeftButton);

        // Bottom-center buttons
//        LinearLayout bottomCenterButtons = createButtonContainer(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
//        bottomCenterButtons.setFocusable(false);
//        rootLayout.addView(bottomCenterButtons);

        // Bottom-right button: Custom keyboard toggle
        LinearLayout bottomRightButton = createButtonContainer(Gravity.BOTTOM | Gravity.END);
        bottomRightButton.setFocusable(false);
        bottomRightButton.addView(createImageButton(R.drawable.ic_fullscreen_keyboard, v -> _toggleFullKeyboard()));
        rootLayout.addView(bottomRightButton);
    }

    /**
     * Toggles the visibility of the on-screen software keyboard.
     */
    @SuppressWarnings("deprecation") // No direct replacement for toggling the soft keyboard
    private void _toggleKeyboard() {
        LimeLog.info("Toggling keyboard overlay on ExternalDisplayControlActivity");
        InputMethodManager inputManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        inputManager.toggleSoftInput(0, 0);
    }

    private void initFullKeyboard(PreferenceConfiguration prefConfig) {
        keyBoardLayoutController = new KeyBoardLayoutController(rootLayout, this, prefConfig);
        keyBoardLayoutController.setViewCallbacks(this);
        keyBoardLayoutController.refreshLayout();
        keyBoardLayoutController.show();
    }

    /**
     * Toggles the visibility of the full screen keyboard
     */
    private void _toggleFullKeyboard() {
        if (keyBoardLayoutController == null) {
            initFullKeyboard(prefConfig);
            return;
        }
        keyBoardLayoutController.toggleVisibility();
    }

    public void toggleZoomMode(boolean callGame) {
        if (Game.instance != null) {
            if (callGame) {
                Game.instance.toggleZoomMode();
            } else {
                if (Game.instance.isZoomModeEnabled()) {
                    zoomButton.setAlpha(1.0f);
                } else {
                    zoomButton.setAlpha(0.5f);
                }
            }
        }
    }

    // --- Public methods to interact with the Delos stream menu ---

    public void showGameMenu() {
        if (Game.instance != null) {
            Game.instance.showStreamMenu();
        }
    }

    // --- UI Factory Methods ---

    private LinearLayout createButtonContainer(int gravity) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(gravity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        layout.setLayoutParams(params);
        return layout;
    }

    private ImageButton createImageButton(int imageResourceId, View.OnClickListener listener) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(imageResourceId);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(56), dpToPx(56)));
        return button;
    }

    // --- Utility Methods ---

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
