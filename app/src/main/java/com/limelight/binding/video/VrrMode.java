package com.limelight.binding.video;

/** The "Variable refresh rate" setting ({@code list_vrr}) and how it combines with what the display reports. */
public final class VrrMode {
    public static final String AUTO = "auto";
    public static final String ON = "on";
    public static final String OFF = "off";

    /** {@code Surface.setFrameRate()} and the whole VRR behaviour need Android 11. */
    public static final int MIN_SDK = 30;

    private VrrMode() {
    }

    /**
     * Whether VRR is active: Auto follows the display's report, On forces it, Off disables it. An unknown stored value
     * behaves as Auto, the default.
     */
    public static boolean resolve(String setting, boolean reported) {
        if (ON.equals(setting)) {
            return true;
        }
        if (OFF.equals(setting)) {
            return false;
        }
        return reported;
    }
}
