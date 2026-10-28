# Code-verified work inventory

Audited 2026-10-28 against application code at `6d0688c2`, preserving the later local A17 request.
This supersedes the earlier “78 open tasks” interpretation. All 83 original IDs are retained for
traceability; N01/N02 are new findings. **These records are not an issue-creation queue or implementation approval.**

The user requires code reconciliation and review of this inventory **before** any GitHub migration.
Future issue titles and bodies are English. Chat remains Czech. No issues, labels or Projects were
created, no permissions changed, and no application implementation was changed by this audit.

## Status definitions and record counts

| Status | Records | Meaning |
| --- | ---: | --- |
| Implemented | 4 | Code exists; any remaining visual/hardware acceptance is tracked separately. |
| Partial | 10 | Working pieces exist; the row names the exact remaining behavior. This is not approval to implement it. |
| Missing | 6 | The inspected active path lacks the stated behavior; design/host prerequisites still apply. |
| Blocked | 2 | External assets, host source/protocol or another prerequisite is missing. |
| Verification | 21 | Existing functionality needs a bounded real-device/host scenario, not a new implementation. |
| Decision | 18 | Optional/deferred/conditional idea. Agree on product/design scope before any code change. |
| Research | 8 | Observation, hardware heuristic or code-level risk; establish the scenario before proposing a fix. |
| Maintenance | 5 | Concrete architectural, source or documentation debt; not a missing user feature. |
| Merged | 3 | No independent task; its remaining work belongs to the referenced records. |
| Historical | 4 | Old comment or legacy limitation; not an active implementation gap. |
| Recurring | 3 | Ongoing maintenance, not an unfinished one-time feature. |
| Bug | 1 | A runtime failure/security boundary was demonstrated; not fixed during this audit. |

Counts describe inventory records, not a promise of that many future issues. In particular, the
partial gesture ideas remain unselected, verification matrices are not missing features, and
merged/historical records must not be bulk-imported as open work. Priorities remain the user's decision.

## Scope and evidence limits

- Complete partitioned source inventory: 139 main Java files, 66 main Kotlin files, 27 owned native/
  shader/integration files, 3 debug probes and 24 unit-test/helper files. The inventory has 259 files.
- Cross-subsystem inspection traced reachable UI actions, settings/profile persistence, stream launch
  and exit, input/USB/touch, pairing/discovery/database, codec negotiation, native rendering, artwork,
  manifest/provider exposure, build source sets and tooling. Source-marker contexts were reconciled too.
- This is a whole-application backlog reconciliation, **not a claim of a line-by-line security review
  of every file**. Evidence is targeted implementation/caller inspection plus the runtime scenarios
  below. A no-match search is bounded evidence, not a global proof of absence.
- Vendored PyroWave/Granite/OpenSSL/opus and common-c were inspected at relevant integration/API
  boundaries, not re-audited wholesale. The inactive root flavor and root-only evdev executable are
  not current product gaps (`app/build.gradle:38-44`, `jni/evdev_reader/Android.mk:8-35`).
- No Vibepollo host checkout was available in this repository. Host-internal claims remain external
  dependencies or dated research, not freshly verified host behavior. Hardware/visual matrices still
  need their actual target devices and user-run games.
- No new build, unit suite or lint run was needed for this documentation-only audit. The previously
  recorded 80 passing unit tests / 0 lint errors / 387 warnings are historical evidence only.

### Source path shorthand

`J/` = `app/src/main/java/com/limelight/`; `S/` = `J/shell/`; `N/` = `app/src/main/jni/`;
`T/` = `app/src/test/java/com/limelight/`; `D/` = `app/src/debug/java/com/limelight/`.
Unprefixed filenames following a full path refer to the same directory. Line references describe
the audited code snapshot; symbol names remain the primary anchors.

### Runtime evidence from this audit

- **R1 — Real Odin GPU:** invoked the already-installed debug APK's
  `com.limelight.binding.video.PyroWaveGpuInstrumentation` on the physical Adreno 740. Shipped shader
  readbacks and the production allocation/dispatch probe passed all four modes, both supersampling
  levels, reset/history frames, sharpness 0/85%, Off teardown and SDR → HDR → SDR rebuilding.
  This does not exercise real host packet decode/presentation, game visuals, sustained 60/120 FPS
  or allocation-failure fallback; C01/C03/C04/F11 and A05's narrow remainder stay open.
- **R2 — Exported poster provider:** first confirmed ShellActivity, not Game, was foreground.
  Created a uniquely named audit-owned PNG inside the box-art cache and another outside it, under
  the app's cache directory. A normal provider read and an encoded parent-directory read each returned
  exactly the same 27,354-byte fixture. The latter escaped `cache/boxart`; the nonnumeric app-ID
  scenario returned an uncaught argument exception. No pre-existing private files were accessed.
  All temporary files/directories were removed. This confirms N01 without changing provider code.
- No APK reinstall, host restart, game launch, injected device navigation or CI run occurred.

