# Příprava na Linuxu

## 1. Nástroje

```bash
# Debian/Ubuntu
sudo apt install git openjdk-17-jdk unzip
# Fedora
sudo dnf install git java-17-openjdk-devel unzip

# GitHub CLI: https://cli.github.com
gh auth login
```

Android Studio: https://developer.android.com/studio (nebo `flatpak install flathub com.google.AndroidStudio`).
V SDK Manageru doinstalovat: **Android SDK Platform 36/37**, **NDK (Side by side)** ve verzi z `app/build.gradle` (`ndkVersion`), **CMake**, **Platform-Tools**.

Claude Code: https://claude.com/claude-code. V adresáři repa stačí spustit `claude`. Kontext projektu je v `CLAUDE.md`.

## 2. Klon

```bash
gh repo clone Meitoncz/Vibetemis
cd Vibetemis
git submodule update --init --recursive

git remote add artemis   https://github.com/ClassicOldSong/moonlight-android.git
git remote add moonlight https://github.com/moonlight-stream/moonlight-android.git
git fetch --all
```

## 3. local.properties

```properties
sdk.dir=/home/<uživatel>/Android/Sdk
ndk.dir=/home/<uživatel>/Android/Sdk/ndk/<verze>
```

## 4. Build

```bash
./gradlew assembleNonRoot_gameDebug   # přesné názvy variant: ./gradlew tasks
```

APK najdeš v `app/build/outputs/apk/`. Instalace do telefonu/handheldu přes USB (zapnuté ladění USB):

```bash
adb install -r app/build/outputs/apk/<varianta>/<soubor>.apk
```
