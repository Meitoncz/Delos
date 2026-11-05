# Mac Setup

Development moved to macOS on Apple Silicon on 2026-11-05. The main checkout is
`/Volumes/Data/Vibecoding/Delos`; do not move it. Windows development instructions
remain in [WINDOWS_SETUP.md](WINDOWS_SETUP.md).

## 1. Tools and Java

Install these tools with Homebrew:

```sh
brew install openjdk@21 node gh android-platform-tools
```

Configure the build environment in your shell (for example, `~/.zshrc`):

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Use JDK 21 to run Gradle. The repository wrapper pins Gradle **9.7.1** in
`gradle/wrapper/gradle-wrapper.properties`; `build.gradle` pins Android Gradle plugin
**9.4.0**. Java source/target compatibility is **17** in `app/build.gradle`, not a
requirement to run Gradle with JDK 17. Use `./gradlew`, not a separately installed Gradle.

## 2. Android SDK

The verified SDK location is `~/Library/Android/sdk`. Install Android SDK Command-line
Tools through Android Studio's SDK Manager if `sdkmanager` is not available. The
verified workstation package set is:

```sh
sdkmanager \
  'platforms;android-36' \
  'platforms;android-37.0' \
  'build-tools;35.0.0' \
  'build-tools;36.0.0' \
  'platform-tools' \
  'ndk;29.0.14206865' \
  'ndk;27.0.12077973' \
  'cmake;3.22.1'
```

This lists installed environment packages, not a claim that every package is required
by the current build. `app/build.gradle` selects **compileSdk 37**, **targetSdk 36**,
**minSdk 29** (Android 10), and **NDK 29.0.14206865**. Native builds use `ndk-build`
and `app/src/main/jni/Android.mk`, not CMake; NDK 27 and CMake are retained workstation
packages, not the selected native toolchain.

Create the ignored `local.properties` in the checkout root, replacing `<name>` with
the macOS account directory name. Use an absolute path; do not use `~` here:

```properties
sdk.dir=/Users/<name>/Library/Android/sdk
```

Do not set `ndk.dir`: Gradle selects the NDK version from `app/build.gradle`.
Optional game-metadata configuration is described in
[WINDOWS_SETUP.md](WINDOWS_SETUP.md#3-localproperties).

## 3. Checkout paths and line endings

**The checkout and build outputs must live under paths without spaces.**
`ndk-build` (make) and the Android Gradle plugin cannot handle spaces reliably;
a characteristic error is `APP_BUILD_SCRIPT points to an unknown file`.
NDK maintainers state that such paths will never work reliably. Quoting the path
in a shell command does not fix the native build. Apply the same constraint to
worktrees and any relocated build-output directories.

For a new checkout, choose a no-space parent directory and initialize submodules:

```sh
gh repo clone Meitoncz/Delos
cd Delos
git submodule update --init --recursive
git config core.autocrlf input
```

For an existing checkout, set `git config core.autocrlf input` there as well. Do not
recreate or relocate the existing main checkout to follow the clone example.

## 4. Build, unit tests and lint

From the checkout root, with the environment above:

```sh
./gradlew :app:testNonRoot_gameDebugUnitTest :app:assembleNonRoot_gameDebug :app:lintNonRoot_gameDebug
```

The flavor is **`nonRoot_game`**. Debug APKs are written under
`app/build/outputs/apk/nonRoot_game/debug/`, including
`app-nonRoot_game-arm64-v8a-debug.apk` and the `armeabi-v7a` counterpart.
The release application ID is **`io.github.meitoncz.delos`**; debug adds `.debug`,
so its ID is **`io.github.meitoncz.delos.debug`**. The source namespace remains
`com.limelight` to keep upstream merges small.

These are setup instructions, not evidence of a new successful build or test run.

## 5. Wireless ADB and the Odin

Use the physical AYN Odin 2 Portal Max, not an emulator. Enable Wireless debugging
in Android Developer options. Open “Pair device with pairing code” and use its
pairing address/port; the pairing port is distinct from the connection port:

```sh
adb pair <odin-ip>:<pairing-port>
adb connect <odin-ip>:5555
```

The `:5555` command applies when the device exposes its ADB connection on that port.
If Wireless debugging shows a different connection port, use that port instead;
it can change after reboot. Discover current endpoints with `adb mdns services`.

Before installing, inspect the foreground activity and ensure no stream is active.
Never reinstall while the user is streaming. When safe, install the ARM64 debug APK:

```sh
adb -s <odin-ip>:<connection-port> install -r app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-arm64-v8a-debug.apk
```

Real game launches and streaming acceptance belong to the user; do not launch PC
games remotely or inject device navigation to perform setup.
