package com.limelight.utils;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.InputDevice;

/**
 * Access to the device's and input devices' vibrators without deprecated APIs on Android 12+.
 */
public final class DeviceVibrator {

    private DeviceVibrator() {
    }

    /**
     * @return the device's default vibrator, or null if the device has none
     */
    public static Vibrator get(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return manager != null ? manager.getDefaultVibrator() : null;
        }
        return getLegacy(context);
    }

    /**
     * @return the default vibrator of an input device such as a gamepad
     */
    public static Vibrator get(InputDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return device.getVibratorManager().getDefaultVibrator();
        }
        return getLegacy(device);
    }

    /**
     * Vibrates once for the given duration with the default amplitude.
     */
    public static void vibrateOnce(Vibrator vibrator, long durationMs) {
        if (vibrator != null) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    @SuppressWarnings("deprecation")
    private static Vibrator getLegacy(Context context) {
        return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    @SuppressWarnings("deprecation")
    private static Vibrator getLegacy(InputDevice device) {
        return device.getVibrator();
    }
}
