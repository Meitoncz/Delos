package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;

import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Test;

public class PyroWaveFormatsTest {
    @Test
    public void sdr420OffersOnly420() {
        assertEquals(MoonBridge.VIDEO_FORMAT_PYROWAVE, PyroWaveFormats.offered(false, false));
    }

    @Test
    public void sdr444KeepsThe420Fallback() {
        assertEquals(MoonBridge.VIDEO_FORMAT_PYROWAVE | MoonBridge.VIDEO_FORMAT_PYROWAVE_444,
                PyroWaveFormats.offered(false, true));
    }

    @Test
    public void hdrNeverOffers444() {
        int expected = MoonBridge.VIDEO_FORMAT_PYROWAVE | MoonBridge.VIDEO_FORMAT_PYROWAVE_HDR10;
        assertEquals(expected, PyroWaveFormats.offered(true, false));
        assertEquals(expected, PyroWaveFormats.offered(true, true));
    }
}