## A. Features and requested follow-ups

| ID | Status | Record | Actual remainder / disposition | Evidence |
| --- | --- | --- | --- | --- |
| A01 | Implemented | PyroWave SGSR 1 Standard | Native mode 1 exists for SDR/HDR; no implementation issue. New-mode game acceptance remains C01/C03/C04/F11. | N/pyrowave-renderer/pyrowave_renderer.cpp:createSgsrMotion,applyUpscalingSettings; shaders/sgsr_spatial.comp; R1 |
| A02 | Implemented | PyroWave SGSR 1 High Quality | Distinct luma, edge thresholds and dual-estimate reconstruction exist; not a sharpening alias. Visual comparison remains C01. | N/pyrowave-renderer/shaders/sgsr_spatial.comp:28-104; R1 |
| A03 | Implemented | PyroWave SGSR 2 Video Quality | Separate Activate resources/pass and weighted-center YCoCg clipping exist. Real-motion/drop coverage remains C03, not another implementation of the mode. | N/pyrowave-renderer/shaders/sgsr_activate.comp; sgsr_upscale.comp:42-84; pyrowave_renderer.cpp:2247-2261; R1 |
| A04 | Implemented | PyroWave independent sharpening | Presentation-only SDR/P010 sharpening, zero bypass and Off bypass exist. Sharpened pixels do not feed temporal history. | N/pyrowave-renderer/shaders/sgsr_sharpen.glsl:8-21; sgsr_present.frag; sgsr_pack.frag; R1 |
| A05 | Partial | Report the applied supersampling level after fallback | Four modes, sharpness, saved defaults and applied mode/size are implemented. Narrow remainder: Java still returns the requested level when native allocation falls back from 2 to 1. Code-path mismatch confirmed; allocation-failure scenario not forced on hardware. | J/binding/video/MediaCodecDecoderRenderer.java:922-932; N/pyrowave-renderer/pyrowave_renderer.cpp:2315-2320 |
| A06 | Missing | Save live upscaler changes per game | Saved per-game launch defaults already exist. Only the live-to-persistent save action is missing. Agree on interaction and whether to persist supersampling before implementation. | J/binding/video/MediaCodecDecoderRenderer.java:866-929; S/settings/GameSettingsStore.kt:57-60; J/preferences/PreferenceConfiguration.java:341-342,826 |
| A07 | Missing | Hide the PC cursor | Local Android cursor control exists, not host cursor hiding. Client setting and supported host protocol remain to be agreed/implemented; host support was not freshly verified. | J/Game.java:3914-3925; S/StreamMenu.kt:554; S/settings/SettingsHelp.kt:89; J/nvstream/http/NvHTTP.java public API |
| A08 | Missing | Custom Delos launcher identity | Launcher/adaptive/monochrome resources exist but retain the aperture-wheel design, distinct from the in-app ring. New artwork requires a design decision; no launcher implementation is missing. | app/src/main/AndroidManifest.xml:53; app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml; mipmap-* launcher PNGs |
| A09 | Partial | Style the virtual-controls editor | OscStyle graphics and no-stream preview exist. Remaining visible pieces: stock mode button/toasts, keyboard Clear all/Add keys controls and multi-choice key picker. Uncalled element-configuration dialogs are cleanup, not missing user flows. | J/binding/input/virtual_controller/VirtualController.java:88-125; keyboard/KeyBoardController.java:92-224,417-697; S/screens/OscPreviewScreen.kt |
| A10 | Merged | Remaining classic stream selectors | Do not create a separate selector-redesign issue: the normal stream opens StreamMenu. Reachable editor dialogs belong to A09/A12; errors and shortcut spinner to A11. Unreachable old selectors are optional D04 cleanup. | J/Game.java:868-869,4147-4156; J/GameMenu.java:114,251,286,300; S/StreamMenu.kt:405,466-476 |
| A11 | Missing | Handle abnormal and shortcut stream exits in Delos UI | Explicit Quit/Disconnect portal exists. Stage failures and abnormal termination still use classic Dialog; graceful host closure directly finishes. Shortcut starts retain the classic spinner/no portal handoff. Agree on visual design before changes. | J/Game.java:386,3243-3398; S/StreamMenu.kt:320-332; S/StreamExit.kt:46-64; J/ShortcutTrampoline.java:498-499 |
| A12 | Partial | Remove three reachable ordinary confirmations | Scope to profile deletion, keyboard-editor Clear all and shortcut launch while another game runs. Stream Quit/Disconnect, PC remove/unpair and per-game reset already avoid confirmation. Retain external launch/pairing security prompts and input editors. | S/screens/ProfileScreen.kt:90-113; J/binding/input/virtual_controller/keyboard/KeyBoardController.java:195-216; J/ShortcutTrampoline.java:165; J/utils/UiHelper.java:268-278 |
| A13 | Decision | Optional launch/dive visual tuning | Portal, loader, ring and shader effects are implemented. No specified defect or remaining implementation; any further appearance change needs agreement. | S/ui/DiveEffect.kt:14-86; S/ui/LaunchOverlay.kt; S/ui/PortalOverlay.kt; S/StreamLoader.kt; S/StreamExit.kt |
| A14 | Partial | Adapt layouts to other screen shapes | Uniform aspect-preserving scaling and resize handling exist. Missing is actual reflow/adaptive layout for different shapes; not basic resizing. Agree target devices/layout rules first. | S/ui/Stage.kt:104-114; S/ui/Layers.kt:150-160; S/StreamStats.kt:204; S/StreamExit.kt:97 |
| A15 | Partial | Localize the new shell | Legacy strings/translations exist. New shell/menu/help use English literals, and SettingsCatalog reads legacy catalog strings with Locale.ENGLISH. Reuse suitable translations; do not claim the entire app lacks localization. | S/settings/SettingsCatalog.kt; S/settings/SettingsHelp.kt; S/screens/NativeSettings.kt; app/src/main/res/values-*/strings.xml; res/xml/locales_config.xml |
| A16 | Blocked | Bundle approved Desktop/app artwork | Manual Desktop artwork selection and local SteamGridDB storage already exist. Built-in fallback assets await the user's app list/PNGs; then add the bundled fallback loader. | S/data/SteamGridDb.kt:203-207; S/screens/HomeScreen.kt:124-127; S/screens/ArtworkScreen.kt:106-109 |
| A17 | Decision | Streaming-menu design polish | Explicitly deferred by the user. Current menu exists; keep it unchanged until specific visual/layout changes are agreed. Not permission for another automatic redesign. | S/StreamMenu.kt:300-600; user instruction recorded in docs/HANDOFF.md |

