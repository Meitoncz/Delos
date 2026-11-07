package com.limelight.binding.video;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Delos: which CPU cores the latency-critical stream threads may run on. A Snapdragon 8 Gen 2 has three
 * clusters (little, gold, prime); the scheduler can park the video receive thread on a little core, where one
 * frame's reassembly and decode takes several times longer. The mask returned here covers the high-capacity
 * cores only and the kernel still picks the core inside it, so a throttled prime core does not trap a thread.
 */
final class ThreadPlacement {
    // Cores with at least this share of the biggest core's capacity are "big": 60 %. The little cores of
    // current Arm big.LITTLE SoCs sit at about 30-40 %, the gold cores at 70 % and above.
    private static final int BIG_CORE_PERCENT = 60;
    // CPU masks travel as one long
    private static final int MAX_CORES = 64;
    private static final String CPU_DIR = "/sys/devices/system/cpu";

    private ThreadPlacement() {
    }

    /**
     * The mask (bit i = CPU i) of the big cores for [capacities] (the kernel's cpu_capacity of CPU 0, 1, ...),
     * or 0 when no placement should be applied: no data, an unreadable entry (a value of 0 or less), more cores
     * than the mask holds, identical cores, or fewer than two big cores (a single core would make the thread
     * compete with everything else pinned there).
     */
    static long bigCoreMask(int[] capacities) {
        if (capacities == null || capacities.length == 0 || capacities.length > MAX_CORES) return 0;
        int max = 0;
        for (int capacity : capacities) {
            if (capacity <= 0) return 0;
            max = Math.max(max, capacity);
        }
        long mask = 0;
        int big = 0;
        for (int cpu = 0; cpu < capacities.length; cpu++) {
            if ((long) capacities[cpu] * 100 >= (long) max * BIG_CORE_PERCENT) {
                mask |= 1L << cpu;
                big++;
            }
        }
        // All cores big: nothing to separate. A lone big core: see above.
        if (big < 2 || big == capacities.length) return 0;
        return mask;
    }

    /** The mask for this device, or 0 to skip the placement; reads the kernel's CPU capacity files. */
    static long systemBigCoreMask() {
        return bigCoreMask(readCapacities(new File(CPU_DIR)));
    }

    /** The capacity of every CPU directory cpu0, cpu1, ... under [cpuDir], or null when any value is missing. */
    static int[] readCapacities(File cpuDir) {
        int[] values = new int[MAX_CORES];
        int count = 0;
        while (count < MAX_CORES && new File(cpuDir, "cpu" + count).isDirectory()) {
            File file = new File(cpuDir, "cpu" + count + "/cpu_capacity");
            try {
                String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.US_ASCII).trim();
                values[count] = Integer.parseInt(text);
            } catch (Exception e) {
                return null;
            }
            count++;
        }
        if (count == 0) return null;
        int[] result = new int[count];
        System.arraycopy(values, 0, result, 0, count);
        return result;
    }
}
