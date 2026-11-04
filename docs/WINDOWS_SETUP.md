# Windows Setup

## 1. Tools

```powershell
winget install Git.Git GitHub.cli Google.AndroidStudio EclipseAdoptium.Temurin.21.JDK
gh auth login
```

**JDK 21** is needed separately: Android Studio bundles Java 25, which the current Gradle 8.13 cannot run on (addressed in Phase 2). Set the user environment variable `JAVA_HOME` to `C:\Program Files\Eclipse Adoptium\jdk-21...`.

In Android Studio → **SDK Manager** (or `sdkmanager.bat` from `cmdline-tools`) install: **Android SDK Platform** matching `compileSdk` in `app/build.gradle`, **NDK (Side by side)** in the version from `ndkVersion`, and **Platform-Tools**. The project uses `ndk-build`, CMake is not needed.

Claude Code: https://claude.com/claude-code. Run `claude` in the repo directory (or open `Delos.code-workspace` in VS Code). Project context lives in `CLAUDE.md`.

Delos is built for ARM only (`arm64-v8a`, `armeabi-v7a`) and tested on a real handheld; the emulator is not used.

## 2. Clone

```powershell
gh repo clone Meitoncz/Delos
cd Delos
git submodule update --init --recursive

git remote add artemis   https://github.com/ClassicOldSong/moonlight-android.git
git remote add moonlight https://github.com/moonlight-stream/moonlight-android.git
git fetch artemis
git fetch moonlight
```

## 3. local.properties

Backslashes and colons in paths must be escaped. Do not set `ndk.dir` (deprecated) – the NDK is picked by `ndkVersion` in `app/build.gradle`:

```properties
sdk.dir=C\:\\Users\\<user>\\AppData\\Local\\Android\\Sdk
```

Game metadata (IGDB/PCGamingWiki/Steam store tags) goes through the Delos metadata Worker. Its base URL is not a secret; set it in `local.properties` (or the `DELOS_METADATA_URL` environment variable, which wins when not blank). Without it the build still works and reports game metadata as unavailable. Deploy the Worker and its secrets with [tools/metadata-worker/README.md](../tools/metadata-worker/README.md).

```properties
DELOS_METADATA_URL=https://delos-meta.<account>.workers.dev
```

## 4. Build

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"   # if not set globally
.\gradlew.bat assembleNonRoot_gameDebug   # exact variant names: .\gradlew.bat tasks
```

APKs end up in `app\build\outputs\apk\nonRoot_game\debug\`.

## 5. Install on a device

Over Wi-Fi (Android 11+, **Wireless debugging** enabled in Developer options; pair once via "Pair device with pairing code" → `adb pair <ip>:<port>`):

```powershell
adb mdns services                  # finds the device (_adb-tls-connect), the port changes after reboot
adb connect <ip>:<port>
adb -s <ip>:<port> install -r app\build\outputs\apk\nonRoot_game\debug\app-nonRoot_game-arm64-v8a-debug.apk
```

Over USB (USB debugging enabled): `adb install -r <apk>`.
