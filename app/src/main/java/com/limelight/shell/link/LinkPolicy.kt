package com.limelight.shell.link

/**
 * Every number of the PyroWave link guidance (issue #18), in one place. The probe and wire-budget values are
 * the host's protocol (Vibepollo `docs/pyrowave-protocol.md`, "FEC-inclusive recommendations and UDP
 * calibration"); the pass thresholds are the ones that document lists; the hint values are Delos's own and
 * have to be calibrated on the Odin (docs/STREAM_QUALITY.md, "Link guidance").
 */
internal object LinkPolicy {
    // --- Probe protocol -------------------------------------------------------------------------------------------
    /** One probe sends for two seconds, paced in 1 ms groups. */
    const val PROBE_DURATION_MS = 2000
    const val WINDOW_MS = 100
    const val WINDOW_COUNT = PROBE_DURATION_MS / WINDOW_MS

    /** The host accepts 5000..3000000 kbps and bisection ends at this precision. */
    const val MIN_KBPS = 5000
    const val MAX_KBPS = 3_000_000
    const val STEP_KBPS = 5000

    /** The host accepts 256..1392 byte packets; 1392 is the streaming packet size. */
    const val MIN_PACKET_SIZE = 256
    const val MAX_PACKET_SIZE = 1392
    const val DEFAULT_PACKET_SIZE = MAX_PACKET_SIZE

    /** The UDP payload is `packetsize + 48`: ASCII token (32), big-endian sequence (4), filler. */
    const val PAYLOAD_EXTRA_BYTES = 48
    const val TOKEN_LENGTH = 32
    const val SEQUENCE_OFFSET = 32

    /** Each probe packet is charged `packetsize + 134` wire bytes (IPv6, encryption, Ethernet overhead). */
    const val WIRE_OVERHEAD_BYTES = 134

    /** The host waits at most this long for the client's token after announcing its UDP port. */
    const val HANDSHAKE_TIMEOUT_MS = 1500

    /** The client repeats its token this often until the first measured packet arrives. */
    const val HANDSHAKE_RETRY_MS = 100L

    /** Late packets of the last group still count; the delay is charged against the send schedule. */
    const val DRAIN_MS = 100L

    /** Whole probe request: handshake (1.5 s) + send (2 s) + margin. */
    const val REQUEST_TIMEOUT_MS = 6000L
    const val RECEIVE_BUFFER_BYTES = 8 * 1024 * 1024

    // --- A probe passes when (the reference client's rules) --------------------------------------------------------
    // Source: Nonary/moonlight-qt app/streaming/video/pyrowave/pyrowavelinkpolicy.h (PyroWaveLink::Result::capacityQualified,
    // the rule the Windows/Qt calibration searches with). The host document's stricter numbers (0.1 % loss, 1 % per window,
    // p99 <= 4 ms as a failure) made a clean 500 Mbps Wi-Fi 7 link fail on Android, where arrival times are read-time.
    /** Aggregate loss must stay below this (`lossLimitPercent = 2.0`, compared with `<`). */
    const val MAX_LOSS_PERCENT = 2.0

    /** Loss in any 100 ms window must stay below this (`windowLossLimitPercent = 5.0`, `<`). */
    const val MAX_WINDOW_LOSS_PERCENT = 5.0

    /** The host must have sent at least this share of the planned packets (`minimumSentShare = 0.98`). */
    const val MIN_SENT_SHARE = 0.98

    /** Delay growth, last window less first, must stay at or below this (`delayGrowthMs > 2.0` fails). Still a failure in the reference. */
    const val MAX_DELAY_GROWTH_MS = 2.0

    /** p99 transit variation above this is only a warning in the reference (`stable()` vs `capacityQualified()`): "a little jitter". */
    const val JITTER_WARNING_P99_MS = 4.0

    /** The host's sending duration may differ from [PROBE_DURATION_MS] by this share (`senderMs` within 0.98..1.02 x 2000). */
    const val DURATION_TOLERANCE = 0.02

    // --- Our own receiver (Android) -----------------------------------------------------------------------------------
    /** A loss with this much time between a datagram arriving and the loop being done with it points at our receive thread. */
    const val RECEIVER_STALL_MS = 5.0

    /** A receive buffer the OS granted below this cannot hold even a short stall at several hundred Mbps. */
    const val MIN_GOOD_RCVBUF_BYTES = 2 * 1024 * 1024

    // --- Search ----------------------------------------------------------------------------------------------------
    const val GROWTH_FACTOR = 1.25
    const val CONFIRM_MARGIN = 0.95
    const val CONFIRM_BACKOFF = 0.8
    const val CONFIRM_RUNS = 2
    const val MAX_SEARCH_PROBES = 40
    const val MAX_CONFIRM_ATTEMPTS = 32

    // --- Wire budget -----------------------------------------------------------------------------------------------
    /** Eight high-quality audio channels, audio parity and control traffic, reserved from every wire budget. */
    const val AUDIO_CONTROL_KBPS = 3572.0
    const val FRAME_HEADER_BYTES = 8
    const val MAX_FEC_BLOCK_SHARDS = 255

    // --- Ordinary codecs (H.264, HEVC, AV1): a share of the tested budget, capped by what the codec can use ----------------
    // Judgment, not measurement; calibrate on the Odin (docs/STREAM_QUALITY.md, "Link guidance for ordinary codecs").
    /**
     * An ordinary codec is recommended at most this share of the tested wire budget. The test measures a clean 2-second
     * burst; a live stream also carries FEC (Sunshine's default is 20 %, up to 100 %), audio and control, and its rate
     * control overshoots on scene cuts and keyframes (several times the average frame), so the stream keeps well clear of
     * the ceiling. PyroWave is exempt: its budget is the wire allowance the host reserves its own FEC from.
     */
    const val ORDINARY_BUDGET_SHARE = 0.65

    /**
     * The useful maximum is the app's default bitrate for the resolution and rate (Moonlight's table: 20 Mbps at 1080p60,
     * 40 at 1440p60, 80 at 4K60) times this factor. H.264 needs about half as much again as HEVC and AV1 for the same
     * picture; the usual picture-quality knee is about 40-60 Mbps at 1080p60 and 100-160 Mbps at 4K60, beyond which the
     * encoder only adds bits to noise. Automatic is assumed to end up on HEVC or AV1.
     */
    const val H264_CAP_FACTOR = 3.0
    const val HEVC_CAP_FACTOR = 2.0
    const val AV1_CAP_FACTOR = 2.0

    /** No ordinary codec is recommended more than this; hardware decoders (H.264 level 5.2 and common HEVC profiles) top out near it. */
    const val ORDINARY_MAX_KBPS = 150_000

    // --- Passive hint (Delos's own; calibrate on the Odin) ---------------------------------------------------------
    /** The rolling window of the in-stream verdict. */
    const val HINT_WINDOW_SECONDS = 5f

    /** The verdict needs at least this many seconds of samples at the current bitrate. */
    const val HINT_MIN_SECONDS = 3f

    /** Partial plus lost frames, as a share of the frames of the window, above which the link counts as dropping. */
    const val HINT_DROP_SHARE = 0.02

    /** The suggested bitrate is the target less [HINT_CUT_PER_SHARE] times the drop share, within these bounds. */
    const val HINT_CUT_PER_SHARE = 4.0
    const val HINT_MIN_CUT = 0.10
    const val HINT_MAX_CUT = 0.40

    /** Milliseconds per day, for the age text. */
    const val MS_PER_DAY = 86_400_000L
}
