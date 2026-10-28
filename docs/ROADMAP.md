# Delos Roadmap

Delos (formerly Vibetemis) is a fork of **Artemis** for **Vibepollo**. Priority: first bring everything new from Moonlight into the Artemis codebase, then review the Artemis-specific code, only then the custom UI and features.

Details and numbers: [ANALYSIS.md](ANALYSIS.md). Environment setup: [WINDOWS_SETUP.md](WINDOWS_SETUP.md).

Live selected work: [GitHub Issues](https://github.com/Meitoncz/Delos/issues) and the private
[Delos Project](https://github.com/users/Meitoncz/projects/1). [TODO.md](TODO.md) maps original audit
IDs to issues; [GITHUB_WORKFLOW.md](GITHUB_WORKFLOW.md) defines operation. This roadmap preserves
high-level phases and history, not a second current task checklist. Older unchecked Phase 4/6 entries
are historical leads, not additional missing features. The complete pre-migration code audit is
[frozen at cab8c7c4](https://github.com/Meitoncz/Delos/blob/cab8c7c4/docs/TODO.md).

## Phase 0 – Fork identity

- [x] Change `applicationId` / `applicationIdSuffix` → `io.github.meitoncz.delos` (debug `.debug`, root flavor `.root`) so the app does not clash with Artemis/Moonlight. The Java package `com.limelight` stays to keep merges easy.
- [x] App name "Delos" (launcher, user-facing strings incl. translations; the Artemis community performance-log strings are kept as-is).
- [ ] Custom icon – together with the UI design.
- [x] Own `versionName` / `versionCode` scheme: semver starting at `0.1.0`, `versionCode = MMmmpp` (0.1.0 → 100).
- [x] README: links to Artemis, Moonlight, Vibepollo; keep GPLv3 and authors.
- [x] GitHub Actions: build a debug APK on every push (`.github/workflows/build.yml`). Signed release builds come in Phase 5.
- [x] Moonlight / Artemis leftovers removed (2026-10-24): `fastlane/` store listing, `.github` issue templates and contributing guide, Wireshark dissectors (`LuaScripts/`), AppVeyor, the Moonlight logo source, the old test guide and an unused Robolectric config. `decoder-errata.txt` moved to `docs/DECODER_ERRATA.md`.
- [ ] When Delos goes public: a store listing (`fastlane/`), issue templates and a contributing guide written for Delos.

## Phase 1 – moonlight-common-c

- [x] Fork `ClassicOldSong/moonlight-common-c` → [`Meitoncz/Delos-common-c`](https://github.com/Meitoncz/Delos-common-c) (public; forks of public repos cannot be private).
- [x] Merge upstream `moonlight-common-c` at `874ac954` – the exact pin used by Moonlight 12.2 (upstream master was 11 commits ahead). No conflicts; Apollo packet table verified.
- [x] Port the API changes: `nanors` replaces `reedsolomon` in `Android.mk`; frame times are now microseconds (`callbacks.c`, `MoonBridge`, `MediaCodecDecoderRenderer` timestamp normalization as in Moonlight).
- [x] Point `.gitmodules` to the new fork, update the submodule pin.
- [x] Stream test on the Odin against Vibepollo (Returnal) – OK.
- [ ] Verify Apollo/Vibepollo extras (server commands, clipboard, virtual display) – in Phase 5 testing.

## Phase 2 – Toolchain (separately, before the Moonlight merge)

- [x] AGP 8.13 → 9.4.0, Gradle wrapper → 9.7.1 (`resValues = true`, `proguard-android-optimize.txt` as required by AGP 9, Groovy DSL `prop = value` syntax).
- [x] Java 11 → 17, enable `coreLibraryDesugaringEnabled` + `desugar_jdk_libs` 2.1.5.
- [x] NDK r27 → r29 (29.0.14206865).
- [x] compileSdk 37. targetSdk stays 34 for now – raising it to 36 enforces edge-to-edge on Android 15+ and comes with the Moonlight merge in Phase 3.
- [x] JitPack libraries (MPAndroidChart, SearchPreference) build with AGP 9. Performance overlay charts verified on the Odin. (The settings search UI was removed by Artemis in `15122dd1`.)
- [x] The app streams exactly as before the change (tested on the Odin).

## Phase 3 – Merge Moonlight 12.2

- [x] `git merge moonlight/master` (12.2, `b48494cb`).
- [x] Resolve conflicts. Most of them were whole-file conflicts caused by CRLF (Artemis) vs. LF (Moonlight) line endings; an EOL-normalized 3-way merge left only a few real ones:
  - `Game.java`: Moonlight's Android 16.1 keyboard capture, Samsung meta-key hack kept as fallback.
  - `KeyboardTranslator.java`: both `VK_F12` (Artemis) and `VK_F13`–`F24` (Moonlight); Artemis `translate(keycode, scancode, deviceId)` signature kept.
  - `XboxOneController.java`: GameSir vendor (Artemis) + Moonlight init packets; dropped Artemis' duplicate `0x0b12`/`0x02fe` entries.
  - `MediaCodecDecoderRenderer.java`: Artemis frame pacing / low-latency release logic kept; dead pre-Lollipop code removed as in Moonlight; duplicate `baseTimestampUs` from the Phase 1 port removed.
  - `UiHelper.java`: Moonlight's more robust `GameManager` handling.
  - Translations: both sides merged; where both translated the same key, the Delos/Artemis text wins (keeps branding and Artemis feature texts).
- [x] Replace OpenSSL 1.1.1q → 4.0.2 and libopus 1.5.2 → 1.6.1 (whole directories from Moonlight, `libopus/` → `opus/`, `Android.mk` identical to Moonlight).
- [x] Native code builds against OpenSSL 4 with zero warnings (libssl no longer linked, only libcrypto).
- [x] Shared dependencies to Moonlight versions (OkHttp 5.5.0, BouncyCastle 1.85, jmDNS 3.6.3).
- [x] targetSdk 34 → 36. Activities using `UiHelper.notifyNewRootView` handle edge-to-edge insets; `DebugInfoActivity` does not (see Phase 4). Not visible on the Odin (Android 13).
- [x] Verify specific fixes are present in the merged code:
  - [x] rumble via `VibratorManager.getDefaultVibrator()` + "force device vibration" (`enableDeviceRumble`) kept
  - [x] Android 16.1 keyboard capture + Samsung meta-key / "Quick Meta" kept
  - [x] producer throttling disabled (Android 16+)
  - [x] H.264 SPS fixups only below Oreo
  - [x] controller LEDs without crashes and ANRs
  - [x] Xbox Series S/X init, 8BitDo
  - [x] new keycodes, Shift for `+`
- [x] New translations from Moonlight: Esperanto, Latvian, Tamil (language list + `locales_config.xml`).
- [x] Stream test on the Odin (video, audio, controller, rumble, performance overlay) – OK.

## Phase 4 – Artemis audit, code review and refactoring

The Artemis vs. Moonlight comparison only covers code both share. This phase looks at what Artemis added on its own (~555 commits): its extra libraries, outdated APIs and overall code quality.

**Audit done – findings, decisions and the fix plan (batches 1–4) are in [AUDIT.md](AUDIT.md).** The checklist below tracks the fixes.

- [x] Audit report (`docs/AUDIT.md`): dependencies, lint, deprecated APIs, dead code, security, targeted code review.
- [x] Decision: remove SBS 3D (MiDaS / LiteRT / OpenCV, ~29 MB) – niche feature, Delos targets handhelds.
- [x] Batch 1 – cleanup: SBS 3D and performance logging removed, unused dependencies and dead code removed, FileProvider narrowed, ARM-only builds, debug builds without R8 (APK 44.2 → 19.4 MB).
- [x] Batch 2 – security: confirmation for external `art://launch` links, keyboard accessibility service only while the stream has focus.
- [x] Batch 3 – platform: minSdk 29 (Android 10), root flavor dropped, no deprecated APIs in Artemis-only code, predictive-back friendly back handling, AppCompat 1.8 / Material 1.14.
- [ ] Batch 4 – code quality / refactoring. Done: 4a (cleanup, branding, tests), 4b (decoder frame pacing). Open: 4c (`Game.java`), 4d (dialogs).
- [x] Review every setting labeled "(Experimental)" – why it is experimental, and drop the label where there is no reason left (most are years old and well tested). Currently: Enable HDR, Force full range video, Prefer AV1, frame pacing Warp Drive / Warp 2, Smart Clipboard Sync, Ultra Low Latency, LFR. (Tight Vsync was removed in step 4b – it never did anything.) Check upstream (Moonlight/Artemis) history and known issues for each; update all translations.
  - Done: label removed from HDR, Force full range video, Prefer AV1 and Smart Clipboard Sync (HDR and AV1 confirmed working on the Odin with Vibepollo + virtual display). Kept for Warp Drive / Warp 2, Ultra Low Latency and LFR (open device-specific upstream issues), although ULL, LFR and Warp 2 work well on the Odin.
- [ ] Fewer confirmation dialogs (product rule: one-click flows): e.g. "resume session?" and "quit?" prompts, quit-app confirmation. Keep only prompts that protect against data loss (delete profile, clear keyboard layout) or pairing with an unknown PC.

- [ ] **Dependency audit** of Artemis-only libraries – current version, maintenance status, known vulnerabilities, APK size impact, alternatives:
  - LiteRT + LiteRT GPU and OpenCV (SBS 3D / MiDaS – large native libraries),
  - MPAndroidChart (performance overlay charts; unmaintained upstream),
  - SearchPreference (JitPack) – **unused**: Artemis removed the search bar in `15122dd1`; drop the dependency and its `searchpreference_*` strings (or bring search back in the new UI),
  - Gson, jcodec, AndroidX (appcompat, preference, recyclerview, cardview), Material Components.
- [ ] **Outdated APIs** in Artemis code: Android lint report, `javac -Xlint:deprecation` warnings, known suspects (deprecated `VIBRATOR_SERVICE`, Samsung `SemWindowManager` reflection hack, `SYSTEM_UI_FLAG_*` immersive flags in `Game.java` → `WindowInsetsController`, deprecated `Handler()`/display/back-press APIs, etc.).
- [ ] **Dead code and resources**: unused classes, features, layouts, strings and assets.
- [ ] Known items from the Moonlight merge:
  - Artemis frame pacing / low-latency code in `MediaCodecDecoderRenderer` (duplicated branches, dead `SDK_INT >= 21` checks, mixed-language comments) – clean up.
  - `DebugInfoActivity` does not apply window insets (content under the system bars on Android 15+ with targetSdk 36).
  - Translations from Moonlight still say "Moonlight" where they mean the app – rebrand to Delos (keep real Moonlight references).
- [ ] **Security review**: exported components, `KeyboardAccessibilityService` scope, `FileProvider` paths, hardcoded URLs/e-mails, network and certificate handling.
- [ ] **Full code review** of the Artemis-specific code → prioritized list of findings (bugs, risks, cleanups) in `docs/`.
- [ ] **Refactoring where needed**: Artemis-only code can be cleaned up freely; code shared with Moonlight (`Game.java`, `ControllerHandler.java`, decoder) only minimally, so future upstream merges stay small.
- [ ] Build and test on the Odin after every step.

## Phase 5 – Testing

Releases are not planned as a phase: the first version is published when the project owner decides (the repository is private for now).

- [ ] Streaming against both **Vibepollo** and **Sunshine**.
- [ ] Virtual display, server commands, clipboard (Vibepollo).
- [ ] Controllers: Xbox, PlayStation (LED, rumble), 8BitDo, Joy-Con; built-in handheld controls.
- [ ] Decoders H.264 / HEVC / AV1, HDR.
- [ ] Android 16 (edge-to-edge, predictive back, keyboard).

## Phase 6 – New UI (handheld friendly)

Principle: keep the streaming part (`Game.java`, overlays, virtual controls), rewrite the "shell".

**Order (decided 2026-10-23): the native UI comes next; new features and smaller changes (including the open Phase 4 items) come after it.**

- [x] Design prototype in the browser first (HTML, 1920×1080 landscape): vision in [GUI.md](GUI.md), interactive prototype in [prototype/delos-gui.html](prototype/delos-gui.html) (open it in a browser; keyboard or gamepad).
- [ ] Implement the prototype natively in Kotlin + Jetpack Compose – **detailed plan, technology and open questions: [UI_PLAN.md](UI_PLAN.md)**. Steps:
  - [x] 6.0 Preparation (Odin key codes, library versions, font licences)
  - [x] 6.1 Build setup (Kotlin + Compose, empty fullscreen shell as a second debug launcher entry)
  - [x] 6.2 Design system (colors incl. Material You, fonts, icons, focus effects, animated backgrounds, blur, sounds, haptics)
  - [x] 6.3 Shell (own status bar and hint bar, gamepad input layer, navigation, context and side menus, interface scale)
  - [x] 6.4 Home (grid and row layouts, logo + hero background, covers, game context menu)
  - [x] 6.5 Launching a game (loader, dive into the stream, resume/quit without dialogs)
  - [x] 6.6 First launch, PCs and pairing (profile, PC circles, PIN/OTP pairing, portal, devices screen, links and shortcuts)
  - [x] 6.7 Settings (all 116 existing controls on the same keys, search and Y shortcut, Appearance, Covers information, settings profiles). Changed resolution confirmed in a real stream; controller navigation and feedback verified on the Odin. See [SETTINGS.md](SETTINGS.md). The previous count of 126 included ten category keys.
  - [x] 6.8 SteamGridDB (API key, matching, 4 images per game, bulk download, the artwork screen)
  - [x] 6.9 Switch-over and cleanup (new shell is the only launcher, old UI and unused dependencies removed; completed in `5e589749`)
  - [ ] 6.10 Polish and testing (side-by-side with the prototype, performance, touch, older Android fallbacks, other handhelds)
    - [x] Phosphor icons (2026-10-28): replaced Material Symbols with bundled Regular/Fill subsets in the app and prototype, and assigned action-specific icons to all 105 catalog settings and menus. Build, 80 unit tests, lint and prototype checks pass; ARM64 build installed and accepted on Odin. The user's final adjustment uses Palette for the color-palette selector. See [ICONS.md](ICONS.md).
    - [x] Settings/help audit (2026-10-28): history reconciled, authored help for all 105 catalog settings and 29 native rows, selected-choice explanations, passive autoscroll and edge fades, per-game and live-menu help, corrected PyroWave applicability. Implementation, local checks and user acceptance of the installed Odin build complete. See [SETTINGS_HELP_AUDIT.md](SETTINGS_HELP_AUDIT.md).
    - [x] Complete external-display support (done 2026-10-27): streams on a monitor/TV use the Delos loading screen and in-stream menu, preserve the configured resolution and FPS instead of replacing them with the TV mode, retain HDR, and return cleanly to the shell. The built-in Odin controls operate the TV menu through a semantic cross-display input route, including D-pad, analog navigation, A/B and adjustable rows, without disabling keyboard `commitText`. See [EXTERNAL_DISPLAY.md](EXTERNAL_DISPLAY.md).
- [ ] Remaining UI work: the controls editor (A09), abnormal/shortcut stream exits (A11) and the
  three ordinary confirmations (A12). The normal streaming menu is already implemented; its further
  design polish is deferred (A17) and requires agreement before changes.

## Phase 7 – Custom features and maintenance

- [ ] Custom features (collect ideas here):
  - [x] **PyroWave** (done 2026-10-25): the intra-only GPU wavelet codec (Themaister/pyrowave, MIT) that Vibepollo 2.0+ streams, as a manual codec choice (Settings → codec → PyroWave, shown only where the device can decode it). Protocol from the official client Nonary/moonlight-qt: its 10 common-c commits cherry-picked into Delos-common-c (record framing, lost/record-start buffers, partial recovery; LZ4 compression off). The library is built from source (`jni/pyrowave/`, vendored by `tools/pyrowave/vendor.ps1` at bitstream `186f0393`, 64-bit ABIs only); the Vulkan renderer `jni/pyrowave-renderer/` (adapted from joemossjr16/artemis-android-pyrowave, framing from moonlight-qt) gets frames straight from moonlight-core, never through Java. Bitrate guidance from the author's regression (`PyroWaveBitrate`: 225 Mbps at 1080p60, 445 Mbps at 1080p120); the slider now goes to 1000 Mbps. The statistics panel works for PyroWave and shows the total delay (network + PC + decoding) for every codec. Tested on the Odin at 1080p120 / 445 Mbps.
  - [x] **PyroWave HDR10** (done 2026-10-25): with HDR on, Delos offers `VIDEO_FORMAT_PYROWAVE_HDR10`; the renderer decodes into R16 planes and follows the host's HDR mode during the stream. The Odin's display hardware ignores the HDR dataspace of RGB layers (BT.2020 PQ shows like sRGB, grey and flat, in 10-bit and FP16 alike) but shows P010 like MediaCodec's output, so in HDR the frame goes to SurfaceFlinger as P010 on a child surface control with `BT2020_ITU_PQ`: Vulkan packs (Y, Cb, Cr) into an RGBA16F AHardwareBuffer and OpenGL ES writes the P010 buffers with `GL_EXT_YUV_target` (`pyrowave_yuv_gl.cpp`, about 0.7 ms on the GPU; a CPU copy is the fallback). Fixed on the way: a stream freeze after packet loss (Granite waited forever for a GPU timestamp query; `tools/pyrowave/patches/0001`, and `hostQueryReset` is enabled like Granite's own devices do). The video thread logs where it is stuck after 0.5 s and its stack after 2 s. HDR bitrate guidance 1.2x (530 Mbps at 1080p120). The statistics show Mb/s next to MB/s. Later: 4:4:4, LZ4 compression, bandwidth calibration.
  - [x] Preserve manual bitrate (2026-10-28): resolution, FPS and codec changes retain the current bitrate unless the resulting codec is PyroWave. PyroWave still recalculates for resolution/FPS/HDR changes. Applies to profile and per-game settings, preserving inheritance. Build, 80 tests and lint pass; user accepted the Odin build.
  - [ ] **Ideas from Starwire** (chosen 2026-10-25; details and credits in `docs/IDEAS_FROM_STARWIRE.md`):
    - [x] Change the bitrate during a stream (done 2026-10-26): live stream-menu control uses Vibepollo's authenticated bitrate endpoint and displays the value accepted by the host. Tested on the Odin without reconnecting. Idea from Moonlight V+.
    - [x] Fast V-Sync (Nano Pacer) and async decoding for MediaCodec (done 2026-10-26): optional latest-frame pacing and callback-based codec operation, independently selectable and tested on the Odin.
    - [x] Per-game settings (2026-10-25): Game settings in each game's dashboard context menu, Stream / Display tabs and cover preview. Resolution, FPS, bitrate, codec, frame pacing, HDR and selected display options inherit from the user profile unless overridden for that PC/game. The user approved the focused quality/display scope; touch mode stays in the profile. Tested on the Odin, including per-game resolution in a real stream. Idea: Moonlight X per-app settings. See [PER_GAME_SETTINGS.md](PER_GAME_SETTINGS.md).
    - [ ] Hide the PC cursor: never / while touching / always (Vibepollo; Starwire)
    - [x] 1% low FPS in the statistics (Moonlight V+, Moonlight X Turnip); done 2026-10-25, over the last ~10 s, gaps over 0.5 s ignored. "Total delay" renamed "Total latency"
    - [x] Wi-Fi keep-alive (inherited from Artemis as "Prevent Packet Loss", renamed 2026-10-28): optional small control packets approximately every 20 ms during a connected stream, in addition to the Wi-Fi locks. Device-specific benefit has not been measured on the Odin.
    - [x] Brightness slider in the stream menu (Moonlight X); done 2026-10-25, Brightness page in the stream menu, for the stream only
  - [ ] **VRR**: the Odin 2 Portal probably has no VRR display; to be looked into after PyroWave.
  - [x] Snapdragon upscaler for streamed video (MediaCodec accepted 2026-10-27; PyroWave parity completed 2026-10-28): both renderers implement SGSR 1 Standard / High Quality and SGSR 2 Video / Video Quality, HDR/P010, live On/Off, separate presentation sharpness, native-resolution supersampling and processing-time accounting. PyroWave Quality has a distinct reactive activation pass. Build, 80 unit tests, lint and real Odin Vulkan shader/lifecycle probes pass. Earlier live acceptance and 118–119 FPS measurements apply to the prior SGSR 2 Video path; new-mode visual/sustained-performance checks remain open. See [STREAM_QUALITY.md](STREAM_QUALITY.md) and [SGSR2_VIDEO.md](SGSR2_VIDEO.md).
- Long-term ideas, not planned yet: [FUTURE_PLANS.md](FUTURE_PLANS.md).
- [ ] Regularly (e.g. monthly) merge `moonlight/master` and upstream `common-c`.
- [ ] Watch the `artemis` remote in case ClassicOldSong resumes development.
- [ ] Keep changes to `Game.java` and `ControllerHandler.java` minimal so future merges stay small.
