package com.limelight.binding.video;

import com.limelight.LimeLog;

import java.io.File;
import java.util.Arrays;

/**
 * Delos: scheduling help for the threads on the stream's latency path (issue #75). Always on while a stream
 * runs, no setting. Threads announce themselves with {@link #register(int)} when they start; the receive
 * thread of moonlight-common-c does it from latency_threads.c on its first frame. While the stream runs
 * ({@link #begin()} to {@link #end()}) every registered thread is kept on the big cores, see
 * {@link ThreadPlacement}.
 */
public final class LatencyThreads {
    private static final Object lock = new Object();
    private static final TidRegistry registry = new TidRegistry();

    private static boolean active;
    // Read once per process: the kernel's CPU capacities do not change
    private static boolean maskRead;
    private static long coreMask;
    private static boolean affinityFailureLogged;

    private LatencyThreads() {
    }

    /** The stream starts: places every thread registered so far. Threads registered later are placed on arrival. */
    public static void begin() {
        synchronized (lock) {
            active = true;
            readCoreMaskOnce();
            for (int tid : registry.live(LatencyThreads::isAlive)) applyAffinity(tid);
        }
    }

    /** The stream ends or pauses: forgets every thread (they exit with the stream). Safe to call repeatedly. */
    public static void end() {
        synchronized (lock) {
            active = false;
            registry.clear();
        }
    }

    /**
     * Announces a thread of the stream path by its kernel id (Process.myTid(), HandlerThread.getThreadId()).
     * Call it once at thread start; repeated or invalid ids are ignored. May run before {@link #begin()}.
     */
    public static void register(int tid) {
        synchronized (lock) {
            if (!registry.add(tid)) return;
            if (active) applyAffinity(tid);
        }
    }

    /** Entry point of latency_threads.c for the receive thread. */
    static void onNativeThread(int tid) {
        register(tid);
    }

    private static void readCoreMaskOnce() {
        if (maskRead) return;
        maskRead = true;
        int[] capacities = ThreadPlacement.readCapacities(new File("/sys/devices/system/cpu"));
        coreMask = ThreadPlacement.bigCoreMask(capacities);
        LimeLog.info("Delos latency threads: cpu_capacity " + Arrays.toString(capacities) + ", big-core mask "
                + (coreMask != 0 ? "0x" + Long.toHexString(coreMask) : "none (placement skipped)"));
    }

    // Called with the lock held
    private static void applyAffinity(int tid) {
        if (coreMask == 0) return;
        try {
            int errno = nativeSetAffinity(tid, coreMask);
            if (errno != 0) logAffinityFailure("sched_setaffinity(" + tid + ") failed, errno " + errno);
        } catch (UnsatisfiedLinkError e) {
            logAffinityFailure("native placement is not available");
            coreMask = 0;
        }
    }

    // Called with the lock held; one line per process, the same failure repeats for every thread
    private static void logAffinityFailure(String message) {
        if (affinityFailureLogged) return;
        affinityFailureLogged = true;
        LimeLog.warning("Delos latency threads: " + message);
    }

    private static boolean isAlive(int tid) {
        return new File("/proc/self/task/" + tid).exists();
    }

    /** sched_setaffinity of one thread of this process; 0 on success, else the errno. */
    private static native int nativeSetAffinity(int tid, long mask);
}
