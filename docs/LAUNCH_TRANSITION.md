# Native launch transition

## Approved reference and preserved shell behavior

The approved visual reference is the local `build/launchfx-proto/b_hyperspace_balanced.html`, preserved under `.gradle-agent/keep/`; neither local artifact is committed. The native constants are in `shell/ui/HyperspaceConfig.kt`. The tunnel and stream image are distinct layers: the tunnel has the approved fixed-center lens, while only the real video image receives elastic warp, skew, zoom, mip blur and temporary RGB separation.

The accepted main fixes are retained: CPU-integrated `HyperspaceTravel`, smoothed speed progress and travel handoff, single-use `LaunchHandoff.reset()`, side-effect-free parameter inspection off the UI thread, and the shell/exit-cover/flat-color window handover. Connection lines no longer hold back a usable game while their queue drains. The first-frame gate retains main's 3-second bound; reduced motion retains its static scene and 500 ms fade.

## Native rendering paths

| Path | Arrival source and output | After completion |
| --- | --- | --- |
| MediaCodec direct, 8-bit SDR | Warm-only temporary `SgsrRenderer.createArrival`: decoder OES texture, RGB mip pyramid and final GLES pass into the actual SurfaceView window. The upscaler is not enabled. | Release EGL window ownership, return the same codec to its original surface using `setOutputSurface`, then remove temporary GL resources/worker. No codec restart on the healthy path. |
| MediaCodec direct, 10-bit SDR/HDR | Same temporary path using FP16 RGB, the existing `GL_EXT_YUV_target` P010 pack and fenced SurfaceControl output. Existing SDR/BT.2020-PQ dataspace, mastering/content metadata and child geometry are retained. | Return to the original direct codec output; keep the last HDR child picture during the bounded direct-frame handover window, then remove the child and GL resources. |
| SGSR enabled, SDR/ten-bit/HDR | Reuse the existing GL context and reconstructed image. Arrival runs after the selected spatial/temporal reconstruction, preserving the existing upscaler configuration and supersampling. FP16/P010 is retained for ten-bit output. | Present the exact normal SGSR identity path and delete arrival-only targets/programs/ticks. The existing SGSR renderer remains because it is the user's selected steady-state renderer. |
| PyroWave without SGSR, SDR/HDR | Arrival-only FP16 RGB conversion from the actual decoded planes, a live mip pyramid, and Vulkan arrival presentation. HDR retains existing PQ/RGB-HDR10 or GPU P010 packing/bridge, not an eight-bit conversion. | Present the original normal planar output and release transient arrival resources after their fence. |
| PyroWave with SGSR, SDR/HDR | The actual reconstructed FP16 RGB source feeds the transient arrival mip image; final sampling uses the same shared pose. Existing HDR packing and metadata remain intact. | Resume the original normal SGSR presentation, then release arrival images/pipelines after their fence. |

`LaunchArrival.writePose()` is the canonical CPU curve source. MediaCodec/SGSR consume its six values directly; PyroWave passes the coherent tuple through JNI. Blur footprint sizing receives `MAX_BLUR_SOURCE_PIXELS` from Java, so native code does not maintain an independent magic blur cap. GL and Vulkan sampling adapt the same top-left image-space warp to their existing presentation coordinate systems; crop/rotation/SurfaceTexture transforms and the P010 vertical mapping are not replaced by the effect.

Each live image refreshes its RGB mip source when needed. The final RGB-separation effect uses at most three prefiltered texture lookups, rather than a full-resolution blur kernel per channel. A repeated animation tick uses the last actual decoded image without re-decoding, advancing temporal history, rebuilding its mip pyramid, copying a CPU bitmap, or constructing per-frame Runnable/array objects.

## Timeline, activation and bounded waits

