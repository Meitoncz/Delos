package com.limelight.binding.video;

import android.os.Build;
import android.view.Display;

import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Chooses the display mode, refresh rate and frame pacing of a stream from plain display and preference
 * data. Pure: {@code DelosStreamActivity} reads the {@link Display}, then applies the {@link Plan} (window attributes,
 * the stream container's aspect ratio, {@code Surface.setFrameRate}).
 */
public final class StreamDisplayPlanner {
    /** Slack for comparing refresh rates the system reports as floats (60.000004 Hz is 60 Hz). */
    private static final float RATE_TOLERANCE = 0.1f;

    private StreamDisplayPlanner() {
    }

    /** A display mode, the data of a {@link Display.Mode}. */
    public static final class Mode {
        public final int id;
        public final int width;
        public final int height;
        public final float refreshRate;

        public Mode(int id, int width, int height, float refreshRate) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.refreshRate = refreshRate;
        }

        public static Mode of(Display.Mode mode) {
            return new Mode(mode.getModeId(), mode.getPhysicalWidth(), mode.getPhysicalHeight(), mode.getRefreshRate());
        }

        public static List<Mode> of(Display.Mode[] modes) {
            List<Mode> result = new ArrayList<>(modes.length);
            for (Display.Mode mode : modes) {
                result.add(of(mode));
            }
            return result;
        }

        /** {@code WIDTHxHEIGHTxREFRESH}, the form of the display mode log lines. */
        @Override
        public String toString() {
            return width + "x" + height + "x" + refreshRate;
        }
    }

    /** How the chosen display mode is applied. */
    public enum ModeSwitch {
        /** The current mode is already the best one. */
        NONE,
        /** Request the mode with {@code preferredDisplayModeId}. */
        PREFERRED_MODE_ID,
        /** Only the refresh rate differs: leave the switch to {@code Surface.setFrameRate()} (Android 12+). */
        SET_FRAME_RATE
    }

    /** What the Cap FPS frame pacing did with the stream frame rate. */
    public enum PacingChange {
        NONE,
        /** The stream FPS is more than 3 above the refresh rate: Balanced (frame drops) instead. */
        DROP_FRAMES_ABOVE_REFRESH_RATE,
        /** The refresh rate is 49 Hz or lower, likely bogus: Balanced instead. */
        BOGUS_REFRESH_RATE,
        /** The stream frame rate is capped 1 FPS below the refresh rate. */
        CAPPED_BELOW_REFRESH_RATE
    }

    /** What the planner needs to know. Plain values; see {@link #plan}. */
    public static final class Input {
        Mode currentMode;
        List<Mode> supportedModes = Collections.emptyList();
        float currentRefreshRate;
        int streamWidth;
        int streamHeight;
        float fps;
        int videoWidth;
        int videoHeight;
        PreferenceConfiguration.ScaleMode scaleMode = PreferenceConfiguration.ScaleMode.FIT;
        int framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
        boolean reduceRefreshRate;
        int framePacingWarpFactor;
        boolean enforceDisplayMode;
        int sdkInt = Build.VERSION_CODES.S;
        boolean television;
        boolean externalDisplay;
        float refreshRateCeiling = Float.POSITIVE_INFINITY;
        boolean vrr;

        /**
         * The output display: its active mode, its supported modes in the order the system lists them, and
         * {@code Display.getRefreshRate()} (may be lower than the mode's rate, e.g. in battery saver).
         */
        public Input display(Mode currentMode, List<Mode> supportedModes, float currentRefreshRate) {
            this.currentMode = currentMode;
            this.supportedModes = supportedModes;
            this.currentRefreshRate = currentRefreshRate;
            return this;
        }

        /** The stream resolution and frame rate from the preferences ({@code width}, {@code height}, {@code fps}). */
        public Input stream(int width, int height, float fps) {
            this.streamWidth = width;
            this.streamHeight = height;
            this.fps = fps;
            return this;
        }

        /** The video size the host renders (the stream size, swapped in portrait) and the scale mode. */
        public Input video(int width, int height, PreferenceConfiguration.ScaleMode scaleMode) {
            this.videoWidth = width;
            this.videoHeight = height;
            this.scaleMode = scaleMode;
            return this;
        }

        public Input pacing(int framePacing, boolean reduceRefreshRate, int framePacingWarpFactor) {
            this.framePacing = framePacing;
            this.reduceRefreshRate = reduceRefreshRate;
            this.framePacingWarpFactor = framePacingWarpFactor;
            return this;
        }

        public Input enforceDisplayMode(boolean enforceDisplayMode) {
            this.enforceDisplayMode = enforceDisplayMode;
            return this;
        }

        public Input sdkInt(int sdkInt) {
            this.sdkInt = sdkInt;
            return this;
        }

        /** {@code FEATURE_TELEVISION} or {@code FEATURE_LEANBACK}. */
        public Input television(boolean television) {
            this.television = television;
            return this;
        }

        public Input externalDisplay(boolean externalDisplay) {
            this.externalDisplay = externalDisplay;
            return this;
        }

        /**
         * The highest refresh rate the system is expected to grant the app right now (see
         * {@link StreamDisplayPlanner#refreshRateCeiling}); infinity, the default, when nothing is known to limit it.
         */
        public Input refreshRateCeiling(float refreshRateCeiling) {
            this.refreshRateCeiling = refreshRateCeiling;
            return this;
        }

        /**
         * Whether variable refresh rate is active ({@link VrrMode#resolve}). Off by default; while off, every field of
         * the {@link Plan} is what it was before VRR existed. While on:
         * <ul>
         * <li>{@link Plan#displayRefreshRate} (reported to the host, used by Cap FPS) is the peak refresh rate of the
         * supported modes at the chosen mode's resolution ({@link StreamDisplayPlanner#peakRefreshRate}), not
         * {@code min(current rate, chosen rate)}: an adaptive display's instantaneous rate can be low when the stream
         * starts and rises as soon as content arrives;</li>
         * <li>{@link Plan#surfaceFrameRate} is the true stream frame rate ({@link Plan#pacedFrameRate}) instead of the
         * chosen mode's refresh rate, so the system can match the content without pull-down;</li>
         * <li>the mode choice ({@code chooseMode}) is unchanged.</li>
         * </ul>
         */
        public Input vrr(boolean active) {
            this.vrr = active;
            return this;
        }
    }

    /** The planner's decisions. */
    public static final class Plan {
        public final Mode currentMode;
        public final Mode bestMode;
        public final ModeSwitch modeSwitch;
        /** The best mode's refresh rate; {@link #surfaceFrameRate} starts from it once the surface exists. */
        public final float desiredRefreshRate;
        /**
         * The refresh rate the display is expected to run during the stream: reported to the host and used by
         * Cap FPS pacing.
         */
        public final float displayRefreshRate;
        /** The frame pacing mode after the Cap FPS adjustment. */
        public final int framePacing;
        public final PacingChange pacingChange;
        public final int roundedRefreshRate;
        /** The stream frame rate after the Cap FPS adjustment, before the warp factor. */
        public final float pacedFrameRate;
        /** The frame rate the host is asked to render ({@link #pacedFrameRate} times the warp factor). */
        public final float streamFrameRate;
        /** False for Stretch: the surface then fills the view without an aspect ratio. */
        public final boolean applyAspectRatio;
        public final double aspectRatio;
        public final boolean fillDisplay;
        /** Whether the plan was made for an active variable refresh rate display ({@link Input#vrr}). */
        public final boolean vrr;
        /**
         * The frame rate passed to {@code Surface.setFrameRate()} once the stream surface exists:
         * {@link StreamDisplayPlanner#surfaceFrameRate} normally, the true stream frame rate
         * ({@link #pacedFrameRate}, before the warp factor) with VRR.
         */
        public final float surfaceFrameRate;

        Plan(Mode currentMode, Mode bestMode, ModeSwitch modeSwitch, float desiredRefreshRate,
             float displayRefreshRate, int framePacing, PacingChange pacingChange, int roundedRefreshRate,
             float pacedFrameRate, float streamFrameRate, boolean applyAspectRatio, double aspectRatio,
             boolean fillDisplay, boolean vrr, float surfaceFrameRate) {
            this.currentMode = currentMode;
            this.bestMode = bestMode;
            this.modeSwitch = modeSwitch;
            this.desiredRefreshRate = desiredRefreshRate;
            this.displayRefreshRate = displayRefreshRate;
            this.framePacing = framePacing;
            this.pacingChange = pacingChange;
            this.roundedRefreshRate = roundedRefreshRate;
            this.pacedFrameRate = pacedFrameRate;
            this.streamFrameRate = streamFrameRate;
            this.applyAspectRatio = applyAspectRatio;
            this.aspectRatio = aspectRatio;
            this.fillDisplay = fillDisplay;
            this.vrr = vrr;
            this.surfaceFrameRate = surfaceFrameRate;
        }
    }

    public static Plan plan(Input in) {
        Mode current = in.currentMode;
        Mode bestMode = chooseMode(in);

        // Only apply new window layout parameters if we've actually changed the display mode
        ModeSwitch modeSwitch;
        if (current.id != bestMode.id) {
            // If we only changed refresh rate and we're on an OS that supports Surface.setFrameRate()
            // use that instead of using preferredDisplayModeId to avoid the possibility of triggering
            // bugs that can cause the system to switch from 4K60 to 4K24 on Chromecast 4K.
            if (in.enforceDisplayMode ||
                    in.sdkInt < Build.VERSION_CODES.S ||
                    current.width != bestMode.width ||
                    current.height != bestMode.height) {
                modeSwitch = ModeSwitch.PREFERRED_MODE_ID;
            }
            else {
                modeSwitch = ModeSwitch.SET_FRAME_RATE;
            }
        }
        else {
            modeSwitch = ModeSwitch.NONE;
        }

        float desiredRefreshRate = bestMode.refreshRate;

        float displayRefreshRate;
        if (in.vrr) {
            // The display adapts its rate to the content: plan for the best it can do at this resolution
            displayRefreshRate = peakRefreshRate(in.supportedModes, bestMode);
        }
        else if (in.television || in.externalDisplay) {
            // TVs may take a few moments to switch refresh rates, and we can probably assume
            // it will be eventually activated.
            // External displays can't be compared with the DisplayManager's current display refresh rate.
            displayRefreshRate = desiredRefreshRate;
        }
        else if (modeSwitch != ModeSwitch.NONE &&
                in.currentRefreshRate >= current.refreshRate - RATE_TOLERANCE &&
                desiredRefreshRate <= in.refreshRateCeiling + RATE_TOLERANCE) {
            // A switch is planned, the display runs its mode's own rate (nothing overrides it) and the
            // system is not known to cap the rate below the chosen one: plan for the mode the display
            // will run after the switch, not for the one it is leaving.
            displayRefreshRate = desiredRefreshRate;
        }
        else {
            // Use the lower of the current refresh rate and the selected refresh rate.
            // The preferred refresh rate may not actually be applied (ex: Battery Saver mode).
            displayRefreshRate = Math.min(in.currentRefreshRate, desiredRefreshRate);
        }

        // If the user requested frame pacing using a capped FPS, we will need to change our
        // desired FPS setting here in accordance with the active display refresh rate.
        int roundedRefreshRate = Math.round(displayRefreshRate);
        float chosenFrameRate = in.fps;
        int framePacing = in.framePacing;
        PacingChange pacingChange = PacingChange.NONE;
        if (framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
            if (in.fps >= roundedRefreshRate) {
                if (in.fps > roundedRefreshRate + 3) {
                    // Use frame drops when rendering above the screen frame rate
                    framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
                    pacingChange = PacingChange.DROP_FRAMES_ABOVE_REFRESH_RATE;
                } else if (roundedRefreshRate <= 49) {
                    // Let's avoid clearly bogus refresh rates and fall back to legacy rendering
                    framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
                    pacingChange = PacingChange.BOGUS_REFRESH_RATE;
                }
                else {
                    chosenFrameRate = roundedRefreshRate - 1;
                    pacingChange = PacingChange.CAPPED_BELOW_REFRESH_RATE;
                }
            }
        }
        float pacedFrameRate = chosenFrameRate;

        if (in.framePacingWarpFactor > 0) {
            chosenFrameRate *= in.framePacingWarpFactor;
        }

        // Don't do setFixedSize since it might not update the view dimensions correctly when entering PiP mode
        boolean applyAspectRatio = in.scaleMode != PreferenceConfiguration.ScaleMode.STRETCH;

        float surfaceFrameRate = in.vrr
                ? pacedFrameRate
                : surfaceFrameRate(desiredRefreshRate, in.fps, framePacing, in.reduceRefreshRate);

        return new Plan(current, bestMode, modeSwitch, desiredRefreshRate, displayRefreshRate, framePacing,
                pacingChange, roundedRefreshRate, pacedFrameRate, chosenFrameRate, applyAspectRatio,
                (double) in.videoWidth / (double) in.videoHeight,
                in.scaleMode == PreferenceConfiguration.ScaleMode.FILL, in.vrr, surfaceFrameRate);
    }

    private static Mode chooseMode(Input in) {
        Mode current = in.currentMode;
        Mode bestMode = current;
        float fps = in.fps;
        boolean mayReduceRefreshRate = mayReduceRefreshRate(in.framePacing, in.reduceRefreshRate);
        boolean isNativeResolutionStream = PreferenceConfiguration.isNativeResolution(in.streamWidth, in.streamHeight);
        boolean refreshRateIsGood = isRefreshRateGoodMatch(bestMode.refreshRate, fps);
        boolean refreshRateIsEqual = isRefreshRateEqualMatch(bestMode.refreshRate, fps);

        for (Mode candidate : in.supportedModes) {
            boolean refreshRateReduced = candidate.refreshRate < bestMode.refreshRate;
            boolean resolutionReduced = candidate.width < bestMode.width ||
                    candidate.height < bestMode.height;
            boolean resolutionFitsStream = candidate.width >= in.streamWidth &&
                    candidate.height >= in.streamHeight;

            if (candidate.width > 4096 && in.streamWidth <= 4096) {
                // Avoid resolutions options above 4K to be safe
                continue;
            }

            // On non-4K streams, we force the resolution to never change unless it's above
            // 60 FPS, which may require a resolution reduction due to HDMI bandwidth limitations,
            // or it's a native resolution stream.
            if (in.streamWidth < 3840 && fps <= 60 && !isNativeResolutionStream) {
                if (current.width != candidate.width || current.height != candidate.height) {
                    continue;
                }
            }

            // Make sure the resolution doesn't regress unless if it's over 60 FPS
            // where we may need to reduce resolution to achieve the desired refresh rate.
            if (resolutionReduced && !(fps > 60 && resolutionFitsStream)) {
                continue;
            }

            if (mayReduceRefreshRate && refreshRateIsEqual && !isRefreshRateEqualMatch(candidate.refreshRate, fps)) {
                // If we had an equal refresh rate and this one is not, skip it. In min latency
                // mode, we want to always prefer the highest frame rate even though it may cause
                // microstuttering.
                continue;
            }
            else if (refreshRateIsGood) {
                // We've already got a good match, so if this one isn't also good, it's not
                // worth considering at all.
                if (!isRefreshRateGoodMatch(candidate.refreshRate, fps)) {
                    continue;
                }

                if (mayReduceRefreshRate) {
                    // User asked for the lowest possible refresh rate, so don't raise it if we
                    // have a good match already
                    if (candidate.refreshRate > bestMode.refreshRate) {
                        continue;
                    }
                }
                else {
                    // User asked for the highest possible refresh rate, so don't reduce it if we
                    // have a good match already
                    if (refreshRateReduced) {
                        continue;
                    }
                }
            }
            else if (!isRefreshRateGoodMatch(candidate.refreshRate, fps)) {
                // We didn't have a good match and this match isn't good either, so just don't
                // reduce the refresh rate.
                if (refreshRateReduced) {
                    continue;
                }
            } else {
                // We didn't have a good match and this match is good. Prefer this refresh rate
                // even if it reduces the refresh rate. Lowering the refresh rate can be beneficial
                // when streaming a 60 FPS stream on a 90 Hz device. We want to select 60 Hz to
                // match the frame rate even if the active display mode is 90 Hz.
            }

            bestMode = candidate;
            refreshRateIsGood = isRefreshRateGoodMatch(candidate.refreshRate, fps);
            refreshRateIsEqual = isRefreshRateEqualMatch(candidate.refreshRate, fps);
        }
        return bestMode;
    }

    /** The refresh rate is the stream FPS or up to 3 Hz above it. */
    static boolean isRefreshRateEqualMatch(float refreshRate, float fps) {
        return reachesFps(refreshRate, fps) &&
                refreshRate <= fps + 3;
    }

    /** The refresh rate is at least the stream FPS and (rounded) a multiple of it, within 3 Hz. */
    static boolean isRefreshRateGoodMatch(float refreshRate, float fps) {
        return reachesFps(refreshRate, fps) &&
                Math.round(refreshRate) % fps <= 3;
    }

    /**
     * The refresh rate is at least the stream FPS. The NTSC-style rates (1000/1001 of the integer rate: 23.976,
     * 29.97, 59.94 and 119.88 Hz) count as the integer FPS they stand for; 50 Hz does not reach 60 FPS.
     */
    static boolean reachesFps(float refreshRate, float fps) {
        return refreshRate + RATE_TOLERANCE >= fps * (1000f / 1001f);
    }

    /** The pacing mode prefers the lowest matching refresh rate over the highest. */
    public static boolean mayReduceRefreshRate(int framePacing, boolean reduceRefreshRate) {
        return framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS ||
                framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS ||
                (framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED && reduceRefreshRate);
    }

    /**
     * True when the window may lay out under display cutouts: a native-resolution stream whose size (either
     * orientation) is exactly one of the display's modes.
     */
    public static boolean shouldIgnoreInsets(int width, int height, List<Mode> displayModes) {
        // Never ignore insets for non-native resolutions
        if (!PreferenceConfiguration.isNativeResolution(width, height)) {
            return false;
        }

        for (Mode candidate : displayModes) {
            // Ignore insets if this is an exact match for the display resolution
            if ((width == candidate.width && height == candidate.height) ||
                    (height == candidate.width && width == candidate.height)) {
                return true;
            }
        }

        return false;
    }

    /**
     * The highest refresh rate the system is expected to grant an app's request for, from what an app can see
     * of the limits Android puts above app requests. Battery saver and thermal throttling hold the display at its
     * low rate, so no rise above the current rate is expected; the user's peak refresh rate setting caps it at
     * the higher of the peak and the minimum setting (0 when unset or unreadable). Infinity when nothing limits it.
     */
    public static float refreshRateCeiling(boolean powerSaveMode, boolean thermalThrottling, float currentRefreshRate,
                                           float peakRefreshRateSetting, float minRefreshRateSetting) {
        if (powerSaveMode || thermalThrottling) {
            return currentRefreshRate;
        }
        if (peakRefreshRateSetting > 0) {
            return Math.max(peakRefreshRateSetting, minRefreshRateSetting);
        }
        return Float.POSITIVE_INFINITY;
    }

    /**
     * The highest refresh rate among [modes] with the resolution of [mode] (at least the mode's own rate): what an
     * adaptive refresh rate display can reach at that resolution.
     */
    public static float peakRefreshRate(List<Mode> modes, Mode mode) {
        float peak = mode.refreshRate;
        for (Mode candidate : modes) {
            if (candidate.width == mode.width && candidate.height == mode.height && candidate.refreshRate > peak) {
                peak = candidate.refreshRate;
            }
        }
        return peak;
    }

    /** The frame rate passed to {@code Surface.setFrameRate()} once the stream surface exists. */
    public static float surfaceFrameRate(float desiredRefreshRate, float fps, int framePacing, boolean reduceRefreshRate) {
        // Android will pick the lowest matching refresh rate for a given frame rate value, so we want
        // to report the true FPS value if refresh rate reduction is enabled. We also report the true
        // FPS value if there's no suitable matching refresh rate. In that case, Android could try to
        // select a lower refresh rate that avoids uneven pull-down (ex: 30 Hz for a 60 FPS stream on
        // a display that maxes out at 50 Hz).
        if (mayReduceRefreshRate(framePacing, reduceRefreshRate) || !reachesFps(desiredRefreshRate, fps)) {
            return fps;
        }
        else {
            // Otherwise, we will pretend that our frame rate matches the refresh rate we picked in
            // the plan. This will usually be the highest refresh rate that our frame rate evenly
            // divides into, which ensures the lowest possible display latency.
            return desiredRefreshRate;
        }
    }
}
