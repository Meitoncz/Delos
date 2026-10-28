# Remaining work

Reconciled on 2026-10-28 against `main` at `d080f200`. This is the consolidated list of recorded
remaining work, not authorization to implement every idea. Sections distinguish requested work,
conditional features, verification and inherited engineering notes. Duplicate and superseded source
checkboxes are not additional tasks. The user decides execution order.

Resume context: [2026-10-28 session closeout](SESSION_2026-10-28.md).
Task-management proposal: [GitHub Issues and Projects](GITHUB_WORKFLOW.md); migration has not started.

## A. Feature gaps and requested follow-ups

- [x] **A01 — PyroWave SGSR 1 Standard:** native Vulkan spatial reconstruction completed, including SDR/HDR, frame-boundary switching and existing GPU processing-time statistics. Verified with real GPU fixtures and the production-renderer lifecycle probe on Odin.
- [x] **A02 — PyroWave SGSR 1 High Quality:** distinct Qualcomm Unity HQ spatial reconstruction completed, not a sharpening-strength alias. GPU textured-edge output differs from Standard and stays locally bounded.
- [x] **A03 — PyroWave SGSR 2 Video Quality:** separate reactive Activate pass and weighted-center YCoCg history clipping completed. Real motion fixtures verify distinct regular/Quality output, reset and scene-cut behavior.
- [x] **A04 — PyroWave sharpness parity:** separate presentation-only sharpening completed for SDR and HDR/P010, with true zero bypass and no sharpening feedback into temporal history.
- [x] **A05 — Honest codec-specific controls:** all four modes, sharpness, supersampling, help and saved/per-game launch defaults agree with the native implementation. Applied mode/size remain separate from requested settings; unsupported processing reports direct output. Build, 80 unit tests, lint and real Odin GPU/lifecycle checks pass. Live visual/sustained-performance checks remain C03/C04/F11, not claimed complete here.
- [ ] **A06 — Save live upscaler changes per game:** the user requested this later. Profile/per-game launch defaults already exist; live changes remain session-only. Decide the exact save interaction and whether supersampling level belongs in the saved subset.
- [ ] **A07 — Hide the PC cursor:** never / while touching / always, using supported Vibepollo host behavior. The Android local-cursor switch already exists and is a different feature.
- [ ] **A08 — Delos launcher icon:** replace the inherited launcher icon with the custom Delos identity; the in-app ring/wordmark already exists.
- [ ] **A09 — Virtual-controls layout editor:** finish its Delos styling, including remaining element-editing dialogs. The control graphics and no-stream preview already use the new style.
- [ ] **A10 — Remaining legacy stream dialogs:** replace still-reachable classic selectors/messages in the Delos style. The main stream menu, its More page and the mouse-mode page are already implemented.
- [ ] **A11 — Abnormal stream exits:** use Delos-style messages/transitions for connection loss, host-side game closure and errors. Explicit Disconnect / Quit already return through the portal.
- [ ] **A12 — Remaining in-app confirmations:** reconcile surviving destructive-action confirmations, including profile deletion, with the current no-confirmation product rule. External-link/pairing security prompts remain a separate allowed case; ordinary input editors are not confirmation dialogs.
- [ ] **A13 — Launch/dive polish:** further tune the shader and ring effects if desired. The portal, wind/chromatic effect and continuous loader are already implemented.
- [ ] **A14 — Responsive layout:** adapt the fixed 1920 × 1080 stage for other aspect ratios and handheld screens rather than only scaling it.
- [ ] **A15 — New-shell translations:** extract/localize English shell/menu/help strings and reuse existing translations where applicable. Current language selection does not translate the entire new shell.
- [ ] **A16 — Built-in Desktop/app artwork:** add approved cover, wide cover, hero and transparent logo assets. Waiting for the user's app list and PNGs.

## B. Host-dependent and longer-term features

