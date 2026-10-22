# Delos – context for Claude Code

Android client for streaming games from **Vibepollo** (a fork of Apollo/Sunshine). A fork of **Artemis** (`ClassicOldSong/moonlight-android`, branch `moonlight-noir`), which is itself a fork of **Moonlight Android**. The project was formerly called **Vibetemis**.

- The user is **not a programmer**. Talk to them **in Czech**, explain clearly, propose steps and ask before big or irreversible actions (force push, deleting branches, releases).
- Code, comments, commit messages, PR descriptions and all repo documentation are **in English**.
- **Never commit or push without the user's explicit approval** – ask first.
- Development happens on **Windows** (same machine as Vibepollo). Write commands for PowerShell, build with `gradlew.bat`.
- Work plan: `docs/ROADMAP.md` (tick off finished items). Diff analysis: `docs/ANALYSIS.md`. Environment: `docs/WINDOWS_SETUP.md`.
- Priority: **Moonlight updates first** (common-c → toolchain → merge `moonlight/master` → OpenSSL/opus), **UI and custom features after that**.

## Remotes

- `origin` – `Meitoncz/Delos` (private)
- `artemis` – `ClassicOldSong/moonlight-android` (`moonlight-noir`)
- `moonlight` – `moonlight-stream/moonlight-android` (`master`)

When merging Moonlight, do each phase in its own branch (`update/common-c`, `update/toolchain`, `update/moonlight-12.2`) and merge into `main` only after a successful build.

## Structure

- `app/src/main/java/com/limelight/` – Java code; `Game.java` = streaming activity, `binding/input/` = input, `binding/video/` = decoder
- `app/src/main/jni/moonlight-core/` – native code; prebuilt OpenSSL and opus; submodule `moonlight-common-c` → `Meitoncz/Delos-common-c` (public fork of ClassicOldSong's Apollo fork, merged with upstream; inside the submodule `origin` = Delos-common-c, `artemis` = ClassicOldSong, `upstream` = moonlight-stream). Changes to it are pushed to the fork first, then the pin is updated here.
- Build: Gradle, `app/build.gradle`, NDK via `Android.mk`
- App identity: `applicationId` `io.github.meitoncz.delos` (debug `.debug`); the Java package stays `com.limelight` to keep upstream merges small

## Devices

- Test device: **AYN Odin 2 Portal Max** (Android 13, 1920×1080 landscape), wireless ADB – find it with `adb mdns services`, install the `arm64-v8a` APK
- The emulator is not used for testing – the Odin is enough
