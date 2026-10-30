# Low-resolution sharpness audit

## Scope

This audit follows a sub-native-resolution stream from the launch request through decoding and local
presentation. It focuses on the direct MediaCodec path with SGSR disabled, because the user observed
an over-sharpened 480p HDR image there. The same appearance was reproduced with the accepted
`714f21a3` main build, before the stream-quality branch existed.

## Findings

### Host request

- `NvHTTP.launchApp()` sends the negotiated stream dimensions in `mode=<width>x<height>x<fps>`.
- `scaleFactor` is a separate virtual-display request derived from `resolutionScaleFactor`; its
  default is 100. It does not alter the negotiated video dimensions in Delos.
- No sharpening, edge-enhancement, or upscaling-quality option is sent by Delos.

This excludes an explicit Delos sharpening request. It does not exclude sharpening inside the game,
Vibepollo, Windows display scaling, or the host encoder.

### Decoder configuration

- With SGSR disabled, `MediaCodecDecoderRenderer` configures the selected decoder directly against
  the app's presentation `Surface`.
- The base `MediaFormat` contains the coded size, frame rate, maximum dimensions, and color metadata.
  Delos does not set a sharpening or image-enhancement codec key.
- Qualcomm options applied by `MediaCodecHelper` control low latency, picture order, output fences,
  frame dropping, operating rate, and priority. None controls sharpening.
- The decoder uses `VIDEO_SCALING_MODE_SCALE_TO_FIT`. Android exposes the fit/crop choice here, but
  not a selectable reconstruction filter or sharpening strength.
- A runtime log now distinguishes `direct MediaCodec surface; no Delos sharpening shader` from the
  SGSR surface path.

### View and compositor path

- `StreamContainer` only calculates fit/fill geometry. It has no image filter.
- `PanZoomHandler` starts at a scale of 1.0 and changes the view transform only after a pinch gesture
  or when an explicitly remembered zoom is restored. It has no sharpening operation.
- Before this audit, `Game` always called `SurfaceHolder.setFixedSize()` with the stream resolution.
  A 480p stream therefore produced an 854x480 surface that SurfaceFlinger had to enlarge to the
  1920x1080 display layer.
- That unconditional fixed-size behavior entered the fork through commit `3c8b9600`, whose stated
  purpose was stabilizing low-latency presentation on MediaTek devices. The later cleanup commit
  `e87e9106` retained it for every device, including the Qualcomm-based Odin.
- Current Moonlight behavior on modern Android normally leaves an aspect-fitted surface at its
  layout size instead of unconditionally forcing the coded size.

The fixed-size surface is the first concrete non-SGSR Delos divergence capable of changing the
low-resolution reconstruction path. It proves that the compositor, rather than an explicit Delos
shader, performed the final 480p-to-1080p scale. It does not by itself prove which compositor or
display-driver filter caused the reported appearance.

### Other renderers

- SGSR 1 and SGSR 2 contain explicit reconstruction and sharpness logic, but only run while the
  upscaler is enabled.
- PyroWave uses a separate native path. Its presentation sampler is linear and its color-conversion
  shaders contain no edge-enhancement kernel. It was not active during the reproduced AV1 test.
- `moonlight-common-c` forwards compressed video to the selected decoder and contains no pixel-domain
  sharpening stage in this path.

## Controlled comparison

A diagnostic build changed the direct MediaCodec path to `SurfaceHolder.setSizeFromLayout()`. This
moved the required reconstruction away from the previous low-resolution SurfaceFlinger layer. The
user compared the same Returnal scene on the Odin at 480p HDR with SGSR disabled and reported no
improvement in the over-sharpened appearance. The change also broke the established SGSR HDR child
layer geometry and made the upscaled image zoom beyond the display bounds.

The diagnostic change was therefore reverted and will not be committed. This result makes the
fixed-size SurfaceFlinger scale unlikely to be the cause of the reported edge halos. The remaining
candidates are the selected decoder's output processing, system/display processing after the layer
scale, or processing on the game/host/encoder side.

A subsequent matched Returnal capture compared AV1 and HEVC at 854x480 HDR. The user judged them
equally over-sharp, their screenshots had similar edge profiles, both Qualcomm low-latency decoders
reported zero enabled pipeline filters, and both exposed the same HDR color metadata. This excludes
an AV1-specific optional decoder filter. The aliased high-contrast edges are already visible in the
captured 1080p composition, so they are not solely an OLED-panel effect.

## Conclusion

No always-on Delos sharpening algorithm or codec option was found in the direct MediaCodec path.
The audit did find an unconditional low-resolution surface-size override inherited from a MediaTek
latency patch, but a controlled device comparison did not change the over-sharpening. Codec, system
display, and host-side causes were narrowed with matched codec and screenshot tests. The remaining
base appearance is consistent with aliasing/reconstruction of high-contrast 480p content at a
non-integer 2.25x display scale; no hidden Delos sharpening control was found. Separately, SGSR 2 had
its own measurable Lanczos ringing, which was corrected in the upscaler rather than changing Off.