## B. Host-dependent and longer-term features

| ID | Status | Record | Actual remainder / disposition | Evidence |
| --- | --- | --- | --- | --- |
| B01 | Missing | Wait for the game window on the client | Transport launch/loader already work. Missing: readiness setting/capability polling, first-frame/host gate, hidden audio/input handling, skip/cancel and bounded fallback. Must not merely delay RTSP. | J/Game.java:3433-3440; S/StreamLoader.kt:66-69,166-170; J/nvstream/NvConnection.java:startApp |
| B02 | Blocked | Expose game-window readiness in Vibepollo | Delos parses no readiness capability. Host source is outside this repo and was not re-audited; choose checkout/fork/build workflow and host protocol before implementation. Combine delivery/acceptance with B01/F17. | J/nvstream/http/NvHTTP.java:402-450; docs/LAUNCH_READINESS.md:8-29,83-85 |
| B03 | Missing | Synchronize artwork choices with the host | Artwork selection, four local image kinds, SteamGridDB downloads and host box-art reads exist. Missing is client-host synchronization/upload plus the agreed host API/policy. | S/data/SteamGridDb.kt:39-147; S/data/BoxArt.kt:38-62; J/nvstream/http/NvHTTP.java:814-817 |
| B04 | Decision | Investigate supported VRR/ARR presentation | Fixed refresh matching and Surface frame-rate hints already exist. No dedicated VRR capability/delivery path was found. Hardware feasibility must precede a feature commitment. | J/Game.java:1373-1451,3589-3595; J/binding/video/MediaCodecDecoderRenderer.java:2410-2412 |
| B05 | Decision | Investigate PlayStation Remote Play | No console streaming/pairing implementation found in client, JNI interfaces or manifest. PlayStation controller handling is a different, existing feature. Research only until selected. | J/nvstream/jni/MoonBridge.java native API; app/src/main/AndroidManifest.xml; J/binding/input/ControllerHandler.java |
| B06 | Partial | Expose and verify PyroWave 4:4:4 | Common-c negotiation/SDP and native chroma444 decoding/framing already exist. Game offers only 4:2:0 PyroWave flags, so the 4:4:4 path is never selected. Add agreed UI/config/offers and validate presentation/HDR; P010 bridge is currently 4:2:0-only. | J/Game.java:717-722; J/binding/video/MediaCodecDecoderRenderer.java:848; N/pyrowave-renderer/pyrowave_renderer.cpp:createDevice,createDecoder,createPlanes; N/moonlight-core/moonlight-common-c/src/RtspConnection.c:1129-1136 |
| B07 | Partial | Complete PyroWave LZ4 handling | Protocol configuration/SDP hooks exist, but Java/JNI never enable pyrowaveCompression and the framing flag does not decompress records. A working decompressor and end-to-end plumbing are missing, not merely a toggle. | N/moonlight-core/moonlight-common-c/src/SdpGenerator.c:442-448; N/moonlight-core/callbacks.c:487-499; N/pyrowave-renderer/pyrowaveframing.h:101-103; pyrowaveframing.cpp:walkRecordFrame |
| B08 | Partial | Measure and feed PyroWave bandwidth guidance | Static recommendations, live bitrate changes and loss statistics already exist. Common-c has link/pace Mbps fields; client measurement and Java/JNI plumbing are missing. No calibration algorithm has been selected. | J/preferences/PyroWaveBitrate.java; J/nvstream/http/NvHTTP.java:setRuntimeBitrate; N/moonlight-core/moonlight-common-c/src/SdpGenerator.c:449-456 |