The normal exit clock is 2200 ms; pose values match the approved reference: initial 118% zoom and blur 20 source-pixel units; settle starts at opening 0.68; spring damping 3.8, frequency 15, squish 0.10, spring zoom 0.025, spatial warp 70 source pixels, and RGB split peak control 9. The endpoint is exactly identity scale with zero skew, warp, blur and split.

| Event | Exit phase / budget |
| --- | --- |
| Resource preparation | Requested early during renderer setup; warm-only, no output takeover or continuous hidden full effect pass. |
| Actual output takeover/activation | Opening 0.55, about 1210 ms into the nominal exit. |
| Readiness checkpoint | Opening 0.60; wait at most 1500 ms for an actual effected output frame. |
| Soft center aperture | Opening 0.68–0.88, unchanged from the tunnel reference. |
| Identity request | Opening 1.0. |
| Loader completion wait | At most 1000 ms; then log and cancel the effect rather than retain the loader indefinitely. |
| Direct identity presentation policy | At most 400 ms before exceptional restoration proceeds. |
| Fresh direct-frame wait | At most 350 ms after the codec returns to its real surface. |

The two linear animation legs add up to the same nominal exit duration; the readiness checkpoint normally finds the late-started backend already ready. Extra waiting occurs only when a required output frame is late. Neither connection nor decoder initialization waits for an animation timer.

On preparation/identity/direct timeout or graphics capability failure, `Game.reportLaunchArrivalError()` logs the reason and requests cancellation; it does not show a long cosmetic toast or kill the live game. The native/controller paths recover direct presentation. This is an explicit failure path, not a claim that an unsupported effect combination rendered successfully. HDR is never quietly converted to SDR to make the transition work.

## Surface ownership and lifecycle

`DirectLaunchArrival` owns a lifecycle-only scheduled controller, not per-frame work. Its pure `LaunchArrivalHandover` policy covers warm/active/identity/direct/finished state, cancellation and monotonic deadlines.

1. The codec initially continues using its normal surface while the temporary pbuffer/OES/programs are prepared.
2. At late activation, redirect the codec to the temporary decoder SurfaceTexture **before** attaching EGL to its former output window.
3. Present the effected stream until identity. Before returning the codec, detach EGL from the output window; keep the temporary decoder surface valid until `setOutputSurface(real)` has completed.
4. A fresh direct callback must have PTS assigned after the surface switch and a render timestamp after it. A delayed old OES callback cannot prematurely remove the retained HDR child.
5. If no new direct frame arrives within 350 ms, close the temporary pipeline anyway. SDR retains its last valid window buffer; HDR removes the child and reveals the valid previous parent buffer. This bounded static-host fallback can expose an older retained parent image until the host produces another frame; it does not convert color space or leave the full temporary pipeline alive forever.
6. A driver exception during return to the real surface requests existing codec recovery while retaining producer lifetime safely. That exceptional recovery is distinct from the healthy no-restart handover.

Only the codec operation itself is serialized under its recovery monitor; potentially blocking EGL attach/detach barriers are outside the monitor, and `setRenderTarget()` does not wait on that monitor on the UI thread. Driver barriers still have finite Java waits; an actually hung graphics driver cannot be forcibly interrupted by this feature.

The ordinary MediaCodec first-presented listener is one-shot when timing metrics are disabled. It is re-enabled only for required recovery/handover windows. PyroWave exposes a native latched presented-frame flag rather than waiting for or consuming one-second statistics.

Stop, cancellation (`progress < 0`), decoder recovery, surface replacement and shader failure remove pending arrival callbacks and transient resources. Warm-only cancellation does not activate a pass. Single-use launch state is reset independently of graphics cleanup; the accepted shell return/snapshot behavior is unchanged.

## Static/low-FPS video and thread affinity

SGSR uses a cached Choreographer callback on its owning GL thread while arrival is active. Decoder callbacks latch new images separately; animation redraw uses the last valid source and does not call `updateTexImage()` as if a new decoder frame existed. Progress 1 can therefore display identity and signal completion without another host frame.

