# Analysis: Artemis vs. Moonlight Android

As of **October 19, 2026**. Compared directly from git history and code.

- **Artemis** – `ClassicOldSong/moonlight-android`, branch `moonlight-noir`, version **20.2.6** (versionCode 57), HEAD `c5cf27f4`
- **Moonlight** – `moonlight-stream/moonlight-android`, branch `master`, version **12.2** (versionCode 315), HEAD `ae0af6e4` (September 12, 2026)

## Key numbers

| | Moonlight | Artemis |
|---|---|---|
| Last active development | September 2026 (big wave Aug 31 – Sep 12) | September 2025; since then only 4 commits (2026-06/07/09, Mac key mapping) |
| Common base (merge-base) | — | `27ded2ad`, **November 14, 2024** |
| Extra commits | **55** (missing in Artemis) | **555** (missing in Moonlight) |
| Difference in `app/src` | — | 516 files, +70k / −60k lines |

## What Artemis has on top

- **Apollo/Vibepollo integration**: virtual display, server commands (`serverCommand` in `NvHTTP`, `GameMenu`), clipboard sync. Uses its own fork of `moonlight-common-c` (`ClassicOldSong/moonlight-common-c`: "server cmd over Control Stream", "send empty payload").
- **Custom virtual controls**: `binding/input/virtual_controller/keyboard/*` (keyboard, buttons, sticks, touchpad, layout snapping), import/export (`assets/config/*.json`, `KeyConfigHelper`, `KeyMapper`), free joystick (`AnalogStickFree`).
- **Mouse/touch**: mouse modes (`MouseModeOption`), trackpad with taps/scrolling (`TrackpadContext`), pan/zoom (`PanZoomHandler`), sensitivity, DeX scrolling.
- **Settings profiles**: `profiles/*`, `ProfilesActivity`, `EditProfileActivity`.
- **Game menu** (`GameMenu`), custom shortcuts, quick soft-keyboard switching, `KeyboardAccessibilityService`.
- **External display**: `ExternalDisplayControlActivity`, **SBS 3D** via MiDaS (LiteRT + OpenCV, `assets/midas-midas-v2-w8a8.tflite`, `Stereo3DRenderer`).
- **Joy-Con / Pro Controller** (`ProConController`), gamepad debug page (`DebugInfoActivity`), forced device vibration.
- **Video**: custom resolution and bitrate, Fit/Fill/Stretch, portrait, in-game rotation, locked framerate fix, performance overlay with charts (MPAndroidChart, `PerformanceDataTracker`).
- **Settings**: ~152 entries in `preferences.xml` (Moonlight ~64), search (SearchPreference).

## What Artemis is missing (outdated parts)

### Native libraries and toolchain

| | Artemis | Moonlight |
|---|---|---|
| OpenSSL | **1.1.1q (2022, EOL since 9/2023 – no security fixes)** | 4.0.2 |
| libopus | 1.5.2 | 1.6.1 |
| targetSdk / compileSdk | 34 / 36 | 36 / 37 |
| NDK | r27 (27.0.12077973) | r29 (29.0.14206865) |
| AGP / Gradle | 8.13 / 8.13 | 9.4.0 / 9.7.1 |
| Java | 11 | 17 (+ core library desugaring) |
| OkHttp | 4.12.0 | 5.5.0 |
| BouncyCastle | 1.81 | 1.85 |
| jmDNS | 3.6.2 | 3.6.3 |

### moonlight-common-c

The Artemis fork is based on upstream from **July 15, 2025** (pin `c9994368`). Upstream pin is `874ac954` (August 18, 2026). About 35 commits are missing, among others:

- fast FEC decoding via **nanors** (NEON/SIMD),
- **RTSP hardening** against malformed headers and large responses (security),
- rewritten gamepad input batching, better locking, thread leak fixes,
- LTR ACK messages, dual-touchpad controllers, Steam controller type,
- `presentationTimeUs` (more accurate frame timing),
- MbedTLS via the modern PSA API.

Trial merge of upstream into the fork: **no text conflicts**.

### Android part (55 commits from Moonlight)

- Keyboard capture on **Android 16.1** (`f144c7fa`). Artemis only has the old Samsung `SemWindowManager` hack.
- Disabled producer throttling of the video surface, Android 16+ (`767cfcd0`, lower latency).
- Disabled H.264 constraint/level_idc fixups on Oreo+ (`ac04a97d`).
- Controller LED crash and ANR fixes (`16477d82`).
- **Rumble fix** (`cbc5270e`, #1454). Artemis still uses the deprecated `VIBRATOR_SERVICE`.
- Xbox Series S/X init packet (`273281a3`, IDs `0x0b05/0x0b12/0x0b13`). Artemis already has the 8BitDo vendor.
- New Android 15/16 keycodes (`075ed853`), Shift for the `+` key (`6d69962d`), Meta Quest UI (`f05d8b46`).
- SDL joystick code sync (`c2e224eb`), desugaring for jmDNS (`ed4cc1e0`).
- ~25 Weblate translation updates; Latvian (`values-lv`) and Tamil (`values-ta`) are missing.

## Trial merge Moonlight → Artemis

Result of `git merge moonlight/master` (performed and aborted):

- Conflicts in **~15 files + 10 translations**.
- Java: always **1 conflict block** – `Game.java`, `ControllerHandler.java`, `KeyboardTranslator.java`, `XboxOneController.java`, `MediaCodecDecoderRenderer.java`, `VideoDecoderRenderer.java`, `MoonBridge.java`, `UiHelper.java`.
- Build: `app/build.gradle` (4 blocks), `build.gradle`, `gradle-wrapper.properties`, `AndroidManifest.xml`, `res/values/arrays.xml`, `res/xml/locales_config.xml`, submodule `moonlight-common-c`.

**Hidden risks git will not show:**

1. **OpenSSL/opus**: Artemis uses a different layout (`jni/moonlight-core/libopus/` instead of `opus/`, OpenSSL 1.1 headers). A merge would produce a mix → take the whole `openssl/` and `opus/` from Moonlight and update `Android.mk`. Verify the native code does not use APIs removed in OpenSSL 3+.
2. **AGP 9 / Gradle 9**: older JitPack libraries (MPAndroidChart, SearchPreference) may cause trouble.
3. **targetSdk 36**: mandatory edge-to-edge, predictive back, foreground service rules (affects `KeyboardAccessibilityService`, overlays and the external display).
4. **Logical conflicts** in `Game.java`/`ControllerHandler.java` (heavily rewritten in Artemis): Android 16.1 keyboard vs. meta-key hack, rumble vs. "force device vibration".
5. **Vibepollo compatibility**: server commands, clipboard and virtual display must keep working after the `common-c` merge.

## Effort estimate

| Part | Estimate |
|---|---|
| Merge common-c + toolchain + Moonlight, build and streaming work | a few evenings to ~1–2 weeks |
| Testing (controllers, decoders, Android 16, Sunshine + Vibepollo) | ongoing, ~1 week |
| New UI "shell" (Compose, custom design system, gamepad navigation) | ~4–8 weeks of evenings |
| Full UI rewrite incl. game menu and controls editor | months |
