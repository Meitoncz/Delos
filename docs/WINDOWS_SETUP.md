# Windows Setup

## 1. Tools

```powershell
winget install Git.Git GitHub.cli Google.AndroidStudio EclipseAdoptium.Temurin.21.JDK
gh auth login
```

**JDK 21** is needed separately: Android Studio bundles Java 25, which the current Gradle 8.13 cannot run on (addressed in Phase 2). Set the user environment variable `JAVA_HOME` to `C:\Program Files\Eclipse Adoptium\jdk-21...`.

In Android Studio → **SDK Manager** (or `sdkmanager.bat` from `cmdline-tools`) install: **Android SDK Platform 36**, **NDK (Side by side)** in the version from `app/build.gradle` (`ndkVersion`), **Platform-Tools**, **Emulator** and a system image (e.g. `system-images;android-36;google_apis;x86_64`). The project uses `ndk-build`, CMake is not needed.

Claude Code: https://claude.com/claude-code. Run `claude` in the repo directory (or open `Delos.code-workspace` in VS Code). Project context lives in `CLAUDE.md`.

### Emulator

Requires the Windows feature **Windows Hypervisor Platform** (as administrator, then reboot):

```powershell
dism /online /enable-feature /featurename:HypervisorPlatform /all
emulator -accel-check   # should print "WHPX ... is installed and usable"
```

The virtual device `Odin2_Portal_Max` (1920×1080 landscape, 320 dpi, 4 GB RAM) is started with `emulator -avd Odin2_Portal_Max`. Install the `x86_64` APK into the emulator and the `arm64-v8a` APK onto the handheld.

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

Backslashes and colons in paths must be escaped:

```properties
sdk.dir=C\:\\Users\\<user>\\AppData\\Local\\Android\\Sdk
ndk.dir=C\:\\Users\\<user>\\AppData\\Local\\Android\\Sdk\\ndk\\<version>
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
