# Analýza: Artemis vs. Moonlight Android

Stav k **19. 10. 2026**. Porovnáno přímo z git historie a kódu.

- **Artemis** – `ClassicOldSong/moonlight-android`, větev `moonlight-noir`, verze **20.2.6** (versionCode 57), HEAD `c5cf27f4`
- **Moonlight** – `moonlight-stream/moonlight-android`, větev `master`, verze **12.2** (versionCode 315), HEAD `b48494cb` (12. 9. 2026)

## Základní čísla

| | Moonlight | Artemis |
|---|---|---|
| Poslední aktivní vývoj | září 2026 (velká vlna 31. 8. – 12. 9.) | září 2025; poté jen 4 commity (2026-06/07/09, mapování kláves pro Mac) |
| Společný základ (merge-base) | — | `27ded2ad`, **14. 11. 2024** |
| Commity navíc | **55** (Artemis je nemá) | **555** (Moonlight je nemá) |
| Rozdíl v `app/src` | — | 516 souborů, +70k / −60k řádků |

## Co má Artemis navíc

- **Integrace s Apollo/Vibepollo**: virtuální displej, serverové příkazy (`serverCommand` v `NvHTTP`, `GameMenu`), synchronizace schránky. Používá vlastní fork `moonlight-common-c` (`ClassicOldSong/moonlight-common-c`: „server cmd over Control Stream“, „send empty payload“).
- **Vlastní virtuální ovládání**: `binding/input/virtual_controller/keyboard/*` (klávesnice, tlačítka, analogy, touchpad, přichytávání layoutu), import/export (`assets/config/*.json`, `KeyConfigHelper`, `KeyMapper`), volný joystick (`AnalogStickFree`).
- **Myš/dotyk**: režimy myši (`MouseModeOption`), trackpad s tapy/scrollem (`TrackpadContext`), pan/zoom (`PanZoomHandler`), citlivost, DeX scrolling.
- **Profily nastavení**: `profiles/*`, `ProfilesActivity`, `EditProfileActivity`.
- **Herní menu** (`GameMenu`), vlastní zkratky, rychlé přepínání soft klávesnice, `KeyboardAccessibilityService`.
- **Externí displej**: `ExternalDisplayControlActivity`, **SBS 3D** přes MiDaS (LiteRT + OpenCV, `assets/midas-midas-v2-w8a8.tflite`, `Stereo3DRenderer`).
- **Joy-Con / Pro Controller** (`ProConController`), debug stránka gamepadu (`DebugInfoActivity`), vynucení vibrací telefonu.
- **Video**: vlastní rozlišení a bitrate, Fit/Fill/Stretch, portrét, otáčení ve hře, fix zamčeného framerate, výkonnostní overlay s grafy (MPAndroidChart, `PerformanceDataTracker`).
- **Nastavení**: ~152 položek v `preferences.xml` (Moonlight ~64), vyhledávání (SearchPreference).

## Co Artemis chybí (zastaralost)

### Nativní knihovny a toolchain

| | Artemis | Moonlight |
|---|---|---|
| OpenSSL | **1.1.1q (2022, EOL od 9/2023 – bez bezpečnostních oprav)** | 4.0.2 |
| libopus | 1.5.2 | 1.6.1 |
| targetSdk / compileSdk | 34 / 36 | 36 / 37 |
| NDK | r27 (27.0.12077973) | r29 (29.0.14206865) |
| AGP / Gradle | 8.13 / 8.13 | 9.4.0 / 9.7.1 |
| Java | 11 | 17 (+ core library desugaring) |
| OkHttp | 4.12.0 | 5.5.0 |
| BouncyCastle | 1.81 | 1.85 |
| jmDNS | 3.6.2 | 3.6.3 |

### moonlight-common-c

Fork Artemis stojí na upstreamu z **15. 7. 2025** (pin `c9994368`). Upstream pin `874ac954` (18. 8. 2026). Chybí ~35 commitů, mimo jiné:

