# Roadmapa Delos

Delos (dříve Vibetemis) = fork **Artemis** pro **Vibepollo**. Priorita: nejdřív dostat do Artemis všechno nové z Moonlightu, až potom vlastní GUI a funkce.

Detaily a čísla: [ANALYSIS.md](ANALYSIS.md). Příprava prostředí: [WINDOWS_SETUP.md](WINDOWS_SETUP.md).

## Fáze 0 – Identita forku

- [ ] Změnit `applicationId` / `applicationIdSuffix` (dnes `com.limelight` + `.noir`), např. na `com.delos`, aby se aplikace nebila s Artemis/Moonlight.
- [ ] Název aplikace „Delos“ (`strings.xml`, launcher), vlastní ikona.
- [ ] Vlastní `versionName` / `versionCode` schéma.
- [ ] README: odkaz na Artemis, Moonlight, Vibepollo; zachovat GPLv3 a autory.
- [ ] GitHub Actions: build debug/release APK při každém pushi.

## Fáze 1 – moonlight-common-c

- [ ] Forknout `ClassicOldSong/moonlight-common-c` → `Delos-common-c` (nebo podobně).
- [ ] Merge `moonlight-stream/moonlight-common-c` master (zkušebně bez konfliktů).
- [ ] Přepnout `.gitmodules` na nový fork, aktualizovat pin submodulu.
- [ ] Ověřit Apollo/Vibepollo funkce (server cmd, empty payload).

## Fáze 2 – Toolchain (zvlášť, před merge Moonlightu)

- [ ] AGP 8.13 → 9.4.0, Gradle wrapper → 9.7.1.
- [ ] Java 11 → 17, zapnout `coreLibraryDesugaringEnabled` + `desugar_jdk_libs`.
- [ ] NDK r27 → r29.
- [ ] compileSdk 37, targetSdk 36 (zatím případně nechat 34, pokud to bude dělat potíže, a řešit ve fázi 4).
- [ ] Ověřit JitPack knihovny (MPAndroidChart, SearchPreference) s AGP 9.
- [ ] Projekt se sestaví a aplikace streamuje stejně jako před změnou.

## Fáze 3 – Merge Moonlight 12.2

- [ ] `git merge moonlight/master`.
- [ ] Vyřešit konflikty: `Game.java`, `ControllerHandler.java`, `KeyboardTranslator.java`, `XboxOneController.java`, `MediaCodecDecoderRenderer.java`, `VideoDecoderRenderer.java`, `MoonBridge.java`, `UiHelper.java`, build soubory, `AndroidManifest.xml`, `arrays.xml`, `locales_config.xml`, překlady.
- [ ] Nahradit OpenSSL 1.1.1q → 4.0.2 a libopus 1.5.2 → 1.6.1 (převzít celé složky z Moonlightu, sjednotit `opus/` vs. `libopus/`, upravit `Android.mk`).
- [ ] Zkontrolovat, že nativní kód nepoužívá API odstraněné v OpenSSL 3+.
- [ ] Ověřit konkrétní opravy:
  - [ ] rumble přes `VibratorManager.getDefaultVibrator()` + zachovat „vynutit vibrace telefonu“
  - [ ] zachytávání klávesnice Android 16.1 + zachovat Samsung meta-key / „Quick Meta“
  - [ ] producer throttling vypnutý (Android 16+)
  - [ ] H.264 SPS úpravy jen pod Oreo
  - [ ] LED ovladačů bez pádů a ANR
  - [ ] Xbox Series S/X init, 8BitDo
  - [ ] nové keycody, Shift u `+`
- [ ] Doplnit chybějící překlady (`values-lv`, `values-ta` apod.).

## Fáze 4 – Testování a první release

- [ ] Streamování proti **Vibepollo** i **Sunshine**.
- [ ] Virtuální displej, server commands, schránka (Vibepollo).
- [ ] Ovladače: Xbox, PlayStation (LED, rumble), 8BitDo, Joy-Con; vestavěné ovládání handheldu.
- [ ] Dekodéry H.264 / HEVC / AV1, HDR.
- [ ] Android 16 (edge-to-edge, predictive back, klávesnice).
- [ ] Release `v0.1` na GitHubu (podepsané APK).

## Fáze 5 – Nové GUI (handheld friendly)

Princip: streamovací část (`Game.java`, overlaye, virtuální ovládání) nechat, přepsat „shell“.

- [ ] Jetpack Compose + Material 3 (Material You barvy, tmavý režim).
- [ ] **Plné ovládání gamepadem / D-padem**: viditelný focus, logické pořadí, A/B potvrzení/zpět, L1/R1 přepínání záložek.
- [ ] Layout pro šířku a malé obrazovky (Retroid, AYN Odin, Ayaneo…), velké cíle pro palce.
- [ ] Seznam PC → seznam aplikací/her s velkými kartami (box art).
- [ ] Nové nastavení: kategorie, vyhledávání, profily viditelně na jednom místě.
- [ ] Rychlé menu během hry přes kombinaci tlačítek.
- [ ] Postupně: herní menu, editor virtuálního ovládání.

## Fáze 6 – Vlastní funkce a údržba

- [ ] Vlastní funkce (sepsat sem nápady).
- [ ] Pravidelně (např. 1× měsíčně) mergovat `moonlight/master` a upstream `common-c`.
- [ ] Sledovat `artemis` remote, kdyby ClassicOldSong pokračoval.
- [ ] Minimalizovat zásahy do `Game.java` a `ControllerHandler.java`, aby budoucí merge zůstaly malé.