PyroWave uses an arrival-only native owning worker to keep Vulkan/P010 and the HDR GL bridge on a consistent thread while timed redraws are needed. Native submit remains synchronous, with no copied frame payload. Completion/cancel fences and releases the transient arrival resources, detaches the HDR EGL context and ends the worker even on a static host. The first subsequent normal submit acquires the context once and resumes the original inline path; there is no steady-state CV dispatch queue or arrival timer. JNI arrival controls/status use short lifetime/control locks, not a lock held across GPU waits. Destruction unregisters the handle and moves waiting/teardown off the UI thread while preserving callback lifetime.

A direct codec cannot populate a brand-new OES texture if the host supplies no frame at all after redirection. There is no CPU screenshot substitute: the bounded readiness cancellation restores the original valid direct picture. After the first OES latch, static-source animation no longer requires new decoded frames.

## Capabilities and device-only limits

Ten-bit arrival needs the existing renderable/filterable FP16 and GPU P010/HDR output prerequisites. Missing support latches a specific error; the CPU HDR fallback is not advertised as an arrival implementation. PyroWave preparation must precede first decoded submission, as enforced by setup ordering; a late request reports an error rather than migrating a current HDR EGL context from an unrelated thread.

PyroWave's normal SGSR presentation uses a nonlinear five-tap sharpening filter. The temporary arrival sampling is plain prefiltered RGB and the exact normal sharpened path resumes at identity. Whether that endpoint difference is visible is **not verified on a device**; a mathematically identical final filter cannot fit the three-sample arrival limit without an additional reconstruction/preprocessing pass. No extra pass or sample count was silently added for this unobserved case. The lead should inspect the endpoint with SGSR enabled.

Hardware frame pacing, codec-specific `setOutputSurface` behavior, HDR10/PQ metadata, ten-bit SDR, static-host return-frame retention, external-display crop/rotation, and cancellation/surface loss during each ownership phase need physical-device validation. The user launches real PC games; no remote game was launched by this work.

## Exercised standalone checks

No Gradle, ADB, emulator, APK install, push or merge into main was run by Codex for Phase 2.

- **52 pure JUnit tests passed** via Kotlin/JVM and javac: approved pose anchors/finite bounds/focus/blur cap; actual bounded handover state and cancellation/deadline cases; effective-settings no-write regressions; accepted main tunnel travel, stage/progress and aperture suites.
- Actual changed video Java classes, actual `StreamLoader.kt` and actual `Game.java` compiled against the Android SDK, cached real AndroidX/Compose dependencies and read-only accepted-main build dependency classes. No replacement production-API stubs were used for these final checks. This is not a full APK/D8/resource/manifest build.
- NDK arm64 C++ syntax checks passed for the renderer, YUV bridge and SGSR HDR output. The read-only common-C headers matched the worktree's pinned submodule commit.
- Java-generated JNI declarations were checked against the native definitions; unsupported-ABI stubs were checked with the NDK's ARM target.
- All **13** native shaders compiled with the pinned NDK `glslc`; regenerated bytes matched `shaders_spv.h` (SHA-256 `8ab203b84a39650ee327b22750c69fd10e03d787f67c36b6574b7ab4e48eaae7`).
- The actual GLES arrival fragment was exercised in headless WebGL2 after only adapting GLSL 3.10 declaration syntax to GLSL 3.00. Identity had zero channel error, warp changed 56,637 pixels of the synthetic coordinate/checker source, mip blur reduced checker variance, RGB path drew with GL error 0. This software-renderer shader smoke is not Android/HDR presentation or hardware-FPS proof.
- Two independent final static reviews found no remaining demonstrated blocker/high regression in the Java/GL handover and native worker/HDR lifetime paths. Static review is not device evidence.

The lead owns full Android build/tests/lint, safe installation outside an active stream, and the SDR/HDR/SGSR/PyroWave device matrix before integrating the feature branch into main.