- [ ] **B01 — Wait for the game window: client:** add an optional profile setting/per-game override, retain the launch scene until a valid frame and host readiness, gate hidden audio/input, and provide skip, cancel and bounded fallback. Do not simply delay RTSP establishment.
- [ ] **B02 — Wait for the game window: Vibepollo:** agree on a source checkout/fork and host workflow, then add an authenticated, versioned readiness capability/endpoint tied to the app, launch generation and captured display. Existing game/process detection can be reused; this is not proof of completed loading/shader compilation. Replacing/restarting the installed host is a separate action.
- [ ] **B03 — Artwork synchronization with Vibepollo:** agree on a host API and sync policy for artwork choices; there is no implemented client-host artwork sync.
- [ ] **B04 — VRR feasibility and support:** verify which handheld/external displays and Android presentation paths can use it, then implement supported cases. Do not assume the Odin panel supports VRR.
- [ ] **B05 — PlayStation Remote Play:** investigate feasibility, pairing and architecture before proposing implementation.
- [ ] **B06 — PyroWave 4:4:4 end to end:** complete negotiation/exposure/presentation and HDR verification. The native renderer already has partial 4:4:4 decoding plumbing; this is not a claim that it has no 4:4:4 code.
- [ ] **B07 — PyroWave LZ4:** investigate and enable compatible compressed record handling across the client/native protocol and host; currently disabled/not offered as a finished feature.
- [ ] **B08 — PyroWave bandwidth calibration:** measure/derive guidance from the actual connection and workload rather than relying only on fixed codec recommendations.

## C. Streaming quality investigation and optimization

- [ ] **C01 — SGSR 1 appearance:** reconcile the earlier softness/image-quality complaint with identical-frame comparisons. Keeping all four modes and accepting SGSR 2 Video Quality does not establish separate visual acceptance of every SGSR 1 case.
- [ ] **C02 — Baseline low-resolution edge halos:** identify the remaining source of over-sharp edges with SGSR off. The original build showed the same baseline appearance; the layout-sized-surface experiment did not fix it and was reverted.
- [ ] **C03 — Motion-quality validation:** known translations/subpixel pans, independent moving objects, occlusions, disocclusions, repeated textures, HUD/text, compression noise, scene cuts and dropped frames; measure flow/reprojection errors, not only constant-color/ramp correctness.
- [ ] **C04 — Sustained performance:** compare all relevant codec/upscaler modes at 60/120 FPS with frame-time percentiles, drops, power, heat and complete added processing/latency. Short static GPU fixtures already exist and do not replace this test.
- [ ] **C05 — Native 1080p to 4K display comparison:** the user wanted to try it later; it is not a blocker for already accepted SGSR work.
- [ ] **C06 — QCOM-assisted optical flow:** optional optimization research for supported GPU block-matching instructions versus the existing ordinary compute path. Benchmark integration overhead and quality before adopting it.
- [ ] **C07 — First-minute PyroWave hitches:** if reproduced, capture logs/timing and diagnose. This is a historical unmeasured observation, not a confirmed current defect or an instruction to guess a fix.

## D. Code quality and maintenance implementation

- [ ] **D01 — Extract Artemis-specific Game responsibilities:** reduce the monolithic streaming activity by moving suitable clipboard/menu/external-display/touch helpers while keeping shared upstream diffs small.
- [ ] **D02 — Reduce static activity coupling:** replace fragile `Game.instance` / external-display activity references with lifecycle-aware ownership where appropriate.
- [ ] **D03 — Modernize remaining shared platform code selectively:** review immersive/system-bar APIs, display access, handlers/back handling and other deprecated paths. The original Artemis-only API batch is complete; deliberate compatibility code must not be rewritten blindly.
- [ ] **D04 — Remaining useful lint/resource cleanup:** prioritize actionable warnings and stale/unused code, including translation quick-fix/report issues. Current lint has zero errors and 387 warnings; this is not a mandate to churn all upstream compatibility code.
- [ ] **D05 — Reconcile stale documentation:** old snapshots still say pending/not implemented for accepted features or retain superseded counts/instructions. Refresh current documents and distinguish historical checkpoints; leave deliberately local-only notes out of commits.

## E. Deferred or unselected ideas — require a product decision

