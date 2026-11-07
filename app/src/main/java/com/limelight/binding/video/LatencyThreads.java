package com.limelight.binding.video;

import android.content.Context;
import android.os.Build;
import android.os.PerformanceHintManager;

import androidx.annotation.RequiresApi;

import com.limelight.LimeLog;

import java.io.File;
import java.util.Arrays;

/**
 * Delos: scheduling help for the threads on the stream's latency path (issue #75). Always on while a stream
 * runs, no setting. Threads announce themselves with {@link #register(int)} when they start; the receive
 * thread of moonlight-common-c does it from latency_threads.c on its first frame. While the stream runs
 * ({@link #begin} to {@link #end()}) every registered thread is
 * <ul>
 * <li>kept on the big cores, see {@link ThreadPlacement}, and</li>
 * <li>part of one performance hint session (ADPF, Android 12+) with a target of 0.6 x the frame period, so the
 * governors do not clock the CPU down between frames; the receive thread's time per frame is reported to it,
 * see {@link WorkHintMath}.</li>
 * </ul>
 * The session exists only between begin and end. Sustained performance mode is deliberately not used: it
 * caps the clocks.
 */
public final class LatencyThreads {
    private static final Object lock = new Object();
    private static final TidRegistry registry = new TidRegistry();

    private static boolean active;
    // Read once per process: the kernel's CPU capacities do not change
    private static boolean maskRead;
    private static long coreMask;
    private static boolean affinityFailureLogged;

    private static Context appContext;
    private static long targetNanos;
    private static int frameRate;
    // False once the platform refused a session (no support, or an error): not asked again this stream
    private static boolean hintAvailable;
    private static boolean hintFailureLogged;
    // Read on every frame by the reporting thread, replaced under the lock
    private static volatile HintSink hint;
    // Only the thread that finishes the frames touches it
    private static final WorkHintMath.Window workWindow = new WorkHintMath.Window(WorkHintMath.REPORT_EVERY_FRAMES);

    private LatencyThreads() {
    }

    /**
     * The stream starts at [fps] frames per second: places every thread registered so far and opens the hint
     * session. Threads registered later join on arrival.
     */
    public static void begin(Context context, int fps) {
        synchronized (lock) {
            closeHintSession();
            workWindow.reset();
            active = true;
            appContext = context.getApplicationContext();
            frameRate = fps;
            targetNanos = WorkHintMath.targetNanos(fps);
            hintAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
            hintFailureLogged = false;
            readCoreMaskOnce();
            for (int tid : registry.live(LatencyThreads::isAlive)) applyAffinity(tid);
            rebuildHintSession();
        }
    }

    /**
     * The stream ends or pauses: closes the hint session and forgets every thread (they exit with the stream).
     * Safe to call repeatedly.
     */
    public static void end() {
        synchronized (lock) {
            active = false;
            closeHintSession();
            registry.clear();
            workWindow.reset();
            appContext = null;
        }
    }

    /** The stream's frame rate changed while it runs: moves the hint target to 0.6 x the new frame period. */
    public static void setFrameRate(int fps) {
        synchronized (lock) {
            if (!active || fps == frameRate) return;
            frameRate = fps;
            targetNanos = WorkHintMath.targetNanos(fps);
            workWindow.reset();
            HintSink sink = hint;
            if (sink == null) return;
            try {
                sink.updateTarget(targetNanos);
            } catch (Throwable t) {
                failHintSession(sink, t);
            }
        }
    }

    /**
     * The time one frame took the thread that finishes the frames, in ns. Cheap: frames are averaged and
     * handed to the session every few. Called from latency_threads.c for every decode unit.
     */
    static void reportWork(long nanos) {
        HintSink sink = hint;
        if (sink == null) return;
        long mean = workWindow.add(nanos);
        if (mean == 0) return;
        try {
            sink.report(mean);
        } catch (Throwable t) {
            failHintSession(sink, t);
        }
    }

    /**
     * Announces a thread of the stream path by its kernel id (Process.myTid(), HandlerThread.getThreadId()).
     * Call it once at thread start; repeated or invalid ids are ignored. May run before {@link #begin()}.
     */
    public static void register(int tid) {
        synchronized (lock) {
            if (!registry.add(tid)) return;
            if (!active) return;
            applyAffinity(tid);
            // A session takes its threads at creation, so the new one needs a new session
            rebuildHintSession();
        }
    }

    /** Entry point of latency_threads.c for the receive thread. */
    static void onNativeThread(int tid) {
        register(tid);
    }

    // Called with the lock held. The session covers the registered threads that still exist.
    private static void rebuildHintSession() {
        closeHintSession();
        if (!active || !hintAvailable) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        int[] tids = registry.live(LatencyThreads::isAlive);
        if (tids.length == 0) return;
        try {
            HintSink created = Adpf.create(appContext, tids, targetNanos);
            if (created == null) {
                hintAvailable = false;
                logHintFailure("the device offers no performance hint session");
                return;
            }
            hint = created;
        } catch (Throwable t) {
            hintAvailable = false;
            logHintFailure("creating the session failed: " + t);
        }
    }

    // Called with the lock held
    private static void closeHintSession() {
        HintSink sink = hint;
        hint = null;
        if (sink == null) return;
        try {
            sink.close();
        } catch (Throwable ignored) {
            // Already gone with its threads
        }
    }

    // A failure of the session in use ends it for this stream. One that comes from a session replaced meanwhile
    // (closed under the reporting thread by a new registration) is not one.
    private static void failHintSession(HintSink failed, Throwable cause) {
        synchronized (lock) {
            if (hint != failed) return;
            hintAvailable = false;
            closeHintSession();
            logHintFailure("the session failed: " + cause);
        }
    }

    // Called with the lock held; one line per stream
    private static void logHintFailure(String message) {
        if (hintFailureLogged) return;
        hintFailureLogged = true;
        LimeLog.warning("Delos latency threads: performance hint " + message);
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

    /** What the hint session offers the rest of this class, so that it never names an Android 12 type. */
    private interface HintSink {
        void report(long actualNanos);

        void updateTarget(long targetNanos);

        void close();
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static final class Adpf implements HintSink {
        private final PerformanceHintManager.Session session;

        private Adpf(PerformanceHintManager.Session session) {
            this.session = session;
        }

        /** A session for [tids], or null when the device has no hint support. */
        static HintSink create(Context context, int[] tids, long targetNanos) {
            PerformanceHintManager manager = context.getSystemService(PerformanceHintManager.class);
            if (manager == null) return null;
            PerformanceHintManager.Session session = manager.createHintSession(tids, targetNanos);
            return session != null ? new Adpf(session) : null;
        }

        @Override
        public void report(long actualNanos) {
            session.reportActualWorkDuration(actualNanos);
        }

        @Override
        public void updateTarget(long targetNanos) {
            session.updateTargetWorkDuration(targetNanos);
        }

        @Override
        public void close() {
            session.close();
        }
    }
}
