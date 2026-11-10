# SGSR2-DV

Initially implemented on `feature/stream-quality` on 2026-10-26 after the user requested
optical-flow research and then explicitly prioritized SGSR 2. The user accepted `SGSR2-DV
Quality` as the best of the four modes in a 480p HDR comparison while retaining every mode.
Physical-display verification is tracked in [#22](https://github.com/Meitoncz/Delos/issues/22),
separately from this recorded acceptance.
This is an adaptation for decoded video, not engine-integrated SGSR 2 with game depth and jitter.
Background and hardware capability evidence: [VIDEO_OPTICAL_FLOW_RESEARCH.md](VIDEO_OPTICAL_FLOW_RESEARCH.md).

Dated test/acceptance entries below are historical evidence, not a live queue. Current codec,
quality and sustained-performance verification is tracked in [#19](https://github.com/Meitoncz/Delos/issues/19).

## Behavior

Stream menu → Upscaler → Mode offers SGSR 1 (Normal), SGSR 1 (Quality), **SGSR2-DV**
and **SGSR2-DV (Quality)**.
All remain opt-in, retain live On/Off and independent sharpness, and use the existing HDR/P010 output.
The mode also participates in global and per-game settings through `list_sgsr_quality=temporal`.
Live changes only affect the session; changing modes resets temporal history without reconnecting.
PyroWave uses separate Vulkan implementations of all four modes; it does not feed this GLES stage.
A fifth mode, **Sharpen only** (`list_sgsr_quality=sharpen`), does no scaling and no temporal work: it is documented in
[STREAM_QUALITY.md](STREAM_QUALITY.md#sharpen-only-mode) together with how its cost is measured.

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
failure status and direct-decoder recovery rather than displaying an invalid HDR frame. A single failed
frame (a texture update or buffer swap failing while the surface is replaced) is skipped; only a streak
of about a second of failed frames stops SGSR and recovers to direct output.

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

A/B verification then used the actual pre-feature CI APK from main `714f21a3`, temporarily re-signed
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

`SGSR2-DV (Quality)` adapts that structure for decoded video. A separate Activate compute pass
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

- **SGSR 1 (Normal):** Qualcomm's spatial edge-direction filter.
- **SGSR 1 (Quality):** the distinct Unity HQ reconstruction with RGB-derived luma,
  lower edge threshold and a 60/40 blend of two weighted estimates.
- **SGSR2-DV:** bidirectional optical flow on an 8-pixel grid and temporal reconstruction.
- **SGSR2-DV (Quality):** a separate input-resolution Activate pass derives a reactive mask
  from luma changes, flow confidence, forward/backward consistency and disocclusion; reconstruction
  additionally clips history in YCoCg around a weighted current-frame center.

Intermediate RGB stays FP16 and in the source's encoded domain, including PQ for HDR.
Presentation-only bounded sharpening is shared by the SDR swapchain and BT.2020/P010 pack pass.
Zero sharpness bypasses the filter; sharpened pixels never feed temporal history.
Off bypasses all reconstruction and sharpening while retaining the saved sharpness setting.

Requested mode and applied mode are separate. Live changes apply after the current frame fence,
without restarting the network connection or decoder. Switching between spatial, temporal and
Quality modes recreates only the required resources; spatial modes allocate no motion/history pair.
Mode and HDR changes reset temporal history. A whole-frame luma cut (the same conservative 8x8
comparison as the MediaCodec path, `sgsr_scene.comp`) and a gap of more than 50 ms between
frames reset it as well. A reset returns fresh reconstruction before reading
undefined previous-frame images. HDR rebuilds the dependent pack/present pipelines in ownership order.
A failed upscaler rebuild, at the HDR switch or when its level does not fit, falls back to level 1 and
then to direct rendering rather than an unsupported active mode; a failed swapchain or pipeline rebuild
is retried before the next frame is drawn.
In HDR the upscaler needs P010 buffers at the presentation size. If those cannot be allocated, the
upscaler is dropped (and stays off until the next HDR switch retries it) and the GPU P010 bridge is
retried at the stream size; only if that fails too does the CPU readback path take over, which also
reports direct rendering. A later HDR switch retries the GPU bridge.
A rebuild of the P010 output (a mode or level change that needs new resources, or the switch to SDR)
keeps the previous output's SurfaceControl on screen with its last picture. The new output's first
transaction shows it and removes the old control atomically; after a switch to the RGB swapchain the
old control leaves once three swapchain frames have been presented. The display therefore holds the
last frame for the rebuild instead of showing the empty window underneath.
All post-decode passes remain inside the existing GPU draw timestamp interval.

Native-resolution supersampling is available for all four modes at the existing 1x/2x levels and
size limits, matching the MediaCodec path. The native planner receives its size limits and the
"native input" tolerance from `SgsrDimensions` when the renderer is created, and judges whether a
stream is native against the display it is aspect-fitted to: a stream larger than its display is
a downscale and is never supersampled (its presentation size stays the stream size, since shrinking is
the display scaler's job). If level 2 cannot be allocated the renderer falls back to level 1 and the
stream menu reports the level in effect: the request at once, 1 after that fallback.

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
The production-renderer probe additionally checks the upscaler's resource allocation and compute
dispatch, actual applied modes, both supersampling levels, the downscale case, Off teardown and the
swapchain-format rebuild of SDR → HDR → SDR. It does not run `present()`, so the swapchain/P010
present passes, the P010 pack pass and the GL bridge are not covered by it.

### Earlier SGSR2-DV acceptance

The target is the real display size, aspect-fitted from the stream dimensions; the first implementation
incorrectly inherited a fixed surface size and reconstructed 720p back to 720p. Device logs after the
fix confirmed `1280x720 -> 1920x1080`. A fused reconstruction/clipping shader reduced draw cost from
about 6.5-6.8 ms to mostly 3.9-4.9 ms, allowing about 118-119 fps during the tested 120 Hz HDR stream.
The user accepted the improved latency, HDR colour, live toggle and processing-time statistics.

## Verification against the official reference (2026-11)

Source audit for [#74](https://github.com/Meitoncz/Delos/issues/74), performed on 2026-11-07.
This verifies the implementation against the specific references below, **not** pixel-identical
engine integration, physical HDR accuracy, sustained performance or subjective quality.
The two video renderers are not interchangeable implementations of an unchanged Qualcomm pipeline.

### Reference revisions and scope

- Qualcomm SGSR repository, `d926f074bcb9d714e179f1ce0fcb9ee2eeb5074e`:
  [SGSR 1 integration/parameters](https://raw.githubusercontent.com/SnapdragonGameStudios/snapdragon-gsr/d926f074bcb9d714e179f1ce0fcb9ee2eeb5074e/sgsr/v1/README.md),
  [edge-direction spatial shader](https://raw.githubusercontent.com/SnapdragonGameStudios/snapdragon-gsr/d926f074bcb9d714e179f1ce0fcb9ee2eeb5074e/sgsr/v1/include/glsl/sgsr1_shader_mobile_edge_direction.frag),
  [SGSR 2 integration contract](https://raw.githubusercontent.com/SnapdragonGameStudios/snapdragon-gsr/d926f074bcb9d714e179f1ce0fcb9ee2eeb5074e/sgsr/v2/README.md),
  [two-pass fragment reconstruction](https://raw.githubusercontent.com/SnapdragonGameStudios/snapdragon-gsr/d926f074bcb9d714e179f1ce0fcb9ee2eeb5074e/sgsr/v2/include/glsl_2_pass_fs/sgsr2_upscale.fs),
  and the three-pass compute Convert/Activate/Upscale sources at that revision.
- Official Unity package, `ab685c993ebd0f65a762c6e28f64f124c7f12532`:
  [SGSR 1 (Normal)/HQ/VR kernels](https://raw.githubusercontent.com/SnapdragonGameStudios/com.qualcomm.snapdragon.sgsr.for.unity/ab685c993ebd0f65a762c6e28f64f124c7f12532/Shaders/v1/sgsr.hlsl),
  [spatial defaults](https://raw.githubusercontent.com/SnapdragonGameStudios/com.qualcomm.snapdragon.sgsr.for.unity/ab685c993ebd0f65a762c6e28f64f124c7f12532/Shaders/v1/SGSRShader.shader),
  and [SGSR 2 parameter setup](https://raw.githubusercontent.com/SnapdragonGameStudios/com.qualcomm.snapdragon.sgsr.for.unity/ab685c993ebd0f65a762c6e28f64f124c7f12532/Runtime/v2/SGSR2RenderPass.cs).
- Official Vulkan/Android sample framework, `60a13ff4bfa2eb4e5b293babbeccb9be9e4bfc4f`:
  [fragment-context setup and dispatch](https://raw.githubusercontent.com/SnapdragonGameStudios/adreno-gpu-vulkan-code-sample-framework/60a13ff4bfa2eb4e5b293babbeccb9be9e4bfc4f/samples/sgsr2/code/main/sgsr2_context_frag.cpp).
  It supplies real depth/velocity, camera reprojection and 32 Halton jitter positions; it allocates
  render-resolution motion data and display-resolution FP16 ping-pong history. Its input point
  samplers do not mean that Delos's history/flow interpolation should become point sampling:
  explicit `texelFetch` reconstruction taps already bypass texture filtering.

Local scope: all ten `assets/sgsr/` shaders, `SgsrRenderer`, `SgsrTemporal`,
`MediaCodecDecoderRenderer`, `SgsrDimensions`, `DelosStreamActivity` surface sizing, and
`jni/pyrowave-renderer/pyrowave_renderer.cpp`, its SGSR source/spatial/flow/activation/upscale/
scene/presentation/pack/sharpen shaders and HDR output bridge. The native sources are under
`jni/pyrowave-renderer/`, not under `moonlight-core/`.

### Verified source-level behavior (audit baseline, before the 2026-11-07 gain follow-up)

| Area | Verified result |
| --- | --- |
| SGSR 1 (Normal) | Twelve-tap positions/gather ordering, green-channel luma, `8/255` edge threshold, direction regularization `3.07574e-05`, variance factor `10.14185`, Lanczos polynomial and `±23/255` correction limit match the edge-direction reference when that optional path is enabled. Delos deliberately enables direction filtering; the standalone official file comments its switch out by default. |
| SGSR 1 HQ | RGB luma `0.299/0.587/0.114`, `4/255` threshold, `-6/sumSquares` variance, both weighted estimates and the 60% edge / 40% smooth blend match the Unity HQ target. The zero-variance guard and GLES full precision are deliberate robustness differences, not a different tap layout. Gain differs materially; see below. |
| Spatial sample coordinates | GLES passes `(1/inputWidth,1/inputHeight,inputWidth,inputHeight)` and full normalized UVs, with the reference's half-pixel convention. Native SGSR 1 reproduces the gather taps with explicit plane reads and bilinear base sampling. No unconditional missing half-pixel correction was found in these spatial paths. |
| GLES temporal reconstruction | `FastLanczos`, the weighted center/variance calculations and nine-tap structure derive from the pinned fragment shader. All nine taps are enabled rather than its default five. `scaleRatio.y = min(20, areaRatio^3)` matches Unity and the Android sample; the footprint's scale-dependent term is intentionally not used for zero-jitter video. |
| Motion direction | Both video paths estimate current-to-previous flow in source pixels. In GLES, `Motion = -2*flow/renderSize` and `PrevUV = uv - 0.5*Motion` correctly reduce to `uv + flow/renderSize`. Flow grid centers at 4,12,... match `((uv*inputSize-0.5)/8)/flowSize`; there is no engine NDC vector being silently treated as source pixels. |
| History/lifecycle | GLES ping-pongs output-resolution RGBA16F history and resets for invalid/nonmonotonic timestamps, gaps above 50 ms, resize and HDR changes. Native ping-pongs history and skips history/flow reads on its reset/cut branch. These are source-level checks, not proof that every real scene cut is detected. |
| Encoded color and range | The native source shader expands limited Y by 219 code units and chroma by 224, selects BT.601/709/2020 NCL coefficients and handles left-aligned higher-bit-depth planes. Image reconstruction remains in encoded RGB/PQ rather than linear light. GLES's external sampler performs the decoder-to-RGB conversion in the driver; that conversion is not proven by this audit. |
| HDR packing | Explicit P010 packing uses BT.2020 NCL for HDR and limited 10-bit `Y=64+876*Y`, `Cb/Cr=512+896*C`, divided by 1023. HDR metadata/dataspace and the existing child-layer/fence path are retained. This verifies formulas and plumbing, not panel luminance or driver chroma resampling. |
| Surface sizing | Scaling modes request layout-sized SDR surfaces, with the decoded 8-bit fallback handled before SGSR creation. Sharpen only retains stream-sized buffers. HDR uses the view-sized child target with parent-size fallback when layout dimensions are unavailable. Log the actual sizes; a requested size is not device evidence. |
| Presentation order | Temporal history excludes final sharpening. Native sharpen is in the presentation/pack pass; GLES temporal sharpening is in `temporal_present.frag`. `arrival.frag` is a transient launch effect, not steady-state reconstruction. |

### Material differences and possible softness mechanisms

These differences are not permission to restore engine defaults indiscriminately. The earlier
zero-jitter phase-noise and anti-ringing fixes above had measured reasons.
Predictions about perceived softness or motion quality in the effect column are **[INFERENCE]**,
not new measurements. Exact formulas and operation order are source observations.

| Difference | Reason/current behavior | Expected effect or limit |
| --- | --- | --- |
| Follow-up status (2026-11-07) | The next two rows preserve the pre-change audit baseline. Both pipelines now fix SGSR 1 (Normal)/HQ reconstruction gain at `2.0` and apply an independent final sharpener; see the dated follow-up below. | The old slider/gain mismatch is no longer the intended behavior. This is not device acceptance. |
| SGSR 1 reconstruction gain | Official Standard/HQ use `2.0` (documented range 1–2). GLES uses `0.5 + 1.5*sharpness`: 50% means 1.25; 100% reaches 2.0. Native uses a fixed 0.5 and applies its separate presentation sharpener afterwards (`sgsr_spatial.comp`, `sgsr_sharpen.glsl`). | The same slider value is **not** the same spatial reconstruction across renderers. Lower gain attenuates the mean-subtracted reconstruction correction before local clamping; a later bounded sharpener is not mathematically equivalent. This is an evidence-backed softness candidate, not a measured ranking or an automatically corrected tuning defect. |
| SGSR 1 slider semantics | GLES spatial modes change the reconstruction gain itself. Native and GLES temporal modes use a separate presentation filter. | The general description of an independent final sharpener does not precisely describe GLES SGSR 1. Zero sharpness does not turn off SGSR 1 reconstruction; use Enabled/Off for that comparison. |
| No engine jitter/depth/object vectors | Video already contains the host's resolved, compressed scene and HUD. Delos substitutes bidirectional Lucas–Kanade flow, confidence, photometric residual and scene heuristics; jitter is zero. | Sound as a conservative video heuristic, **not equivalent** to engine SGSR 2. A static image supplies no new jittered subpixel samples; optical flow cannot infer reliable depth, transparency, occlusion or lost source detail. |
| Analysis precision/domain | GLES builds R8 luma with 0.2126/0.7152/0.0722 weights, four pyramid levels and six LK refinements; native estimates from its decoded luma planes with its own iteration policy. Actual HDR image/history remains FP16. | R8 quantization and encoded-domain brightness changes can weaken flow confidence. These luma weights are an analysis proxy, not an assertion that PQ/BT.2020 was converted into linear BT.709 color. Pixel/flow accuracy on games remains unverified. |
| Fixed temporal footprint | Both video implementations use the `distanceSquared*0.25` Lanczos footprint rather than the engine's scale/depth-dependent bias. | Wider support than the engine default at common upscale ratios can smooth detail. GLES intentionally fixed it after measured zero-jitter instability; restoring the official term without rerunning the ramp/phase fixture would repeat a known risk. |
| Tighter reconstruction bounds | Video reconstruction clamps to the actual neighborhood rather than the old fragment shader's `±0.075` allowance. Official compute/Unity variants also differ in their clamp margin/domain. | Suppresses bright/dark outlines but limits edge overshoot. Less ringing is not necessarily more recovered detail or the sharpest-looking result. |
| Native temporal kernel is not identical to GLES | Native centers its 3×3 taps on `floor(uv*inputSize-0.5)`; GLES uses `floor(uv*inputSize)`. Native uses YCoCg history bounds even in regular Video, a 0.12 current-weight floor and residual-driven rejection; GLES regular Video uses an RGB variance box and limits trusted history to at most 75%. | Tap selection can differ by one source pixel for part of an upscale phase. History smoothing/rejection differs too. Shared mode names do not establish pixel parity; compare phase sweeps, mirrored edges and moving fine detail before choosing a policy. |
| Quality is video-specific | The extra Activate pass uses confidence, forward/backward consistency and luma change, not official depth separation, pre-transparent color and luma-history bit packing. Its YCoCg center/extent clipping is not an unchanged three-pass SGSR 2 port. | Can reduce colored history contamination but cannot reproduce the engine's disocclusion/reactive information. The corrected GLES gate is described below. |
| HDR working domain | The video filter blends encoded PQ directly. The official three-pass compute path has its own packed/tonemapped YCoCg and pre-exposure contract; the fragment and Unity variants are not one universal HDR pipeline. | Do not claim full HDR equivalence to the engine sample. Exposure changes/highlights need actual HDR comparisons; matrix/range correctness alone is insufficient. |
| Extra resampling | GLES first resolves the external texture through the actual SurfaceTexture transform with linear sampling. Near native-size input, both renderers can reconstruct at 4/3× or 2× internal resolution and linearly resolve to presentation size. | A crop/producer transform can prevent a perfectly 1:1 copy. A 2× linear resolve averages a 2×2 footprint; 4/3× has phase-dependent weights. This can reduce aliasing **and** attenuate detail. A supersampled output is not additional source information. |
| Final sharpener differs from Qualcomm | Video presentation uses Delos's bounded cross filter, not the optional engine RCAS pass. Sharpen only uses the separately documented CAS-style filter at stream size. | Matching the number on a slider does not match RCAS or the original SGSR 1 gain. Upscaling after stream-size Sharpen only is a different operation order from sharpening reconstructed display-size content. |
| HUD and chroma limits | Official guidance overlays 2D UI at native resolution. Delos cannot separate the game's already-encoded HUD; HDR presentation uses the existing 4:2:0 P010 bridge. | Tiny HUD text and colored one-pixel edges are a poor basis for expecting engine-demo results. Native 4:4:4 input does not establish 4:4:4 HDR output through that bridge. |

### Confirmed defect corrected during this audit

The GLES Quality shader calculated a clipped YCoCg history color but decided whether to use that
color with an **RGB-only** bounding-box test. A history color can be inside the RGB box and outside
the tighter YCoCg box; in that case `mix(clampedcolor, HistoryColor, 1)` restored the raw history.
The rejection predicate now includes the same YCoCg bounds that Quality clips against. Regular
Video retains its existing RGB predicate. Native Quality already consumes its YCoCg-clipped history
without this gate and required no corresponding change.

Controlled reproduction used the actual fragment source, changing only ES 3.1 declarations to
WebGL2-compatible ES 3.0 declarations for headless execution: a 3×3 primary-color input, white
history, zero motion, full flow confidence and no scene/reactive reset, rendered to 9×9.

| Center RGB | Before | After |
| --- | --- | --- |
| Regular Video | (0.75, 1.0, 0.75) | (0.75, 1.0, 0.75) |
| Video Quality | (0.75, 1.0, 0.75) | approximately (0.35294, 0.64706, 0.35294) |

Both runs returned no GL error. RGBA16F output after the fix was approximately
(0.35278, 0.64697, 0.35278), within the existing 0.003 GPU-fixture tolerance.
This proves the conditional-history defect and the source-port correction, not performance or
Adreno/driver behavior. The same case is now in `SgsrGpuInstrumentation` alongside constants and
the phase ramp; its updated Java compiled against the real Android API. It was **not run on Odin**
in this audit. The shader and GPU regression are isolated in commit `e5ad4ce3`.

### Concrete on-device comparison

The lead owns builds, tests, lint, safe installation and device execution. Do not run instrumentation
while streaming: it restarts the app. Let the user launch games normally.

1. **Record the real path first:** codec, decoded/cropped size, SDR/HDR and color metadata,
   presentation/internal sizes, applied mode, supersampling level and sharpness. Include the
   `SGSR output` and P010 target/parent-size messages, or the native applied-size diagnostics.
   Verify the HDR view has been laid out; a parent-size fallback can invalidate an assumed 1080p test.
2. **Separate scale from sharpness:** compare a low-resolution stream (480p or 720p) with a
   native-resolution stream. Compare GLES SGSR 1 at 0/50/100%; the last reaches the reference gain.
   Compare native separately at the same source/output sizes, not as an assumed identical slider.
   Keep the host's own AA, sharpening and bitrate unchanged.
3. **Use identical decoded content:** freeze a game scene/menu on the host through normal user
   interaction, capture several settled frames after each mode change, and compare equal crops.
   A paused animation or a short loop is preferable to unrelated screenshots. Off is the existing
   direct display path, not a neutral software-bilinear reference. Do not attribute every Off/On
   difference to the reconstruction kernel.
4. **Static crops:** small white text, a saturated yellow/red/cyan focus border, slanted rails or
   wires, brick/foliage detail and a dark-to-bright smooth ramp. Inspect overshoot, phase speckle,
   one-pixel shift, chroma spread and lost texture separately. Include corners/edges to exercise
   clamped taps. Native supersampling comparisons must state the actual internal size.
5. **Motion crops:** slow fractional-pixel pans, fast pans, an object crossing a contrasting
   background, particles/transparency, HUD over moving scenery, and a hard scene cut. Compare
   a sequence rather than one still for trails, flicker and delayed detail. Also test 20 FPS or
   lower: the GLES 50 ms reset threshold can suppress accumulation at low cadence.
6. **HDR/color:** repeat neutral gray, near-black ramps, bright highlights and saturated borders
   in SDR and HDR with the same exposure. Check legal-range black/white and BT.709/2020 transitions.
   Screenshots may pass through tone mapping; they alone cannot prove PQ luminance accuracy.
7. **Run existing GPU probes outside a stream:** GLES now includes the colored-history regression;
   retain constants, ramp, resize/reset checks and native source/P010 probes. Then measure actual
   processing time, frame cadence and thermals during sustained 60/120 FPS playback separately.

Unverified here: physical same-frame renderer comparisons, actual crop/SurfaceTexture matrices,
first-frame EGL/view sizing, driver external-YUV conversion/chroma siting, SurfaceFlinger filtering,
P010 display colorimetry, real-game flow/occlusion accuracy and sustained latency/power. No Gradle,
ADB, installation or game launch was performed. Other differences above were documented rather
than silently retuned; the source audit does not establish one universal cause of perceived softness.

## SGSR 1 fixed-gain follow-up (2026-11-07)

The user approved restoring the official **2.0 reconstruction gain** for SGSR 1 (Normal) and
High Quality in both MediaCodec/GLES and PyroWave/Vulkan. This supersedes the two historical
SGSR 1 gain/slider rows above, without changing their audit evidence or the earlier device results.

| Pipeline / mode | Audit baseline | Follow-up |
| --- | --- | --- |
| GLES SGSR 1 (Normal) and HQ | Reconstruction gain `0.5 + 1.5*s`: `1.25` at 50%, `2.0` at 100%. | Fixed reconstruction gain `2.0` at every slider value; independent bounded final presentation filter. |
| Native SGSR 1 (Normal) and HQ | Fixed reconstruction gain `0.5`, then the existing presentation filter. | Fixed reconstruction gain `2.0`; the same SGSR 1-only final-filter mapping as GLES. |
| SGSR2-DV / Video Quality | Independent presentation sharpening after reconstruction. | Unchanged reconstruction and slider mapping; sharpened pixels still never enter history. |
| Sharpen only | Stream-size CAS-style filter, no reconstruction. | Unchanged algorithm, slider mapping and sizing. |

Here `s` is the normalized Sharpness slider (`0..1`, default `0.5`). For **SGSR 1 only**, effective
presentation strength is `0.5*s`. The existing bounded cross filter's detail coefficient is `0.75`,
so its pre-limit correction is `detail * (0.375*s)`: coefficient `0.1875` at the default 50% and
`0.375` at 100%. Each channel's correction remains bounded by `min(0.06, 0.25*localRange)`,
with the existing local-range/output clamps retained. At 0% the final filter is bypassed, but
SGSR 1 reconstruction still uses gain 2.0; only Upscaler Off bypasses reconstruction.

This half-strength mapping is deliberately milder than the native baseline's full-strength
presentation mapping (detail coefficient `0.375` at 50%, `0.75` at 100%), because reconstruction
now contributes its full official correction. The default is intended to be official SGSR 1 plus
a mild independent sharpen **[INFERENCE]**, not a measured perceptual equivalence to Qualcomm RCAS.
The optional official RCAS stage is separate from reconstruction; Delos keeps its existing bounded
cross filter rather than adding RCAS or retuning SGSR 2/CAS.

GLES reuses the existing supersampling RGB intermediate target for one reconstruction draw followed
by cross-filter presentation, rather than evaluating five full reconstruction kernels per output
pixel. No new target type is introduced, but ordinary spatial upscaling now also needs that RGB
intermediate (including at zero sharpness, where the final resolve skips its cross taps). HDR
intermediates remain encoded RGB/PQ; conversion
to P010 belongs to the existing final-output helper, not to the reconstruction target. Native already
has its compute intermediate and final presentation/pack stage. The added GLES pass, intermediate
storage and bandwidth cost are **unmeasured**; target reuse is not a claim of zero extra GPU cost.

The lead's device comparison should keep the same scene, source/output sizes, host AA/sharpening
and bitrate: compare main versus this follow-up at 50% for Standard and HQ in each pipeline,
then 0/50/100% to isolate final sharpening. Unlike the historical comparison recipe above, all
three SGSR 1 slider positions now use the official gain. Include SDR/HDR/P010, the launch arrival
effect, live mode changes, halos, fine text, texture and sustained processing time. Stronger
reconstruction detail is expected **[INFERENCE]**, but ringing or compression visibility may also
increase. Device acceptance and performance have not been measured for this follow-up.

Standalone checks for this follow-up: all 13 native shaders compiled with the NDK compiler and
the embedded SPIR-V header was regenerated; the renderer C++ passed Android ARM64/API 29 syntax
checking. The changed Java renderer and help Kotlin compiled against real cached Android/project
APIs. All three GLES shaders passed offline compilation; Standard's existing precision-qualified
`highp vec2(...)` constructors were normalized only in disposable compiler input because `glslc`
rejects that driver-specific syntax. No production constructor syntax was changed.

Actual headless GL execution of the GLES final shader and the native shared filter body gave the
same bounded results on a center `0.5` / cross-neighbor `0.4` fixture:

| Slider | GLES output | Native SGSR 1 filter output |
| --- | --- | --- |
| 0% | 0.5 | 0.5 |
| 50% | 0.51875 | 0.51875 |
| 100% | 0.525 | 0.525 |

At 100% the local-range limit clamps the correction to `0.025`, as intended. These are filter
runtime checks, not an Android Vulkan presentation, a physical HDR test, or a reconstruction
quality/performance comparison. Gradle and ADB were not run.
