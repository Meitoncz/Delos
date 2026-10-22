# Delos Roadmap

Delos (formerly Vibetemis) is a fork of **Artemis** for **Vibepollo**. Priority: first bring everything new from Moonlight into the Artemis codebase, only then the custom UI and features.

Details and numbers: [ANALYSIS.md](ANALYSIS.md). Environment setup: [WINDOWS_SETUP.md](WINDOWS_SETUP.md).

## Phase 0 – Fork identity

- [x] Change `applicationId` / `applicationIdSuffix` → `io.github.meitoncz.delos` (debug `.debug`, root flavor `.root`) so the app does not clash with Artemis/Moonlight. The Java package `com.limelight` stays to keep merges easy.
- [x] App name "Delos" (launcher, user-facing strings incl. translations; the Artemis community performance-log strings are kept as-is).
- [ ] Custom icon – together with the UI design.
- [x] Own `versionName` / `versionCode` scheme: semver starting at `0.1.0`, `versionCode = MMmmpp` (0.1.0 → 100).
- [x] README: links to Artemis, Moonlight, Vibepollo; keep GPLv3 and authors.
- [x] GitHub Actions: build a debug APK on every push (`.github/workflows/build.yml`). Signed release builds come in Phase 4.
- [ ] Later: rewrite `fastlane/` metadata (store listing) and `.github/ISSUE_TEMPLATE` for Delos.

## Phase 1 – moonlight-common-c

- [ ] Fork `ClassicOldSong/moonlight-common-c` → `Delos-common-c` (or similar).
- [ ] Merge `moonlight-stream/moonlight-common-c` master (trial merge had no conflicts).
- [ ] Point `.gitmodules` to the new fork, update the submodule pin.
- [ ] Verify Apollo/Vibepollo features (server commands, empty payload).

## Phase 2 – Toolchain (separately, before the Moonlight merge)

- [ ] AGP 8.13 → 9.4.0, Gradle wrapper → 9.7.1.
- [ ] Java 11 → 17, enable `coreLibraryDesugaringEnabled` + `desugar_jdk_libs`.
- [ ] NDK r27 → r29.
- [ ] compileSdk 37, targetSdk 36 (may stay at 34 for now if it causes trouble, then handled in Phase 4).
- [ ] Verify JitPack libraries (MPAndroidChart, SearchPreference) with AGP 9.
- [ ] The project builds and the app streams exactly as before the change.

## Phase 3 – Merge Moonlight 12.2

- [ ] `git merge moonlight/master`.
- [ ] Resolve conflicts: `Game.java`, `ControllerHandler.java`, `KeyboardTranslator.java`, `XboxOneController.java`, `MediaCodecDecoderRenderer.java`, `VideoDecoderRenderer.java`, `MoonBridge.java`, `UiHelper.java`, build files, `AndroidManifest.xml`, `arrays.xml`, `locales_config.xml`, translations.
- [ ] Replace OpenSSL 1.1.1q → 4.0.2 and libopus 1.5.2 → 1.6.1 (take the whole directories from Moonlight, unify `opus/` vs. `libopus/`, update `Android.mk`).
- [ ] Check that the native code does not use APIs removed in OpenSSL 3+.
- [ ] Verify specific fixes:
  - [ ] rumble via `VibratorManager.getDefaultVibrator()` + keep "force device vibration"
  - [ ] Android 16.1 keyboard capture + keep Samsung meta-key / "Quick Meta"
  - [ ] producer throttling disabled (Android 16+)
  - [ ] H.264 SPS fixups only below Oreo
  - [ ] controller LEDs without crashes and ANRs
  - [ ] Xbox Series S/X init, 8BitDo
  - [ ] new keycodes, Shift for `+`
- [ ] Add missing translations (`values-lv`, `values-ta`, etc.).

## Phase 4 – Testing and first release

- [ ] Streaming against both **Vibepollo** and **Sunshine**.
- [ ] Virtual display, server commands, clipboard (Vibepollo).
- [ ] Controllers: Xbox, PlayStation (LED, rumble), 8BitDo, Joy-Con; built-in handheld controls.
- [ ] Decoders H.264 / HEVC / AV1, HDR.
- [ ] Android 16 (edge-to-edge, predictive back, keyboard).
- [ ] Release `v0.1` on GitHub (signed APK, release keystore stored as a GitHub secret + offline backup).

## Phase 5 – New UI (handheld friendly)

Principle: keep the streaming part (`Game.java`, overlays, virtual controls), rewrite the "shell".

- [ ] Design prototype in the browser first (HTML, 1920×1080 landscape), then implement.
- [ ] Kotlin + Jetpack Compose with a **custom console-like design system** (no stock Material look): blur, glow, AGSL shaders, focus animations, UI sounds, haptics. Fallbacks below Android 13.
- [ ] **Full gamepad / D-pad control**: visible focus, logical order, A/B confirm/back, L1/R1 to switch tabs.
- [ ] Layout for landscape and small screens (AYN Odin, Retroid, Ayaneo…), large touch targets.
- [ ] PC list → app/game list with large cards (box art).
- [ ] New settings: categories, search, profiles visible in one place.
- [ ] In-game quick menu via a button combination.
- [ ] Later: game menu, virtual controls editor.

## Phase 6 – Custom features and maintenance

- [ ] Custom features (collect ideas here).
- [ ] Regularly (e.g. monthly) merge `moonlight/master` and upstream `common-c`.
- [ ] Watch the `artemis` remote in case ClassicOldSong resumes development.
- [ ] Keep changes to `Game.java` and `ControllerHandler.java` minimal so future merges stay small.