- [ ] **E01 — Audio haptics:** turn game audio/bass into device/controller vibration.
- [ ] **E02 — Microphone to PC:** choose a compatible host protocol or an encrypted/authenticated receiver approach and its PC-side setup.
- [ ] **E03 — Monitor switcher:** choose the captured PC monitor from the stream menu.
- [ ] **E04 — Custom Turnip driver:** investigate loading, provenance and Vulkan/HDR/AHardwareBuffer constraints on supported devices; do not promise faster decoding or preserved zero-copy HDR.
- [ ] **E05 — Always-available pinch zoom / trackpad long-press right-click:** decide gesture behavior and conflicts with existing game input and zoom modes.
- [ ] **E06 — Alternative menu gesture:** a configurable gesture such as three-finger tap, if wanted. It currently conflicts with the keyboard gesture; the floating menu button was deliberately removed, not accidentally omitted.
- [ ] **E07 — Structured performance-impact hints:** optional recommendation/impact indicators beyond the detailed explanations now present in the right panel.
- [ ] **E08 — Foldable-device behavior:** adapt the stream when switching to an inner display, if this becomes a target device class.
- [ ] **E09 — Local-only performance recording/export:** only if needed; the old third-party community log-sharing feature was intentionally removed.
- [ ] **E10 — Neutral Bilinear comparison mode:** an unresolved diagnostic/product idea; do not change the meaning of Off without a decision.

## F. Required verification and acceptance — existing functionality, not missing features

- [ ] **F01 — Screen-by-screen prototype comparison** on the Odin, incorporating later explicitly approved UI changes.
- [ ] **F02 — Controller navigation sweep:** analog/D-pad, all action/menu buttons, direct focus movement, shoulders and settings/help behavior across screens. External-display analog/menu navigation already has a recorded successful test; this is the broader sweep.
- [ ] **F03 — Pairing:** fresh PIN pairing after unpairing and OTP pairing.
- [ ] **F04 — PC management:** manual IP/address add, wake an offline PC from the start screen, remove/unpair and skip-start/default-PC flows.
- [ ] **F05 — Large library performance:** 120 Hz navigation, memory/cache behavior with many covers/logos and long scrolling sessions.
- [ ] **F06 — Touch sweep:** all screens, gestures, fields, sliders, artwork selection, on-screen controls and editor interactions.
- [ ] **F07 — Android 10–12:** fallback rendering without newer blur/AGSL features and controller/input behavior on real supported devices.
- [ ] **F08 — Android 15+/16/16.1:** edge-to-edge/insets, predictive back and supported keyboard-capture paths on real devices.
- [ ] **F09 — Other handhelds/aspect ratios** after responsive-layout work; never substitute an emulator for required device acceptance.
- [ ] **F10 — Host compatibility matrix:** Vibepollo and Sunshine, plus Apollo-specific features where applicable. Existing successful Vibepollo tests are not a complete multi-host matrix.
- [ ] **F11 — Codec/HDR matrix:** H.264, HEVC, AV1 and PyroWave; SDR/HDR transitions, reconnect/recovery and supported resolutions/rates. Many Odin cases already passed; remaining combinations need explicit coverage.
- [ ] **F12 — Controller matrix:** built-in controls, Xbox, PlayStation LED/rumble/motion, 8BitDo and Joy-Con where supported.
- [ ] **F13 — Host extras:** server commands, clipboard manual/automatic lifecycle, virtual display and host permission/unsupported-feature behavior.
- [ ] **F14 — JSON import compatibility:** verify supported Axixi2233/Artemis variants and malformed-file behavior. Keyboard/custom-command import/export already exists.
- [ ] **F15 — Wi-Fi keep-alive effectiveness:** optional controlled on/off measurement on the Odin if its benefit needs to be established. The feature itself is already implemented.
- [ ] **F16 — Latest help/menu external-display layout:** verify the passive help panels and native mouse-mode subpage with an attached TV. Core external-display resolution/FPS/HDR/menu input was already accepted before this help layout change.
- [ ] **F17 — Launch-readiness acceptance if implemented:** direct EXE, Steam/Playnite, fullscreen/borderless/windowed games, virtual display, resume, launcher prompts, stale generations, timeout, skip/cancel, hidden audio/input and hosts without the capability.

## G. Inherited TODO/FIXME triage in application source

These comments are engineering leads, not verified current defects or automatically approved features.
Read their current code and provenance before acting. Third-party/submodule TODOs are excluded.

