# Live bitrate, MediaCodec pacing and SGSR

Historical implementation and measurement record, requested 2026-10-26. Live bitrate, MediaCodec
pacing and SGSR behavior are described below; dated acceptance statements are not current task status.
Current verification and follow-up measurements live in [#19](https://github.com/Meitoncz/Delos/issues/19).
Feature provenance is preserved in the [README](../README.md#feature-provenance).

## Runtime bitrate

The stream menu's **Bitrate** page (a popover above the menu bar) previews 1–1000 Mbps and sends **Apply** over the existing pinned,
client-certificate HTTPS connection to Vibepollo's `/bitrate?bitrate=<kbps>`. No common-c protocol
extension or host modification is needed. Requests run off the UI thread and only one runs at a time.
The host's XML status must indicate success, and its returned bitrate is the authoritative value.
The installed Vibepollo 2.0.1 implementation caps this command at 500 Mbps (and its configured ceiling).
The menu reports the applied value rather than pretending that an unclamped request was accepted.

Changes affect the current stream only; profile/per-game defaults are not overwritten. Unsupported
hosts, missing sessions, permission errors and connection failures report a message without claiming
success. The stream stays connected. Source: Vibepollo `src/nvhttp.cpp::setBitrate` at installed host
commit `66e169c325dab73e4e780fe34ac3c613af4cdf52`.

## Fast V-Sync and asynchronous decoding

**Fast V-Sync (Nano Pacer)** is a separate `frame_pacing` choice; the existing default remains Latency.
It estimates a stable cadence at the lower of stream/display rate, handles fractional display rates,
maps PTS to monotonic time and drains stale output while waiting. Delos bounds the final spin to
100 microseconds and makes the wait interruptible. Existing Balanced/LFR/Cap FPS/Smoothness/Warp paths
remain separate; Fast V-Sync takes precedence over the optional Balanced LFR switch. A frame that
arrives a full period or more late is presented at once instead of waiting another period.

Decode time and total latency in the statistics are stamped when a frame leaves the codec, before any
pacer wait, so they do not include time spent pacing. The 1% low FPS is measured when frames are handed
to the display (after `releaseOutputBuffer`), so it reflects submission cadence, not the panel's
presentation times.

**Asynchronous decoding** is an opt-in Stream setting. MediaCodec callbacks feed bounded input/output
queues instead of polling the codec. The existing submit/render/recovery threads consume those queues.
Generation guards and a closed acceptance gate prevent old indices crossing flush/reset/restart.
Flush resumes callbacks with `start()`, as required by MediaCodec; stop/reset/release detach callbacks.
Callback errors are handed to the render loop's recovery handling rather than swallowed or handled
by blocking the callback thread. Decode timestamp bookkeeping is synchronized between submit/output.
The output queue holds 32 frames, above any codec's own buffer count, so the codec's buffer pool is the
backpressure as in synchronous decoding. If the renderer stalls long enough to fill it (or the input
queue), the dropped output frame or left-out input index is counted and logged, not silent.

Both are MediaCodec features (H.264, HEVC, AV1); PyroWave remains on its separate native Vulkan path.
These settings also participate in the existing per-game profile/launch snapshot mechanism.

The callback adapter and pacer are adapted from SicarioGaming/Starwire at
`87624983e78782ac6346c0f7369f68f39dfe1082` (GPL-3.0), which credits **derflacco / Artemide** for the
original async decoding and Nano Pacer. Reference flush-order regression tests retain that provenance.

## PyroWave diagnostics

PyroWave decodes and presents on moonlight-common-c's video receive thread, so a slow GPU frame also
stops the UDP socket from being read. The overlay's "Latency" is only the control-channel round trip and
"Lost frames" counts whole frames, so neither shows this. The full statistics panel (not the compact line)
therefore has a **Backlog** section for PyroWave, averaged over one-second windows:

- **Stall (recv thread) avg/max**: time spent inside one frame's submit call, i.e. how long the socket
  was not read. Several times the frame interval (8 ms at 120 FPS) means the kernel buffer can overflow.
- **Frame age at submit avg/max**: time since the frame's first packet arrived when the renderer starts on
  it. Growing values mean frames queue up behind a slow previous frame.
- **Receive to present avg/max**: the same age once the frame has been handed to the presentation queue.
- **Partial frames /s**: frames that arrived with missing packets (zero-filled). **Skipped /s**: frames
  the renderer dropped.

Reading it: high or growing stall and frame age = receiver/GPU backlog (lower bitrate or chroma
resolution, or a faster renderer); partial frames with a low stall = packets lost on the air or in the
network. In debug builds the same numbers, plus received KB/s, bitrate, chroma mode and the per-stage
waits (previous frame, swapchain image, submit+present), are logged once a second under the logcat tag
`DelosPyroDiag`, with a `start:` line (bitrate, chroma requested/negotiated, directSubmit, capability) and
the swapchain image count, present mode and pre-transform when it is created (with the present modes the surface
offers), and in the `present=` / `images=` / `preTransform=` part of the per-second line. The UDP receive buffer is set and
checked inside moonlight-common-c, which logs a warning only when the kernel clamps it; Delos reads the
grant back itself (see "Stream thread scheduling" below). Suggested A/B: 4:2:0 at 225 Mbps, 4:4:4 at 225,
4:4:4 at 270, and 4:4:4 at 150.

## Stream thread scheduling and performance hints (#75)

Always on while a stream runs, with no setting (decided 2026-11-07). It changes where and how urgently the
stream's threads run, not what they do; nothing in moonlight-common-c is modified (`LatencyThreads.java`,
`latency_threads.c`).

**What it does**

- **Priority.** The video receive thread (`VideoRecv`; for PyroWave it also decodes and presents) is raised
  to nice -8 (`THREAD_PRIORITY_URGENT_DISPLAY`, what the MediaCodec render thread already used) from its
  first decode-unit callback. `InputSend` and `ControlRecv` ran at nice 0 and never call into Delos, so they
  are found by their thread names (`/proc/self/task/*/comm`) when the connection has started and raised the
  same way; `VideoRecv` is found too, for decoders that are not direct-submit (there `VideoDec` is the thread
  that calls back).
- **Big cores.** Threads that announce themselves (`LatencyThreads.register(tid)`: the receive thread, the
  MediaCodec `Video - Renderer` thread, the `CodecAsync` callback thread and the `Video - SGSR` GL thread)
  get a `sched_setaffinity` mask of the high-capacity cores: every core with at least 60 % of the biggest
  core's `cpu_capacity` (the gold and prime cores of a Snapdragon 8 Gen 2). The kernel still picks the core
  inside the mask. Nothing is pinned to the little cores, and placement is skipped when any capacity value is
  unreadable, all cores are alike, or fewer than two cores qualify. The capacities and the mask are logged once
  per process (`Delos latency threads: cpu_capacity [...]`).
- **Performance hint session (ADPF, Android 12+).** One `PerformanceHintManager` session covers those
  threads, with a target work duration of 0.6 x the frame period (5.0 ms at 120 FPS), and exists only from the
  decoder's start to its stop or pause. The receive thread's time per decode unit (measured natively around
  the submit callback, so MediaCodec and PyroWave are both covered) is reported as the mean of four frames. A
  session takes its threads at creation, so it is re-created when a thread registers later (a codec reset
  starting a new `CodecAsync` or SGSR thread). A device without support or any API failure is logged once and
  the stream goes on without it. `setSustainedPerformanceMode` is deliberately not used: it caps clocks.
  The stream's frame rate is fixed for a connection today; `LatencyThreads.setFrameRate` moves the target
  if a live change is ever added.
- **Receive buffer and UDP drops (evidence only).** On the first frame the receive thread logs the receive
  buffer of every UDP socket of the process to `DelosPyroDiag`; the video socket is the largest. The kernel
  reports twice the granted size, so about 5800 KB (23000 KB for PyroWave) is a full grant and less means the
  kernel clamped the request. The socket's descriptor is not exposed by moonlight-common-c, hence the scan.
  Debug builds also read `/proc/net/snmp` once a second and log increases of `RcvbufErrors` (kernel buffer
  full) and `InErrors` to `DelosPyroDiag` (whole system, not only Delos); Android may deny apps that file,
  and one line says so.

**Cost.** Higher and steadier CPU clocks for the stream's threads: more battery use and heat than before,
most visible on a long 120 FPS session. The whole path is per stream, so nothing runs in the menus. If the
device throttles, the hint is a request, not a guarantee.

**How it is verified (needs the device).** Compare a session before and after on the same stream:

- Perfetto: `sched` and CPU frequency counters (the receive thread on a gold or prime core, no little-core
  residency, no long runnable gaps), GPU frequency counters (the hint is expected to keep clocks from dropping
  between frames).
- `DelosPyroDiag` per second: stall max, frame age at submit and receive-to-present p95/p99 should fall
  (the mean may not move: the expected gain is in the tail). The `DelosLatency` lines of the render-side work
  in #75 give the same view for the MediaCodec path.
- The `DelosThreads` lines (`raised to nice -8: ...`) and `Delos latency threads:` lines in logcat show what
  was applied.

**Not done, and why**

- `recvmmsg` batching of the UDP reads: needs changes inside moonlight-common-c's receive loop (the fork),
  and the saving (0.2-0.7 ms per frame) is only a guess until a trace shows per-packet syscall cost.
- Splitting receive from decode for PyroWave: a structural change to the single-threaded reassembly queue; it
  only avoids loss when a decode exceeds a frame interval, which the stall numbers must show first.
- The input path (1 ms mouse batching, reliable controller packets): protocol behaviour, host-gated, and for
  a handheld with its own controller the path is already short. Only `InputSend` and `ControlRecv` priority
  was changed.
- Socket marking (DSCP on the video socket): lives inside moonlight-common-c, and the downlink class is
  decided by the host's marking; only the receive buffer and drop counters were made observable.

## PyroWave link guidance (#18)

PyroWave needs hundreds of Mbps and degrades into blur, not stalls, when the link falls short, so Delos
tells the user what the link carries instead of guessing. The **Test link** and the recommendation work for **every
codec** (H.264, HEVC, AV1, automatic and PyroWave): the probe measures the network, not the codec. Only the passive
in-stream hint and the Link statistics block are PyroWave-only (see "Passive hint"). Everything is recommendation
only: Delos never changes the bitrate setting by itself and never sends `pyrowavePaceMbps` or `pyrowaveLinkMbps` to
the host (no JNI plumbing for them). Code: `app/src/main/java/com/limelight/shell/link/` (pure logic and the probe
client), the rows in `shell/screens/LinkGuidanceRows.kt`.

1. **Test link** (Settings, Stream tab, under the bitrate row): an active UDP probe against the PC. It runs
   only when the user presses it, never automatically and never during a stream (the host refuses probes
   while a stream session exists, answering HTTP 400 "Stop streaming before testing bandwidth"). A cancellable
   portal says what is being tested ("Testing 250 Mbps · stay on Wi-Fi · B cancels").
2. **Recommended bitrate with a one-line reason** (Settings bitrate row, per-game bitrate row, stream menu
   Bitrate page): `Recommended: 225 Mbps · link carries up to 665 Mbps` (both numbers when the link has headroom; the
   help panel says it in one sentence: "Tested yesterday on Wi-Fi: carries up to 665 Mbps; 225 Mbps is enough for
   1080p60 PyroWave 4:2:0."). Settings also offers **Use recommended
   bitrate**, which fills the bitrate on the user's press; the stream menu's row fills the slider on A and
   still needs Apply.
3. **Passive in-stream hint**: a rolling ~5 s window of the renderer's own counters. When the link drops
   frames the recommendation becomes `Recommended: 135 Mbps · drops frames at 225 Mbps` (same row) and the
   help line says so.
4. **Link block** in the full statistics panel: received rate against the target bitrate, partial and lost
   frames per second, and a one-line verdict.

### The probe, as implemented

Source of truth: Vibepollo `docs/pyrowave-protocol.md` ("Negotiation", "FEC-inclusive recommendations and UDP
calibration") and the host source (`src/pyrowave_udp_probe.h`, `nvhttp.cpp::getPyroWaveUdpProbe`); the
conversion is a port of `pyrowavebandwidth.h` (Nonary/moonlight-qt, identical in Vibepollo).

- **Host support.** A paired HTTPS `/serverinfo` must carry `PyroWaveUdpProbeVersion=1` and
  `PyroWaveWireBudgetVersion=1`, plus `PyroWaveCriticalFecPercentage` (0..255) and `PyroWaveMinParityShards`.
  `PyroWaveUdpHandshakeVersion=1` selects the handshake; `PyroWaveHostLinkMbps` (the host's routed wired
  transmit speed, 0 for Wi-Fi/unknown) is read as a ceiling; `PyroWaveBandwidthProbeBytes` is parsed but only
  the legacy HTTPS download probe uses it, which Delos does not run. `PyroWaveHostInfo` parses them on every poll
  into `ComputerDetails.pyroWave`; the test re-reads `/serverinfo` fresh. A PC without the fields, or with a
  newer protocol version than this Delos knows, gets a sentence instead of a test ("This PC's Vibepollo cannot
  test the link yet. Update it."); the static recommendation is still shown.
- **Request.** `GET /pyrowave-udp-probe?kbps=<5000..3000000>&port=<receiver port>&packetsize=1392&token=<32
  lowercase hex>&handshake=1` over the existing pinned, client-certificate HTTPS connection to the PC's
  address (resolved once, IPv4 preferred; the UDP receiver and the HTTPS peer use the same address). The token
  is fresh per probe (16 random bytes). Packet size is 1392, the streaming packet size.
- **Handshake.** The host binds its UDP sender, sends a 32-byte token warm-up, flushes the HTTP headers with
  `X-PyroWave-Udp-Port`, and keeps the XML body pending. Delos sends the 32-byte token from its receiving
  socket to that port every 100 ms until the first measured packet arrives; the host waits at most 1500 ms. A
  host failure after the headers arrives as XML with `status_code=500` (a message containing "handshake" is
  reported as blocked UDP). Hosts without handshake support get the legacy probe (no `handshake`, source
  checked by address only).
- **Probe.** Two seconds, paced in 1 ms groups, every packet charged `packetsize + 134` wire bytes, UDP
  payload `packetsize + 48`: ASCII token (bytes 0-31), big-endian sequence (32-35), filler. The response XML
  gives `expected`, `sent`, `elapsedMs`; `expected` must equal `kbps * 2000 / (8 * (packetsize + 134))`
  (integer division) or the answer is rejected. Only exact-size datagrams from the PC (and, with the
  handshake, from the announced port) that start with the token count; duplicates and foreign packets never
  raise delivery. After the response Delos waits 100 ms for late packets.
- **Scoring** (`scoreProbe`). Arrival times come from `System.nanoTime()` stamped right after `receive()`
  returns on a dedicated thread at `THREAD_PRIORITY_URGENT_AUDIO` (the Android socket has no kernel timestamps).
  Delay is relative: transit = arrival less the packet's slot `ceil((seq + 1) * 2000 / expected) - 1` ms, so the
  clocks' offset cancels. The rules are the **reference client's**
  (`pyrowavelinkpolicy.h`, `capacityQualified()`), not the stricter numbers in the host document. A probe passes
  when: the host sent at least 98 % of the planned packets (and not more); aggregate loss < 2 %; loss < 5 % in each
  of the 20 sequence windows (100 ms); delay growth (mean transit of the last window less the first) <= 2 ms; the
  host's `elapsedMs` is within 2 % of 2000. **p99 transit variation above 4 ms is only a warning** ("Stable, a little
  jitter", shown in the success toast); it never fails a probe. Unsent packets count as lost.
- **Receiver.** The receive loop allocates nothing, logs nothing and takes no locks: buffer, packet and the
  score array exist before the first datagram; the receive buffer is requested at 8 MiB and the granted size is
  recorded (and logged in debug builds); scoring runs after the loop. The loop's busiest datagram handling time and the
  granted buffer let a loss failure be attributed to this device ("This device could not receive 450 Mbps that
  fast") when the granted buffer is under 2 MiB or one datagram kept the loop busy for over 5 ms.
- **Debug log.** Debug builds log one line per probe under the tag `DelosLinkTest`: rate, expected, sent, received,
  granted rcvbuf, loss, worst 100 ms window, p99, growth, host elapsed, longest loop stall, authenticated packets, and
  PASS or FAIL with the rule.
- **Search** (`searchLink`, pure, tested with fake paths). Start at the selected bitrate (the current bitrate
  setting, rounded down to 5 Mbps), bounded by `PyroWaveHostLinkMbps` and 3 Gbps. Grow by x1.25 (rounded down to
  5 Mbps) while probes pass; on a failure, bisect the passing/failing bracket down to 5 Mbps (a failing start
  bisects downwards to the 5 Mbps floor). Take 95 % of the best pass (rounded down to 5 Mbps) and measure it
  twice afresh; if either fails, lower the rate by 20 % and test again (at most 32 attempts, down to 5 Mbps).
  A rate that never confirms gives no result, and the message then names the last probe's real reason ("Lost 3.0% of the test packets at 450 Mbps", "This device could not receive 450 Mbps that fast", ...) and whether any rate passed once. Cancelling (B) abandons everything measured. The confirmed rate
  is a **total wire allowance**, the same unit as the bitrate setting.
- **Failure reasons** (all readable, no raw errors): host too old or newer protocol, stream active, pairing
  denied (403), UDP blocked (no packets / handshake timeout), network error, timeout, host error, invalid
  result, and the search-level reasons (host could not send fast enough, link lost packets, link too unsteady).
- **Stored result.** The last confirmed test per PC and kind of network (Wi-Fi / Ethernet / other; an SSID needs
  a location permission, so the transport is the cheap stand-in) in the `delos_link_tests` preferences, with its
  date. It is shown with its age ("Tested yesterday on Wi-Fi") and only feeds the recommendation; it is never
  silently re-applied, and a network that was not tested shows "Not tested on Wi-Fi yet".

### Wire budget and recommendation

The host reserves one maximum feasible critical-FEC block (the largest critical count with
`critical + max(ceil(critical * fec / 100), minParity) <= 255` shards), packet rounding, IPv6, encryption,
Ethernet overhead and 3572 kbps for audio and control from every wire allowance. `WireBudget` ports
`pyrowavebandwidth.h` (`maxCriticalParity`, `imageBytes`, `wireBytes`, `imageKbps`, `totalKbps`) and is only used
to say how much picture a tested budget carries ("about 102 Mbps of picture after FEC and overhead").

`recommend(...)` = `min(PyroWaveBitrate.recommendedKbps, tested wire budget, PyroWaveHostLinkMbps)`, rounded
down to 5 Mbps, never under 5 Mbps; the live drop hint can only lower it further. The bitrate setting is a total
wire allowance (Delos sends it as `configuredBitrateKbps`), as is the probe's rate, so they compare directly.
Reasons: `not tested on this link`, `link carries up to N Mbps` (headroom) or `link carries N Mbps` (not enough),
`the PC's link is N Mbps`, `drops frames at N Mbps`.

### Ordinary codecs (H.264, HEVC, AV1)

Host support is the same check as for PyroWave: the paired `/serverinfo` must advertise `PyroWaveUdpProbeVersion=1`. The
host source shows the probe handler itself (`getPyroWaveUdpProbe`) asks for nothing but a paired client and no running
stream, and no PyroWave setting; but the `/serverinfo` fields are only written when the host's PyroWave encoder works on its
capture adapter, so a host without PyroWave support does not advertise the probe and Delos does not try it (a host
that does not advertise gets "cannot test the link yet" and, for an ordinary codec, no extra row at all). The stored
result is a wire budget, independent of the codec, so one test serves every codec.

Recommendation for an ordinary codec: `min(useful maximum, 65 % of the tested budget, 65 % of the PC's link)`, never
below 5 Mbps; with no test, the app's own default bitrate (Moonlight's table: 20 Mbps at 1080p60, 40 at 1440p60, 80 at
4K60, shown as before). Constants, in `LinkPolicy` (**judgment, not measurement; verify on the Odin**):

| Constant | Value | Reasoning |
|---|---|---|
| `ORDINARY_BUDGET_SHARE` | 0.65 | The test is a clean 2 s burst. A live stream adds FEC (Sunshine default 20 %, configurable higher), audio and control, and its rate control overshoots on scene cuts and keyframes; the 5 % confirmation margin is already in the tested budget. |
| `H264_CAP_FACTOR` | 3.0 x default | 60 Mbps at 1080p60: H.264 needs about half as much again as HEVC/AV1 for the same picture; the quality knee is around 40-60 Mbps. |
| `HEVC_CAP_FACTOR`, `AV1_CAP_FACTOR` | 2.0 x default | 40 Mbps at 1080p60, 80 at 1440p60. Automatic assumes this middle value. |
| `ORDINARY_MAX_KBPS` | 150 Mbps | The knee at 4K60 is about 100-160 Mbps, and hardware decoders (H.264 level 5.2, common HEVC profiles) top out near it. |

Example: HEVC 1080p60 on a link that carries up to 665 Mbps is recommended 40 Mbps (the knee, not 65 % of the link), reason
`link carries up to 665 Mbps`; H.264 on a link that carries 80 Mbps is recommended 50 Mbps (65 % of 80, below the 60 Mbps
useful maximum), reason `link carries 80 Mbps`. The stream menu shows the row for an ordinary codec only when a test exists
(testing is done in Settings, before connecting); Settings shows it when the PC can test or a result exists.

### Passive hint (PyroWave only)

Ordinary codecs show neither this hint nor the Link block: their loss counters are not equivalent. The RTP queue repairs
loss with FEC and recovers with IDR or reference invalidation, and the frame-loss figure counts only frames dropped
after all that, while PyroWave's partial and lost frames count every frame that arrived damaged. A threshold calibrated on
one would be meaningless on the other, so the hint stays PyroWave-only until it is measured for the others.

Every second the PyroWave stats thread feeds `LinkMonitor` (also with the overlay off): frames, partial, lost
and skipped frames and the app's received rate, with the bitrate in force. The verdict covers the trailing
samples at the current bitrate (a bitrate change starts a fresh reading), at least 3 s and about 5 s:

| Reading | Verdict |
|---|---|
| (partial + lost) / frames >= 2 % | **Link drops frames at N Mbps, try M** (M = N less `clamp(4 x share, 10 %, 40 %)`, rounded down to 5 Mbps) |
| skipped / frames >= 2 % without the above | Device skips frames; the link is fine (no bitrate advice: it is not the network) |
| otherwise | Link keeps up |

The thresholds are named constants in `LinkPolicy` and are **not yet calibrated**; they are Delos's own
(`HINT_DROP_SHARE`, `HINT_CUT_PER_SHARE`, `HINT_MIN_CUT`, `HINT_MAX_CUT`, window and minimum seconds). The
received rate is `TrafficStats` of the whole app (video, audio, control and HTTP together), the same source as
the existing Bandwidth row but rx only; there is no video-only byte counter in Java. Expect it to read a few
Mbps high.

### How to read it

- Tested well above the format's need and no drops: leave the bitrate alone.
- "link tested at 180 Mbps" under a 225 Mbps guide: the format will lose detail on this link; Use recommended
  bitrate, or lower the resolution or FPS for the full picture.
- Drops in the stream but a good test: the radio changed (distance, interference, roaming) or the device is the
  limit; look at the Backlog block (stall and frame age) and at "Device skips frames".
- A test result is a snapshot of a 2-second burst on a Wi-Fi that swings with RSSI, roaming and power save: repeat
  it before concluding anything, and expect variance of a few tens of Mbps.
- The test measures UDP delivery only. It does not prove sustained smoothness, host encoding speed, frame-burst
  delivery or decoder load ([#19](https://github.com/Meitoncz/Delos/issues/19)).

### Where Delos follows the reference client, not the host document

The host document lists 0.1 % aggregate loss, 1 % per window, p99 <= 4 ms and delay growth <= 2 ms, all as failures.
Nonary's moonlight-qt (`pyrowavelinkpolicy.h`) uses loss < 2 %, any 100 ms window < 5 %, at least 98 % of planned
packets sent, delay growth <= 2 ms, and treats p99 > 4 ms as a warning only. Delos first implemented the document's
numbers; on the Odin a clean 500+ Mbps Wi-Fi 7 link then ended with "too unsteady" (the message for a search whose last
probe failed on delay). The most likely cause [INFERENCE, no log was captured] is that Android's read-time arrival stamps
push p99 over 4 ms (and 0.1 % loss is tight for a phone's receive path), so no step passed. Two points of the reference are kept as written: delay growth stays a
failure (it detects a queue building up), and the search is started at the selected bitrate (the reference starts at
a ceiling bounded by the quality need, Delos at the setting as the user asked).

### Open points the documents leave unclear

- The documents do not say how the wire allowance relates to the author's *image* recommendation in the
  bitrate setting: Delos treats the setting and the probe as the same wire unit and compares `recommendedKbps`
  directly (as the app's default bitrate already does). The FEC-inclusive conversion is therefore informational.
- "All planned packets sent": the host document says all, the reference accepts 98 %; Delos uses 98 %.
- The host's stream-active refusal is an HTTP 400 with the text "Stop streaming before testing bandwidth"
  (from the host source, not the document); Delos matches the word "streaming". A probe running concurrently
  from another client gets the same answer.
- `PyroWaveCriticalFecPercentage` is documented 0..255 (reference client accepts 0..100); Delos accepts 0..255.
- The client's routed wired link speed (`pyrowaveLinkMbps`) is deliberately not measured or sent.

### Results

To be filled after the Odin measurements (issue #18). One row per place and band; run Test link three times and
record every result to show the variance.

| Date | Place / AP | Band, distance | Run 1 | Run 2 | Run 3 | Static guide | Recommended | Stream check (partial/s at the recommendation) |
|---|---|---|---|---|---|---|---|---|
| | | | | | | | | |

## Snapdragon Game Super Resolution 1

The official Qualcomm SGSR 1 spatial shader works with the final streamed image; temporal SGSR 2
requires game-engine inputs that a streaming client does not receive. The shader is vendored from
SnapdragonGameStudios/snapdragon-gsr at `d926f074bcb9d714e179f1ce0fcb9ee2eeb5074e`, under BSD-3-Clause.
Its copyright remains in the shader and the full license is bundled as `assets/licenses/sgsr.txt`.
Delos enables edge-direction filtering, exposes sharpening strength and bypasses reconstruction when
downscaling. The optional setting requires OpenGL ES 3.1.

The **Quality** choice offers Standard (the original mobile edge-direction shader) and **High Quality**.
The latter ports `SGSR_TARGET_HIGH_QUALITY` from Qualcomm's official Unity package at
`ab685c993ebd0f65a762c6e28f64f124c7f12532`, `Shaders/v1/sgsr.hlsl` (BSD-3-Clause). It uses RGB-derived
luma, a 4/255 edge threshold and two weighted estimates blended 60% edge / 40% smooth. This is a
different spatial filter, not just a higher sharpening value. Delos uses full precision for this
shader and guards zero variance. It shares HDR/P010 conversion, output size and geometry with
Standard. Both programs compile at initialization; live switching selects the next frame's program
without resetting the decoder. Sharpness remains independent. GPU cost/visual gain require Odin
acceptance. The choice is available globally, per game and session-only in Stream menu → Upscaler.

SGSR 2 is not integrated: its color/depth/velocity, camera transforms and jitter inputs are unavailable
from the decoded stream. Substituting zero motion is not correct for moving video; deriving optical
flow and replacing its other assumptions would be separate video reconstruction work.

`SgsrRenderer` owns its EGL context on a dedicated thread. MediaCodec renders into a SurfaceTexture;
a first pass resolves its transform/crop to a 2D image, then SGSR reconstructs at the display size.
SDR uses an RGBA8 intermediate and the final SurfaceView. The SurfaceView retains aspect-ratio and
input geometry, while interface/statistics/virtual-controls overlays remain outside the filter.
The surface is sized when the stream starts from the requested HDR; if the host then settles on 8-bit
video, the decoder asks `DelosStreamActivity` to give the surface its layout size before creating the SGSR stage.

Ten-bit video uses an RGBA16F intermediate and **P010 AHardwareBuffer** output, via a child
SurfaceControl. This is required on the Odin: labelling an RGB layer as HDR is insufficient on its
display stack. The SGSR fragment output converts encoded RGB into limited-range BT.2020 YCbCr for
HDR (BT.709 when the host switches to SDR). Surface transactions carry BT.2020 PQ dataspace and the
host's SMPTE 2086 / CTA-861.3 metadata. The four-buffer pool honors SurfaceFlinger release fences;
when none is available it drops a frame rather than overwriting a held buffer. GPU completion is
currently synchronized with `glFinish()` before submitting the buffer; there is no CPU pixel copy.
This extra GPU synchronization must be measured on the device, not assumed free.

**Stream menu → Upscaler** contains live On/Off, mode and sharpness controls. MediaCodec toggling
requests a local decoder reset/IDR to change its output surface, preserving the network session.
PyroWave applies changes at a frame boundary without resetting its decoder. Sharpness updates
presentation parameters without restarting either decoder. Live changes are session-only; initial
defaults remain in Display settings and may be overridden per game. Initialization/runtime failures
fall back to direct rendering and are exposed in the menu/logs; HDR is not silently converted to SDR.

Every change on this page is also kept for the running game, automatically: it applies live and, about 500 ms after the
last change (a slider drag or held D-pad writes once; the menu closing or the stream ending writes at once), the current
upscaler on/off, mode, sharpness and supersampling level are written as that game's per-game overrides (see
[PER_GAME_SETTINGS.md](PER_GAME_SETTINGS.md#the-menu-keeps-the-live-upscaler-for-the-game)) and apply from its next
launch. Nothing else is written (no bitrate, no other display setting) and neither the profile nor other games change; the
running stream keeps its live values. A stream started without its launch identity stays session-only.

Both renderers implement SGSR 1 (Normal), SGSR 1 (Quality), SGSR2-DV and SGSR2-DV (Quality), and
the [Sharpen only mode](#sharpen-only-mode).
PyroWave uses native Vulkan shaders rather than the GLES stage. Spatial modes use distinct Qualcomm
Standard/HQ kernels; temporal Quality adds a separate reactive activation pass and tighter YCoCg
history clipping. All modes retain FP16 intermediates and use the existing HDR/P010 bridge.
Independent bounded sharpening runs only during presentation; zero bypasses the sharpening filter,
and Off disables all SGSR processing. Actual applied native mode/size are reported separately from
the requested setting. Unsupported GPU-P010 processing reports direct rendering, not a false active mode.

The statistics overlay includes the measured post-decoding processing time for every SGSR variant in
both renderers and adds it to total latency. MediaCodec uses smoothed wall time around a successful
SGSR draw; PyroWave uses its Vulkan draw timestamps. This is processing cost, not a claim of complete
input-to-photon latency.

### SGSR 1 reconstruction gain and Sharpness (2026-11-07 follow-up)

SGSR 1 **Standard and High Quality use the official fixed reconstruction gain of 2.0** in both
MediaCodec/GLES and PyroWave/Vulkan. Previously GLES used `0.5 + 1.5*s` (`1.25` at the default
50%), while native used `0.5` plus a separate presentation sharpener. The historical audit is
preserved in [SGSR2_VIDEO.md](SGSR2_VIDEO.md#sgsr-1-fixed-gain-follow-up-2026-11-07).

Sharpness now controls only an independent final bounded cross filter in both SGSR 1 pipelines.
For normalized slider `s` (`0..1`, default `0.5`), its effective strength is `0.5*s`; the existing
detail gain of `0.75` gives a correction coefficient of `0.375*s` (`0.1875` at 50%, `0.375` at
100%). Each channel's correction limit remains `min(0.06, 0.25*localRange)`, with the existing
local-range/output clamps. **0 bypasses only the final sharpener: fixed-gain SGSR 1 reconstruction
remains active.** Upscaler Off bypasses both. The milder final-filter mapping is intended to add
gentle sharpening to full-gain reconstruction **[INFERENCE]**; it is not RCAS equivalence or device
acceptance. SGSR2-DV/Video Quality and Sharpen only's CAS mapping are unchanged.

GLES reuses the existing supersampling RGB target for one reconstruction draw and a separate
cross-filter presentation draw, avoiding five complete reconstruction evaluations per output
pixel. Ordinary spatial upscaling therefore also needs the intermediate when final filtering is
active, although no new target type is added. HDR reconstruction remains encoded RGB/PQ until the
existing final P010 conversion. Native already uses its compute intermediate and final
presentation/pack pass. Extra GLES storage, bandwidth and GPU cost have not been measured.
No build checks or device acceptance are claimed for this follow-up; compare the same scene on
main and this change at 50% in Standard/HQ for each renderer, including HDR, live switching and
the launch arrival effect, then compare 0/50/100% and sustained processing time.

### Sharpen only mode

Issue [#67](https://github.com/Meitoncz/Delos/issues/67). **Sharpen only** is a fifth Upscaler mode (setting value
`sharpen`, Mode row position 5) for sharpness without scaling: the internal size is the stream's resolution whatever
the supersampling level, and the only picture change is one contrast-adaptive sharpen pass using the existing
sharpness value (0 leaves the picture unchanged). The Sharpness slider keeps working in every other mode as before.
The Enabled toggle, the Mode row and Sharpness work live; the supersampling quality has no meaning in this mode, so
the stream menu hides that row and Settings disables it with "Not used by Sharpen only.". The mode is saved and
restored globally and per game like the others (`list_sgsr_quality=sharpen`); older builds read the value as Standard.

**Algorithm.** SGSR 1 at a scale factor of 1 is not a plain sharpen (it is an edge-directed resample of the luma with
a Lanczos kernel), so the mode has its own pass: FidelityFX CAS (contrast adaptive sharpening, AMD, MIT) on the
five-sample cross, per colour channel, in the encoded domain (SDR gamma or HDR PQ code values, never linearised):

```
mn, mx = min / max of centre e and its up b, left d, right f, down h
amp    = sqrt(clamp(min(mn, 1 - mx) / max(mx, 1e-4), 0, 1))   // less where the signal is near black or white
w      = -amp * 0.2 * sqrt(sharpness)                          // sharpness 0..1; 0 = off, 1 = CAS's strongest 1/5
out    = clamp((b + d + f + h) * w + e) / (1 + 4 w)           // the weights sum to 1, flat areas never change
```

CAS's own default peak is 1/6.5; the square root puts the middle of the slider (50 %) at 0.141 instead of the weak
0.1 a linear ramp would give. `SharpenOnly.java` is the CPU reference with unit tests of documented values; the
shaders evaluate the same steps and must be kept in step with it.

| Pipeline | Where | Passes per frame |
| --- | --- | --- |
| OpenGL ES (MediaCodec) | `assets/sgsr/sharpen_only.frag`, drawn by `SgsrRenderer` | the existing OES to 2D copy, then the sharpen pass straight into the window or the P010 layer |
| Vulkan (PyroWave) | `shaders/sgsr_sharpen.glsl` (`contrastAdaptive`) in `sgsr_present.frag` / `sgsr_pack.frag`; mode 5 of `sgsr_spatial.comp` | a YCbCr to RGB compute pass into the existing FP16 image at stream size, then the sharpen present (or P010 pack) pass |

Both are GPU-only and keep FP16 intermediates, the P010/PQ HDR output and the launch arrival pass of the SGSR modes
(the arrival renders the sharpened frame). The Vulkan side supports 4:2:0 and 4:4:4 planes (the chroma planes are
sampled at their own size) and SDR, HDR10 swapchain and the HDR P010 bridge.

**Surface size.** Sharpen only must not enlarge anything, so the cost stays that of the stream's own pixels. SDR
MediaCodec output keeps the stream-sized SurfaceView buffers (`StreamSurfaceController.sizeStreamSurfaceForUpscaler`: a scaling mode
asks for the layout size, Sharpen only for the stream's fixed size) and the HDR child layer is created at the
stream's size, not the display's; SurfaceFlinger scales the result to the view as it does for direct output.
PyroWave presents at the stream's size in this mode (`sgsrPresentationWidth/Height` = stream size, no supersampling
candidate). Because these sizes differ from the scaling modes', switching Mode to or from Sharpen only (or enabling
with a stored Sharpen only) rebuilds the output stage: a MediaCodec decoder reset like the Enabled toggle, and for
PyroWave the existing upscaler recreate at a frame boundary. Switching among the other four modes is as before.

**Measuring the cost.** Both pipelines publish what the mode costs, for the next stream test:

- OpenGL ES: once a second, while the mode runs, one debug line under the log tag `DelosSharpen`
  (`adb logcat -s DelosSharpen`), for example
  `sharpen-only 1920x1080->1920x1080 sharpness=0.5 p010=true hdr=true timer=gpu | pass gpu avg=0.310 max=0.620 ms (n=119) | copy gpu avg=0.180 max=0.400 ms | cpu submit pass avg=0.040 max=0.200 ms copy avg=0.030 max=0.100 ms`.
  GPU time is an `EXT_disjoint_timer_query` time-elapsed query around the sharpen draw and around the OES copy that
  the direct output does not have; if the extension is missing (or a probe query errors) the line says
  `timer=cpu-submit` and only the CPU time of submitting the draws is reported. Disjoint GPU events discard the
  affected samples. The full statistics panel shows the row **Sharpen pass** (`0.31 ms avg / 0.62 max · copy 0.18 ms`,
  with `(CPU submit)` when no GPU timer exists).
- Vulkan: the existing timestamp queries gain a fourth stamp after the reconstruction stage, so the draw time is
  split into the YCbCr to RGB conversion and the sharpen/pack pass. `DelosPyroDiag` (debug builds) ends with
  `sharpen pass gpu avg/max .../... ms, conversion avg/max .../... ms (n=...)` (or `sharpen off`), the native 5-second
  log line `Sharpen only: GPU sharpen pass ... ms, colour conversion ... ms`, and the full panel's **Backlog**
  section gains **Sharpen pass** (`0.50 ms avg / 0.90 max · conversion 0.25 ms`). The existing "GPU draw" number
  already covers the whole draw stage in every mode, so the cost against the direct path is the difference in it.

The numbers are what the pass costs; they are not an input-to-photon measurement. Cost on the Odin at 1080p120,
the visual result, and the shader compile on the Adreno 740 are verified only by a stream test (issue #67).

### Native-resolution supersampling

For all four SGSR modes, a stream already matching the display uses supersampling instead of
same-resolution reconstruction. The live stream menu calls this mode **Supersampling** and offers two
levels, session-only unless saved: `1.33x` (stored value 1, labelled 1x until 2026-11-07) reconstructs 1920x1080 into 2560x1440, while `2x` reconstructs it into
3840x2160. A separate presentation pass reduces the internal image to the display resolution.
The HDR path performs RGB-to-P010 conversion only after that reduction; intermediate RGB remains
FP16 and PQ-encoded, not linear-light or eight-bit SDR.

The level a stream starts with is the Display setting **Supersampling quality**
(`list_sgsr_supersampling_level`, 1 or 2, default 1; profile value with a per-game override). An unknown
stored value reads as 1.33x, and a 2x that does not fit falls back to 1.33x exactly as for the live choice.

The sizing policy tolerates a two-pixel Surface aspect-fit difference, checks the GPU texture limit,
and caps the internal target at 3840x2160. Native 4K therefore never attempts an unsafe 8K target.
Changing the level live resets only temporal history and size-dependent SGSR resources, not the
network stream or decoder. PyroWave recreates those Vulkan resources after its current frame fence and
falls back from `2x` to `1x` if the larger allocation fails. The stream menu row shows the level in
effect, which is the request at once and `1x` after such a fallback, so one D-pad press moves it; it
does not wait for the renderer's next frame to apply the request. In HDR the P010 output is rebuilt
with the upscaler: the previous surface control stays on screen with its last picture until the rebuilt
output has shown its first frame, so a level or mode change holds the picture for the rebuild instead
of flashing black.

The full statistics panel reports the actual active target directly below the source resolution, for
example `Supersampled to: 2560 x 1440` or `Upscaled to: 1920 x 1080`. It reads the renderer's applied
dimensions, so a pending change or fallback is not reported as completed. On the Odin's Adreno 740,
the headless SGSR 2 temporal stage measured about 6.85 ms median / 7.39 ms p95 for 1080p-to-1440p and
10.23 ms median / 16.76 ms p95 for 1080p-to-4K. The first level is the default; the second is primarily
suited to 60 FPS streams.

The two-pass SurfaceTexture/2D approach was also reviewed against linckosz's Moonlight Android
PR #1567 (`37e7a00ce8cde55b936c9239d8e717336f55da63`); the lifecycle, live controls and P010 HDR output
here are implemented for Delos rather than copying that bridge's synchronous frame wait.

## Gyro smoothing (#77)

**Controls → Gamepad → Gyro smoothing** (`seekbar_gyro_smoothing`, 0..100, default 0) steadies the gyroscope before it is
sent to the host. 0 is off and the sensor path is then unchanged (the filter is never created). It acts on the gyroscope
only, never on the accelerometer, for both the external gamepad's sensors and the built-in device sensors
(`ControllerHandler.createSensorListener`). The stream menu (More → Gyro smoothing, steps of 5) changes it live and keeps it for this game as its per-game override (the profile is not written; the Settings slider sets the profile value and applies to the next stream, because Settings is not open during a stream).

**Live change and threads**: the menu writes the new strength into `PreferenceConfiguration.gyroSmoothing` (`volatile`) and
nothing else (no profile write). Each gyro sensor listener reads that field once per sample (a volatile read, never
`SharedPreferences`) and, when it differs from the filter's, calls `GyroSmoother.setStrength` on the sensor thread, which is the
only thread that ever touches the filter: no lock and no state hand-over. 0 to positive starts a fresh filter, positive to 0 is
an exact bypass (`filter` returns false and the original unfiltered code path sends the sample) and forgets the state, and a
change between positive strengths keeps the state, so there is no jump. Listeners created later (a controller that connects
mid-stream) start from the current value.

**Filter** (`binding/input/GyroSmoother.java`, pure and unit-tested): a One Euro filter per axis, one instance per sensor
listener (so per controller and per report rate; a new report rate starts a fresh filter), no allocation per sample. The
cut-off is `minCutoff + beta * |d|`, with `d` the rate of change of the angular velocity, itself low-passed at a fixed 1 Hz.
At rest and in slow aiming the cut-off stays near `minCutoff` and removes jitter; in a flick `|d|` grows, the cut-off opens
and the lag almost vanishes. It runs on the raw reading converted to deg/s (the unit the host receives, so `beta` is in Hz
per deg/s per second), before the display-rotation axis remapping, so a rotation never mixes the state of two axes. Samples
are timed with the sensor's own timestamps; the same timestamp repeats the last output, a gap above 250 ms or a timestamp
going backwards restarts the filter from the new sample.

**Mapping** (`t = slider / 100`): `minCutoff = 8 Hz * 0.025^t`, `beta = 0.02 * 0.25^t`.

| Slider | minCutoff | beta | Group delay at rest | Noise variance (rest, vs raw) | Lag of a slow 0.5 Hz, 30 deg/s sway | Lag of a 3 Hz, 300 deg/s flick | 0 to 300 deg/s in 100 ms reaches |
|---|---|---|---|---|---|---|---|
| 0 | off | off | 0 | 100 % | 0 | 0 | 300 |
| 1 | 7.71 Hz | 0.0197 | 21 ms | 10.9 % | 15 ms | 5 ms | 292 |
| 10 | 5.53 Hz | 0.0174 | 29 ms | 8.1 % | 15 ms | 5 ms | 291 |
| 25 | 3.18 Hz | 0.0141 | 50 ms | 4.9 % | 20 ms | 5 ms | 289 |
| 50 | 1.26 Hz | 0.0100 | 126 ms | 2.1 % | 30 ms | 10 ms | 286 |
| 75 | 0.50 Hz | 0.0071 | 316 ms | 1.0 % | 35 ms | 10 ms | 283 |
| 100 | 0.20 Hz | 0.0050 | 796 ms | 0.47 % | 45 ms | 10 ms | 278 |

Numbers come from `GyroSmootherTest` (200 Hz samples, white noise of 0.5 deg/s sigma, lag resolution 5 ms): the group delay
at rest is `1 / (2 pi minCutoff)`, which only matters for the first moments after a still hand starts to move; the
latency a player feels is the sway and flick columns. These are filter numbers, not measured input-to-photon latency.
Unverified without a real game on a handheld: how it feels in a game, and whether the host (Steam Input, emulators) already
smooths the data, which would make 0 the right choice.

## Latency (#75)

Status: the optimisations below are implemented; **none of them has been measured on the device yet** (results table at
the end of this section is empty). Everything marked [INFERENCE] is derived from the source and from Android's
standard behaviour, not observed on the Odin. The input-to-photon number can only come from the camera test below; the
in-app stage timestamps never include the controller, network, host or decoding of the host side.

### Latency budget

Typical values in milliseconds, a handheld at 120 FPS on a 120 Hz panel. (doc) = from the SGSR measurements in this document;
everything else is [INFERENCE].

| Stage | Owner | ms |
|---|---|---|
| Controller poll and send | Android input | 1-4 |
| Host capture and encode | Vibepollo | 2-6 (shown as "PC processing") |
| Host capture and encode, PyroWave | Vibepollo | about 1-2 |
| Wi-Fi transit and jitter | network | 1-4 (round trip / 2) |
| Reassembly and FEC, MediaCodec | moonlight-common-c | below 1 |
| Decode | MediaCodec, low-latency Qualcomm decoder | 2-5 |
| Pacing wait | Latency: about 0; Fast V-Sync / Balanced: 0-8.3 by design | |
| Output stage, MediaCodec direct | codec to SurfaceView | none |
| Output stage, Sharpen only | listener thread hop, `updateTexImage`, OES copy 0.18 (doc) + sharpen 0.30 (doc) | about 0.7-1 |
| Output stage, SGSR 2 | 3.3-3.9 ms at 480p/720p to 1080p (doc, headless); 3.9-4.9 in play (doc) | |
| Output stage, supersampling | spatial about 1.3-2 at 1x (4K target 2.5-4 at 2x); temporal 6.85 at 1x, 10.23 at 2x (doc, headless, over the 8.3 ms frame at 2x) | |
| PyroWave | Vulkan decode about 3 (doc); receive to present 4-9 (doc) | |
| Compositor tail | SurfaceFlinger latch plus scan-out | 8-12 on average, up to one frame more when the latch is missed |

The compositor tail is the one a client cannot shorten by much: a buffer queued at time t is latched at the next SurfaceFlinger
latch point and scanned out at the following panel refresh, 1 to 1.5 frame periods plus half a scan-out for the screen centre.
If the stream FPS equals the display rate, the phase between frame-ready and refresh is fixed for stretches of a session and
drifts slowly (host and panel clocks differ by tens of ppm), so a measured latency wanders over a window of about one frame
period across minutes. Camera runs must be long enough or sample the trigger phase randomly.

### What was changed

One commit each, in the order of the table. None changes the picture, adds a setting or touches moonlight-common-c.

| Change | Where | Expected gain [INFERENCE] |
|---|---|---|
| **Newest frame only in the SGSR GL thread.** The frame-available callback runs on its own thread and counts queued frames (`FrameQueue`); a draw consumes every waiting frame with one `updateTexImage()` each and draws only the newest, so a stale frame is never shown while a newer one waits. SGSR 2 sees the newest frame's timestamp, so one skipped frame does not reset its history (it resets only when the gap exceeds its 50 ms limit, about 6 frames at 120 FPS). The callback has to count on its own thread because on the GL thread the callbacks of frames queued behind a long draw are not delivered until that draw is over. | `SgsrRenderer.draw`, `FrameQueue` | About 0 ms while the output keeps up; when the GPU work per frame exceeds the frame interval (SGSR 2 at 1x or 2x at 120 FPS, 4K supersampling) it removes the 1-3 queued frames, 8-25 ms. The depth depends on the codec's buffer count, which was not read. Costs frames per second only in that overloaded state. |
| **Fast V-Sync aware of the output stage.** The pacer releases a frame earlier by the smoothed frame-available-to-swap time of the SGSR GL thread plus 0.5 ms, capped at half a frame period and never before now; the phase bookkeeping keeps the display target (`SgsrPacing`, `NanoPacer`). Latency mode is untouched. Balanced is not adjusted: its release time is the Choreographer callback, and the SGSR consumer ignores the release timestamp, so there is nothing to move. | `NanoPacer.waitAndDrainLatest`, `SgsrPacing` | 0 to a few ms: avoids a missed latch for a share of the frames (up to 8.3 ms each) with Fast V-Sync and SGSR. On the SDR path the estimate is CPU submit time and underestimates GPU completion; the cap bounds the damage of a wrong estimate. |
| **HDR output without `glFinish`.** The P010 buffer goes to the SurfaceControl transaction with an `EGL_ANDROID_native_fence_sync` fence, so the GL thread no longer waits for the GPU. Reuse stays protected by the transaction's release fences as before. Without the extension the code falls back to `glFinish`. Every fence descriptor is owned by exactly one place: the transaction takes the one passed to `setBuffer`, the output keeps a `dup` per frame (closed when it signals, when its slot is reused or at teardown) and the present fence of the transaction statistics is closed the same way. | `sgsr_hdr_output.cpp` | 0.2-0.5 ms (one wake after the GPU is idle) and the next frame's `updateTexImage` and command recording overlap the previous frame's GPU work. |
| **GPU work in flight bounded to two frames** (device finding below). Every frame the GL thread submits gets a native fence (SDR: `eglCreateSyncKHR` with `EGL_SYNC_NATIVE_FENCE_ANDROID` plus a flush, polled with a zero timeout; HDR: the fence of the P010 hand-over). With two frames still running, the draw is not queued: the newest frame stays in the texture and is drawn as soon as the GPU is free (retried every millisecond) or replaced by a newer one, so latency stays at about one to two GPU frames however slow the GPU is, at the cost of frames per second. Two, not one, because the CPU needs 4-6 ms per frame to record while the GPU needs about 10: with one the GPU would idle during every recording. A fence that has not signalled after 250 ms is given up so a hung GPU cannot freeze the picture. Frames dropped this way are the `GPU busy /s` figure of the Frame queue row. Without native fences the GPU is waited for after every frame. SGSR 2 history is unaffected: a dropped frame never reaches it. | `GpuInFlight`, `SgsrRenderer.draw`, `sgsr_frame_timestamps.cpp` | Bounds the 70-140 ms measured with SGSR 2 at 4K to about 20 ms. |
| **Honest GPU time.** The Upscaling figure and the Fast V-Sync input are now the GPU execution time of a frame from its fences (from the later of its submission and the end of the previous frame to its signal time; queueing behind the previous frame is not counted), not CPU submit time or a backlog-dominated wall time. Until the first fence is read, or without fences, it is the CPU submit time. | `GpuInFlight`, `SgsrRenderer` | None; the numbers mean what they say. |
| **Overload notice and load governor.** After two consecutive seconds with the GPU time above 90 % of the frame period or more than 20 % of the frames dropped for a busy GPU, one toast per stream and mode says "Upscaler is too heavy for this frame rate: lower the supersampling or the frame rate" (logged under `DelosSharpen`); no setting is changed. In the SGSR 2 modes, while the GPU time is above 70 % of the frame period, every second frame reuses the previous frame's optical flow instead of recomputing it (back to every frame below 45 %), which saves the flow passes (a quarter to a third of the temporal cost) on half the frames; only changing motion can look slightly less sharp, and only under load. | `OverloadDetector`, `FlowGovernor`, `SgsrTemporal.render` | Under load: about 10-15 % less GPU time for SGSR 2. [INFERENCE] |
| **GL thread at urgent display priority and a high-priority EGL context.** Tried and **reverted**: a high GPU priority lets the SGSR context pre-empt the compositor's and the system UI's GPU work, and with the GPU saturated (below) the whole system stuttered. Do not use `EGL_CONTEXT_PRIORITY_HIGH_IMG` for the stream. The GL thread stays at `THREAD_PRIORITY_DISPLAY`. | `SgsrRenderer` | None. |
| **PyroWave present observability** (logging only): the present modes the surface offers and the chosen one, the swapchain image count and the pre-transform, once when the swapchain is created and in the `DelosPyroDiag` line. | `pyrowave_renderer.cpp` | None by itself; it shows whether MAILBOX is used and whether the compositor rotates. |
| **Stage timestamps** (below). | `LatencyStages`, `SgsrRenderer`, `sgsr_frame_timestamps.cpp` | None; measurement only. |

Candidates from the investigation that were **not** done: the reactive-mask texture to a single channel (OpenGL ES 3.1 allows only
`rgba16f`, `r32f`, `rgba8` and a few integer formats for image stores; `r16f` and `r8` need an extension, `r32f` is not filterable
without `OES_texture_float_linear`, and `rgba8` would quantise the mask and so change the picture, for about 0.1 ms in the Quality
mode only); the OES-copy fusion; cheaper SGSR 2 flow and a 2x cap (they change the picture, issue #74); ADPF hint sessions
(power and heat, wants its own decision); front-buffer rendering (risk, only after these are measured).

### GPU saturation by the temporal modes (device finding, 2026-11-07)

Measured on the Odin with this branch's first version, SGSR2-DV (Normal) at 2x supersampling (a 4K target), 120 FPS: the
Upscaling figure peaked above 150 ms, `DelosSharpen` showed `queue pending avg=1.0 max=1, skipped=0` (the SurfaceTexture queue
was not the problem), `draw` 4-6 ms on average (maximum 41-50), and `DelosLatency` showed `avail->gpu` 60-130 ms,
`avail->latch` 20-60 ms and `avail->present` 70-140 ms (maximum 290). The whole app and the system stuttered.

Reading: the CPU submitted much faster than the Adreno 740 could execute (SGSR2-DV at 4K costs about 10 ms of GPU
time headless against an 8.3 ms frame), so 8-15 frames of work piled up inside the driver's command queue and the swap
chain, where neither the SurfaceTexture coalescing nor the pacer could see them. With the GPU saturated, the compositor's and
the system UI's GPU work waited behind it, which is the system-wide stutter [INFERENCE: the stutter itself was not
profiled]. A high EGL context priority made this worse by design and is gone.

Consequences, now in the code: the GPU frames in flight are bounded (above), the Upscaling figure is real GPU time, and the
user is told when the upscaler is too heavy. **A temporal mode at a 4K target (2x supersampling) needs about 10 ms of GPU time
per frame, so on the Odin it can only hold 60-90 FPS; use 1x, or a 60 FPS stream, for SGSR2-DV at 4K.** What the bound
cannot do is create GPU time: a saturated GPU stays saturated, and the dropped frames are the price.

Memory traffic is the other candidate for a system-wide slowdown that a longer GPU queue does not explain (the GUI was also
slow with SGSR2-DV (Quality) at 1x). Per frame the temporal path writes the RGBA16F history at the output size (8 bytes per
pixel: 71 MB at 4K, 16 MB at 1080p), reads the previous history, and reads it again in the present pass, plus 16 MB
for the stream-sized RGBA16F reactive mask in Quality mode: about 210 MB per frame at 4K, 25 GB/s at 120 FPS, and about
50 MB (6 GB/s) at 1080p with Quality, on a memory bus the CPU, display controller and decoder share [INFERENCE from the texture
formats in `SgsrTemporal`, not measured]. Halving it needs a different history format (an 8-bit history changes the
picture; `R11G11B10F` has no alpha) or a single-channel mask, so it is left for a decision with a before/after picture
comparison. The in-flight bound also keeps the GPU from running at 100 % when frames are being dropped anyway, but it does
not leave headroom on its own; the load governor above lowers the flow work from 70 % of the frame period.

### Measurement plan

1. **Camera (the only input-to-photon number).** A phone with 240 FPS slow motion pointed at the handheld. A debug-only
   "latency flash" action in the stream menu is not built yet; until it is, press a controller button that makes a white
   flash on the host (a trivial full-screen window that turns white on a key, for example) and count camera frames from the
   physical press, or an LED on the controller, to the screen turning white. At 240 FPS one camera frame is 4.2 ms. Do 30-50 presses at
   random intervals (so different refresh phases are sampled) and report median and p90. Measure once without the stream (the
   flash on the client's own screen) to know the camera and display floor. Do each configuration in the same session.
2. **In-app stages (no pixel reads).** Debug build, statistics overlay on. `adb logcat -s DelosLatency DelosSharpen DelosPyroDiag`:
   - `DelosLatency` once a second: `decode`, `pacing`, `avail->draw`, `draw`, `avail->gpu`, `avail->latch`, `avail->present`,
     each as avg/p95/max in ms with the sample count. `decode` is codec submit to codec output, `pacing` is codec output to
     release, the `avail->` stages count from the SGSR frame-available callback. `avail->present` is the "Render latency" row.
     SDR: GPU, latch and present come from `eglGetFrameTimestampsANDROID` a few frames after the swap. HDR: GPU from the fence signal
     time, latch and present from the SurfaceControl transaction statistics. A stage the platform does not give has no
     entry. Direct MediaCodec has `decode` and `pacing` only.
   - `DelosSharpen` once a second: `queue pending avg/max`, `age avg/max`, `gpu avg/max` (GPU execution time per frame from the
     frame fences), `in=` (frames per second from the decoder), `drawn`, `skipped` (frames skipped because a newer one was
     waiting) and `gpuBusySkipped` (frames that waited for a busy GPU and were replaced). Pending above 1 on average, or
     skipped above 0, means the CPU side cannot keep up; `gpu avg` above the frame period (8.3 ms at 120 FPS) or
     `gpuBusySkipped` above 0 means the GPU cannot. Two lines under the same tag say when the overload governor acted
     (`overload: ...`, `SGSR 2 under load: ...`).
   - `DelosPyroDiag` (PyroWave): the existing numbers plus `present=` (MAILBOX or FIFO), `images=` and `preTransform=`.
3. **No code needed.** `adb shell dumpsys SurfaceFlinger --latency "<layer>"` (layer names from `dumpsys SurfaceFlinger --list`;
   the HDR child layer is `Delos SGSR HDR`) for per-frame desired and actual present times; a Perfetto trace
   (`sched freq gfx view` categories, with the frame timeline) for GPU and CPU frequencies and missed refreshes;
   `dumpsys SurfaceFlinger` for the composition type of the stream layer (device/overlay or client/GPU).

Verify each change with the same measurement before and after, one configuration per row, no change of the stream between
runs. Sub-millisecond changes cannot be seen by the camera; judge them with `DelosLatency`/`DelosSharpen` and the p95.

### Results

Empty until the device is measured. One row per run.

| Date | Pipeline | Codec / HDR | FPS / Hz | Pacing | Overlay | Change under test | Camera median / p90 (ms) | `avail->present` avg / p95 | `pacing` avg | Skipped /s | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|

## Validation and device acceptance

Unit tests cover applied bitrate parsing and XML failures, Nano Pacer lock/reset/rate handling and
MediaCodec flush/restart generation ordering. The settings catalog includes the three new controls.
At the first HDR build checkpoint: 61 unit tests passed, debug build and lint passed (zero errors,
383 warnings); the packaged ARM64 native library was checked for the SGSR JNI entry points.

The ARM64 build was installed on the Odin after checking that no Game activity was foreground.
The user was asked to start HEVC at 720p with HDR, Fast V-Sync and async decoding, change runtime
bitrate, and toggle SGSR/sharpness live. Check actual image orientation, HDR color/luminance, stable
frames, connection continuity and logs confirming the GPU/P010 path rather than fallback. Repeat
SDR, existing pacing/async-off, repeated toggles and stream reconnect/teardown. The user starts games
and performs navigation; agents only install/start the app and read diagnostics.

Do not mark these roadmap items complete or merge until the user's device results and any resulting
fixes are recorded. Runtime bitrate is host/encoder-dependent; an HTTP acknowledgement alone is not
proof that every codec's measured traffic changes as requested.

### Odin feedback, 2026-10-26

The user confirmed Fast V-Sync, asynchronous decoding and runtime bitrate changes work. SGSR's first
HDR test exposed a vertically inverted image and an enlarged/cropped viewport; HDR colors could not
be assessed. The correction keeps the HDR parent SurfaceView at its original stream buffer size,
maps the display-sized P010 child into that parent's coordinate space, and explicitly flips the raw
GL framebuffer vertically at presentation. Unlike an EGL window swap, raw SurfaceControl buffer
submission does not supply the GL-to-Android orientation conversion. Repeat visual acceptance after
installing this correction; HDR color and luminance are still unverified.

The user subsequently confirmed the corrected HDR colors are good and reported little visual change
at 480p. Device logs show actual SGSR output from 854x480 to 1920x1079 (the SurfaceView's aspect-fit
size), with sharpness 100%; scaling is directly to the view, not a fixed 2x stage. The user authorized
adding the High Quality variant for comparison. Its visual acceptance is pending.

Final upscaler acceptance used 480p HDR as a deliberately difficult source. The user preferred
`SGSR2-DV (Quality)` over the other three modes while asking to retain all four choices. The
improvement was modest and static source aliasing remains visible. A future 1080p-to-4K comparison
should be a more representative upscale ratio but is not required to complete this feature.

The High Quality build passed assembly, all 62 tests and lint (`check-hq-final.log`) and was installed
on the Odin after confirming ShellActivity was foreground. Later device logs confirm `SGSR quality:
High Quality` actually rendered on the GPU/P010 path. This confirms shader compilation/selection;
the user's visual and sustained-performance acceptance is still pending.

### PyroWave SGSR 2 and AV1 recovery, 2026-10-27

PyroWave's Vulkan renderer now runs its video-specific SGSR 2 reconstruction for SDR and HDR/P010.
The first full-resolution version added about 6.5-6.8 ms of GPU draw work and was visibly too latent.
Combining neighbourhood reconstruction and YCoCg clipping removed the duplicate nine-sample pass;
the Odin then measured mostly 3.9-4.9 ms of draw work and sustained about 118-119 fps in the tested
720p-to-1920x1080 HDR stream. The user confirmed substantially better latency, correct HDR colour and
working live On/Off. The native status also keeps the menu toggle synchronized.

Adding post-processing timing initially exposed an unrelated lifetime hazard in asynchronous
MediaCodec input. A decoder recovery could invalidate a retained direct input buffer and the next
`put()` crashed inside `GetByteArrayRegion`/`memcpy`. Async mode now fetches the callback-owned buffer
for each frame instead of retaining it across a possible flush/reset. GL timer queries that triggered
the recovery were removed in favour of safe wall timing. On the corrected build, the user started
AV1 with SGSR twice; both sessions kept the upscaler available and neither crashed. Device logs also
confirmed MediaCodec output at 1920x1080 for a 1280x720 source.

### PyroWave upscaler parity, 2026-10-28

Completed A01–A05: genuine SGSR 1 (Normal)/HQ, distinct temporal Quality activation/reconstruction,
presentation sharpening and matching saved/live controls/help. The native mode replaces the old
temporal-enabled boolean; requested settings remain separate from the actually applied mode.
Shaders are generated with `jni/pyrowave-renderer/shaders/build.sh`; no runtime shader compiler
or network dependency is introduced. GPU draw timestamps include all new post-decoding passes.

The local ARM64/ARMv7 debug build, all 80 unit tests and lint passed (0 errors, 387 warnings).
The existing Turkish translation quick-fix/report exception remains non-fatal and unchanged.
The ARM64 APK was installed after checking that no Game activity was running.
`PyroWaveGpuInstrumentation` passed real Vulkan shader readbacks on the Odin Adreno 740:
spatial constants/edges, bidirectional motion, Quality activation and distinct reconstruction,
history resets/scene changes, ten-bit BT.2020 preservation, zero/nonzero presentation sharpening
and HDR P010 packing. A second probe created the actual renderer, allocated/dispatched each mode
at both supersampling levels, checked applied state/Off teardown and rebuilt SDR → HDR → SDR.
No game, emulator, streaming connection or injected navigation was used.

These checks establish implementation and GPU correctness, not new visual acceptance or sustained
game performance. The earlier 118–119 FPS result above is specifically the old SGSR2-DV path,
not a benchmark for the new HQ/Quality modes. Track current acceptance in [#19](https://github.com/Meitoncz/Delos/issues/19).
