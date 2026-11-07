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
the swapchain image count and present mode when it is created. The UDP receive buffer size is set and
checked inside moonlight-common-c and not exposed to the app; it logs a warning only when the kernel
clamps it. Suggested A/B: 4:2:0 at 225 Mbps, 4:4:4 at 225, 4:4:4 at 270, and 4:4:4 at 150.

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
video, the decoder asks `Game` to give the surface its layout size before creating the SGSR stage.

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

The page's last row, **Save for this game**, is the one way to keep live changes: it writes the current
upscaler on/off, mode, sharpness and supersampling level as that game's per-game overrides (see
[PER_GAME_SETTINGS.md](PER_GAME_SETTINGS.md#saving-the-live-upscaler)) and applies from its next
launch. It saves nothing else (no bitrate, no other display setting) and changes neither the profile
nor other games; the running stream keeps its live values. It is hidden for a stream started without
its launch identity.

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
MediaCodec output keeps the stream-sized SurfaceView buffers (`Game.sizeStreamSurfaceForUpscaler`: a scaling mode
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
levels, session-only unless saved: `1x` reconstructs 1920x1080 into 2560x1440, while `2x` reconstructs it into
3840x2160. A separate presentation pass reduces the internal image to the display resolution.
The HDR path performs RGB-to-P010 conversion only after that reduction; intermediate RGB remains
FP16 and PQ-encoded, not linear-light or eight-bit SDR.

The level a stream starts with is the Display setting **Supersampling quality**
(`list_sgsr_supersampling_level`, 1 or 2, default 1; profile value with a per-game override). An unknown
stored value reads as 1x, and a 2x that does not fit falls back to 1x exactly as for the live choice.

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
