package com.limelight.binding.video;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LaunchArrivalHandoverTest {
    @Test public void warmingNeverTakesOverAndActivationIsOneShot() {
        LaunchArrivalHandover state = new LaunchArrivalHandover();
        assertFalse(state.canRestore(true, Long.MAX_VALUE));
        assertFalse(state.requestIdentity(0));
        assertTrue(state.activate());
        assertFalse(state.activate());
        assertFalse(state.canRestore(true, 0));
        assertFalse(state.finishDirectWait(Long.MAX_VALUE));
    }

    @Test public void identityMustActuallyPresentBeforeTheNormalHandover() {
        LaunchArrivalHandover state = active();
        assertTrue(state.requestIdentity(100));
        assertFalse(state.requestIdentity(200));
        assertFalse(state.canRestore(false, 101));
        assertTrue(state.canRestore(true, 102));
        assertTrue(state.hasIdentityPresentation());
        assertFalse(state.identityTimedOut(Long.MAX_VALUE));
        state.restored(123, 200);
        assertFalse(state.canRestore(true, 201));
        assertFalse(state.directFrame(123, 201)); // Old OES PTS, although callback time is new.
        assertFalse(state.directFrame(124, 199)); // Queued before direct ownership.
        assertTrue(state.directFrame(124, 201));
        assertFalse(state.directFrame(125, 202));
        assertFalse(state.finishDirectWait(Long.MAX_VALUE));
    }

    @Test public void staticHostCannotHoldTheIdentityWaitForever() {
        LaunchArrivalHandover state = active();
        long start = 5_000_000L;
        state.requestIdentity(start);
        assertFalse(state.canRestore(false, start + LaunchArrivalHandover.IDENTITY_WAIT_NS - 1));
        assertFalse(state.identityTimedOut(start + LaunchArrivalHandover.IDENTITY_WAIT_NS - 1));
        assertTrue(state.canRestore(false, start + LaunchArrivalHandover.IDENTITY_WAIT_NS));
        assertTrue(state.identityTimedOut(start + LaunchArrivalHandover.IDENTITY_WAIT_NS));
        assertFalse(state.hasIdentityPresentation());
    }

    @Test public void staticHostFinishesAfterDirectRestoreWithoutAnotherFrame() {
        LaunchArrivalHandover state = active();
        long identity = 100;
        state.requestIdentity(identity);
        assertTrue(state.canRestore(true, identity));
        long restored = 200;
        state.restored(900, restored);
        assertFalse(state.finishDirectWait(restored + LaunchArrivalHandover.DIRECT_WAIT_NS - 1));
        assertTrue(state.finishDirectWait(restored + LaunchArrivalHandover.DIRECT_WAIT_NS));
        assertTrue(state.hasIdentityPresentation());
        assertFalse(state.finishDirectWait(Long.MAX_VALUE));
        assertFalse(state.directFrame(901, Long.MAX_VALUE));
    }

    @Test public void absentIdentityAndDirectFramesHaveOneCombinedFiniteBudget() {
        LaunchArrivalHandover state = active();
        long start = 1_000;
        state.requestIdentity(start);
        long restored = start + LaunchArrivalHandover.IDENTITY_WAIT_NS;
        assertTrue(state.canRestore(false, restored));
        state.restored(10, restored);
        long complete = restored + LaunchArrivalHandover.DIRECT_WAIT_NS;
        assertTrue(complete - start < 1_000_000_000L);
        assertTrue(state.finishDirectWait(complete));
        assertFalse(state.hasIdentityPresentation());
    }

    @Test public void cancelBeforeActivationNeverConnectsATemporaryProducer() {
        LaunchArrivalHandover state = new LaunchArrivalHandover();
        state.cancel();
        state.cancel();
        assertFalse(state.activate());
        assertFalse(state.requestIdentity(100));
        assertTrue(state.canRestore(false, 100));
    }

    @Test public void cancelDuringEffectSkipsIdentityButStillBoundsTheDirectWait() {
        LaunchArrivalHandover state = active();
        state.cancel();
        assertTrue(state.canRestore(false, 100));
        assertFalse(state.identityTimedOut(Long.MAX_VALUE));
        state.restored(30, 200);
        state.cancel();
        assertFalse(state.canRestore(false, 201));
        assertTrue(state.finishDirectWait(200 + LaunchArrivalHandover.DIRECT_WAIT_NS));
        assertFalse(state.hasIdentityPresentation());
    }

    @Test public void codecRecoveryStartsAFreshDirectPresentationWindow() {
        LaunchArrivalHandover state = active();
        state.restored(100, 1_000);
        state.restored(200, 2_000);
        assertFalse(state.directFrame(150, 2_001));
        assertFalse(state.directFrame(201, 1_999));
        assertFalse(state.finishDirectWait(1_000 + LaunchArrivalHandover.DIRECT_WAIT_NS));
        assertTrue(state.finishDirectWait(2_000 + LaunchArrivalHandover.DIRECT_WAIT_NS));
    }

    private static LaunchArrivalHandover active() {
        LaunchArrivalHandover state = new LaunchArrivalHandover();
        assertTrue(state.activate());
        return state;
    }
}