- rychlé FEC dekódování přes **nanors** (NEON/SIMD),
- **zpevnění RTSP** proti poškozeným hlavičkám a velkým odpovědím (bezpečnost),
- přepsané dávkování vstupů gamepadu, lepší zamykání, opravy úniků vláken,
- LTR ACK zprávy, ovladače s dvojitým touchpadem, typ ovladače Steam,
- `presentationTimeUs` (přesnější časování snímků),
- MbedTLS přes moderní PSA API.

Zkušební merge upstreamu do forku: **bez textových konfliktů**.

### Android část (55 commitů z Moonlightu)

- Zachytávání klávesnice v **Androidu 16.1** (`ddb674a9`). Artemis má jen starý Samsung `SemWindowManager` hack.
- Vypnutí producer throttlingu video surface, Android 16+ (`6d4c64a5`, nižší latence).
- Vypnuté úpravy H.264 constraint/level_idc na Oreo+ (`68adf9ec`).
- Opravy pádů a ANR v LED ovladačů (`abde6021`).
- **Oprava rumble** (`3c6a0d12`, #1454). Artemis stále používá zastaralé `VIBRATOR_SERVICE`.
- Xbox Series S/X init paket (`280454fd`, ID `0x0b05/0x0b12/0x0b13`). 8BitDo vendor už Artemis má.
- Nové keycody Android 15/16 (`8974dcda`), Shift u klávesy `+` (`b9c5eddd`), Meta Quest UI (`0711e236`).
- Sync SDL joystick kódu (`c2e224eb`), desugaring kvůli jmDNS (`4eb24a8d`).
- ~25 překladů z Weblate, chybí lotyština (`values-lv`) a tamilština (`values-ta`).

## Zkušební merge Moonlight → Artemis

Výsledek `git merge moonlight/master` (proveden a zrušen):

- Konflikty v **~15 souborech + 10 překladech**.
- Java: vždy **1 konfliktní blok** – `Game.java`, `ControllerHandler.java`, `KeyboardTranslator.java`, `XboxOneController.java`, `MediaCodecDecoderRenderer.java`, `VideoDecoderRenderer.java`, `MoonBridge.java`, `UiHelper.java`.
- Build: `app/build.gradle` (4 bloky), `build.gradle`, `gradle-wrapper.properties`, `AndroidManifest.xml`, `res/values/arrays.xml`, `res/xml/locales_config.xml`, submodul `moonlight-common-c`.

**Skrytá rizika, která git neukáže:**

1. **OpenSSL/opus**: Artemis má jinou strukturu (`jni/moonlight-core/libopus/` místo `opus/`, hlavičky OpenSSL 1.1). Po merge by vznikla směs → převzít celé `openssl/` a `opus/` z Moonlightu a upravit `Android.mk`. Ověřit, že nativní kód nepoužívá API odstraněné v OpenSSL 3+.
2. **AGP 9 / Gradle 9**: starší JitPack knihovny (MPAndroidChart, SearchPreference) mohou zlobit.
3. **targetSdk 36**: povinné edge-to-edge, predictive back, pravidla foreground služeb (týká se `KeyboardAccessibilityService`, overlayů a externího displeje).
4. **Logické konflikty** v `Game.java`/`ControllerHandler.java` (Artemis je tam hodně přepsaný): klávesnice Android 16.1 vs. meta-key hack, rumble vs. „vynutit vibrace telefonu“.
5. **Kompatibilita s Vibepollo**: server commands, clipboard a virtual display musí po merge `common-c` dál fungovat.

## Odhad náročnosti

| Část | Odhad |
|---|---|
| Merge common-c + toolchain + Moonlight, build a streamování funguje | pár večerů až ~1–2 týdny |
| Testování (ovladače, dekodéry, Android 16, Sunshine + Vibepollo) | průběžně, ~1 týden |
| Nový „shell“ GUI (Compose + Material 3, ovládání gamepadem) | ~4–8 týdnů po večerech |
| Kompletní přepis GUI vč. herního menu a editoru ovládání | měsíce |
