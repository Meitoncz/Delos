package com.limelight.binding.video;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Delos: the kernel thread ids that take part in the latency-critical stream path (see LatencyThreads). Not
 * thread-safe: LatencyThreads guards it with its own lock.
 */
final class TidRegistry {
    private final Set<Integer> tids = new LinkedHashSet<>();

    /** Records [tid]; false for an invalid id (0 or less) or one already recorded. */
    boolean add(int tid) {
        return tid > 0 && tids.add(tid);
    }

    /** The recorded ids that [alive] still accepts, in registration order; dead ones are forgotten. */
    int[] live(IntPredicate alive) {
        tids.removeIf(tid -> !alive.test(tid));
        int[] result = new int[tids.size()];
        int i = 0;
        for (int tid : tids) result[i++] = tid;
        return result;
    }

    void clear() {
        tids.clear();
    }

    int size() {
        return tids.size();
    }
}
