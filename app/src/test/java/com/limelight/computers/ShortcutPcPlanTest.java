package com.limelight.computers;

import static com.limelight.computers.ShortcutPcPlan.Step;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.http.ComputerDetails.State;
import com.limelight.nvstream.http.PairingManager.PairState;

import org.junit.Test;

public class ShortcutPcPlanTest {
    private static final long FRESH = ShortcutPcPlan.FRESH_STATE_MS;

    @Test
    public void recentOnlineStateIsUsedWithoutPolling() {
        assertTrue(ShortcutPcPlan.canUseCachedState(State.ONLINE, 0, FRESH));
        assertTrue(ShortcutPcPlan.canUseCachedState(State.ONLINE, FRESH, FRESH));
    }

    @Test
    public void oldUnknownOfflineOrNeverPolledStateIsPolled() {
        assertFalse(ShortcutPcPlan.canUseCachedState(State.ONLINE, FRESH + 1, FRESH));
        assertFalse(ShortcutPcPlan.canUseCachedState(State.ONLINE, -1, FRESH));
        assertFalse(ShortcutPcPlan.canUseCachedState(State.UNKNOWN, 100, FRESH));
        assertFalse(ShortcutPcPlan.canUseCachedState(State.OFFLINE, 100, FRESH));
    }

    @Test
    public void negativeMaxAgeAlwaysPolls() {
        assertFalse(ShortcutPcPlan.canUseCachedState(State.ONLINE, 0, -1));
    }

    @Test
    public void onlinePairedPcStarts() {
        assertEquals(Step.START, ShortcutPcPlan.afterPoll(State.ONLINE, PairState.PAIRED, 0, true, 0));
    }

    @Test
    public void onlineUnpairedPcFailsAsNotPaired() {
        assertEquals(Step.FAIL_NOT_PAIRED, ShortcutPcPlan.afterPoll(State.ONLINE, PairState.NOT_PAIRED, 0, true, 0));
    }

    @Test
    public void offlinePcWithMacIsWokenUntilTheTriesRunOut() {
        assertEquals(Step.WAKE_THEN_POLL, ShortcutPcPlan.afterPoll(State.OFFLINE, PairState.PAIRED, 1, true, 0));
        assertEquals(Step.WAKE_THEN_POLL, ShortcutPcPlan.afterPoll(State.OFFLINE, PairState.PAIRED, 9, true, ShortcutPcPlan.MAX_WAKES - 1));
        assertEquals(Step.FAIL_OFFLINE, ShortcutPcPlan.afterPoll(State.OFFLINE, PairState.PAIRED, 11, true, ShortcutPcPlan.MAX_WAKES));
    }

    @Test
    public void offlinePcWithoutMacIsPolledAgainOnceThenFails() {
        assertEquals(Step.POLL, ShortcutPcPlan.afterPoll(State.OFFLINE, PairState.PAIRED, 1, false, 0));
        assertEquals(Step.FAIL_OFFLINE, ShortcutPcPlan.afterPoll(State.OFFLINE, PairState.PAIRED, ShortcutPcPlan.POLLS_BEFORE_OFFLINE, false, 0));
    }

    @Test
    public void unknownStateAfterPollIsTreatedAsOffline() {
        assertEquals(Step.POLL, ShortcutPcPlan.afterPoll(State.UNKNOWN, PairState.NOT_PAIRED, 1, false, 0));
    }
}
