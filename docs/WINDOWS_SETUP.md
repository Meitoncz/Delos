# Windows Setup

Development moved to macOS Apple Silicon on 2026-11-05; see [MAC_SETUP.md](MAC_SETUP.md)
for the current workstation. These instructions remain available for Windows development;
the Windows PC also runs the Vibepollo host.

## 1. Tools

```powershell
winget install Git.Git GitHub.cli Google.AndroidStudio EclipseAdoptium.Temurin.21.JDK
gh auth login
```

Use **JDK 21** to run the build, independently of the JDK bundled with Android Studio.
Set the user environment variable `JAVA_HOME` to the actual installed JDK 21 directory,
for example `C:\Program Files\Eclipse Adoptium\jdk-21...`. The wrapper pins **Gradle 9.7.1**
(`gradle/wrapper/gradle-wrapper.properties`) and the Android Gradle plugin is **9.4.0**
(`build.gradle`). App Java source/target compatibility is **17**; this is distinct from
the JDK used to run Gradle.

In Android Studio → **SDK Manager** (or `sdkmanager.bat` from `cmdline-tools`) install
**Android SDK Platform 37** (package `platforms;android-37.0`), **NDK (Side by side)
29.0.14206865**, and **Platform-Tools**. `app/build.gradle` sets `compileSdk 37`,
`targetSdk 36`, and `minSdk 29` (Android 10). The project uses `ndk-build`, not CMake.

Claude Code: https://claude.com/claude-code. Run `claude` in the repo directory (or open `Delos.code-workspace` in VS Code). Project context lives in `CLAUDE.md`.

Delos is built for ARM only (`arm64-v8a`, `armeabi-v7a`) and tested on a real handheld; the emulator is not used.

## 2. Clone

Use a checkout path without spaces, such as `C:\src\Delos`. The checkout and build-output
paths must not contain spaces: `ndk-build`/make and the Android Gradle plugin cannot
handle them reliably, even when the shell path is quoted.

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
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21..."   # use the installed directory
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

There are no product flavors; debug APKs end up in `app\build\outputs\apk\debug\`,
including `app-arm64-v8a-debug.apk` and `app-armeabi-v7a-debug.apk`.
For release builds, use `:app:assembleRelease`; APKs are written under
`app\build\outputs\apk\release\`, with the standard `-unsigned` suffix when unsigned.
The release application ID is `io.github.meitoncz.delos`; debug adds `.debug`
(`io.github.meitoncz.delos.debug`). The source namespace remains `com.limelight`.

## 5. Install on a device

Over Wi-Fi (Android 11+, **Wireless debugging** enabled in Developer options; pair once via "Pair device with pairing code" → `adb pair <ip>:<port>`):

```powershell
adb mdns services                  # finds the device (_adb-tls-connect), the port changes after reboot
adb connect <ip>:<port>
adb -s <ip>:<port> install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
```

Over USB (USB debugging enabled): `adb install -r <apk>`.
