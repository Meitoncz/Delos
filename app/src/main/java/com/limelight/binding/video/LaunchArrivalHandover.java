package com.limelight.binding.video;

/** Pure, monotonic-time policy for the temporary output's bounded handover windows. */
final class LaunchArrivalHandover {
    static final long IDENTITY_WAIT_NS = 400_000_000L;
    static final long DIRECT_WAIT_NS = 350_000_000L;
    private enum Stage { WARM, ACTIVE, IDENTITY, DIRECT, FINISHED }
    private Stage stage = Stage.WARM;
    private boolean cancelled, identityPresented;
    private long identityDeadlineNs, directDeadlineNs, directPtsExclusive, directNotBeforeNs;

    synchronized boolean activate() {
        if (cancelled || stage != Stage.WARM) return false;
        stage = Stage.ACTIVE;
        return true;
    }

    synchronized boolean requestIdentity(long nowNs) {
        if (cancelled || stage != Stage.ACTIVE) return false;
        stage = Stage.IDENTITY;
        identityDeadlineNs = nowNs + IDENTITY_WAIT_NS;
        return true;
    }

    synchronized boolean canRestore(boolean presented, long nowNs) {
        if (stage == Stage.DIRECT || stage == Stage.FINISHED) return false;
        if (stage == Stage.IDENTITY && presented) identityPresented = true;
        return cancelled || (stage == Stage.IDENTITY
                && (identityPresented || nowNs >= identityDeadlineNs));
    }

    synchronized boolean identityTimedOut(long nowNs) {
        return !cancelled && stage == Stage.IDENTITY && !identityPresented
                && nowNs >= identityDeadlineNs;
    }

    synchronized void restored(long lastAssignedPtsUs, long nowNs) {
        if (stage == Stage.FINISHED) return;
        stage = Stage.DIRECT;
        directPtsExclusive = lastAssignedPtsUs;
        directNotBeforeNs = nowNs;
        directDeadlineNs = nowNs + DIRECT_WAIT_NS;
    }

    synchronized boolean directFrame(long ptsUs, long nowNs) {
        if (stage != Stage.DIRECT || ptsUs <= directPtsExclusive || nowNs < directNotBeforeNs) return false;
        stage = Stage.FINISHED;
        return true;
    }

    synchronized boolean finishDirectWait(long nowNs) {
        if (stage != Stage.DIRECT || nowNs < directDeadlineNs) return false;
        stage = Stage.FINISHED;
        return true;
    }

    synchronized void cancel() { cancelled = true; }
    synchronized boolean hasIdentityPresentation() { return identityPresented; }
}
