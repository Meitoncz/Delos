# Video optical flow for an SGSR 2-derived upscaler

Research requested 2026-10-26. This is a feasibility report and proposed experiment, not an implemented
feature or a benchmark. The existing SGSR 1 (Normal) / High Quality implementation remains separate.

## Finding

Motion can be estimated from consecutive decoded video frames. A promising path on the actual Odin is
coarse-to-fine GPU block matching with confidence/occlusion rejection. However, feeding estimated
motion into unmodified SGSR 2 is insufficient: its engine depth, camera and jitter assumptions must
also be addressed. A working result would be an SGSR 2-derived video reconstruction pipeline whose
quality and latency need validation, not equivalent to native engine-integrated SGSR 2.

The main opportunity is stable reconstruction in motion. Do not promise native-resolution detail from
480p: a non-jittered stationary video stream contains no new subpixel samples to accumulate. Compression,
motion blur and already-rendered HUD/text also constrain the result.

## Verified on the Odin

Read-only ADB queries and a small native Vulkan capability probe were run on the physical device.
No games, navigation, rendering workloads or benchmark streams were started by the agent.

| Capability | Observed result |
| --- | --- |
| SoC property | `QCS8550` |
| GPU | Adreno 740 |
| GLES | OpenGL ES 3.2, driver V@0676.53 |
| `VK_QCOM_image_processing` | Present |
| `textureBlockMatch`, `textureBoxFilter`, `textureSampleWeighted` | All true |
| Maximum block-match region | 64x64 |
| `VK_FORMAT_R8_UNORM` optimal-tiling block matching | Supported |
| `VK_FORMAT_R16_SFLOAT` optimal-tiling block matching | Not supported |
| `VK_QCOM_image_processing2` | Absent |
| `VK_NV_optical_flow` | Absent |

Evidence: ignored `build/stream-quality/odin-optical-flow-capabilities.txt`, `odin-vulkan.json`,
`optical_flow_probe.cpp`. The probe only creates an instance and queries physical-device properties;
it does not create queues or render. Its executable was deployed as
`/data/local/tmp/delos-optical-flow-probe`. These findings prove capability, not speed or correctness
of an optical-flow implementation. Image formats, enabled features and shader compilation must be
checked again in the actual rendering context.

The first Qualcomm extension accelerates a **candidate block comparison**, not a complete motion
search. Delos would still supply pyramids, search strategy, filtering, subpixel refinement and
confidence. The second extension's window-search operations cannot be assumed available on this Odin.
No `GL_QCOM_image_processing` was advertised in the SurfaceFlinger GLES extension dump; that dump is
not a substitute for checking the app's own context. The verified acceleration is Vulkan, whereas
the current MediaCodec SGSR stage uses GLES. Interop or a renderer change is a material cost.

## Candidate algorithms

| Candidate | Role and advantages | Limitations / decision |
| --- | --- | --- |
| Pyramidal SAD/SSD block matching on GPU, optionally QCOM accelerated | Fits the verified hardware; configurable search and resolution; keeps video on GPU | Recommended first device prototype; block boundaries, repeated textures and occlusions require refinement and rejection |
| FidelityFX Optical Flow | Public game-oriented pyramidal block matching; 8x8 output grid; scene-cut detection and HDR input parameters | Useful reference or port candidate, not a drop-in Android library: published implementation requires SM 6.2, wave operations and `msad4`; actual Adreno cost unmeasured |
| OpenCV DIS (Dense Inverse Search) | Existing dense CPU algorithm with Ultrafast/Fast/Medium presets; useful offline baseline | GPU readback/CPU work/upload risks stalls in the current pipeline; desktop paper timings do not establish Odin performance |
| Pyramidal Lucas-Kanade / sparse-to-dense RLOF | Efficient tracking and possible global camera-motion estimation | Sparse tracking alone does not provide reliable dense object motion; interpolation and occlusion work still needed |
| RAFT / RAFT Small | Learned dense optical flow; useful quality comparator | Neural runtime, correlation memory, model conversion and NPU/GPU copies need separate validation; not the first low-latency prototype |
| Qualcomm FastCV / CVP | Vendor CV and hardware processing may be useful | Public FastCV information and a robotics CVP plug-in do not establish an accessible, redistributable dense-flow API on Odin's Android firmware; do not assume availability |
| Decoder/bitstream motion vectors | Potentially avoids estimating everything from pixels | FFmpeg can export vectors for supported decoders, but no such export is wired into Delos's MediaCodec Surface path. Coding vectors reference compression blocks/reference pictures, not necessarily the immediately preceding displayed frame; not a direct SGSR 2 input |
| NVIDIA Optical Flow SDK | Dedicated hardware estimation | Not applicable to the tested Odin; its Vulkan optical-flow extension is absent |

