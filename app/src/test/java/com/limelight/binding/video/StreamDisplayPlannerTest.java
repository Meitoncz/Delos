package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Build;

import com.limelight.binding.video.StreamDisplayPlanner.Mode;
import com.limelight.binding.video.StreamDisplayPlanner.ModeSwitch;
import com.limelight.binding.video.StreamDisplayPlanner.PacingChange;
import com.limelight.binding.video.StreamDisplayPlanner.Plan;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.PreferenceConfiguration.ScaleMode;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * The display planner's decisions. Most are characterisation tests of what DelosStreamActivity did before the
 * planner was extracted; the expected refresh rate and the NTSC-style rate matches were changed since (#80).
 */
public class StreamDisplayPlannerTest {
    private static final int LATENCY = PreferenceConfiguration.FRAME_PACING_MIN_LATENCY;
    private static final int BALANCED = PreferenceConfiguration.FRAME_PACING_BALANCED;
    private static final int CAP_FPS = PreferenceConfiguration.FRAME_PACING_CAP_FPS;
    private static final int SMOOTHNESS = PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS;
    private static final int FAST_VSYNC = PreferenceConfiguration.FRAME_PACING_FAST_VSYNC;

    // A 1080p panel with 60, 90, 120 and 144 Hz modes
    private static final Mode HZ60 = new Mode(1, 1920, 1080, 60f);
    private static final Mode HZ90 = new Mode(2, 1920, 1080, 90f);
    private static final Mode HZ120 = new Mode(3, 1920, 1080, 120f);
    private static final Mode HZ144 = new Mode(4, 1920, 1080, 144f);
    private static final List<Mode> PANEL = Arrays.asList(HZ60, HZ90, HZ120, HZ144);

    /** A 1080p stream (a preset, not native) on the panel, Balanced pacing, Android 12. */
    private static StreamDisplayPlanner.Input input(Mode current, float fps) {
        return new StreamDisplayPlanner.Input()
                .display(current, PANEL, current.refreshRate)
                .stream(1920, 1080, fps)
                .video(1920, 1080, ScaleMode.FIT)
                .pacing(BALANCED, false, 0)
                .sdkInt(Build.VERSION_CODES.S);
    }

    private static Plan plan(StreamDisplayPlanner.Input input) {
        return StreamDisplayPlanner.plan(input);
    }

    // --- Match rules

    @Test
    public void equalMatchIsTheFpsOrUpToThreeHzAbove() {
        assertTrue(StreamDisplayPlanner.isRefreshRateEqualMatch(60f, 60f));
        assertTrue(StreamDisplayPlanner.isRefreshRateEqualMatch(63f, 60f));
        assertFalse(StreamDisplayPlanner.isRefreshRateEqualMatch(63.5f, 60f));
        assertFalse(StreamDisplayPlanner.isRefreshRateEqualMatch(120f, 60f));
        // The NTSC-style rate just below the FPS is the FPS
        assertTrue(StreamDisplayPlanner.isRefreshRateEqualMatch(59.94f, 60f));
        assertTrue(StreamDisplayPlanner.isRefreshRateEqualMatch(119.88f, 120f));
        assertFalse(StreamDisplayPlanner.isRefreshRateEqualMatch(50f, 60f));
        assertFalse(StreamDisplayPlanner.isRefreshRateEqualMatch(59f, 60f));
    }

    @Test
    public void goodMatchIsARoundedMultipleOfTheFpsWithinThreeHz() {
        assertTrue(StreamDisplayPlanner.isRefreshRateGoodMatch(60f, 60f));
        assertTrue(StreamDisplayPlanner.isRefreshRateGoodMatch(120f, 60f));
        assertTrue(StreamDisplayPlanner.isRefreshRateGoodMatch(119.88f, 60f));
        assertTrue(StreamDisplayPlanner.isRefreshRateGoodMatch(90f, 30f));
        assertTrue(StreamDisplayPlanner.isRefreshRateGoodMatch(144f, 72f));
        assertTrue(StreamDisplayPlanner.isRefreshRateGoodMatch(63f, 60f));
        assertFalse(StreamDisplayPlanner.isRefreshRateGoodMatch(90f, 60f));
        assertFalse(StreamDisplayPlanner.isRefreshRateGoodMatch(144f, 60f));
        assertFalse(StreamDisplayPlanner.isRefreshRateGoodMatch(144f, 120f));
        assertTrue(StreamDisplayPlanner.isRefreshRateGoodMatch(59.94f, 60f));
        assertTrue(StreamDisplayPlanner.isRefreshRateGoodMatch(59.94f, 30f));
        assertFalse(StreamDisplayPlanner.isRefreshRateGoodMatch(50f, 60f));
        assertFalse(StreamDisplayPlanner.isRefreshRateGoodMatch(60f, 90f));
    }

    @Test
    public void ntscStyleRatesReachTheIntegerFps() {
        assertTrue(StreamDisplayPlanner.reachesFps(23.976f, 24f));
        assertTrue(StreamDisplayPlanner.reachesFps(29.97f, 30f));
        assertTrue(StreamDisplayPlanner.reachesFps(59.94f, 60f));
        assertTrue(StreamDisplayPlanner.reachesFps(119.88f, 120f));
        assertTrue(StreamDisplayPlanner.reachesFps(60f, 60f));
        assertTrue(StreamDisplayPlanner.reachesFps(144f, 120f));
        // Further below than the NTSC fraction is too slow
        assertFalse(StreamDisplayPlanner.reachesFps(59.5f, 60f));
        assertFalse(StreamDisplayPlanner.reachesFps(50f, 60f));
        assertFalse(StreamDisplayPlanner.reachesFps(25f, 30f));
        assertFalse(StreamDisplayPlanner.reachesFps(100f, 120f));
        assertFalse(StreamDisplayPlanner.reachesFps(119f, 120f));
    }

    @Test
    public void aSixtyFpsStreamStaysOnAMatching5994HzModeWhenReducing() {
        Mode ntsc = new Mode(1, 1920, 1080, 59.94f);
        Mode hz50 = new Mode(2, 1920, 1080, 50f);
        Plan plan = plan(input(ntsc, 60).display(ntsc, Arrays.asList(hz50, ntsc, HZ120), 59.94f).pacing(BALANCED, true, 0));
        // 59.94 Hz is the match for 60 FPS, so the rate is not raised to 120 Hz
        assertSame(ntsc, plan.bestMode);
        assertEquals(ModeSwitch.NONE, plan.modeSwitch);

        // From 50 Hz the 59.94 Hz mode is the lowest match
        Plan from50 = plan(input(hz50, 60).display(hz50, Arrays.asList(hz50, ntsc, HZ120), 50f).pacing(BALANCED, true, 0));
        assertSame(ntsc, from50.bestMode);
    }

    @Test
    public void onlyCapFpsSmoothnessAndBalancedWithReduceMayLowerTheRefreshRate() {
        assertTrue(StreamDisplayPlanner.mayReduceRefreshRate(CAP_FPS, false));
        assertTrue(StreamDisplayPlanner.mayReduceRefreshRate(SMOOTHNESS, false));
        assertTrue(StreamDisplayPlanner.mayReduceRefreshRate(BALANCED, true));
        assertFalse(StreamDisplayPlanner.mayReduceRefreshRate(BALANCED, false));
        assertFalse(StreamDisplayPlanner.mayReduceRefreshRate(LATENCY, true));
        assertFalse(StreamDisplayPlanner.mayReduceRefreshRate(FAST_VSYNC, true));
    }

    // --- Mode choice: highest matching refresh rate unless the pacing may reduce it

    @Test
    public void sixtyFpsPicksTheHighestMultipleWithoutReduce() {
        Plan plan = plan(input(HZ60, 60));
        assertSame(HZ120, plan.bestMode);
        assertEquals(ModeSwitch.SET_FRAME_RATE, plan.modeSwitch);
        assertEquals(120f, plan.desiredRefreshRate, 0f);
        // The display is expected to run 120 Hz once the switch is applied
        assertEquals(120f, plan.displayRefreshRate, 0f);
        assertEquals(60f, plan.streamFrameRate, 0f);
    }

    @Test
    public void sixtyFpsKeepsAHigherGoodModeWithoutReduce() {
        Plan plan = plan(input(HZ120, 60));
        assertSame(HZ120, plan.bestMode);
        assertEquals(ModeSwitch.NONE, plan.modeSwitch);
        assertEquals(120f, plan.displayRefreshRate, 0f);
    }

    @Test
    public void sixtyFpsWithReduceRefreshRatePicksSixtyHz() {
        Plan fromSixty = plan(input(HZ60, 60).pacing(BALANCED, true, 0));
        assertSame(HZ60, fromSixty.bestMode);
        assertEquals(ModeSwitch.NONE, fromSixty.modeSwitch);

        Plan fromHundredTwenty = plan(input(HZ120, 60).pacing(BALANCED, true, 0));
        assertSame(HZ60, fromHundredTwenty.bestMode);
        assertEquals(ModeSwitch.SET_FRAME_RATE, fromHundredTwenty.modeSwitch);
        assertEquals(60f, fromHundredTwenty.desiredRefreshRate, 0f);
        assertEquals(60f, fromHundredTwenty.displayRefreshRate, 0f);
    }

    @Test
    public void maxSmoothnessReducesLikeReduceRefreshRate() {
        Plan plan = plan(input(HZ144, 60).pacing(SMOOTHNESS, false, 0));
        assertSame(HZ60, plan.bestMode);
    }

    @Test
    public void latencyAndFastVsyncIgnoreReduceRefreshRate() {
        assertSame(HZ120, plan(input(HZ60, 60).pacing(LATENCY, true, 0)).bestMode);
        assertSame(HZ120, plan(input(HZ60, 60).pacing(FAST_VSYNC, true, 0)).bestMode);
    }

    @Test
    public void thirtyFpsPicksTheHighestMultipleOrTheLowestWithReduce() {
        assertSame(HZ120, plan(input(HZ60, 30)).bestMode);
        assertSame(HZ60, plan(input(HZ144, 30).pacing(BALANCED, true, 0)).bestMode);
    }

    @Test
    public void ninetyFpsPicksNinetyHz() {
        Plan plan = plan(input(HZ60, 90));
        assertSame(HZ90, plan.bestMode);
        assertEquals(90f, plan.desiredRefreshRate, 0f);
        assertEquals(90f, plan.displayRefreshRate, 0f);
    }

    @Test
    public void hundredTwentyFpsPicksHundredTwentyHz() {
        assertSame(HZ120, plan(input(HZ60, 120)).bestMode);
        assertSame(HZ120, plan(input(HZ144, 120)).bestMode);
        assertSame(HZ120, plan(input(HZ144, 120).pacing(BALANCED, true, 0)).bestMode);
    }

    @Test
    public void unlockedFpsAboveEveryModeKeepsTheHighestRate() {
        // 144 FPS (unlocked FPS) only matches 144 Hz; 240 FPS matches nothing, so the rate is not reduced
        assertSame(HZ144, plan(input(HZ60, 144)).bestMode);
        Plan plan = plan(input(HZ144, 240));
        assertSame(HZ144, plan.bestMode);
        assertEquals(ModeSwitch.NONE, plan.modeSwitch);
    }

    @Test
    public void withoutAGoodMatchTheRateIsNotReduced() {
        // 50 FPS: no multiple on the panel; from 120 Hz the 144 Hz mode is taken, nothing lower
        assertSame(HZ144, plan(input(HZ120, 50)).bestMode);
    }

    // --- How the mode is applied

    @Test
    public void refreshRateOnlySwitchUsesPreferredModeBeforeAndroid12OrWhenEnforced() {
        assertEquals(ModeSwitch.SET_FRAME_RATE, plan(input(HZ60, 60)).modeSwitch);
        assertEquals(ModeSwitch.PREFERRED_MODE_ID, plan(input(HZ60, 60).sdkInt(Build.VERSION_CODES.R)).modeSwitch);
        assertEquals(ModeSwitch.PREFERRED_MODE_ID, plan(input(HZ60, 60).enforceDisplayMode(true)).modeSwitch);
    }

    @Test
    public void resolutionSwitchAlwaysUsesPreferredMode() {
        Mode uhd60 = new Mode(1, 3840, 2160, 60f);
        Mode fhd120 = new Mode(2, 1920, 1080, 120f);
        Plan plan = plan(new StreamDisplayPlanner.Input()
                .display(uhd60, Arrays.asList(uhd60, fhd120), 60f)
                .stream(1920, 1080, 120)
                .video(1920, 1080, ScaleMode.FIT)
                .pacing(BALANCED, false, 0));
        // Over 60 FPS the resolution may drop if the stream still fits
        assertSame(fhd120, plan.bestMode);
        assertEquals(ModeSwitch.PREFERRED_MODE_ID, plan.modeSwitch);
    }

    // --- Resolution rules

    @Test
    public void presetStreamUpToSixtyFpsNeverChangesTheResolution() {
        Mode fhd60 = new Mode(1, 1920, 1080, 60f);
        Mode uhd120 = new Mode(2, 3840, 2160, 120f);
        Plan plan = plan(new StreamDisplayPlanner.Input()
                .display(fhd60, Arrays.asList(fhd60, uhd120), 60f)
                .stream(1920, 1080, 60)
                .video(1920, 1080, ScaleMode.FIT));
        assertSame(fhd60, plan.bestMode);
    }

    @Test
    public void aboveSixtyFpsAHigherResolutionModeMayBeChosen() {
        Mode fhd60 = new Mode(1, 1920, 1080, 60f);
        Mode uhd120 = new Mode(2, 3840, 2160, 120f);
        Plan plan = plan(new StreamDisplayPlanner.Input()
                .display(fhd60, Arrays.asList(fhd60, uhd120), 60f)
                .stream(1920, 1080, 120)
                .video(1920, 1080, ScaleMode.FIT));
        assertSame(uhd120, plan.bestMode);
        assertEquals(ModeSwitch.PREFERRED_MODE_ID, plan.modeSwitch);
    }

    @Test
    public void modesWiderThan4kAreSkippedForStreamsUpTo4k() {
        Mode fhd60 = new Mode(1, 1920, 1080, 60f);
        Mode wide120 = new Mode(2, 5120, 2880, 120f);
        Plan plan = plan(new StreamDisplayPlanner.Input()
                .display(fhd60, Arrays.asList(fhd60, wide120), 60f)
                .stream(1920, 1080, 120)
                .video(1920, 1080, ScaleMode.FIT));
        assertSame(fhd60, plan.bestMode);
    }

    @Test
    public void resolutionDropIsRejectedWhenTheStreamDoesNotFit() {
        Mode uhd60 = new Mode(1, 3840, 2160, 60f);
        Mode fhd120 = new Mode(2, 1920, 1080, 120f);
        Plan plan = plan(new StreamDisplayPlanner.Input()
                .display(uhd60, Arrays.asList(uhd60, fhd120), 60f)
                .stream(2560, 1440, 120)
                .video(2560, 1440, ScaleMode.FIT));
        assertSame(uhd60, plan.bestMode);
    }

    @Test
    public void nativeStreamUpToSixtyFpsMayChangeTheResolution() {
        // 2400x1080 is not a preset, so the "keep the resolution" rule does not apply
        Mode small60 = new Mode(1, 1600, 720, 60f);
        Mode native60 = new Mode(2, 2400, 1080, 60f);
        Plan plan = plan(new StreamDisplayPlanner.Input()
                .display(small60, Arrays.asList(small60, native60), 60f)
                .stream(2400, 1080, 60)
                .video(2400, 1080, ScaleMode.FIT));
        assertSame(native60, plan.bestMode);
        assertEquals(ModeSwitch.PREFERRED_MODE_ID, plan.modeSwitch);
    }

    // --- Expected refresh rate: the mode the display runs after the planned switch

    @Test
    public void aPlannedSwitchIsExpectedToApply() {
        assertEquals(120f, plan(input(HZ60, 60)).displayRefreshRate, 0f);
        assertEquals(90f, plan(input(HZ60, 90)).displayRefreshRate, 0f);
        // Downwards too
        assertEquals(60f, plan(input(HZ120, 60).pacing(BALANCED, true, 0)).displayRefreshRate, 0f);
        // The mode's rate as the system reports it (60.000004 Hz) is not an override
        Mode noisy = new Mode(1, 1920, 1080, 60.000004f);
        Plan fromNoisy = plan(input(noisy, 120).display(noisy, Arrays.asList(noisy, HZ120), 60f));
        assertEquals(120f, fromNoisy.displayRefreshRate, 0f);
    }

    @Test
    public void aSwitchTheSystemIsKnownToRefuseKeepsTheCurrentRate() {
        // Battery saver, thermal throttling or a 60 Hz peak setting: nothing above 60 Hz is granted
        Plan capped = plan(input(HZ60, 60).refreshRateCeiling(60f));
        assertSame(HZ120, capped.bestMode);
        assertEquals(120f, capped.desiredRefreshRate, 0f);
        assertEquals(60f, capped.displayRefreshRate, 0f);
        assertEquals(60f, plan(input(HZ60, 120).refreshRateCeiling(90f)).displayRefreshRate, 0f);
        // A mode at the ceiling is granted
        assertEquals(90f, plan(input(HZ60, 90).refreshRateCeiling(90f)).displayRefreshRate, 0f);
        // TVs and external displays trust the chosen mode whatever the ceiling
        assertEquals(120f, plan(input(HZ60, 60).refreshRateCeiling(60f).television(true)).displayRefreshRate, 0f);
        assertEquals(120f, plan(input(HZ60, 60).refreshRateCeiling(60f).externalDisplay(true)).displayRefreshRate, 0f);
    }

    @Test
    public void aDisplayRunningBelowItsModesRateIsNotExpectedToRise() {
        // The current mode says 120 Hz, the display runs 60 Hz (a refresh rate override, e.g. battery saver)
        Plan noSwitch = plan(input(HZ120, 60).display(HZ120, PANEL, 60f));
        assertEquals(ModeSwitch.NONE, noSwitch.modeSwitch);
        assertEquals(120f, noSwitch.desiredRefreshRate, 0f);
        assertEquals(60f, noSwitch.displayRefreshRate, 0f);

        Plan withSwitch = plan(input(HZ90, 60).display(HZ90, PANEL, 60f));
        assertSame(HZ120, withSwitch.bestMode);
        assertEquals(60f, withSwitch.displayRefreshRate, 0f);
    }

    @Test
    public void refreshRateCeilingComesFromBatterySaverThermalThrottlingAndThePeakSetting() {
        float unlimited = Float.POSITIVE_INFINITY;
        assertEquals(unlimited, StreamDisplayPlanner.refreshRateCeiling(false, false, 60f, 0f, 0f), 0f);
        // Battery saver and thermal throttling: no rise above the current rate
        assertEquals(60f, StreamDisplayPlanner.refreshRateCeiling(true, false, 60f, 120f, 0f), 0f);
        assertEquals(60f, StreamDisplayPlanner.refreshRateCeiling(false, true, 60f, 0f, 0f), 0f);
        // The user's peak refresh rate, or the minimum when that is higher
        assertEquals(60f, StreamDisplayPlanner.refreshRateCeiling(false, false, 120f, 60f, 0f), 0f);
        assertEquals(90f, StreamDisplayPlanner.refreshRateCeiling(false, false, 60f, 60f, 90f), 0f);
        // Android 14 stores infinity for "the highest rate"
        assertEquals(unlimited, StreamDisplayPlanner.refreshRateCeiling(false, false, 60f, unlimited, 0f), 0f);
    }

    // --- Cap FPS pacing adjustment

    @Test
    public void capFpsCapsOneBelowTheRefreshRate() {
        Plan plan = plan(input(HZ60, 60).pacing(CAP_FPS, false, 0));
        assertSame(HZ60, plan.bestMode);
        assertEquals(CAP_FPS, plan.framePacing);
        assertEquals(PacingChange.CAPPED_BELOW_REFRESH_RATE, plan.pacingChange);
        assertEquals(59f, plan.pacedFrameRate, 0f);
        assertEquals(59f, plan.streamFrameRate, 0f);

        Plan high = plan(input(HZ120, 120).pacing(CAP_FPS, false, 0));
        assertEquals(PacingChange.CAPPED_BELOW_REFRESH_RATE, high.pacingChange);
        assertEquals(119f, high.streamFrameRate, 0f);
    }

    @Test
    public void capFpsWithinThreeAboveTheRefreshRateIsCappedToo() {
        Mode hz58 = new Mode(9, 1920, 1080, 58f);
        Plan plan = plan(new StreamDisplayPlanner.Input()
                .display(hz58, Arrays.asList(hz58), 58f)
                .stream(1920, 1080, 60)
                .video(1920, 1080, ScaleMode.FIT)
                .pacing(CAP_FPS, false, 0));
        assertEquals(PacingChange.CAPPED_BELOW_REFRESH_RATE, plan.pacingChange);
        assertEquals(57f, plan.streamFrameRate, 0f);
    }

    @Test
    public void capFpsBelowTheRefreshRateIsUnchanged() {
        Plan plan = plan(input(HZ60, 30).pacing(CAP_FPS, false, 0));
        assertEquals(CAP_FPS, plan.framePacing);
        assertEquals(PacingChange.NONE, plan.pacingChange);
        assertEquals(30f, plan.streamFrameRate, 0f);
    }

    @Test
    public void capFpsFollowsTheModeTheDisplaySwitchesTo() {
        // 120 FPS from a 60 Hz mode: 120 Hz is chosen and expected, so the stream is capped just below it
        Plan plan = plan(input(HZ60, 120).pacing(CAP_FPS, false, 0));
        assertSame(HZ120, plan.bestMode);
        assertEquals(120f, plan.displayRefreshRate, 0f);
        assertEquals(CAP_FPS, plan.framePacing);
        assertEquals(PacingChange.CAPPED_BELOW_REFRESH_RATE, plan.pacingChange);
        assertEquals(119f, plan.streamFrameRate, 0f);
    }

    @Test
    public void capFpsAboveTheRefreshRateDropsFramesInBalanced() {
        // 120 FPS from a 60 Hz mode the system keeps (e.g. battery saver): 120 Hz is chosen, the display stays at 60 Hz
        Plan plan = plan(input(HZ60, 120).pacing(CAP_FPS, false, 0).refreshRateCeiling(60f));
        assertSame(HZ120, plan.bestMode);
        assertEquals(60f, plan.displayRefreshRate, 0f);
        assertEquals(BALANCED, plan.framePacing);
        assertEquals(PacingChange.DROP_FRAMES_ABOVE_REFRESH_RATE, plan.pacingChange);
        assertEquals(120f, plan.streamFrameRate, 0f);
    }

    @Test
    public void capFpsOnABogusRefreshRateFallsBackToBalanced() {
        Mode hz48 = new Mode(9, 1920, 1080, 48f);
        Plan plan = plan(new StreamDisplayPlanner.Input()
                .display(hz48, Arrays.asList(hz48), 48f)
                .stream(1920, 1080, 50)
                .video(1920, 1080, ScaleMode.FIT)
                .pacing(CAP_FPS, false, 0));
        assertEquals(BALANCED, plan.framePacing);
        assertEquals(PacingChange.BOGUS_REFRESH_RATE, plan.pacingChange);
        assertEquals(48, plan.roundedRefreshRate);
        assertEquals(50f, plan.streamFrameRate, 0f);
    }

    @Test
    public void otherPacingModesKeepTheStreamFrameRate() {
        for (int pacing : new int[] {LATENCY, BALANCED, SMOOTHNESS, FAST_VSYNC}) {
            Plan plan = plan(input(HZ60, 60).pacing(pacing, false, 0));
            assertEquals(pacing, plan.framePacing);
            assertEquals(PacingChange.NONE, plan.pacingChange);
            assertEquals(60f, plan.streamFrameRate, 0f);
        }
    }

    @Test
    public void warpFactorMultipliesTheStreamFrameRate() {
        Plan plan = plan(input(HZ60, 60).pacing(LATENCY, false, 2));
        assertEquals(60f, plan.pacedFrameRate, 0f);
        assertEquals(120f, plan.streamFrameRate, 0f);
        assertEquals(240f, plan(input(HZ60, 60).pacing(LATENCY, false, 4)).streamFrameRate, 0f);
    }

    // --- Aspect ratio

    @Test
    public void aspectRatioFollowsTheVideoSizeUnlessStretched() {
        Plan fit = plan(input(HZ60, 60).video(1080, 1920, ScaleMode.FIT));
        assertTrue(fit.applyAspectRatio);
        assertEquals(1080.0 / 1920.0, fit.aspectRatio, 0d);
        assertFalse(fit.fillDisplay);

        Plan fill = plan(input(HZ60, 60).video(1920, 1080, ScaleMode.FILL));
        assertTrue(fill.applyAspectRatio);
        assertTrue(fill.fillDisplay);

        assertFalse(plan(input(HZ60, 60).video(1920, 1080, ScaleMode.STRETCH)).applyAspectRatio);
        // External displays get the aspect ratio too
        assertTrue(plan(input(HZ60, 60).externalDisplay(true)).applyAspectRatio);
    }

    // --- Insets

    @Test
    public void insetsAreIgnoredOnlyForNativeStreamsMatchingADisplayMode() {
        List<Mode> phone = Arrays.asList(new Mode(1, 1080, 2400, 60f), new Mode(2, 1080, 2400, 120f));
        assertTrue(StreamDisplayPlanner.shouldIgnoreInsets(2400, 1080, phone));
        assertTrue(StreamDisplayPlanner.shouldIgnoreInsets(1080, 2400, phone));
        assertFalse(StreamDisplayPlanner.shouldIgnoreInsets(2340, 1080, phone));
        // A preset is never treated as native, even when it is the panel's size
        assertFalse(StreamDisplayPlanner.shouldIgnoreInsets(1920, 1080, PANEL));
    }

    // --- Surface frame rate

    @Test
    public void surfaceFrameRateIsTheChosenRefreshRateUnlessReducingOrTooLow() {
        assertEquals(120f, StreamDisplayPlanner.surfaceFrameRate(120f, 60f, BALANCED, false), 0f);
        assertEquals(120f, StreamDisplayPlanner.surfaceFrameRate(120f, 60f, LATENCY, true), 0f);
        assertEquals(120f, StreamDisplayPlanner.surfaceFrameRate(120f, 60f, FAST_VSYNC, false), 0f);
        // Pacing that may reduce the rate reports the true FPS
        assertEquals(60f, StreamDisplayPlanner.surfaceFrameRate(120f, 60f, BALANCED, true), 0f);
        assertEquals(60f, StreamDisplayPlanner.surfaceFrameRate(120f, 60f, CAP_FPS, false), 0f);
        assertEquals(60f, StreamDisplayPlanner.surfaceFrameRate(120f, 60f, SMOOTHNESS, false), 0f);
        // No matching rate (50 Hz for 60 FPS): the true FPS
        assertEquals(60f, StreamDisplayPlanner.surfaceFrameRate(50f, 60f, BALANCED, false), 0f);
        // 59.94 Hz is the match for 60 FPS and is requested as chosen
        assertEquals(59.94f, StreamDisplayPlanner.surfaceFrameRate(59.94f, 60f, BALANCED, false), 0f);
    }

    @Test
    public void capFpsDroppedToBalancedReportsTheChosenRateToTheSurface() {
        // The surface sees the pacing after the Cap FPS adjustment (here: the system keeps the display at 60 Hz)
        Plan plan = plan(input(HZ60, 120).pacing(CAP_FPS, false, 0).refreshRateCeiling(60f));
        assertEquals(BALANCED, plan.framePacing);
        assertEquals(120f, StreamDisplayPlanner.surfaceFrameRate(plan.desiredRefreshRate, 120f, plan.framePacing, false), 0f);
    }

    @Test
    public void modeLogFormIsWidthHeightRefresh() {
        assertEquals("1920x1080x60.0", HZ60.toString());
        assertEquals("1920x1080x59.94", new Mode(5, 1920, 1080, 59.94f).toString());
    }
}
