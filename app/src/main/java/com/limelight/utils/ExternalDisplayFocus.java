package com.limelight.utils;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;

import com.limelight.DelosStreamActivity;
import com.limelight.display.DualScreen;

/**
 * Moves focus between the stream (on the external display) and the controller activity on the
 * device's own screen.
 */
public final class ExternalDisplayFocus {
    private static final long TIMEOUT_MS = 300;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static boolean isTimeoutActive = false;

    private ExternalDisplayFocus() {
    }

    public static void requestFocusToExternalDisplayControl(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intentTouchpad = new Intent(context, ExternalDisplayControlActivity.class);
            intentTouchpad.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            Bundle optionsDefault = ActivityOptions.makeBasic().setLaunchDisplayId(DualScreen.mainDisplayId(context)).toBundle();
            context.startActivity(intentTouchpad, optionsDefault);
        }
    }

    /** Brings the stream's task to the front; repeated calls within a short time are ignored. */
    public static void requestFocusToGameActivity() {
        if (isTimeoutActive) {
            return;
        }

        isTimeoutActive = true;

        if (DelosStreamActivity.instance != null) {
            ActivityManager am = (ActivityManager) DelosStreamActivity.instance.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.moveTaskToFront(DelosStreamActivity.instance.getTaskId(), 0);
            }
        }

        handler.postDelayed(() -> isTimeoutActive = false, TIMEOUT_MS);
    }
}
