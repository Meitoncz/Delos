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
- [x] JitPack libraries (MPAndroidChart, SearchPreference) build with AGP 9. Performance overlay charts verified on the Odin; settings search to be rechecked in Phase 5.
- [x] The app streams exactly as before the change (tested on the Odin).

## Phase 3 – Merge Moonlight 12.2

- [ ] `git merge moonlight/master`.
- [ ] Resolve conflicts: `Game.java`, `ControllerHandler.java`, `KeyboardTranslator.java`, `XboxOneController.java`, `MediaCodecDecoderRenderer.java`, `VideoDecoderRenderer.java`, `MoonBridge.java`, `UiHelper.java`, build files, `AndroidManifest.xml`, `arrays.xml`, `locales_config.xml`, translations.
- [ ] Replace OpenSSL 1.1.1q → 4.0.2 and libopus 1.5.2 → 1.6.1 (take the whole directories from Moonlight, unify `opus/` vs. `libopus/`, update `Android.mk`).
- [ ] Check that the native code does not use APIs removed in OpenSSL 3+.
- [ ] Shared dependencies to Moonlight versions (OkHttp 5.5.0, BouncyCastle 1.85, jmDNS 3.6.3).
- [ ] targetSdk 34 → 36: edge-to-edge, predictive back, foreground service rules (Artemis activities, overlays, external display).
- [ ] Verify specific fixes:
  - [ ] rumble via `VibratorManager.getDefaultVibrator()` + keep "force device vibration"
  - [ ] Android 16.1 keyboard capture + keep Samsung meta-key / "Quick Meta"
  - [ ] producer throttling disabled (Android 16+)
  - [ ] H.264 SPS fixups only below Oreo
  - [ ] controller LEDs without crashes and ANRs
  - [ ] Xbox Series S/X init, 8BitDo
  - [ ] new keycodes, Shift for `+`
- [ ] Add missing translations (`values-lv`, `values-ta`, etc.).

## Phase 4 – Artemis audit, code review and refactoring

The Artemis vs. Moonlight comparison only covers code both share. This phase looks at what Artemis added on its own (~555 commits): its extra libraries, outdated APIs and overall code quality.

- [ ] **Dependency audit** of Artemis-only libraries – current version, maintenance status, known vulnerabilities, APK size impact, alternatives:
  - LiteRT + LiteRT GPU and OpenCV (SBS 3D / MiDaS – large native libraries),
  - MPAndroidChart (performance overlay charts; unmaintained upstream),
  - SearchPreference (settings search, JitPack),
  - Gson, jcodec, AndroidX (appcompat, preference, recyclerview, cardview), Material Components.
- [ ] **Outdated APIs** in Artemis code: Android lint report, `javac -Xlint:deprecation` warnings, known suspects (deprecated `VIBRATOR_SERVICE`, Samsung `SemWindowManager` reflection hack, deprecated `Handler()`/display/back-press APIs, etc.).
- [ ] **Dead code and resources**: unused classes, features, layouts, strings and assets.
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