SGSR uses BSD-3-Clause and current OpenCV uses Apache-2.0. Audit the exact FidelityFX component's
license and revision before porting it; preserve all notices. No code from these flow candidates
was imported as part of this research. The third-party Android win-fg project was encountered as a
reference lead, but its performance claims were not validated and are not used as device evidence.

## What SGSR 2 needs beyond motion

Reviewed official SGSR at `d926f074bcb9d714e179f1ce0fcb9ee2eeb5074e`, specifically
`sgsr/v2/include/glsl_2_pass_fs/sgsr2_convert.fs` and `sgsr2_upscale.fs`.

1. **Direction and units.** Produce current-to-previous flow for history sampling. Forward flow from
   previous to current cannot generally be converted by simply negating it. Convert source-pixel
   displacement to normalized/clip-space coordinates with the shader's exact axis conventions.
   The reviewed upscale shader uses `PrevUV.x = currentUV.x - 0.5 * Motion.x`; its Y sign depends on
   `REQUEST_NDC_Y_UP`. A custom adapter can write `MotionDepthClipAlphaBuffer` directly rather than
   re-encode into the engine's InputVelocity format only to decode it again.
2. **Camera motion.** Optical flow already includes camera and object motion. Avoid adding engine
   camera reprojection again. Replace the Convert stage's camera/depth branch, or ensure all valid
   pixels take the explicit flow path. Identity camera matrices alone do not solve missing depth.
3. **Occlusion and confidence.** Engine depth detects unreliable history around depth discontinuities.
   Video has no real depth buffer. Use bidirectional consistency, photometric residuals, matching
   ambiguity and bounds checks to build a history-rejection mask instead. This is an approximation
   and must be calibrated to the upscaler's depth-factor/history-weight semantics, not blindly copied
   as a depth texture. Newly uncovered regions should fall back to current-frame spatial filtering.
4. **Jitter.** Set source jitter to zero unless actual render jitter is known. Inventing a Halton
   offset after decoding does not create the distinct scene samples engine TAAU receives. This limits
   reconstruction quality even with perfect estimated motion.
5. **History lifetime.** Reset after scene cuts, source/output-size changes, decoder recovery, missed
   continuity, toggles and HDR/SDR transitions. Track actual frame timestamps; do not assume every
   decoded frame is consecutive. Avoid treating compression changes or HUD transitions as geometry.
6. **HDR.** Keep the existing ten-bit/FP16 color path and P010 output. Derive a stable analysis-luma
   texture separately for matching; an R8 flow-analysis image does not require quantizing the output.
   Test PQ gradients, highlights, exposure transitions and saturated colors. The unsupported R16F
   block-match format is relevant when selecting the matching representation.

## Proposed verification stages

The user subsequently authorized implementing SGSR 2 after SGSR 1 is finished (2026-10-26).
The stages below are engineering milestones for that work; performance and visual gains remain
unproven. Resolve the current SGSR 1 softness report before starting the temporal pipeline.

