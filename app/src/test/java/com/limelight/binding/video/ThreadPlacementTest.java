package com.limelight.binding.video;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ThreadPlacementTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void threeClusterSocKeepsGoldAndPrimeOnly() {
        // 3 little, 2 gold, 2 gold, 1 prime (the layout of a Snapdragon 8 Gen 2)
        int[] capacities = {300, 300, 300, 770, 770, 880, 880, 1024};
        assertEquals(0xF8L, ThreadPlacement.bigCoreMask(capacities));
    }

    @Test
    public void littleCoresAreNeverIncluded() {
        // 4 little, 3 mid, 1 prime
        int[] capacities = {400, 400, 400, 400, 800, 800, 800, 1024};
        long mask = ThreadPlacement.bigCoreMask(capacities);
        assertEquals(0xF0L, mask);
        assertEquals(0, mask & 0x0FL);
    }

    @Test
    public void thresholdIsSixtyPercentOfTheBiggestCore() {
        assertEquals(0b1100L, ThreadPlacement.bigCoreMask(new int[]{100, 599, 600, 1000}));
        assertEquals(0b1110L, ThreadPlacement.bigCoreMask(new int[]{100, 600, 600, 1000}));
    }

    @Test
    public void identicalCoresNeedNoPlacement() {
        assertEquals(0, ThreadPlacement.bigCoreMask(new int[]{1024, 1024, 1024, 1024}));
    }

    @Test
    public void aSingleBigCoreIsNotPinned() {
        assertEquals(0, ThreadPlacement.bigCoreMask(new int[]{300, 300, 300, 1024}));
    }

    @Test
    public void missingOrUnreadableCapacityDataSkipsPlacement() {
        assertEquals(0, ThreadPlacement.bigCoreMask(null));
        assertEquals(0, ThreadPlacement.bigCoreMask(new int[0]));
        assertEquals(0, ThreadPlacement.bigCoreMask(new int[]{300, 0, 800, 800, 1024}));
        assertEquals(0, ThreadPlacement.bigCoreMask(new int[]{300, -1, 800, 800, 1024}));
    }

    @Test
    public void moreCoresThanTheMaskHoldsSkipsPlacement() {
        int[] capacities = new int[65];
        java.util.Arrays.fill(capacities, 500);
        capacities[64] = 1024;
        assertEquals(0, ThreadPlacement.bigCoreMask(capacities));
    }

    @Test
    public void readsTheCapacityFilesOfEveryCpu() throws IOException {
        File cpu = temp.newFolder("cpu");
        writeCapacity(cpu, 0, "300\n");
        writeCapacity(cpu, 1, "300\n");
        writeCapacity(cpu, 2, "1024\n");
        assertArrayEquals(new int[]{300, 300, 1024}, ThreadPlacement.readCapacities(cpu));
    }

    @Test
    public void aCpuWithoutCapacityDataMakesTheWholeReadFail() throws IOException {
        File cpu = temp.newFolder("cpu");
        writeCapacity(cpu, 0, "300");
        assertEquals(true, new File(cpu, "cpu1").mkdirs());
        assertNull(ThreadPlacement.readCapacities(cpu));
    }

    @Test
    public void noCpuDirectoriesIsNoData() throws IOException {
        assertNull(ThreadPlacement.readCapacities(temp.newFolder("empty")));
    }

    private static void writeCapacity(File cpuDir, int cpu, String text) throws IOException {
        File dir = new File(cpuDir, "cpu" + cpu);
        assertEquals(true, dir.mkdirs());
        Files.write(new File(dir, "cpu_capacity").toPath(), text.getBytes(StandardCharsets.US_ASCII));
    }
}
