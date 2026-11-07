package com.limelight.nvstream.http;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * Delos: the host's answer to GET /pyrowave-udp-probe: the packets it planned and sent, and how long its sending
 * took. Reliable counts: they include packets lost on the way, which only this answer can reveal. A host failure
 * after the UDP handshake (status_code 500 with a message) arrives in the same XML and throws
 * {@link HostHttpResponseException}.
 */
public final class PyroWaveProbeResponse {
    public final long expected;
    public final long sent;
    public final double elapsedMs;

    PyroWaveProbeResponse(long expected, long sent, double elapsedMs) {
        this.expected = expected;
        this.sent = sent;
        this.elapsedMs = elapsedMs;
    }

    public static PyroWaveProbeResponse parse(String body) throws IOException, XmlPullParserException {
        // getXmlString verifies the host's XML status first (status_code 500 -> HostHttpResponseException).
        String expected = NvHTTP.getXmlString(body, "expected", true);
        String sent = NvHTTP.getXmlString(body, "sent", true);
        String elapsed = NvHTTP.getXmlString(body, "elapsedMs", true);
        try {
            long e = Long.parseLong(expected.trim());
            long s = Long.parseLong(sent.trim());
            double ms = Double.parseDouble(elapsed.trim());
            if (e < 0 || s < 0 || s > e || Double.isNaN(ms) || Double.isInfinite(ms) || ms < 0) {
                throw new NumberFormatException();
            }
            return new PyroWaveProbeResponse(e, s, ms);
        } catch (NumberFormatException ex) {
            throw new IOException("The host sent an invalid UDP probe result", ex);
        }
    }
}
