package com.limelight.nvstream.http;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class RuntimeBitrateTest {
    @Test public void usesAppliedHostValueIncludingCeiling() throws Exception {
        assertEquals(500000, NvHTTP.parseRuntimeBitrate("<root status_code=\"200\"><bitrate>500000</bitrate></root>"));
        assertEquals(1000, NvHTTP.parseRuntimeBitrate("<root status_code=\"200\"><bitrate>1000</bitrate></root>"));
    }
    @Test public void xmlFailureIsNotMistakenForHttpSuccess() {
        assertThrows(HostHttpResponseException.class, () -> NvHTTP.parseRuntimeBitrate(
                "<root status_code=\"403\" status_message=\"Denied\"><bitrate>0</bitrate></root>"));
    }
    @Test public void rejectsMissingZeroAndInvalidValues() {
        for (String value : new String[]{"0","-1","nan","2147483648","1000001"}) {
            assertThrows(java.io.IOException.class, () -> NvHTTP.parseRuntimeBitrate(
                    "<root status_code=\"200\"><bitrate>"+value+"</bitrate></root>"));
        }
        assertThrows(Exception.class, () -> NvHTTP.parseRuntimeBitrate("<root status_code=\"200\"/>"));
    }
}