1. **Flow-only GPU spike.** Reuse decoded textures without CPU readback. At 480p/720p input, build a
   luma pyramid, perform coarse-to-fine block search and subpixel refinement, output vectors plus a
   confidence mask. Compare ordinary compute against QCOM-accelerated matching on the actual driver.
   Benchmark GLES/Vulkan sharing overhead too; native GPU work alone is not the total cost.
2. **Validate motion before reconstruction.** Synthetic known translations, subpixel camera pans,
   independent moving objects, occlusions, scrolling text, compression noise, scene cuts and repeated
   textures. Measure endpoint error for known motion and residual error after warping real pairs.
   Include a vector/confidence visualization; test forward/backward consistency and frame drops.
3. **Adapt SGSR 2's two-pass fragment path.** Replace its Convert input logic with estimated total
   motion and rejection, keep jitter zero, conservative history clipping, and spatial fallback for
   uncertain pixels. Compare with bilinear and SGSR 1 (Quality) at identical inputs/bitrate/HDR.
   This may work, but visual superiority is not established by source inspection.
4. **Device acceptance.** Measure complete added GPU time at 60/120 fps, p50/p95/p99 frame delivery,
   end-to-end latency, dropped frames, sustained thermals/power and HDR correctness. No future-frame
   lookahead is inherently needed for current/previous flow; processing and synchronization still add
   latency. One frame interval is 16.67 ms at 60 fps and 8.33 ms at 120 fps. As a proposed gate, target
   no more than 1–2 ms of *total added* processing at 120 fps and no extra queued frame; this is an
   engineering target, not a measured result. Stop or reduce scope if it loses to SGSR 1 HQ in motion.

Recommended first implementation milestone after SGSR 1 acceptance: a **flow-only benchmark spike**
before the SGSR 2 adapter. No PC/Vibepollo modification is needed for that approach.

## Primary sources

- [Qualcomm SGSR 2 inputs and integration](https://github.com/SnapdragonGameStudios/snapdragon-gsr/blob/d926f074bcb9d714e179f1ce0fcb9ee2eeb5074e/sgsr/v2/README.md)
- [SGSR 2 Convert shader](https://github.com/SnapdragonGameStudios/snapdragon-gsr/blob/d926f074bcb9d714e179f1ce0fcb9ee2eeb5074e/sgsr/v2/include/glsl_2_pass_fs/sgsr2_convert.fs)
- [SGSR 2 Upscale shader](https://github.com/SnapdragonGameStudios/snapdragon-gsr/blob/d926f074bcb9d714e179f1ce0fcb9ee2eeb5074e/sgsr/v2/include/glsl_2_pass_fs/sgsr2_upscale.fs)
- [Khronos: VK_QCOM_image_processing](https://docs.vulkan.org/features/latest/features/proposals/VK_QCOM_image_processing.html)
- [Khronos: VK_QCOM_image_processing2](https://docs.vulkan.org/refpages/latest/refpages/source/VK_QCOM_image_processing2.html)
- [AMD FidelityFX Optical Flow](https://gpuopen.com/manuals/fidelityfx_sdk/techniques/optical-flow/)
- [OpenCV DISOpticalFlow](https://docs.opencv.org/4.10.0/de/d4f/classcv_1_1DISOpticalFlow.html)
- [OpenCV sparse-to-dense / RLOF](https://docs.opencv.org/4.10.0/d2/d84/group__optflow.html)
- [Torchvision RAFT implementation](https://github.com/pytorch/vision/blob/main/torchvision/models/optical_flow/raft.py)
- [Qualcomm FastCV](https://www.qualcomm.com/developer/software/qualcomm-fastcv-sdk)
- [FFmpeg motion-vector side data](https://www.ffmpeg.org/doxygen/9.0/group__lavu__frame.html)
- [Khronos NVIDIA optical-flow extension](https://docs.vulkan.org/refpages/latest/refpages/source/VK_NV_optical_flow.html)
