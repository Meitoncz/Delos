package com.limelight.binding.video;

import com.limelight.nvstream.jni.MoonBridge;

/** Which PyroWave video-format flags Delos offers the host. */
public final class PyroWaveFormats {
    private PyroWaveFormats() {}

    /**
     * The flags to offer. moonlight-common-c picks the richest one the host also supports
     * (RtspConnection.c), so the 4:2:0 flag always stays offered as the fallback and the
     * renderer follows the negotiated format. HDR10 is only ever offered as 4:2:0: the P010
     * output of PyroWaveRenderer cannot take 4:4:4, so HDR10_444 is never offered.
     */
    public static int offered(boolean hdr, boolean chroma444) {
        int formats = MoonBridge.VIDEO_FORMAT_PYROWAVE;
        if (hdr) {
            formats |= MoonBridge.VIDEO_FORMAT_PYROWAVE_HDR10;
        } else if (chroma444) {
            formats |= MoonBridge.VIDEO_FORMAT_PYROWAVE_444;
        }
        return formats;
    }
}
