package com.limelight.utils;

import android.os.Build;

/**
 * Basic device information used by the debug page and as the client name sent to the host.
 */
public final class DeviceUtils {

    private DeviceUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * @return the Android version of the device, e.g. "13"
     */
    public static String getSDKVersionName() {
        return Build.VERSION.RELEASE;
    }

    /**
     * @return the manufacturer of the device, e.g. "AYN"
     */
    public static String getManufacturer() {
        return Build.MANUFACTURER;
    }

    /**
     * @return the device model without whitespace, e.g. "Odin2Portal"
     */
    public static String getModel() {
        String model = Build.MODEL;
        if (model != null) {
            model = model.trim().replaceAll("\s*", "");
        } else {
            model = "";
        }
        return model;
    }
}
