package com.limelight.binding.video;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TidRegistryTest {
    @Test
    public void keepsRegistrationOrderWithoutDuplicates() {
        TidRegistry registry = new TidRegistry();
        assertTrue(registry.add(30));
        assertTrue(registry.add(10));
        assertFalse(registry.add(30));
        assertTrue(registry.add(20));
        assertArrayEquals(new int[]{30, 10, 20}, registry.live(tid -> true));
    }

    @Test
    public void rejectsInvalidIds() {
        TidRegistry registry = new TidRegistry();
        assertFalse(registry.add(0));
        assertFalse(registry.add(-5));
        assertEquals(0, registry.size());
    }

    @Test
    public void liveDropsThreadsThatHaveExitedForGood() {
        TidRegistry registry = new TidRegistry();
        registry.add(1);
        registry.add(2);
        registry.add(3);
        assertArrayEquals(new int[]{1, 3}, registry.live(tid -> tid != 2));
        // Forgotten, not just skipped: a later check does not see it again
        assertArrayEquals(new int[]{1, 3}, registry.live(tid -> true));
        // and it can be registered again, as a recycled id would be
        assertTrue(registry.add(2));
    }

    @Test
    public void clearForgetsEverything() {
        TidRegistry registry = new TidRegistry();
        registry.add(7);
        registry.clear();
        assertEquals(0, registry.size());
        assertTrue(registry.add(7));
    }
}