## C. Streaming-quality investigations and measurements

| ID | Status | Record | Actual remainder / disposition | Evidence |
| --- | --- | --- | --- | --- |
| C01 | Verification | Compare SGSR 1 appearance on identical frames | Implementation exists, including debug MediaCodec identical-frame capture. Resolve the recorded visual complaint with agreed scenes; earlier SGSR 2 acceptance does not accept every SGSR 1 case. | J/binding/video/SgsrRenderer.java:228-236; SgsrDiagnostics.java:14-67; docs/SGSR2_VIDEO.md:19-21 |
| C02 | Research | Investigate baseline low-resolution halos | Recorded AV1/HEVC and original-build comparisons did not isolate a new Delos defect. Surface-size experiment was ineffective and reverted. Collect new matched evidence before another rendering change. | docs/LOW_RESOLUTION_SHARPNESS_AUDIT.md; J/binding/video/SgsrRenderer.java; N/pyrowave-renderer/shaders/planar_csc.frag |
| C03 | Verification | Validate reconstruction on demanding motion | Existing real GPU fixtures cover constants, translation, reset/scene change and mode differences. Remaining: subpixel pans, independent movers, occlusion/disocclusion, repeated textures, HUD/noise/dropped frames and quantitative motion error. | N/pyrowave-renderer/pyrowave_upscale_probe.cpp:443-579; J/binding/video/SgsrTemporal.java; R1 |
| C04 | Verification | Measure sustained real-stream quality/performance | Live averages, 1% low and native draw/decode timings exist. Synthetic 128x96 lifecycle checks and earlier old-mode FPS are not sustained new-mode 60/120 FPS, percentiles, power or heat acceptance. | J/binding/video/MediaCodecDecoderRenderer.java:reportPyroWaveStats; N/pyrowave-renderer/pyrowave_renderer.cpp:readTimestamps,logStatsIfDue; docs/STREAM_QUALITY.md:186-206 |
| C05 | Verification | Compare native 1080p to a physical 4K display | Sizing/supersampling/external display paths already exist. This is the user's deferred visual comparison on actual 4K hardware, not an unimplemented 4K upscaler. | J/binding/video/SgsrDimensions.java; N/pyrowave-renderer/pyrowave_renderer.cpp:createSgsrMotion; J/utils/ExternalDisplayControlActivity.java; docs/EXTERNAL_DISPLAY.md |
| C06 | Decision | Research Qualcomm-assisted optical flow | Current flow is ordinary compute. No specialized QCOM block-match path was found; adopt only after end-to-end quality/overhead measurements on supported hardware. | N/pyrowave-renderer/shaders/sgsr_flow.comp; docs/VIDEO_OPTICAL_FLOW_RESEARCH.md |
| C07 | Research | Investigate initial PyroWave hitches if reproduced | A historical unmeasured observation, not a confirmed current defect. Stage/timeout/stack diagnostics already exist; collect a fresh timing trace only if it recurs. | N/pyrowave-renderer/pyrowave_renderer.cpp:enterStage,nativeTakeStats; docs/TODO.md previous C07 record |

## D. Maintenance and documentation

| ID | Status | Record | Actual remainder / disposition | Evidence |
| --- | --- | --- | --- | --- |
| D01 | Maintenance | Extract suitable Game responsibilities | Streaming, clipboard, input, display and menu behavior already exist in the large activity. Optional maintainability refactor only; preserve upstream-shared behavior and keep merge differences small. | J/Game.java:137,2008-2160,4120; S/StreamMenu.kt |
| D02 | Maintenance | Reduce static Activity coupling | Game.instance is set/cleared and inspected consumers null-check it. Direct coupling still exists; replacing ownership is architectural debt, not proof that current controls are missing or leaking. | J/Game.java:344,1531; J/KeyboardAccessibilityService.java:27-65; J/utils/ExternalDisplayControlActivity.java:initViews; keyboard controllers |
| D03 | Maintenance | Review remaining legacy platform APIs selectively | Shell/stream overlays/external display already use modern APIs. Review Game system UI/display access and unqualified Handler constructors where warranted. Do not count HelpActivity's pre-33 back compatibility as missing modern handling. | J/Game.java:361-369,1478-1489; J/utils/UiHelper.java:178; J/HelpActivity.java:24-39,81-117; S/ShellActivity.kt:116 |
| D04 | Maintenance | Narrow stale-source and useful lint cleanup | Concrete leads: inactive ShellState.kt.new; unused classic menu/editor dialog helpers; old asset adapter family (DiskAssetLoader still serves TV posters); G06/G09 comments. Existing 387-warning result is historical, not a new lint run in this audit. No wholesale compatibility cleanup. | S/ShellState.kt.new; J/Game.java:4147-4156; J/GameMenu.java; J/grid/assets/CachedAppAssetLoader.java and construction-call search; J/PosterContentProvider.java:80; G06/G09 |
| D05 | Maintenance | Reconcile historical documentation with current code | This audit replaces the inaccurate remaining-work inventory and migration instructions. Preserve dated audit/history instead of treating its old failures as live bugs. Other snapshots may still need targeted editorial reconciliation; this is not app implementation. | docs/ROADMAP.md old Phase 4/6 checklists; docs/SESSION_2026-10-28.md historical PyroWave section; docs/IDEAS_FROM_STARWIRE.md:60-65; docs/WINDOWS_SETUP.md:10 vs gradle/wrapper/gradle-wrapper.properties:3 |

