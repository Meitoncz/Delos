# Delos Roadmap

Delos (formerly Vibetemis) is a fork of **Artemis** for **Vibepollo**. Priority: first bring everything new from Moonlight into the Artemis codebase, then review the Artemis-specific code, only then the custom UI and features.

Details and numbers: [ANALYSIS.md](ANALYSIS.md). Environment setup: [WINDOWS_SETUP.md](WINDOWS_SETUP.md).

## Phase 0 – Fork identity

- [x] Change `applicationId` / `applicationIdSuffix` → `io.github.meitoncz.delos` (debug `.debug`, root flavor `.root`) so the app does not clash with Artemis/Moonlight. The Java package `com.limelight` stays to keep merges easy.
- [x] App name "Delos" (launcher, user-facing strings incl. translations; the Artemis community performance-log strings are kept as-is).
- [ ] Custom icon – together with the UI design.
- [x] Own `versionName` / `versionCode` scheme: semver starting at `0.1.0`, `versionCode = MMmmpp` (0.1.0 → 100).
- [x] README: links to Artemis, Moonlight, Vibepollo; keep GPLv3 and authors.
- [x] GitHub Actions: build a debug APK on every push (`.github/workflows/build.yml`). Signed release builds come in Phase 5.
- [ ] Later: rewrite `fastlane/` metadata (store listing) and `.github/ISSUE_TEMPLATE` for Delos.

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
- [ ] Batch 2 – security (`art://` confirmation, accessibility service).
- [ ] Batch 3 – platform (minSdk 29, deprecated APIs, insets, back handling).
- [ ] Batch 4 – code quality / refactoring.
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

## Phase 5 – Testing and first release

- [ ] Streaming against both **Vibepollo** and **Sunshine**.
- [ ] Virtual display, server commands, clipboard (Vibepollo).
- [ ] Controllers: Xbox, PlayStation (LED, rumble), 8BitDo, Joy-Con; built-in handheld controls.
- [ ] Decoders H.264 / HEVC / AV1, HDR.
- [ ] Android 16 (edge-to-edge, predictive back, keyboard).
- [ ] Release `v0.1` on GitHub (signed APK, release keystore stored as a GitHub secret + offline backup).

## Phase 6 – New UI (handheld friendly)

Principle: keep the streaming part (`Game.java`, overlays, virtual controls), rewrite the "shell".

- [ ] Design prototype in the browser first (HTML, 1920×1080 landscape), then implement.
- [ ] Kotlin + Jetpack Compose with a **custom console-like design system** (no stock Material look): blur, glow, AGSL shaders, focus animations, UI sounds, haptics. Fallbacks below Android 13.
- [ ] **Full gamepad / D-pad control**: visible focus, logical order, A/B confirm/back, L1/R1 to switch tabs.
- [ ] **True fullscreen on every screen** (today only the stream hides the system bars, the menus show them): hide the status and navigation bars via `WindowInsetsController` (swipe from the edge shows them only temporarily) and draw **our own status bar** in the UI – time, device battery, Wi-Fi, later controller battery / connected PC.
- [ ] Layout for landscape and small screens (AYN Odin, Retroid, Ayaneo…), large touch targets.
- [ ] PC list → app/game list with large cards (box art).
- [ ] New settings: categories, search, profiles visible in one place.
- [ ] In-game quick menu via a button combination.
- [ ] Later: game menu, virtual controls editor.

## Phase 7 – Custom features and maintenance

- [ ] Custom features (collect ideas here).
- [ ] Regularly (e.g. monthly) merge `moonlight/master` and upstream `common-c`.
- [ ] Watch the `artemis` remote in case ClassicOldSong resumes development.
- [ ] Keep changes to `Game.java` and `ControllerHandler.java` minimal so future merges stay small.
