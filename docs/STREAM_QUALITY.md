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

Both renderers implement SGSR 1 Standard, SGSR 1 High Quality, SGSR 2 Video and SGSR 2 Video Quality.
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
`SGSR 2 Video Quality` over the other three modes while asking to retain all four choices. The
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

Completed A01–A05: genuine SGSR 1 Standard/HQ, distinct temporal Quality activation/reconstruction,
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
game performance. The earlier 118–119 FPS result above is specifically the old SGSR 2 Video path,
not a benchmark for the new HQ/Quality modes. Track current acceptance in [#19](https://github.com/Meitoncz/Delos/issues/19).