## E. Unselected or deferred product ideas

| ID | Status | Record | Actual remainder / disposition | Evidence |
| --- | --- | --- | --- | --- |
| E01 | Decision | Audio-driven haptics | Audio playback and host-driven controller/device rumble already exist. Audio analysis/haptic synthesis is absent from inspected audio path; no product design approved. | J/binding/audio/AndroidAudioRenderer.java:189; J/binding/input/ControllerHandler.java:2026-2251 |
| E02 | Decision | Microphone uplink to PC | No RECORD_AUDIO permission, AudioRecord capture or MoonBridge mic API. Controller mic button flags are not audio transport. Protocol/receiver/security/setup must be chosen first. | app/src/main/AndroidManifest.xml:4-17; J/nvstream/jni/MoonBridge.java:353-436 |
| E03 | Decision | Switch the host capture monitor | No capture-monitor selection action/protocol call found in inspected Game/menu/HTTP/native interfaces. Android external-display selection is a different existing function. Agree host mechanism and UI first. | S/StreamMenu.kt; J/GameMenu.java; J/nvstream/http/NvHTTP.java public API; J/utils/ExternalDisplayControlActivity.java |
| E04 | Decision | Research custom Turnip driver loading | Renderer currently loads system Vulkan. No driver loader found. Investigate provenance, device support and AHardwareBuffer/P010/HDR constraints before promising performance or zero-copy behavior. | N/pyrowave-renderer/pyrowave_renderer.cpp:VulkanLoader,createInstanceAndSurface; tools/pyrowave/vendor.ps1 |
| E05 | Partial | Always-available pinch zoom and trackpad long-press | Explicit zoom mode/ScaleGestureDetector, absolute-touch long-press and trackpad two-finger right-click already exist. Only always-on gesture behavior and trackpad long-press are missing; resolve conflicts before implementation. | J/Game.java:2850-2854,3767-3781,3975-3977; J/utils/PanZoomHandler.java; J/binding/input/touch/AbsoluteTouchContext.java:169-176; TrackpadContext.java:171-225 |
| E06 | Partial | Configurable alternative menu gesture | Configurable controller combo, edge swipe, five-finger menu and Back already exist. Three/four-finger gestures open keyboards. Missing is an agreed configurable alternative, not basic touch access to the menu; floating button was intentionally removed. | S/StreamMenu.kt:110-215; S/settings/StreamMenuSettings.kt; S/screens/NativeSettings.kt:82-90; J/Game.java:3009-3019,3064-3095,3753-3757 |
| E07 | Decision | Structured performance-impact hints | Detailed per-setting/per-choice prose about GPU/latency/bandwidth already exists. Optional structured impact/recommendation metadata has not been specified. | S/settings/SettingsCatalog.kt:41-56; S/settings/SettingsHelp.kt |
| E08 | Decision | Dedicated foldable-device behavior | Configuration changes, orientation/OSC layout refresh and uniform Stage scaling exist. No dedicated fold-posture/inner-display policy was found. Decide whether this device class is a target before implementation. | app/src/main/AndroidManifest.xml:98,148; J/Game.java:1019-1037; S/ui/Stage.kt |
| E09 | Decision | Local performance recording/export | Live statistics and debug diagnostics exist. No user-facing local recording/export path was found. The removed community sharing feature must not be restored automatically. | S/StreamStats.kt; J/binding/video/MediaCodecDecoderRenderer.java:reportPyroWaveStats; N/pyrowave-renderer/pyrowave_renderer.cpp:logStatsIfDue |
| E10 | Decision | Explicit neutral bilinear comparison mode | Bilinear diagnostic capture and direct Off paths exist. A user-facing comparison mode is not implemented or agreed; do not silently change Off semantics. | J/binding/video/SgsrDiagnostics.java:43-67; N/pyrowave-renderer/pyrowave_renderer.cpp:createPipeline |

## F. Verification of existing functionality

