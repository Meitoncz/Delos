# Native launch transition

## Feasibility findings (2026-11-04)

Phase 1 is committed as `27f83386`: the shared API-33 tunnel, actual stage FIFO, information block and first-presented-frame aperture. The approved stream arrival remains part of the required design; a transparent Compose overlay alone cannot implement it.

Inspection found three concrete rendering integration points:

- `MediaCodecDecoderRenderer.configureAndStartDecoder()` currently chooses the direct SurfaceView surface or `SgsrRenderer.getDecoderSurface()`. Direct output can be redirected locally with `MediaCodec.setOutputSurface()` (the application's minimum Android version already exceeds API 23). Producer ownership must be ordered: redirect the codec before attaching EGL to its former window; detach EGL before returning the codec to that window. This does not require reconnecting the host or restarting the codec.
- `SgsrRenderer` already owns an external-OES SurfaceTexture, GLES 3.1 rendering thread, and RGBA8/FP16 intermediate textures. Arrival can run in that existing context. For direct streams, the same renderer infrastructure can provide a temporary pass without enabling the upscaler; its decoder texture is prepared before taking ownership of the real output window. Normal direct presentation must resume after an identity frame, with temporary resources removed.
- Ten-bit `SgsrRenderer` output already uses FP16 RGB processing followed by `GL_EXT_YUV_target` P010 packing and the fenced SurfaceControl bridge in `sgsr_hdr_output.cpp`. Reusing this path avoids inserting an eight-bit/SDR intermediate and preserves existing PQ/BT.2020 metadata and geometry. Hardware support for this path is a real capability requirement, not something the overlay can emulate.
- `pyrowave_renderer.cpp` has direct planar-color and SGSR-RGB final presentation passes, plus separate P010 packing paths for HDR. The effect must be applied in those GPU presentation paths before the existing packing/bridge, not on the Compose overlay or via CPU frame copies. Its Vulkan final push constants and transient sampled images provide the integration boundary; the normal decoder/upscaler path must remain unchanged after completion.

The selected approach is a shared, allocation-free six-value stream pose derived from the approved prototype (scale X/Y, skew, spatial warp, blur, RGB split), a temporary GPU-readable arrival path for MediaCodec-direct, and arrival-only processing within the existing SGSR/PyroWave contexts. Preparation and first-effect-frame readiness are distinct from network readiness. Animation time is shared with the tunnel, and final handover occurs only at the exact identity pose.

No device, MediaCodec surface-switch, HDR, or Vulkan presentation validation is implied by this feasibility inspection. Any unsupported HDR capability must be reported explicitly; converting HDR to SDR or quietly omitting arrival is not an implementation of the approved design.
