# SGSR 2 Video

Initially implemented on `feature/stream-quality` on 2026-10-26 after the user requested
optical-flow research and then explicitly prioritized SGSR 2. The user accepted `SGSR 2 Video
Quality` as the best of the four modes in a 480p HDR comparison while retaining every mode.
Physical-display verification is tracked in [#22](https://github.com/Meitoncz/Delos/issues/22),
separately from this recorded acceptance.
This is an adaptation for decoded video, not engine-integrated SGSR 2 with game depth and jitter.
Background and hardware capability evidence: [VIDEO_OPTICAL_FLOW_RESEARCH.md](VIDEO_OPTICAL_FLOW_RESEARCH.md).

Dated test/acceptance entries below are historical evidence, not a live queue. Current codec,
quality and sustained-performance verification is tracked in [#19](https://github.com/Meitoncz/Delos/issues/19).

## Behavior

Stream menu → Upscaler → Mode offers SGSR 1 Standard, SGSR 1 High Quality, **SGSR 2 Video**
and **SGSR 2 Video Quality**.
All remain opt-in, retain live On/Off and independent sharpness, and use the existing HDR/P010 output.
The mode also participates in global and per-game settings through `list_sgsr_quality=temporal`.
Live changes only affect the session; changing modes resets temporal history without reconnecting.
PyroWave uses separate Vulkan implementations of all four modes; it does not feed this GLES stage.

The user reported SGSR 1 made the overall game look worse even though identical-frame diagnostics
showed stronger text edges than bilinear. Do not claim SGSR 1 visual acceptance or dismiss this report.
The user chose 480p for comparison on the 7-inch OLED; 720p already looks close to native to them.

## Pipeline

`SgsrTemporal` runs on the existing SGSR GL thread, after the SurfaceTexture resolve and before final
presentation. It shares the current GPU image and never requests a CPU pixel copy in normal playback.

1. Render analysis luma into an R8 texture and generate a four-level pyramid. Ping-pong current and
   previous luma. Matching uses encoded RGB-derived luma; the actual picture stays FP16 for HDR.
2. Compute **pyramidal Lucas-Kanade backward flow** (current → previous) on an 8-pixel grid, with
   up to six refinement iterations per level and floating-point subpixel propagation.
3. Compute independent forward flow (previous → current). Motion includes the camera and objects.
   Tensor conditioning, photometric residual, bounds and forward/backward consistency control trust.
4. Detect large frame changes from a conservative GPU 8x8 luma comparison; reset rather than reusing
   uncertain history. This is a heuristic, not proof that every game scene cut will be recognized.
5. Reconstruct into ping-pong RGBA16F output textures using the adapted Qualcomm SGSR 2 two-pass-FS
   upscale shader. Its engine Convert stage is replaced by estimated total flow and history rejection.
   Source jitter is zero. Bound input fetches and signed Lanczos normalization avoid edge reads and
   singular/negative-weight instability. All nine spatial taps are enabled. Untrusted history is
   discarded, and trusted accumulation is conservatively limited.
6. Apply bounded, contrast-dependent sharpening only during presentation, never inside accumulated
   history. HDR uses the same ten-bit P010 conversion, metadata, geometry and fence handling as SGSR 1.

History resets on mode changes, initialization/output resize, HDR transitions, nonmonotonic timestamps
and source timestamp gaps over 50 ms. The last threshold is conservative and needs revisiting for
unusually low frame rates. Decoder reset/recreate closes the stage. Spatial modes do not allocate
temporal resources until needed. A failed temporal shader/allocation goes through the existing SGSR
failure status and direct-decoder recovery rather than displaying an invalid HDR frame.

The current flow implementation uses ordinary GLES compute, not QCOM block-matching instructions.
The simple SAD prototype was rejected after synthetic periodic patterns exposed incorrect vectors.
Lucas-Kanade performed substantially better in that fixture. Qualcomm Vulkan block matching is a
possible later optimization, not the algorithm currently shipping in this branch.

## Credits and exact changes

- Official Qualcomm SGSR 2 shader: SnapdragonGameStudios/snapdragon-gsr,
  `d926f074bcb9d714e179f1ce0fcb9ee2eeb5074e`,
  `sgsr/v2/include/glsl_2_pass_fs/sgsr2_upscale.fs`, BSD-3-Clause. Copyright retained in
  `assets/sgsr/sgsr2_video.frag`; license bundled in `assets/licenses/sgsr.txt`.
- Official Unity reference used to verify reconstruction parameters:
  SnapdragonGameStudios/com.qualcomm.snapdragon.sgsr.for.unity,
  `ab685c993ebd0f65a762c6e28f64f124c7f12532`, `Runtime/v2/SGSR2RenderPass.cs`:
  bias is output-width/input-width; the variance-box factor is min(20, area-scale cubed).
- The GLSL video flow, scene-change detector, rejection adapter, lifecycle and presentation sharpening
  are original Delos code under the project's GPL license. No optical-flow library or model weights
  were imported. The mathematical Lucas-Kanade method is implemented directly.

## Verification performed on the physical Odin

- Native headless GLES fixture on Adreno 740 executed the actual flow shader at 854x480 and 1280x720.
  Known shifts (3,-2), (12,7), (0.5,-0.25), (0,0) had mean endpoint error 0.003–0.021 source pixels,
  with 100% of evaluated interior grid points below one pixel error. These are synthetic translations,
  not measured correctness on independently moving objects or occlusions.
- One-direction flow median wall time, including GPU completion, was about 0.53–0.96 ms in that fixture.
  It excludes luma preparation, reverse flow, reconstruction, decoding and display.
- The actual adapted fragment shader compiled on device and preserved uniform values near 0.01,
  0.4 and 0.9 with maximum error around 0.00011 in the FP16 output fixture; no nonfinite values.
- Debug-only `SgsrGpuInstrumentation` exercised the **actual Java renderer** headlessly on the Odin,
  without an Activity or game launch. Passed constants, color transitions, history reset, resize and
  HDR-state transition. Static full temporal-stage wall times after warm-up (20 samples):

| Input → output | Median | p95 |
| --- | --- | --- |
| 854x480 → 1920x1079 | 2.50 ms | 3.09 ms |
| 1280x720 → 1920x1080 | 3.33 ms | 3.70 ms |

These are short static fixtures, not sustained game performance, end-to-end latency, or measured
HDR display accuracy. They exclude the decoder, OES resolve and final presentation. At 120 fps the
entire frame interval is 8.33 ms, so the added cost is material and must be tested during playback.

The headless check is debug-only and invoked with:

```powershell
adb shell am instrument -w io.github.meitoncz.delos.debug/com.limelight.binding.video.SgsrGpuInstrumentation
```

Do not run it while streaming: instrumentation restarts the app process. Inspect foreground first.
Native fixture logs/scripts remain in ignored `build/stream-quality/`; headless Java checks are
versioned under `app/src/debug/` and excluded from release. The stream build has been installed after
checking ShellActivity was foreground. User was asked to compare 480p HDR On/Off, slow/fast pans,
ghosting, smoothness, color/luminance and live toggling. Results are pending.

## Remaining uncertainty

Optical flow can fail on motion blur, occlusions, transparency, particles, repeated textures, tiny
HUD text and compression. Rejection/clamping reduces risk but does not establish good game quality.
No engine depth or actual render jitter is available; stationary 480p input cannot recover detail it
never contains. Long-session performance, thermal behavior and difficult game motion require device
validation before marking this feature complete or merging the branch.

## First game feedback and phase-noise correction

The user tested 480p HDR and reported smooth playback without ghosting, but a spray/dotted appearance
across the picture and text. Reproduced a related failure on the physical GPU with a linear-ramp
fixture at 2.25x scale: max reconstruction error 0.03194 despite constant-color tests passing.
The engine-oriented footprint, when widened by output/input ratio without engine render jitter,
crossed a near-zero signed weight sum and amplified subpixel phase differences. Use a fixed
source-pixel Lanczos footprint for this zero-jitter video adaptation. The same native fixture then
passed with max error 0.00377 (roughly 8x lower); movement fixtures still passed. Added the ramp case
to the versioned Java GPU instrumentation so constants alone cannot mask a recurrence.
The full Java/GPU instrumentation also passed the new ramp regression (max error 0.00377), plus
existing color/reset/resize checks. Corrected static-stage timing was about 2.06 ms median / 2.61 ms
p95 at 480p and 2.80 / 3.00 ms at 720p in the short fixture. Assembly, all 63 JVM tests and lint passed.
Installed outside a stream and reopened ShellActivity. The user's game retest is pending.

The user also asked about perceived sharpening with SGSR disabled. Inspected the decoder surface
selection: on disable, SGSR is closed (including hiding/clearing the HDR child layer), and MediaCodec
is configured with the original `renderTarget`. No Delos sharpening shader is applied on that path.
Added an explicit configuration log to verify live toggles reach direct output. This does not establish
whether the game, host, decoder/display scaler or device software applies its own enhancement.

A/B verification then used the actual pre-feature CI APK from main `9fd31402`, temporarily re-signed
with the existing local debug key for a data-preserving update. User tested the same 480p HDR game
and confirmed the original build is equally oversharpened. This rules out today's changes as the
origin of that baseline appearance; it does not identify the exact upstream/display component.
The current feature APK was restored outside a stream. The user declined a neutral bilinear mode;
Off retains the original direct output. New persistence of session upscaler choices per game was
explicitly deferred by the user.

## Returnal edge capture and anti-ringing correction

Continuous one-second captures compared the same 480p HDR Returnal menu through direct AV1, direct
HEVC and SGSR 2 at zero presentation sharpness. The user judged AV1 and HEVC equally over-sharp.
Both Qualcomm decoders reported zero enabled pipeline filters and identical HDR color metadata.
The direct screenshots also looked alike, excluding an AV1-specific post-process as the cause.

SGSR 2 itself made the high-contrast UI edges worse even at zero presentation sharpness. The older
2-pass shader allowed its signed-Lanczos result to exceed the actual 3x3 source neighbourhood by
`0.075`. On the captured yellow focus rectangle, the lower edge reached screenshot luma about 212
against an interior near 179. The adapted shader now clamps reconstruction to the actual local
minimum and maximum. The repeated capture measured the edge near 173 against an interior near 178,
removing the bright halo. This matches the exact min/max clamp in Qualcomm's newer 3-pass compute
implementation. It does not remove aliasing already present in the 480p encoded source.

The corrected build, 63 JVM tests and lint passed. Headless instrumentation on the Odin's Adreno 740
also passed constants, scene/reset/resize/HDR transitions and the 2.25x ramp regression. Static full
temporal-stage timing was 2.40 ms median / 2.56 ms p95 at 480p and 2.76 / 2.88 ms at 720p.

## Official quality variant and tuning audit

Qualcomm provides three SGSR 2 variants at the pinned source revision: recommended fast 2-pass
fragment and compute implementations, plus a 3-pass compute implementation that explicitly favors
visual quality. The current Delos video adaptation uses the 2-pass fragment reconstruction, already
with all nine spatial samples rather than the faster five-sample option.

The quality-oriented 3-pass pipeline adds an Activate stage, reactive/disocclusion handling and an
optional pixel lock for thin-feature stability. Its optional RCAS sharpening is a separate fourth
stage and is disabled by default in Qualcomm's Unity integration.

`SGSR 2 Video Quality` adapts that structure for decoded video. A separate Activate compute pass
derives a reactive mask from optical-flow confidence, luma residual and forward/backward
consistency. Reconstruction retains the nine-tap kernel and clips temporal history in YCoCg so
colored edges are not rejected independently per RGB channel. RCAS and pixel lock remain disabled:
RCAS would duplicate the existing optional presentation sharpener, while pixel lock without engine
jitter can preserve source aliasing on static thin features. Decoded video still has no engine depth,
object motion vectors, camera matrices or render jitter, so this is a video-specific adaptation, not
an unchanged copy of the engine integration.

The mode passed the real Adreno 740 shader compiler and headless GPU checks for constant colors,
scene/reset/resize/HDR transitions and the 2.25x phase-noise regression. At 854x480 to 1920x1079 the
full temporal stage measured 3.26 ms median / 3.99 ms p95; at 1280x720 to 1920x1080 it measured
3.90 / 4.26 ms. The user found the improvement modest at 480p but selected this as the best mode;
remaining static logo aliasing is consistent with missing source detail at that resolution.

The Sharpness control is not a SGSR 2 reconstruction-quality parameter. It controls a separate,
bounded presentation-only sharpener after reconstruction; 0% bypasses that sharpening and its output
is never accumulated into temporal history.

## PyroWave Vulkan path

PyroWave implements all four modes directly from decoded Y/Cb/Cr planes:

- **SGSR 1 Standard:** Qualcomm's spatial edge-direction filter.
- **SGSR 1 High Quality:** the distinct Unity HQ reconstruction with RGB-derived luma,
  lower edge threshold and a 60/40 blend of two weighted estimates.
- **SGSR 2 Video:** bidirectional optical flow on an 8-pixel grid and temporal reconstruction.
- **SGSR 2 Video Quality:** a separate input-resolution Activate pass derives a reactive mask
  from luma changes, flow confidence, forward/backward consistency and disocclusion; reconstruction
  additionally clips history in YCoCg around a weighted current-frame center.

Intermediate RGB stays FP16 and in the source's encoded domain, including PQ for HDR.
Presentation-only bounded sharpening is shared by the SDR swapchain and BT.2020/P010 pack pass.
Zero sharpness bypasses the filter; sharpened pixels never feed temporal history.
Off bypasses all reconstruction and sharpening while retaining the saved sharpness setting.

Requested mode and applied mode are separate. Live changes apply after the current frame fence,
without restarting the network connection or decoder. Switching between spatial, temporal and
Quality modes recreates only the required resources; spatial modes allocate no motion/history pair.
Mode and HDR changes reset temporal history. A reset returns fresh reconstruction before reading
undefined previous-frame images. HDR rebuilds the dependent pack/present pipelines in ownership order.
Failure or a CPU-only P010 bridge reports direct rendering rather than an unsupported active mode.
All post-decode passes remain inside the existing GPU draw timestamp interval.

Native-resolution supersampling is available for all four modes at the existing 1x/2x levels and
size limits, matching the MediaCodec path.

### GPU regression probe

The debug APK includes `PyroWaveGpuInstrumentation`. It dispatches the shipped Vulkan SPIR-V and
reads back real GPU output without launching a game or opening a streaming connection:

```powershell
adb -s <device> shell am instrument -w io.github.meitoncz.delos.debug/com.limelight.binding.video.PyroWaveGpuInstrumentation
```

The Odin Adreno 740 run passed constant preservation, Standard/HQ textured edges and local bounds,
bidirectional motion, distinct regular/Quality output, activation/reset and scene transitions,
16-bit BT.2020 values, zero/nonzero sharpening and limited-range P010 packing.
Standard/HQ maximum pixel difference was 0.0620117; regular/Quality was 0.0114746.
P010 packing maximum error was 0.000482917. These are synthetic correctness checks, not a claim
of visual acceptance or sustained 60/120 FPS performance in a game.
The production-renderer probe additionally checks allocation/dispatch, actual applied modes,
both supersampling levels, Off teardown and SDR → HDR → SDR pipeline rebuilding.

### Earlier SGSR 2 Video acceptance

The target is the real display size, aspect-fitted from the stream dimensions; the first implementation
incorrectly inherited a fixed surface size and reconstructed 720p back to 720p. Device logs after the
fix confirmed `1280x720 -> 1920x1080`. A fused reconstruction/clipping shader reduced draw cost from
about 6.5-6.8 ms to mostly 3.9-4.9 ms, allowing about 118-119 fps during the tested 120 Hz HDR stream.
The user accepted the improved latency, HDR colour, live toggle and processing-time statistics.