| ID | Status | Record | Actual remainder / disposition | Evidence |
| --- | --- | --- | --- | --- |
| F01 | Verification | Compare every screen with the approved prototype | Implemented screens and specific Odin acceptances exist. A systematic screen-by-screen pass, including later approved changes, is not established. Not an instruction to redesign screens. | S/Shell.kt; S/screens/*; docs/UI_PLAN.md:170-176; docs/prototype/delos-gui.html |
| F02 | Verification | Run the full controller navigation sweep | Gamepad routing, focus, analog repeat and external-display control exist. Remaining is the complete hardware/screen matrix, not implementing controller navigation from scratch. | S/GlobalKeys.kt; S/ui/Focus.kt; S/StreamMenu.kt; T/shell/StreamMenuInputTest.kt; T/ui/ExternalControllerViewTest.java; docs/EXTERNAL_DISPLAY.md |
| F03 | Verification | Fresh PIN and OTP pairing acceptance | Both flows exist, including host challenge/response and OTP extension. Remaining: actual fresh pairing after unpair and OTP on a real host. | S/DeviceFlows.kt:pairWithCode; S/screens/PairScreens.kt; J/nvstream/http/PairingManager.java:187-228,323-346 |
| F04 | Verification | Exercise complete PC management flows | Manual add, wake, unpair/remove, default PC and skip-start are implemented. Confirm real offline/paired state transitions and persistence; do not list these as missing functions. | S/DeviceFlows.kt:addByAddress,wake,unpair,remove; S/settings/StartupStore.kt; S/screens/DeviceScreens.kt |
| F05 | Verification | Profile a large library at 120 Hz | Memory/disk artwork caching and navigation exist. Need representative many-cover/long-scroll frame-time and memory evidence; no performance defect is inferred from missing stress testing. | S/data/BoxArt.kt:29-62; S/data/SteamGridDb.kt:ArtworkStore; S/screens/HomeScreen.kt |
| F06 | Verification | Complete the touch interaction sweep | Touch actions, fields, sliders, artwork selection and on-screen controls/editor already exist. Check the full real-device interaction matrix. | S/ui/Controls.kt; S/ui/Rows.kt; S/screens/ArtworkScreen.kt; J/binding/input/virtual_controller/* |
| F07 | Verification | Verify Android 10–12 fallbacks | Minimum API 29 and older-device dim/background fallbacks exist. Odin Android 13 cannot establish their hardware acceptance. | app/build.gradle:13-14; S/ui/Backdrop.kt; S/ui/BackgroundShaders.kt |
| F08 | Verification | Verify Android 15/16/16.1 platform behavior | Modern shell back/insets and keyboard-capture paths exist; Game deliberately disables predictive back for menu behavior. Test real target systems. Removed DebugInfoActivity's historical defect is NOT a current finding. | app/src/main/AndroidManifest.xml:97,152; S/ShellActivity.kt; S/screens/DebugInfoScreen.kt; J/HelpActivity.java:24-39,81-117; J/binding/input/capture/* |
| F09 | Verification | Check other handhelds and aspect ratios | Existing uniform scaling can be observed now; final adaptive-layout acceptance depends on A14 and access to actual target devices. Not a second responsive-layout implementation. | S/ui/Stage.kt:104-114; A14; docs/UI_PLAN.md:188 |
| F10 | Verification | Complete the host compatibility matrix | Vibepollo/Sunshine-family detection, pairing, virtual-display and host extension code exists. Verify actual host variants/capabilities, including permission/unsupported responses. | J/nvstream/http/NvHTTP.java:433-445,569-580; PairingManager.java; J/nvstream/NvConnection.java |
| F11 | Verification | Complete the codec/HDR/recovery matrix | All four codec paths and GPU checks exist. Remaining combinations need real host packets, SDR/HDR transitions, reconnect, resolutions/rates and device acceptance. Include N02's conditional HDR capability case. | J/binding/video/MediaCodecDecoderRenderer.java; N/moonlight-core/callbacks.c:107-163; D/binding/video/PyroWaveGpuInstrumentation.java; R1 |
| F12 | Verification | Verify physical controller variants | Built-in, USB/Bluetooth controller routing, rumble/motion/LED and selected paddle mappings exist. Check actual Xbox, PlayStation, 8BitDo/Joy-Con models; extend mappings only for demonstrated gaps. | J/binding/input/ControllerHandler.java; J/binding/input/driver/*; N/moonlight-core/simplejni.c:274-278; G02 |
| F13 | Verification | Exercise host extras and their lifecycle | Server commands, manual/automatic clipboard sync and virtual displays are implemented. Check permission/unsupported cases, focus/exit sync and lifecycle corners. | J/Game.java:2008-2160,3465,4019; J/nvstream/http/NvHTTP.java:launchApp,getClipboard,sendClipboard,getServerCmds; S/StreamMenu.kt |
| F14 | Verification | Verify supported JSON import variants and failures | Import/export exists with 1 MiB/JSON/value checks. Compatibility across Axixi/Artemis variants and malformed nested layouts remains unproven; do not create an import feature from scratch. | S/screens/SettingsActions.kt:46-77; J/binding/input/virtual_controller/keyboard/KeyBoardController.java:426-465; KeyBoardControllerConfigurationLoader.java:386-446 |
| F15 | Verification | Optionally measure Wi-Fi keep-alive benefit | The 20 ms control payload and separate Wi-Fi locks already exist. Only a controlled on/off effectiveness measurement is optional. | J/Game.java:322-327,545-559,3469-3470; J/nvstream/jni/MoonBridge.java:sendEmptyPayload |
| F16 | Verification | Check latest help/menu on an external TV | Core external-display resolution/FPS/HDR/controller routing has earlier acceptance. The later help auto-scroll/fades and mouse page still need a targeted attached-TV check. | S/StreamMenu.kt:549; S/ui/AutoScrollingHelp.kt; J/utils/ExternalDisplayControlActivity.java; docs/EXTERNAL_DISPLAY.md |
| F17 | Merged | Future launch-readiness acceptance | Move this scenario matrix into B01/B02 acceptance criteria. It is conditional on implementing readiness, not a separate missing feature or immediate test obligation. | B01/B02; docs/LAUNCH_READINESS.md:38-80; J/Game.java:connectionStarted |

## G. Inherited source-note triage

| ID | Status | Record | Actual remainder / disposition | Evidence |
| --- | --- | --- | --- | --- |
| G01 | Research | Validate requested TV refresh activation if needed | Existing TV/external-display heuristic returns the requested rate without comparing it with the Android primary display. Establish a real mode-activation discrepancy before changing the heuristic. | J/Game.java:1451-1461 |
| G02 | Historical | Paddle support is already implemented for selected models | PADDLE1–4 scancode handling exists on key down/up; native identification covers Xbox Elite/DualSense Edge. Retire the generic Paddles? task. Other models belong to F12 until a concrete missing mapping is demonstrated. | J/binding/input/ControllerHandler.java:111,740,2623-2641,2840-2858; N/moonlight-core/simplejni.c:274-278; J/nvstream/input/ControllerPacket.java:21-24 |
| G03 | Research | Revisit xpad detection only with hardware evidence | The UNKNOWN-key scancode-to-D-pad fallback intentionally operates when hat axes are absent. The comment records an uncertain kernel-binding heuristic, not missing USB support. | J/binding/input/ControllerHandler.java:1602-1620; J/binding/input/driver/UsbDriverService.java:shouldClaimDevice |
| G04 | Research | Revisit the Amlogic S905X workaround only on affected hardware | An Android P+ HEVC whitelist already exists with a sabrina exclusion. Do not broaden compatibility work from a FIXME without actual devices/evidence. | J/binding/video/MediaCodecHelper.java:176-189 |
| G05 | Merged | Verify additional hardware AV1 decoders | Hardware AV1 selection already exists (API 29+, excluding software). Fold additional-decoder coverage into F11 and the relevant F07/F08 device cases; do not create a separate decoder implementation or duplicate test issue. | J/binding/video/MediaCodecHelper.java:845-875; F11 |
| G06 | Historical | Obsolete commented color-format investigation | Only dead commented logging remains; current MediaCodec format setup already works. Retire as a functional task; optional comment removal belongs to D04. | J/binding/video/MediaCodecDecoderRenderer.java:795-801 |
| G07 | Research | Investigate service shutdown only if lifecycle evidence warrants it | Pollers/interrupts and DB reference guards exist. onDestroy does not await workers due to HTTP timeout constraints. Measure lingering threads/callbacks before choosing a fix; no reproduced shutdown failure in this audit. | J/computers/ComputerManagerService.java:529-536,758-793,937-941 |
| G08 | Decision | Optional square virtual-stick response | Current sticks work with circular response; four unused circle_stick fields do not implement a square variant. One unselected feature idea, not four bugs. Dead fields are optional D04 cleanup. | J/binding/input/virtual_controller/AnalogStick.java:93; AnalogStickFree.java:95; keyboard/KeyAnalogStick.java:95; keyAnalogStickFree.java:93 |
| G09 | Historical | Stale no-picture/mute bridge comments | JNI video/audio callbacks are wired to functioning renderers. No-picture is obsolete commentary; mute is an unselected feature thought, not evidence of broken sound. Retire the generic task; comment cleanup belongs to D04. | J/nvstream/jni/MoonBridge.java:228-236,268-274; J/nvstream/NvConnection.java:458; N/moonlight-core/callbacks.c:155-216,284; J/binding/audio/AndroidAudioRenderer.java:189 |
| G10 | Verification | Optionally measure default bitrate recommendations | Defaults and interpolation already exist, with separate PyroWave guidance. This is measurement/tuning, not missing bitrate controls; preserve accepted manual-bitrate behavior. | J/preferences/PreferenceConfiguration.java:466-540; J/preferences/PyroWaveBitrate.java; T/preferences/PyroWaveBitrateTest.java |
| G11 | Research | Review old pairing HTTP/port notes only against a demonstrated case | HTTPS PairStatus/challenge/signature handling and address-port selection exist. Comments alone justify neither a protocol rewrite nor weakened certificate handling. | J/nvstream/http/NvHTTP.java:349-388,426-431,577-580; PairingManager.java:187-318 |
| G12 | Historical | Legacy GFE resolution whitelist, not a Vibepollo gap | The hardcoded SOPS list is guarded by isNvidiaServerSoftware/MJOLNIR. Sunshine/Vibepollo do not take it. Retain a legacy note only; reopen if legacy GFE support is explicitly selected. | J/nvstream/http/NvHTTP.java:444-445,865-876 |

## H. Conditional public-project work

| ID | Status | Record | Actual remainder / disposition | Evidence |
| --- | --- | --- | --- | --- |
| H01 | Decision | Prepare a Delos store listing if the project becomes public | Conditional publication material only. No store-listing folder was found in the current repo inventory. No release/version/signing work is authorized. | Repository root inventory; docs/ROADMAP.md:15-20 |
| H02 | Decision | Prepare issue templates and contribution guidance if public | The .github directory currently contains the build workflow, not these public-facing templates. Conditional future work, not required to track private issues. | .github/workflows/build.yml; .github directory inventory |
| H03 | Decision | Refresh public README/credits at the selected milestone | README/license/credits already exist. Publication-oriented rewriting is conditional; current factual doc inconsistencies are D05, not missing attribution. | README.md:1-20,97-105; LICENSE.txt |
| H04 | Decision | Add a Delos-style update check after publication | About currently has Version/Based on/Replay welcome, not an updater. Still conditional on publication; not a release or signing task. | S/screens/NativeSettings.kt:143-146; docs/UI_PLAN.md:177 |

## I. Recurring work

| ID | Status | Record | Actual remainder / disposition | Evidence |
| --- | --- | --- | --- | --- |
| I01 | Recurring | Review Moonlight Android/common-c updates | Merge/build/integration paths exist. Future upstream checks are recurring maintenance, not an unfinished initial merge; no upstream refresh was executed during this audit. | .gitmodules; N/moonlight-core/Android.mk; docs/ROADMAP.md:22-63 |
| I02 | Recurring | Review Artemis changes if development resumes | An ongoing upstream watch, not a missing app feature. No update was fetched/merged during this audit. | docs/ROADMAP.md:148-150; README.md:5 |
| I03 | Recurring | Repeat focused dependency/security/compatibility reviews when relevant | Original audit/fixes exist and obsolete dependencies are absent from current build. This is a trigger-based recurring activity, not proof that the previous review was never done. | app/build.gradle:160-192; docs/AUDIT.md dated scope; app/src/main/AndroidManifest.xml; res/xml/provider_file_paths.xml |

## N. New audit findings — review before issue creation

| ID | Status | Record | Actual remainder / disposition | Evidence |
| --- | --- | --- | --- | --- |
| N01 | Bug | Constrain exported poster reads to the box-art cache | Confirmed on Odin with only an audit-owned PNG: an encoded ../ directory segment returned the same 27,354 bytes from outside cache/boxart. A nonnumeric app ID also yields an uncaught argument exception. Validate/canonicalize the permitted path and return a controlled not-found error. Not fixed in this audit. | app/src/main/AndroidManifest.xml:61-65; J/PosterContentProvider.java:35-60; J/grid/assets/DiskAssetLoader.java:146-148; J/utils/CacheHelper.java:16-30; R2 |
| N02 | Research | Verify HDR negotiation on PyroWave-only HDR hosts | Code-level edge case: negotiatedHdr is cleared unless HEVC/AV1 Main10 bits 0x20200 exist, although separate PyroWave HDR capability bits exist. Not reproduced against such a host; evaluate with F11 before claiming a general HDR failure. | J/nvstream/NvConnection.java:248-254; J/nvstream/http/NvHTTP.java:887; N/moonlight-core/moonlight-common-c/src/Limelight.h:568-571 |

## Rules for the later GitHub migration

1. Review this corrected inventory with the user first. Do not import every historical ID as an open issue.
2. Keep implemented work out of the open-feature queue. Preserve merged/historical IDs as aliases or notes.
3. Use the exact remainder for partial work. A06 is live-to-persistent save, B06 is offers/config/presentation
   verification, and A05 is the applied-level fallback display — not reimplementation of working features.
4. Keep verification, optional decisions, external blockers and recurring maintenance distinguishable.
   F17 becomes B01/B02 acceptance criteria; G05 is AV1 coverage within the device/codec matrix.
5. Never treat an inherited FIXME or an untried device as a confirmed bug. N01 is runtime-confirmed; N02
   remains a conditional code-level finding until the relevant host scenario is established.
6. Titles/bodies in English, private project visibility, no CI or account-authorization changes without
   explicit user instruction. Current proposal: [GITHUB_WORKFLOW.md](GITHUB_WORKFLOW.md).
