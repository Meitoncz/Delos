package com.limelight.ui;

import static com.limelight.utils.ServerHelper.getActiveDisplay;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.util.Rational;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.UiHelper;

/**
 * The stream activity's window: requested orientation, picture-in-picture (auto-enter, parameters, hiding
 * the overlays while in PiP), multi-window and the immersive system UI. {@code DelosStreamActivity} forwards the Activity
 * callbacks it receives; the controller reaches the rest of the stream through {@link Host}.
 */
public final class StreamWindowController implements View.OnSystemUiVisibilityChangeListener {
    /** What the controller needs from the stream activity. */
    public interface Host {
        boolean isConnected();

        boolean isOnExternalDisplay();

        /** The video size the host renders (the stream size, swapped for an inverted portrait stream). */
        int videoWidth();

        int videoHeight();

        StreamContainer streamContainer();

        /** The stream container's parent, the PiP source when the stream is zoomed or not in Fit mode. */
        View streamRoot();

        View performanceOverlay();

        View notificationOverlay();

        /** Null when the stream was started from "Resume Session". */
        @Nullable
        String appName();

        @Nullable
        String pcName();

        /** Refreshes the on-screen controls' layout for a possibly new screen size. */
        void refreshControlLayouts();

        /** Hides the on-screen controls, keyboards and the game menu when entering PiP. */
        void hideControlsForPip();

        /** Shows the on-screen controls and keyboards that were shown before PiP. */
        void restoreControlsAfterPip();

        void setControllerSensorsEnabled(boolean enabled);

        /** The video went to the background (multi-window or PiP) or came back. */
        void setVideoBackground(boolean background);
    }

    // The platform's config_pictureInPictureMaxAspectRatio (2.39:1); the minimum is its inverse
    private static final int PIP_RATIO_LIMIT_NUMERATOR = 239;
    private static final int PIP_RATIO_LIMIT_DENOMINATOR = 100;

    private final Activity activity;
    private final PreferenceConfiguration prefConfig;
    private final Host host;

    private int currentOrientation;
    private boolean autoEnterPip = false;
    private int suppressPipRefCount = 0;
    private boolean isHidingOverlays;
    private int requestedNotificationOverlayVisibility = View.GONE;

    public StreamWindowController(Activity activity, PreferenceConfiguration prefConfig, Host host) {
        this.activity = activity;
        this.prefConfig = prefConfig;
        this.host = host;
    }

    // --- Orientation

    /** An external display stream is always landscape. */
    public void lockLandscape() {
        currentOrientation = Configuration.ORIENTATION_LANDSCAPE;
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
    }

    /** Picks the stream orientation: the device's current one with auto orientation, otherwise landscape. */
    public int chooseOrientation() {
        if (prefConfig.autoOrientation) {
            currentOrientation = activity.getResources().getConfiguration().orientation;
        } else {
            currentOrientation = Configuration.ORIENTATION_LANDSCAPE;
        }
        return currentOrientation;
    }

    /** Locks to the chosen orientation; a squarish display follows the video's shape instead (see {@link #requestedOrientation}). */
    public void applyPreferredOrientation() {
        Display display = getActiveDisplay(activity, prefConfig);
        activity.setRequestedOrientation(requestedOrientation(
                PreferenceConfiguration.isSquarishScreen(display),
                prefConfig.onscreenController,
                PreferenceConfiguration.isNativeResolution(prefConfig.width, prefConfig.height),
                host.videoWidth(), host.videoHeight(), currentOrientation));
    }

