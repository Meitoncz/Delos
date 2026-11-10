package com.limelight.diag

import com.limelight.nvstream.jni.MoonBridge
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamEventsTest {
    @Test fun theNegotiatedLineNamesCodecChromaAndHdr() {
        val hevcHdr = MoonBridge.VIDEO_FORMAT_H265_MAIN10
        val line = StreamEvents.negotiated(hevcHdr, 1920, 1080, 120, 80_000)
        assertTrue(line, line.startsWith("Negotiated video: HEVC 4:2:0 10-bit (HDR) 1920x1080 at 120 fps, bitrate 80000 kbps"))
        val h264 = StreamEvents.negotiated(MoonBridge.VIDEO_FORMAT_H264, 1280, 720, 60, 20_000)
        assertTrue(h264, h264.startsWith("Negotiated video: H.264 4:2:0 8-bit (SDR) 1280x720 at 60 fps"))
    }
}
