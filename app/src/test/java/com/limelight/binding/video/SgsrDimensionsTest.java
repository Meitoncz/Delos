package com.limelight.binding.video;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SgsrDimensionsTest {
    @Test
    public void native1080pLevelOneUses1440pInternalTarget() {
        SgsrDimensions.Plan plan = SgsrDimensions.plan(1920, 1080, 1920, 1080, 8192, 1);
        assertTrue(plan.isSupersampling());
        assertEquals(2560, plan.internalWidth);
        assertEquals(1440, plan.internalHeight);
    }

    @Test
    public void native1080pLevelTwoUses4kInternalTarget() {
        SgsrDimensions.Plan plan = SgsrDimensions.plan(1920, 1080, 1920, 1080, 8192, 2);
        assertTrue(plan.isSupersampling());
        assertEquals(3840, plan.internalWidth);
        assertEquals(2160, plan.internalHeight);
    }

    @Test
    public void onePixelAspectFitDifferenceStillUsesSupersampling() {
        SgsrDimensions.Plan plan = SgsrDimensions.plan(1920, 1080, 1920, 1079, 8192, 2);
        assertTrue(plan.isSupersampling());
        assertEquals(3840, plan.internalWidth);
        assertEquals(2156, plan.internalHeight);
    }

    @Test
    public void lowerResolutionUsesDisplaySizedUpscaling() {
        SgsrDimensions.Plan plan = SgsrDimensions.plan(1280, 720, 1920, 1080, 8192, 2);
        assertFalse(plan.isSupersampling());
        assertEquals(1920, plan.internalWidth);
        assertEquals(1080, plan.internalHeight);
    }

    @Test
    public void native4kNeverAttempts8k() {
        SgsrDimensions.Plan plan = SgsrDimensions.plan(3840, 2160, 3840, 2160, 16384, 2);
        assertFalse(plan.isSupersampling());
        assertEquals(3840, plan.internalWidth);
        assertEquals(2160, plan.internalHeight);
    }

    @Test
    public void gpuTextureLimitDisablesSupersampling() {
        SgsrDimensions.Plan plan = SgsrDimensions.plan(1920, 1080, 1920, 1080, 2048, 2);
        assertFalse(plan.isSupersampling());
    }
}
