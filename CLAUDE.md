# Delos – kontext pro Claude Code

Android klient pro streamování her z **Vibepollo** (fork Apollo/Sunshine). Fork **Artemis** (`ClassicOldSong/moonlight-android`, větev `moonlight-noir`), který je forkem **Moonlight Android**. Projekt se dřív jmenoval **Vibetemis**.

- Uživatel **není programátor**. Vysvětluj česky a srozumitelně, navrhuj kroky a před velkými nebo nevratnými zásahy (force push, mazání větví, release) se ptej.
- Vývoj probíhá na **Windows** (stejný stroj jako Vibepollo). Příkazy piš pro PowerShell, build přes `gradlew.bat`.
- Plán práce: `docs/ROADMAP.md` (odškrtávej hotové body). Analýza rozdílů: `docs/ANALYSIS.md`. Prostředí: `docs/WINDOWS_SETUP.md`.
- Priorita: **nejdřív aktualizace z Moonlightu** (common-c → toolchain → merge `moonlight/master` → OpenSSL/opus), **až potom GUI** a vlastní funkce.

## Remotes

- `origin` – `Meitoncz/Delos` (private)
- `artemis` – `ClassicOldSong/moonlight-android` (`moonlight-noir`)
- `moonlight` – `moonlight-stream/moonlight-android` (`master`)

Při merge Moonlightu dělej každou fázi ve vlastní větvi (`update/common-c`, `update/toolchain`, `update/moonlight-12.2`) a do `main` merguj až po úspěšném buildu.

## Struktura

- `app/src/main/java/com/limelight/` – Java kód; `Game.java` = streamovací aktivita, `binding/input/` = vstupy, `binding/video/` = dekodér
- `app/src/main/jni/moonlight-core/` – nativní kód; submodul `moonlight-common-c` (fork ClassicOldSong kvůli Apollo funkcím); předkompilované OpenSSL a opus
- Build: Gradle, `app/build.gradle`, NDK přes `Android.mk`