    /**
     * The {@code ActivityInfo.SCREEN_ORIENTATION_*} to request.
     *
     * <p>A squarish display (narrower than 1.3:1, see {@code PreferenceConfiguration.isSquarishScreen}: 1:1, 5:4, the
     * 31:27 panels; 4:3 is not squarish) shows a stream about as large in either orientation, so turning it would
     * only move the controls and the keyboard around and make a handheld that is tilted while playing flip the
     * stream. It therefore always follows the shape of the video: landscape for a 16:9 or square stream, portrait
     * for a taller one (the user's rotation lock still applies, and the upside-down landscape stays possible).
     * The one exception is the on-screen controls with a stream that is not at native resolution: they do not
     * support portrait, so the stream stays landscape.
     */
    static int requestedOrientation(boolean squarishDisplay, boolean onscreenController, boolean nativeResolution,
                                    int videoWidth, int videoHeight, int currentOrientation) {
        if (squarishDisplay) {
            int desiredOrientation = videoWidth >= videoHeight
                    ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;

            // OSC doesn't properly support portrait displays, so don't use it in portrait mode unless the user chose a native resolution
            if (onscreenController && !nativeResolution) {
                desiredOrientation = Configuration.ORIENTATION_LANDSCAPE;
            }

            if (desiredOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                return ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE;
            }
            else {
                return ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
            }
        }
        else {
            // Lock to current orientation
            if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                return ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE;
            } else {
                return ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
            }
        }
    }

    // --- Configuration changes and PiP overlays

    /** {@code Activity.onConfigurationChanged}: new screen size, or entering or leaving PiP. */
    public void onConfigurationChanged() {
        // Set requested orientation for possible new screen size
        applyPreferredOrientation();

        host.refreshControlLayouts();

        // Hide on-screen overlays in PiP mode
        if (activity.isInPictureInPictureMode()) {
            isHidingOverlays = true;

            host.hideControlsForPip();

            host.performanceOverlay().setVisibility(View.GONE);
            host.notificationOverlay().setVisibility(View.GONE);

            // Disable sensors while in PiP mode
            host.setControllerSensorsEnabled(false);

            // Update GameManager state to indicate we're in PiP (still gaming, but interruptible)
            UiHelper.notifyStreamEnteringPiP(activity);
        }
        else {
            isHidingOverlays = false;

            // Restore overlays to previous state when leaving PiP
            host.restoreControlsAfterPip();

            if (prefConfig.enablePerfOverlay) {
                host.performanceOverlay().setVisibility(View.VISIBLE);
            }

            host.notificationOverlay().setVisibility(requestedNotificationOverlayVisibility);

            // Enable sensors again after exiting PiP
            host.setControllerSensorsEnabled(true);

            // Update GameManager state to indicate we're out of PiP (gaming, non-interruptible)
            UiHelper.notifyStreamExitingPiP(activity);
        }
    }

    /** Remembers whether the connection warning should show; see {@link #applyNotificationOverlayVisibility}. */
    public void requestNotificationOverlayVisibility(int visibility) {
        requestedNotificationOverlayVisibility = visibility;
    }

    /** Shows the connection warning as requested, unless PiP hides the overlays (it comes back after PiP). */
    public void applyNotificationOverlayVisibility() {
        if (!isHidingOverlays) {
            host.notificationOverlay().setVisibility(requestedNotificationOverlayVisibility);
        }
    }

    // --- Picture-in-picture

    private PictureInPictureParams getPictureInPictureParams(boolean autoEnter) {
        StreamContainer streamContainer = host.streamContainer();
        View view;
        Rect hint;
        if (pipFromStreamContainer(prefConfig.videoScaleMode, streamContainer.getScaleX())) {
            view = streamContainer;
        } else {
            view = host.streamRoot();
        }

        int[] viewLocation = new int[2];

        view.getLocationOnScreen(viewLocation);

        int left = viewLocation[0];
        int top = viewLocation[1];
        int width = view.getWidth();
        int height = view.getHeight();
        hint = new Rect(left, top, left + width, top + height);

        PictureInPictureParams.Builder builder =
                new PictureInPictureParams.Builder()
                        .setSourceRectHint(hint);

        int[] aspectRatio = pipAspectRatio(width, height);
        if (aspectRatio != null) {
            builder.setAspectRatio(new Rational(aspectRatio[0], aspectRatio[1]));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter);
            builder.setSeamlessResizeEnabled(true);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String appName = host.appName();
            String pcName = host.pcName();
            String title = pipTitle(appName, pcName);
            if (title != null) {
                builder.setTitle(title);
            }
            String subtitle = pipSubtitle(appName, pcName);
            if (subtitle != null) {
                builder.setSubtitle(subtitle);
            }
        }

        return builder.build();
    }

    /**
     * The PiP window's aspect ratio as {numerator, denominator}: the source view's, clamped to the 1:2.39 to
     * 2.39:1 range Android accepts (it throws IllegalArgumentException for anything outside, e.g. a 32:9
     * stream). Null before the view is laid out, so that no ratio is set.
     */
    @Nullable
    static int[] pipAspectRatio(int width, int height) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        if ((long) width * PIP_RATIO_LIMIT_DENOMINATOR > (long) height * PIP_RATIO_LIMIT_NUMERATOR) {
            return new int[] { PIP_RATIO_LIMIT_NUMERATOR, PIP_RATIO_LIMIT_DENOMINATOR };
        }
        if ((long) height * PIP_RATIO_LIMIT_DENOMINATOR > (long) width * PIP_RATIO_LIMIT_NUMERATOR) {
            return new int[] { PIP_RATIO_LIMIT_DENOMINATOR, PIP_RATIO_LIMIT_NUMERATOR };
        }
        return new int[] { width, height };
    }

    /** The PiP source is the stream view itself only when it fits the screen unzoomed. */
    static boolean pipFromStreamContainer(PreferenceConfiguration.ScaleMode scaleMode, float scaleX) {
        return scaleMode == PreferenceConfiguration.ScaleMode.FIT && scaleX == 1;
    }

    /** The PiP title: the game's name, or the PC's when the game is unknown ("Resume Session"). */
    @Nullable
    static String pipTitle(@Nullable String appName, @Nullable String pcName) {
        return appName != null ? appName : pcName;
    }

    /** The PiP subtitle: the PC's name below the game's. */
    @Nullable
    static String pipSubtitle(@Nullable String appName, @Nullable String pcName) {
        return appName != null ? pcName : null;
    }

    /** PiP may start on its own while connected and no USB permission prompt is on screen. */
    static boolean pipAutoEnter(boolean connected, int suppressPipRefCount) {
        return connected && suppressPipRefCount == 0;
    }

    /** Updates PiP auto-enter after the connection, the surface, the zoom or a USB prompt changed. */
    public void updatePipAutoEnter() {
        if (!prefConfig.enablePip || host.isOnExternalDisplay()) {
            return;
        }

        boolean autoEnter = pipAutoEnter(host.isConnected(), suppressPipRefCount);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.setPictureInPictureParams(getPictureInPictureParams(autoEnter));
        }
        else {
            autoEnterPip = autoEnter;
        }
    }

    /**
     * Disables PiP auto-enter while the USB permission prompt is on-screen. This prevents us from entering
     * PiP while the user is interacting with the OS permission dialog. Paired with {@link #endPipSuppression}.
     */
    public void beginPipSuppression() {
        suppressPipRefCount++;
        updatePipAutoEnter();
    }

    public void endPipSuppression() {
        suppressPipRefCount--;
        updatePipAutoEnter();
    }

    /** {@code Activity.onUserLeaveHint}. */
    public void onUserLeaveHint() {
        // We don't need to manually enter PiP on Android S and later.
        // On Android R, we will use onPictureInPictureRequested() instead.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (autoEnterPip && !host.isOnExternalDisplay()) {
                try {
                    // This has thrown all sorts of weird exceptions on Samsung devices
                    // running Oreo. Just eat them and close gracefully on leave, rather
                    // than crashing.
                    activity.enterPictureInPictureMode(getPictureInPictureParams(false));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /** {@code Activity.onPictureInPictureRequested}. */
    public void onPictureInPictureRequested() {
        // Enter PiP when requested unless we're on Android 12 which supports auto-enter.
        if (autoEnterPip && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            activity.enterPictureInPictureMode(getPictureInPictureParams(false));
        }
    }

    // --- Multi-window and system UI

    /** {@code Activity.onMultiWindowModeChanged}, also called for PiP. */
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
        // In multi-window, we don't want to use the full-screen layout
        // flag. It will cause us to collide with the system UI.
        // This function will also be called for PiP so we can cover
        // that case here too.
        if (isInMultiWindowMode) {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            host.setVideoBackground(true);
        }
        else {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            host.setVideoBackground(false);
        }

        // Correct the system UI visibility flags
        hideSystemUi(50);
    }

    @SuppressLint("InlinedApi")
    private final Runnable hideSystemUi = new Runnable() {
        @Override
        public void run() {
            // TODO: Do we want to use WindowInsetsController here on R+ instead of
            // SYSTEM_UI_FLAG_IMMERSIVE_STICKY? They seem to do the same thing as of S...

            // In multi-window mode, we need to drop our layout flags or we'll
            // be drawing underneath the system UI.
            if (activity.isInMultiWindowMode()) {
                activity.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
            else {
                // Use immersive mode
                activity.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                                View.SYSTEM_UI_FLAG_FULLSCREEN |
                                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        }
    };

    /** Hides the system bars right away. */
    public void hideSystemUiNow() {
        hideSystemUi.run();
    }

    /** Hides the system bars after {@code delay} ms, replacing a pending hide. */
    public void hideSystemUi(int delay) {
        Handler h = activity.getWindow().getDecorView().getHandler();
        if (h != null) {
            h.removeCallbacks(hideSystemUi);
            h.postDelayed(hideSystemUi, delay);
        }
    }

    @Override
    public void onSystemUiVisibilityChange(int visibility) {
        // Don't do anything if we're not connected
        if (!host.isConnected()) {
            return;
        }

        // This flag is set for all devices
        if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
            hideSystemUi(2000);
        }
        else if ((visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
            hideSystemUi(2000);
        }
    }
}
