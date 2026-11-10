package com.limelight.computers;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;

/**
 * The decisions of a shortcut or link that starts a game on a known PC (ShortcutTrampoline): when the state
 * the service already holds is good enough, and what to do with the answer of a poll.
 */
public final class ShortcutPcPlan {
    /** An ONLINE state that is at most this old is used without polling (the polling thread's round is 1.5 s). */
    public static final long FRESH_STATE_MS = 5000;
    /** Polls that find the PC offline (and cannot wake it) before the start gives up. */
    public static final int POLLS_BEFORE_OFFLINE = 2;
    /** Wake-on-LAN packets sent before the start gives up on a PC that does not come online. */
    public static final int MAX_WAKES = 10;

    public enum Step {
        /** The PC is online and paired: start the game (or show its games). */
        START,
        /** Poll the PC again. */
        POLL,
        /** Send Wake-on-LAN, then poll again. */
        WAKE_THEN_POLL,
        FAIL_OFFLINE,
        FAIL_NOT_PAIRED,
    }

    private ShortcutPcPlan() {}

    /**
     * Whether the state the service holds can be used without a poll: the PC was seen ONLINE at most
     * {@code maxAgeMs} ago. A negative {@code maxAgeMs} never accepts it; {@code ageMs} is negative when never polled.
     */
    public static boolean canUseCachedState(ComputerDetails.State state, long ageMs, long maxAgeMs) {
        return state == ComputerDetails.State.ONLINE && maxAgeMs >= 0 && ageMs >= 0 && ageMs <= maxAgeMs;
    }

    /**
     * What to do with the PC's state after a poll (or a cached state that was good enough).
     *
     * @param failedPolls polls so far that did not find the PC online
     * @param canWake     the PC's MAC address is known and a Wake-on-LAN packet can be sent
     * @param wakesSent   Wake-on-LAN packets sent so far
     */
    public static Step afterPoll(ComputerDetails.State state, PairingManager.PairState pairState,
                                 int failedPolls, boolean canWake, int wakesSent) {
        if (state == ComputerDetails.State.ONLINE) {
            return pairState == PairingManager.PairState.PAIRED ? Step.START : Step.FAIL_NOT_PAIRED;
        }
        if (canWake && wakesSent < MAX_WAKES) {
            return Step.WAKE_THEN_POLL;
        }
        return failedPolls < POLLS_BEFORE_OFFLINE ? Step.POLL : Step.FAIL_OFFLINE;
    }
}
