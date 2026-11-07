package com.limelight.binding.video;

import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Delos, debug builds only: tells whether the kernel drops UDP datagrams before an app reads them (receive
 * buffer full), which looks the same as loss on the air in the stream's loss counters. Reads the
 * system-wide UDP counters of /proc/net/snmp once a second while a stream runs and logs the increase to
 * DelosPyroDiag. Evidence only, nothing reacts to it. Android 10+ may deny apps that file; then one line says
 * so and the sampler stops.
 */
final class UdpDropSampler {
    private static final String TAG = "DelosPyroDiag";
    private static final File SNMP = new File("/proc/net/snmp");
    private static final long PERIOD_MS = 1000;

    private static Thread thread;

    private UdpDropSampler() {
    }

    /** The two counters that mean "dropped before the app read it". */
    static final class Counters {
        final long inErrors;
        final long rcvbufErrors;

        Counters(long inErrors, long rcvbufErrors) {
            this.inErrors = inErrors;
            this.rcvbufErrors = rcvbufErrors;
        }
    }

    /** The Udp row of a /proc/net/snmp listing (a header line, then the values), or null when it is not there. */
    static Counters parseSnmp(List<String> lines) {
        String[] header = null;
        for (String line : lines) {
            if (!line.startsWith("Udp:")) continue;
            String[] fields = line.trim().split("\\s+");
            if (header == null) {
                header = fields;
                continue;
            }
            long inErrors = field(header, fields, "InErrors");
            long rcvbufErrors = field(header, fields, "RcvbufErrors");
            return inErrors >= 0 && rcvbufErrors >= 0 ? new Counters(inErrors, rcvbufErrors) : null;
        }
        return null;
    }

    private static long field(String[] header, String[] values, String name) {
        if (header.length != values.length) return -1;
        for (int i = 1; i < header.length; i++) {
            if (!header[i].equals(name)) continue;
            try {
                return Long.parseLong(values[i]);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /** What changed between two readings, or null when nothing was dropped. */
    static String describeIncrease(Counters before, Counters after) {
        long rcvbuf = after.rcvbufErrors - before.rcvbufErrors;
        long errors = after.inErrors - before.inErrors;
        if (rcvbuf <= 0 && errors <= 0) return null;
        return "udp drops in the last second (whole system, /proc/net/snmp): receive buffer full +" + Math.max(rcvbuf, 0)
                + ", all input errors +" + Math.max(errors, 0);
    }

    static synchronized void start() {
        if (thread != null) return;
        thread = new Thread(UdpDropSampler::run, "Delos UDP drops");
        thread.setDaemon(true);
        thread.start();
    }

    static synchronized void stop() {
        if (thread == null) return;
        thread.interrupt();
        thread = null;
    }

    private static Counters read() throws Exception {
        List<String> lines = Files.readAllLines(SNMP.toPath(), StandardCharsets.US_ASCII);
        Counters counters = parseSnmp(lines);
        if (counters == null) throw new IllegalStateException("no Udp counters in " + SNMP);
        return counters;
    }

    private static void run() {
        Counters previous;
        try {
            previous = read();
        } catch (Exception e) {
            Log.i(TAG, "udp drop counters are not readable by the app (" + e + "); not sampled");
            return;
        }
        Log.i(TAG, "udp drop counters readable: receive buffer full " + previous.rcvbufErrors
                + ", input errors " + previous.inErrors + " (system-wide totals; only increases are logged)");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(PERIOD_MS);
                Counters now = read();
                String increase = describeIncrease(previous, now);
                if (increase != null) Log.i(TAG, increase);
                previous = now;
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Log.i(TAG, "udp drop counters stopped: " + e);
                return;
            }
        }
    }
}
