package com.limelight.nvstream.http;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * Delos: what a paired Vibepollo advertises in its HTTPS /serverinfo about PyroWave link measurement
 * (docs/pyrowave-protocol.md, "Negotiation" and "FEC-inclusive recommendations and UDP calibration"). Hosts that
 * do not know PyroWave, and unpaired (HTTP) answers, leave every field out: {@link #NONE}.
 */
public final class PyroWaveHostInfo {
    public static final PyroWaveHostInfo NONE = new PyroWaveHostInfo(0, 0, 0, 0, 0, -1, -1);

    /** The wire budget conversion the host uses (`PyroWaveWireBudgetVersion`); this client implements 1. */
    public final int wireBudgetVersion;
    /** `PyroWaveUdpProbeVersion`: 1 = the authenticated UDP probe at /pyrowave-udp-probe. */
    public final int udpProbeVersion;
    /** `PyroWaveUdpHandshakeVersion`: 1 = the client sends its token to the host's announced port first. */
    public final int udpHandshakeVersion;
    /** The host's routed wired link speed to this client in Mbps; 0 when unknown or wireless (its transmit side only). */
    public final int hostLinkMbps;
    /** `PyroWaveBandwidthProbeBytes`: the legacy HTTPS download probe's size (not used for calibration). */
    public final long bandwidthProbeBytes;
    /** `PyroWaveCriticalFecPercentage` (0..255, 0 = off), -1 when the host did not say. */
    public final int criticalFecPercent;
    /** `PyroWaveMinParityShards`, -1 when the host did not say. */
    public final int minParityShards;

    PyroWaveHostInfo(int wireBudgetVersion, int udpProbeVersion, int udpHandshakeVersion, int hostLinkMbps,
                     long bandwidthProbeBytes, int criticalFecPercent, int minParityShards) {
        this.wireBudgetVersion = wireBudgetVersion;
        this.udpProbeVersion = udpProbeVersion;
        this.udpHandshakeVersion = udpHandshakeVersion;
        this.hostLinkMbps = hostLinkMbps;
        this.bandwidthProbeBytes = bandwidthProbeBytes;
        this.criticalFecPercent = criticalFecPercent;
        this.minParityShards = minParityShards;
    }

    /**
     * The UDP link test needs the probe and wire budget versions this client implements, and the FEC numbers
     * the conversion uses. Anything else (older host, newer protocol) is "not supported".
     */
    public boolean supportsLinkTest() {
        return udpProbeVersion == 1 && wireBudgetVersion == 1
                && criticalFecPercent >= 0 && criticalFecPercent <= 255
                && minParityShards >= 0 && minParityShards <= 255;
    }

    /** The host sent a probe version, but not one this client knows (a newer Vibepollo than Delos). */
    public boolean linkTestNeedsNewerClient() {
        return udpProbeVersion > 1 || wireBudgetVersion > 1;
    }

    public static PyroWaveHostInfo parse(String serverInfo) throws IOException, XmlPullParserException {
        return new PyroWaveHostInfo(
                intField(serverInfo, "PyroWaveWireBudgetVersion", 0),
                intField(serverInfo, "PyroWaveUdpProbeVersion", 0),
                intField(serverInfo, "PyroWaveUdpHandshakeVersion", 0),
                Math.max(0, intField(serverInfo, "PyroWaveHostLinkMbps", 0)),
                longField(serverInfo, "PyroWaveBandwidthProbeBytes", 0),
                intField(serverInfo, "PyroWaveCriticalFecPercentage", -1),
                intField(serverInfo, "PyroWaveMinParityShards", -1));
    }

    private static int intField(String serverInfo, String name, int fallback) throws IOException, XmlPullParserException {
        String value = NvHTTP.getXmlString(serverInfo, name, false);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longField(String serverInfo, String name, long fallback) throws IOException, XmlPullParserException {
        String value = NvHTTP.getXmlString(serverInfo, name, false);
        if (value == null) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