- [ ] **G01 — TV/external refresh-rate confirmation:** improve the assumption that a requested TV mode will activate; see `Game.java`.
- [ ] **G02 — Controller paddles:** investigate the remaining mapping/protocol note in `ControllerHandler.java`; distinguish remote game input from the already configurable stream-menu shortcut.
- [ ] **G03 — USB xpad detection:** investigate the uncertain native-driver binding check in `ControllerHandler.java`.
- [ ] **G04 — Amlogic compatibility:** determine whether the existing decoder workaround should cover more S905X devices; actual hardware evidence required.
- [ ] **G05 — Additional AV1 decoders:** test decoder capability/performance assumptions rather than assume the Odin result applies to every decoder.
- [ ] **G06 — MediaCodec color-format note:** determine whether the commented-out format investigation still warrants work.
- [ ] **G07 — Computer-manager shutdown:** revisit waiting for worker termination versus HTTP timeout constraints.
- [ ] **G08 — Square virtual-stick response:** one inherited feature idea repeated in four virtual gamepad/keyboard stick implementations; do not count it as four tasks.
- [ ] **G09 — Old MoonBridge picture/mute notes:** establish whether these comments still describe required work. They do not prove that normal video/audio is broken.
- [ ] **G10 — Empirical default bitrates:** validate initial recommendations with measurements. This is separate from, and must not undo, the accepted manual-bitrate preservation policy.
- [ ] **G11 — HTTP pairing/port notes:** review the old PairStatus and port-selection comments in `NvHTTP.java` without weakening certificate/authentication behavior.
- [ ] **G12 — Host-supported resolution discovery:** review the hardcoded legacy NVIDIA SOPS resolution list and whether `serverinfo` can supply the relevant capability. Scope it to hosts where that path is used.

The other `Game.java` TODO (modern system bars) is covered by D03. There are no additional explicit
TODO/FIXME markers in the owned shell or PyroWave renderer at this checkpoint. This is a source-comment
inventory, not a claim that all future bugs have been discovered.

## H. Conditional on the owner making the project public

- [ ] **H01 — Store listing** written for Delos.
- [ ] **H02 — Delos issue templates and contributing guide.**
- [ ] **H03 — README/credits refresh** at the owner-selected public milestone.
- [ ] **H04 — Delos-style Check for updates** after the repository is public.

This section does not schedule a release, new version or signing setup.

## I. Recurring work

- [ ] **I01 — Review/merge Moonlight Android and common-c updates** periodically, followed by local validation and device testing.
- [ ] **I02 — Review Artemis changes** if development resumes.
- [ ] **I03 — Repeat focused dependency/security/compatibility reviews** when upstream, dependencies or exposed surfaces change. The original audit and named fixes are already complete.

## Already complete — do not put back on the implementation list

- Moonlight/common-c merge, toolchain migration, original dependency/security/branding cleanup and decoder-pacing cleanup.
- Native shell/switch-over, profile isolation/deletion, clock, battery, palettes/backgrounds and SteamGridDB integration.
- Main Delos stream menu, remappable shortcut, edge swipe, live bitrate, stream brightness, 1% low FPS and full/compact statistics.
- Per-game launch settings, MediaCodec async decode/Nano Pacer, four MediaCodec SGSR modes and native PyroWave SGSR 2 Video/supersampling.
- External-display resolution/FPS/HDR and controller routing, Wi-Fi keep-alive, settings/choice help with passive scrolling/fades.
- Dashboard logo flicker correction/presentation and manual bitrate preservation, including the PyroWave exception.
- Smart automatic clipboard sync is implemented: `Game.connectionStarted` invokes sync, `handleFocusChange` sends/fetches, and `disconnect` fetches before exit. Verify corner cases in F13 instead of declaring the feature absent.
- JSON custom-key/layout import already exists; compatibility uncertainty belongs in F14.

## Sources and reconciliation

Primary plans: [ROADMAP](ROADMAP.md), [UI plan](UI_PLAN.md), [GUI vision](GUI.md),
[future plans](FUTURE_PLANS.md), [Starwire ideas](IDEAS_FROM_STARWIRE.md),
[original audit](AUDIT.md), [SGSR 2](SGSR2_VIDEO.md), [stream quality](STREAM_QUALITY.md),
[low-resolution investigation](LOW_RESOLUTION_SHARPNESS_AUDIT.md),
[optical-flow research](VIDEO_OPTICAL_FLOW_RESEARCH.md), [external displays](EXTERNAL_DISPLAY.md),
[per-game settings](PER_GAME_SETTINGS.md) and [settings/help audit](SETTINGS_HELP_AUDIT.md).

Also reconciled the local-only merged roadmap, handoff, current-plan and launch-readiness notes.
Their content informs this list; their old status counts are not authoritative and the files remain
uncommitted. Source comments were checked in first-party Java/Kotlin and owned native code;
vendor/submodule internals and token-like names containing XXX are not additional application tasks.
