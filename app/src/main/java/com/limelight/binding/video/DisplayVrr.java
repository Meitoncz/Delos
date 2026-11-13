package com.limelight.binding.video;

import android.annotation.SuppressLint;
import android.os.Build;
import android.view.Display;

/**
 * Whether a display reports adaptive refresh rate (variable refresh rate, "ARR") support. Android 16 (API 36) added
 * {@code Display.hasArrSupport()}; below it the platform has no way to ask, so nothing is reported.
 *
 * <p>Only the public, permission-free query is used, and a vendor that throws from it counts as "not reported": the
 * result decides a user-visible behaviour change, so a failed probe must leave the stream exactly as it was before VRR.
 */
public final class DisplayVrr {
    /** The first Android version with {@code Display.hasArrSupport()}. */
    public static final int MIN_SDK = 36;

    private DisplayVrr() {
    }

    /** The platform query, separated from {@link Display} so it can be faked in tests. */
    public interface ArrQuery {
        boolean hasArrSupport();
    }

    /** What was found and where the answer came from (English, for the diagnostics). */
    public static final class Result {
        public final boolean reported;
        public final String source;

        Result(boolean reported, String source) {
            this.reported = reported;
            this.source = source;
        }

        @Override
        public String toString() {
            return (reported ? "yes" : "no") + " (" + source + ")";
        }
    }

    /** {@link #detect(ArrQuery, int)} for a real display; call with {@code Build.VERSION.SDK_INT}. */
    @SuppressLint("NewApi") // the query is only invoked when sdkInt >= MIN_SDK
    public static Result detect(final Display display, int sdkInt) {
        return detect(new ArrQuery() {
            @Override
            public boolean hasArrSupport() {
                return display.hasArrSupport();
            }
        }, sdkInt);
    }

    /**
     * The adaptive refresh rate report of a display on Android [sdkInt]: not reported below API 36, otherwise
     * {@code Display.hasArrSupport()}, and not reported when it throws.
     */
    public static Result detect(ArrQuery query, int sdkInt) {
        if (sdkInt < MIN_SDK) {
            return new Result(false, "Android API " + sdkInt + " has no hasArrSupport()");
        }
        try {
            boolean reported = query.hasArrSupport();
            return new Result(reported, "Display.hasArrSupport(), Android API " + sdkInt);
        } catch (Throwable t) {
            return new Result(false, "Display.hasArrSupport() failed: " + t);
        }
    }

    /** One line for the Debug info panel and the log: {@code reported yes (…)}. */
    public static String describe(Display display) {
        return "reported " + detect(display, Build.VERSION.SDK_INT);
    }
}
