package com.limelight.preferences;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PyroWaveBitrateTest {
    // The official client's numbers: about 220 Mbps for 1080p60, 1.2x for HDR10, 5 Mbps steps
    @Test
    public void matchesTheOfficialGuidance() {
        assertEquals(225000, PyroWaveBitrate.recommendedKbps(1920, 1080, 60, false, false));
        assertEquals(445000, PyroWaveBitrate.recommendedKbps(1920, 1080, 120, false, false));
        assertEquals(530000, PyroWaveBitrate.recommendedKbps(1920, 1080, 120, false, true));
    }

    @Test
    public void resolutionsOutsideTheRegressionUseItsNearestEnd() {
        assertEquals(PyroWaveBitrate.recommendedKbps(1280, 720, 60, false, false),
                PyroWaveBitrate.recommendedKbps(640, 360, 60, false, false));
        assertEquals(PyroWaveBitrate.recommendedKbps(3840, 2160, 60, false, false),
                PyroWaveBitrate.recommendedKbps(7680, 4320, 60, false, false));
    }
}
