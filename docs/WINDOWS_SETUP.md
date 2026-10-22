# Příprava na Windows

## 1. Nástroje

```powershell
winget install Git.Git GitHub.cli Google.AndroidStudio
gh auth login
```

Java (JDK 17+) není potřeba instalovat zvlášť – Android Studio má vlastní (JBR).

V Android Studiu → **SDK Manager** doinstalovat: **Android SDK Platform 36/37**, **NDK (Side by side)** ve verzi z `app/build.gradle` (`ndkVersion`), **CMake**, **Platform-Tools**.

Claude Code: https://claude.com/claude-code. V adresáři repa stačí spustit `claude` (nebo otevřít `Delos.code-workspace` ve VS Code). Kontext projektu je v `CLAUDE.md`.

## 2. Klon

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

Lomítka v cestě musí být zdvojená:

```properties
sdk.dir=C\:\\Users\\<uživatel>\\AppData\\Local\\Android\\Sdk
ndk.dir=C\:\\Users\\<uživatel>\\AppData\\Local\\Android\\Sdk\\ndk\\<verze>
```

## 4. Build

Pokud `java` není v PATH, nastav JDK z Android Studia:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleNonRoot_gameDebug   # přesné názvy variant: .\gradlew.bat tasks
```

APK najdeš v `app\build\outputs\apk\`. Instalace do telefonu/handheldu přes USB (zapnuté ladění USB):

```powershell
adb install -r app\build\outputs\apk\<varianta>\<soubor>.apk
```
