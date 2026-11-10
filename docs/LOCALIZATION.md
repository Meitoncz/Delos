# Localization

How Delos text is stored, written, translated and checked. Tracking issue: [#10](https://github.com/Meitoncz/Delos/issues/10).

## Languages

English is the base and the wording of the app itself. The inherited Moonlight/Artemis translations were deleted; every language is
rebuilt from Delos's own English.

| Tag (`locales_config.xml`) | Folder | Language | Address |
|---|---|---|---|
| `en` | `values` | English (base) | neutral |
| `cs` | `values-cs` | Czech | informal *ty* |
| `es` | `values-es` | Spanish | informal *tú* |
| `pt-BR` | `values-pt-rBR` | Portuguese (Brazil) | *você* |
| `fr` | `values-fr` | French | informal *tu* |
| `de` | `values-de` | German | informal *du* |
| `ja` | `values-ja` | Japanese | polite です/ます |
| `ko` | `values-ko` | Korean | polite 해요체 |
| `zh-CN` | `values-zh-rCN` | Simplified Chinese | neutral, no honorifics |
| `uk` | `values-uk` | Ukrainian | informal *ти* |

The list lives in three places that must agree: `res/xml/locales_config.xml`, `language_names` / `language_values` in
`res/values/arrays.xml` (the in-app Language setting), and `SUPPORTED_*` in `StringResourcesTest` (which fails when they differ or
when a `values-<lang>` folder exists for an unsupported language). Adding a language means editing all three.

## How English text lives in the code

1. **Every user-facing string is a resource** in `res/values/strings_<area>.xml`. No new literal goes into Kotlin or Java.
   `tools/l10n/find_literals.py` lists what is still hardcoded in the shell (appendix).
2. **Inside composition** use the Compose resource functions directly:
   `stringResource(R.string.profile_rename)`, `stringResource(R.string.profile_restored, name)`,
   `pluralStringResource(R.plurals.profile_signed_in, count, model, count)`.
3. **Outside composition** (toast, dialog and menu callbacks, `ShellState`/`ContextMenu`/`DialogSpec` builders, stores that
   produce messages) the text is resolved when the object is built, from a `Resources` the screen captured in composition:
   `val res = textResources()` (`shell/ui/Text.kt`, reads the configuration so the screen recomposes when the language changes)
   and then `res.getString(R.string.x, arg)` / `res.getQuantityString(R.plurals.y, n, n)`. Pass `res` down as the first parameter
   of the helper functions, as `ProfileScreen.kt` does. `ShellState`, `Toast`, `MenuItem` and `DialogSpec` keep holding plain
   `String`s: they are already localized when created. Do not store a `@StringRes` id in a model that outlives the language.
4. **Plain Kotlin classes with no screen** (data/ clients, stores) return a typed result (an enum, a sealed class, error codes)
   and the screen maps it to a string. Do not give them a `Context` just to format a message. A message that must stay English
   (a log line, `DiagLog`, a developer-only error) stays a literal and is excluded from the finder by being a log call, or with a
   trailing `// l10n-ignore`.
5. **Legacy Java UI** (stream activity, `GameMenu`, preferences XML) keeps using `R.string`; those 383 strings stay in
   `res/values/strings.xml`, which is the "legacy" area. When a legacy string moves to the shell it moves to its area file.
6. **Not text:** technical readouts (stream statistics, `%.1f ms`, resolutions like `1920 × 1080`, codec names, key names such as
   `Ctrl`) are formatted in code with `Locale.US` on purpose and are not translated; a surrounding sentence is.

### The Language setting and per-app locales

* `AndroidManifest.xml` declares `android:localeConfig="@xml/locales_config"`: on Android 13+ the system's per-app language
  picker lists exactly the ten languages.
* The in-app **Language** row (`list_languages`, values from `language_values`; `default` follows the system) is stored in
  preferences. The classic stream activity applies it in `UiHelper.setLocale` by overriding the configuration. **The Compose
  shell does not apply it yet** (`ShellActivity` is a `ComponentActivity`, and nothing calls `AppCompatDelegate` or
  `LocaleManager`).
* Target design (to implement before the translations are verified on a device): one source of truth, the Android per-app
  locale. Choosing a language calls `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))`
  (`default` passes an empty list). On Android 13+ this delegates to `LocaleManager` and recreates the activities; for API 29-32
  it needs the AppCompat `AppLocalesMetadataHolderService` (`autoStoreLocales`) and an `AppCompatActivity` or an
  `attachBaseContext` wrapper for `ShellActivity` and the stream activity. After that `UiHelper.setLocale` and the manual
  `Configuration` override can go, and the preference becomes a mirror of the system choice (so a change made in system settings
  shows in the row). Compose then follows automatically: `stringResource` and `textResources()` read the activity's resources.
* Fallback: an Android locale without a `values-<lang>` folder, or a key missing from one, shows the English text. This is
  intentional while languages are being filled in (lint `MissingTranslation` is off; `StringResourcesTest` enforces coverage
  rules on every folder that exists).

### Settings catalog and settings help

`SettingsCatalog` reads titles, summaries, entries and dialog messages from `res/xml/preferences.xml` and `arrays.xml`, i.e. from
the legacy English strings, through a configuration context forced to `Locale.ENGLISH`, and `SettingsHelp` /
`NativeSettingsHelp` / `StreamMenuHelp` hold English literals in Kotlin maps. To make them locale-aware:

* Drop the `Locale.ENGLISH` override in `SettingsCatalog` and read through `context.resources`; build the catalog with
  `remember(LocalConfiguration.current.locales)` so it is rebuilt when the language changes (it is cheap, no views are inflated).
  Titles and choices then come from the language folders for free, because the catalog already reads resource ids.
* Move the help texts to `res/values/strings_settings_help.xml`, key `help_<setting key>` for the description and
  `help_<setting key>_<value>` for the per-choice explanation (`help_list_fps_60` ...). `SettingsHelp.description(key)` looks the
  resource up with `resources.getIdentifier("help_$key", "string", packageName)` and falls back to the XML summary. Because
  lint cannot see `getIdentifier` uses, add `res/raw/keep.xml` with `tools:keep="@string/help_*"` so UnusedResources stays
  quiet. Dynamic help (`"Requests a ${value} stream."`) becomes a format string with a positional argument.
* `SettingsHelp.LINE_CHARS` / `MAX_LINES` and the tests that check help length describe the **English** text in the 432 px box.
  Translations are not held to them (see "Text fit"); the box scrolls.
* The legacy summaries that the catalog reads also appear in the classic UI; they are the same resources, so there is one text.

### Genre and theme names

The genre and theme names on the game card and in the Y filter are resources in `res/values/strings_tags.xml`
(`tags_genre_*`, `tags_theme_*`), looked up by IGDB's fixed ids, not by text: `genreName(res, genre)` / `themeName(res, theme)` in
`shell/ui/GenreIcons.kt` return the translated name at draw time (`textResources()` in composition) and IGDB's English `name`
for an id that has no string yet. Genre and theme ids overlap numerically, so the two lookups are separate. The ranking, the
filters and the Steam mapping (`shell/ui/SteamTags.kt`, `GameTags.kt`) keep the ids and the English names as **data**; the Steam
tag names are matched against what the store returns (always English) and are never shown or translated, which is why those
table lines carry `// l10n-ignore`. A new genre or theme needs its id in `IGDB_*_NAMES`, `genreNameRes`/`themeNameRes`, the icon
functions and a string; `TagNamesTest` fails when they disagree or the English text differs from IGDB's name.

## File layout

One file per area, so that parallel agents and translators touch different files and merge conflicts stay rare. The same file
names are used in every language folder (`values-de/strings_home.xml` translates `values/strings_home.xml`); the test only
needs the keys, but identical names keep diffs reviewable.

| File | Area |
|---|---|
| `strings.xml` | legacy Java UI and preferences (existing, shrinking) |
| `strings_common.xml` | words shared by several areas: OK, Cancel, Save, Undo, Back, On, Off, units |
| `strings_home.xml` | start screen, side menu, dashboard, first run (boot, welcome) |
| `strings_library.xml` | game list, game detail, tags, sorting |
| `strings_stream_menu.xml` | in-stream menu, its help, loader, stream result and statistics labels |
| `strings_devices.xml` | PCs, add/remove/wake, device setup, status |
| `strings_pairing.xml` | pairing dialogs and PIN flow |
| `strings_profile.xml` | profiles, avatars, profile dialogs (**reference**) |
| `strings_settings.xml` | settings tabs, rows, native settings screens, game settings |
| `strings_settings_help.xml` | settings help texts (`help_*`) |
| `strings_filters.xml` | artwork and library filters |
| `strings_metadata.xml` | game metadata, tags, genres, sources |
| `strings_tags.xml` | genre and theme names of the game card and filter (`tags_*`) |
| `strings_artwork.xml` | artwork picker and progress |
| `strings_toasts.xml` | toasts that belong to no single screen |
| `strings_dialogs.xml` | generic dialogs, security prompts |
| `strings_debug.xml` | debug info screen (user-visible) |
| `strings_keyboard.xml` | on-screen keyboard labels and its settings |
| `strings_diagnostics.xml` | log export, crash report offer |
| `strings_link.xml` | link test and guidance |

Add an area file (and a row here) rather than overloading an unrelated one.

## Keys

* `snake_case`, English, prefixed with the area / feature: `profile_`, `settings_`, `help_`, `home_`, `library_`, `stream_menu_`,
  `devices_`, `pairing_`, `filters_`, `metadata_`, `artwork_`, `toast_`, `dialog_`, `debug_`, `keyboard_`, `diag_`, `link_`,
  `common_`.
* Then the screen or feature, then the role: `_title`, `_note` (explanatory line), `_action` (button), `_hint`, `_label`,
  `_error`, `_toast` when the role is not obvious: `profile_rename_title`, `profile_name_note`, `profile_name_required`.
* Keys name the **place**, not the English text, so rewording the English does not rename the key. Never reuse a key for a
  different meaning; never build a key by concatenation in code except the documented `help_*` lookup.
* Keys are code only. They are never shown, never stored, never sent over the network. If a stored or protocol value looks like a
  label (a setting value, a stream kind), the value stays an identifier and the label is looked up from it.
* Words that look identical in English but differ elsewhere (`Open` a menu vs `Open` a file, `Set`) get separate keys.
* Put a `<!-- … -->` comment above any string whose meaning or arguments are not obvious (translators and the review pass read it).

## Writing a string

* **Placeholders are positional**: `%1$s`, `%2$d`, even for one argument is fine, mandatory for two or more (the test rejects
  non-positional multi-argument strings). A translation may reorder them but must contain the same placeholders. Write a literal
  percent as `%%`. Never use `%n`.
* **No concatenation of translatable fragments.** Word order, case and agreement differ per language. Not
  `"Signed in on " + model + ...`, not a `getString(a) + " " + getString(b)`. One full sentence per resource with arguments.
  Prefixes like `"Error: "` + message are also fragments; use `%1$s`. A fragment that is truly generic (a list separator, a
  middle dot) is a resource of its own.
* **Plurals use `<plurals>`**, never `if (n == 1) "profile" else "profiles"`. English defines `one` and `other`. **Every
  quantity item contains the number placeholder** (`%d profile`, not `A profile`), so that the quantities of other languages
  (below) can all use the same arguments. Pass the number twice when the sentence has another argument first
  (`getQuantityString(id, n, model, n)`), as in `profile_signed_in`.
* **Arguments** are values, not sentences: names, numbers, device models. A localized word is never passed as an argument to be
  inserted into another sentence (`"No %1$s match"` with `tab.noun`): make a string per case, or an `<string-array>` selected by
  index, so each language can inflect.
* **Numbers and dates in text** go through the format arguments (`%d`, `%1$.1f`), which use the app locale (decimal comma in
  Czech). Dates and times use `java.text.DateFormat` / `DateTimeFormatter.ofLocalizedDate`, not an English pattern or an
  `Locale.ENGLISH` formatter (`ArtworkFilters`, `DeviceStatus` still do; stage 1 fixes them).
* **Typography**: use the real characters `…`, `·`, `×`, `–`, `’` in the English source; escape an apostrophe as `\'` or write
  `’`; `\n` only for a deliberate line break. No HTML, no markup in strings. No trailing space, no leading space (a separator
  belongs in the format).
* **No text in images or drawable names**, no ids in labels, no keyboard-shortcut letters embedded in the sentence ("Press Y")
  without a way for the translator to see them: `Press %1$s to undo` with the button name as argument.
* **Capitalization**: English UI uses sentence case. Translators follow the convention of their language (German nouns,
  no title case in French/Spanish/Czech).
* Strings that are not text for the user (log lines, identifiers, URLs, brand and codec names alone) are not resources; a string
  that must not be translated but must be a resource gets `translatable="false"`.

## "Delos" and other brand names

* The app name **Delos** is never translated, transliterated or inflected, in any language. The token appears exactly as `Delos`.
  Sentences are rephrased instead of declining it: Czech *„aplikace Delos“*, not *„Delosu“*; German *„Delos-Server“* (hyphen),
  *„die Delos-App“*; French *„l’app Delos“*; Japanese *„Delosの“* (a particle is fine); Ukrainian *„застосунок Delos“*,
  not *„Делос“*. No possessive `Delos's` in English either (*„the Delos driver“*, *„Delos starts with …“*).
* `StringResourcesTest` enforces it on every translation file: a translation whose English contains `Delos` must contain `Delos`
  unchanged; no translated string may contain `Delos` directly followed by a Latin letter (a case ending: *Delosu, Delosem, Delosa*)
  or `'s`, or a transliteration (*Делос, デロス, 델로스, 德洛斯*). New patterns go into `DECLINED_DELOS` /
  `TRANSLITERATIONS` in the test.
* Also untranslated: **Vibepollo**, **Apollo**, **Sunshine**, **Moonlight**, **Steam**, **SteamGridDB**, **IGDB**,
  **HowLongToBeat**, **PCGamingWiki**, **PyroWave**, **Android**, **Wi-Fi**, **HDR**, **HEVC**, **H.264**, **AV1**, **V-Sync**, **USB**, **PC**,
  **LFR**, key names (**Ctrl**, **Alt**, **Win**, **Esc**, **F11**), game and PC names, profile names.

## Tone and style per language

Short, direct, friendly and calm; the English is already this. Translate meaning, not words; keep the length close. Same
formality for the whole app. UI labels are nouns or imperatives, no full stops on single labels and buttons; sentences in notes and
toasts have them (as the English does).

| Language | Address and register | Notes |
|---|---|---|
| Czech | informal *ty* (imperative: „Vyber“, „Zkus“) | no vykání; gender-neutral phrasing where possible; Czech plurals one/few/many/other |
| Spanish | informal *tú* | neutral Latin-American-friendly vocabulary („ordenador“ vs „computadora“: use **PC**); infinitive for buttons |
| Portuguese (BR) | *você* | Brazilian spelling and vocabulary („tela“, „arquivo“, „controle“); infinitive or imperative for buttons, consistently |
| French | informal *tu* | space before `: ; ! ?` is a non-breaking space (U+00202F or U+00A0); „Annuler“, „Enregistrer“; infinitive for buttons |
| German | informal *du* | compounds can be very long: hyphenate with „-“ (*Delos-Server*), prefer short words; capitalize nouns |
| Japanese | polite です/ます; buttons as plain noun/verb („保存“, „開く“) | no spaces between words, no `Delos` declension (use の, を, が), full-width punctuation 、。; katakana for loanwords per convention („コントローラー“) |
| Korean | polite 해요체 („저장해요“ in sentences); buttons as noun/verb stems („저장“) | spaces between words; particles attach to `Delos` after a space or directly („Delos를“): no change to the token |
| Simplified Chinese | neutral, no honorifics, no 您 | full-width punctuation `，。：`; no spaces between CJK and Latin is acceptable |
| Ukrainian | informal *ти* | Ukrainian, never Russian vocabulary; plurals one/few/many/other; „застосунок“ for app |

Plural categories required by the test and by Android lint (`MissingQuantity`): en/de `one, other`; es/fr/pt `one, many, other`;
cs/uk `one, few, many, other`; ja/ko/zh `other` only.

## Glossary seed

Technical terms and brands that gamers know stay English in every language unless noted. This is a seed: stage 2 translators
extend it, record decisions here and keep it consistent across all files.

| English | Rule | cs | es | pt-BR | fr | de | ja | ko | zh-CN | uk |
|---|---|---|---|---|---|---|---|---|---|---|
| stream (noun/verb) | keep English where natural, else translate | stream | stream / transmisión | stream / transmissão | stream / diffusion | Stream | ストリーム | 스트림 | 串流 | стрім |
| host | the PC being streamed from; prefer "PC" | PC | PC | PC | PC | PC | PC | PC | 电脑 | ПК |
| PC | keep | PC | PC | PC | PC | PC | PC | PC | PC | ПК |
| pair / pairing | translate | spárovat | emparejar | parear | associer | koppeln | ペアリング | 페어링 | 配对 | з’єднати |
| bitrate | keep | bitrate | bitrate | bitrate | débit | Bitrate | ビットレート | 비트레이트 | 码率 | бітрейт |
| codec | keep | kodek | códec | codec | codec | Codec | コーデック | 코덱 | 编解码器 | кодек |
| HDR | keep | HDR | HDR | HDR | HDR | HDR | HDR | HDR | HDR | HDR |
| upscaler | translate descriptively | upscaler | escalador | upscaler | upscaler | Upscaler | アップスケーラー | 업스케일러 | 超分 | апскейлер |
| supersampling | keep / transliterate | supersampling | supermuestreo | supersampling | supersampling | Supersampling | スーパーサンプリング | 슈퍼샘플링 | 超采样 | суперсемплінг |
| sharpness | translate | ostrost | nitidez | nitidez | netteté | Schärfe | シャープネス | 선명도 | 锐度 | різкість |
| frame pacing | keep English term, explain in help | frame pacing | frame pacing | frame pacing | frame pacing | Frame-Pacing | フレームペーシング | 프레임 페이싱 | 帧节奏 | frame pacing |
| controller | gamepad | ovladač | mando | controle | manette | Controller | コントローラー | 컨트롤러 | 手柄 | контролер |
| gyro | keep | gyro | giroscopio | giroscópio | gyroscope | Gyro | ジャイロ | 자이로 | 陀螺仪 | гіроскоп |
| rumble | vibration of a controller | vibrace | vibración | vibração | vibration | Vibration | 振動 | 진동 | 震动 | вібрація |
| library | the game list | knihovna | biblioteca | biblioteca | bibliothèque | Bibliothek | ライブラリ | 라이브러리 | 游戏库 | бібліотека |
| cover | game cover art | obal | portada | capa | jaquette | Cover | カバー | 커버 | 封面 | обкладинка |
| hero | the wide banner artwork | hero (banner) | hero (banner) | hero (banner) | hero (bannière) | Hero (Banner) | ヒーロー画像 | 히어로 이미지 | 主视觉图 | hero (банер) |
| profile | translate | profil | perfil | perfil | profil | Profil | プロフィール | 프로필 | 个人资料 | профіль |
| toast | never shown to users | – | – | – | – | – | – | – | – | – |

The per-language cells are proposals for the reviewer, not final. "Keep" rows must stay identical in every file.

## Text fit

* German, French, Spanish, Portuguese and Ukrainian run 20-40 % longer than English; Czech about 20 %; very short labels
  (under 12 characters, such as buttons and circle items) can double. Japanese, Korean and Chinese are shorter in width but their
  glyphs are taller: check line height and the 3-line limits.
* The layout must cope with longer text: do not rely on an exact character budget. Prefer wrapping and the scrolling help
  box over truncation; the stage's marquee (`MarqueeText`) handles one-line labels that do not fit. Shorten the **translation**
  before shrinking the font. Never abbreviate with a trailing dot to save space without the reviewer's approval.
* CJK text has no spaces: line breaking happens between any two characters, so never insert manual breaks or rely on word
  count; do not add spaces around `Delos` in ja/zh unless the language needs them for readability.
* The English budgets in code (`SettingsHelp.LINE_CHARS`, `MAX_LINES`, toast width tests) describe English only.
* Fonts: Figtree and Unbounded are Latin(-extended) display fonts; check Cyrillic (uk) coverage and that Japanese, Korean and
  Chinese fall back to a system CJK font without changing line heights. This is part of stage 3 (per-language device check).

## Workflow: adding or changing a string

1. Pick the area file from the table (create it if none fits) and add the English string with a key by the scheme above, with a
   comment for arguments and context. Use `<plurals>` for any count.
2. Use it: `stringResource`/`pluralStringResource` in composition; `res.getString(...)` from a `textResources()` captured in
   composition otherwise. Delete the literal.
3. Check: `python3 tools/l10n/find_literals.py --list <File.kt>` shows no remaining hardcoded text in the file you touched.
4. Run `./gradlew :app:testDebugUnitTest :app:lintDebug`. `StringResourcesTest` covers the base and every language folder
   that exists; lint covers formatting, quantities and unused resources.
5. **Changing an existing English string's meaning**: rename the key (so stale translations disappear, as the test then flags them
   as missing/unknown) or update every language file in the same change. Fixing a typo keeps the key.
6. **Removing a string**: delete the key from the base and from every language file in the same change (the test reports
   unknown keys; lint reports unused resources).
7. **Adding a language**: create `values-<lang>/strings*.xml` for all areas, add the language to the three lists, run the tests.
   While some keys are missing, the English fallback is shown.
8. Translators: translate `res/values/strings_*.xml` into `res/values-<lang>/strings_*.xml` (same file and key names), keep the
   placeholders, follow this guide and the glossary.

## Tooling

* `tools/l10n/find_literals.py`: hardcoded user-facing English literals in `app/src/main/java/com/limelight/shell/**/*.kt`.
  `--list [files…]` prints every literal with line numbers; `--markdown` prints the table below. It is a heuristic (uppercase start,
  a space or more than 3 characters; logs, keys, identifiers, regexes and format keys skipped); a line with a trailing
  `// l10n-ignore` is skipped. Python 3.9, standard library only.
* `app/src/test/java/com/limelight/l10n/StringResourcesTest.kt`: for every `values-<lang>/strings*.xml`: same keys as the English
  base (no missing unless the language is listed in `PARTIAL_LANGUAGES` in the test, which is empty and only for a language that is
  being filled in; no extra, nothing for a `translatable="false"` key), same kind (string / plurals / array), equal placeholder
  multisets (positional index and conversion, order-independent; two or more arguments must be positional), the plural
  quantities of the language, no empty values, and the Delos rules. It also checks the English base itself and that the language
  lists agree.
* Lint: `MissingTranslation` is disabled while English is the fallback; `ExtraTranslation`, `MissingQuantity`, `StringFormat*`
  and `UnusedResources` are active.

## Appendix: hardcoded literals still in the shell

Output of `python3 tools/l10n/find_literals.py --markdown` (a heuristic; it over-counts data tables such as the Steam tag names
in `ui/SteamTags.kt`, which stage 1 decides on separately, and some developer messages, and under-counts text assembled from parts).

| File (under `shell/`) | Literals |
|---|---:|
| `ui/SteamTags.kt` | 275 |
| `settings/SettingsHelp.kt` | 167 |
| `screens/DebugInfoScreen.kt` | 142 |
| `screens/NativeSettings.kt` | 107 |
| `Shell.kt` | 97 |
| `screens/ArtworkScreen.kt` | 68 |
| `settings/StreamMenuHelp.kt` | 65 |
| `StreamMenuRoot.kt` | 51 |
| `StreamStats.kt` | 49 |
| `StreamMenuLayout.kt` | 42 |
| `screens/NativeSettingsHelp.kt` | 37 |
| `screens/HomeScreen.kt` | 32 |
| `DeviceFlows.kt` | 30 |
| `StreamResult.kt` | 29 |
| `screens/DeviceScreens.kt` | 28 |
| `StreamLoader.kt` | 25 |
| `screens/MetadataUi.kt` | 24 |
| `screens/PairScreens.kt` | 23 |
| `settings/AppearanceStore.kt` | 23 |
| `data/PcSetup.kt` | 20 |
| `screens/GameSettingsScreen.kt` | 19 |
| `settings/SettingsCatalog.kt` | 17 |
| `screens/SettingsScreen.kt` | 16 |
| `link/LinkProbeClient.kt` | 15 |
| `screens/FirstRunScreens.kt` | 14 |
| `link/LinkVerdictText.kt` | 13 |
| `screens/FilterModal.kt` | 13 |
| `data/Profiles.kt` | 11 |
| `keyboard/FullKeyboard.kt` | 10 |
| `screens/SettingsActions.kt` | 10 |
| `settings/SettingsCapabilities.kt` | 10 |
| `data/ArtworkFilters.kt` | 8 |
| `link/LinkGuidance.kt` | 8 |
| `keyboard/FullKeyboardKeys.kt` | 7 |
| `link/LinkResult.kt` | 7 |
| `screens/LinkGuidanceRows.kt` | 7 |
| `data/GameLauncher.kt` | 6 |
| `data/SteamGridDb.kt` | 6 |
| `data/HowLongToBeatClient.kt` | 5 |
| `link/LinkMonitor.kt` | 5 |
| `screens/GameSettingsLogic.kt` | 4 |
| `settings/StreamProfileStore.kt` | 4 |
| `ui/HeroLensShader.kt` | 4 |
| `ui/ProblemOfferCard.kt` | 4 |
| `data/GameMetadataStore.kt` | 3 |
| `data/PcGamingWikiClient.kt` | 3 |
| `settings/StreamMenuSettings.kt` | 3 |
| `settings/StreamSettingsStore.kt` | 3 |
| `ui/ImpactChips.kt` | 3 |
| `ui/Rows.kt` | 3 |
| `ui/StreamResultLayer.kt` | 3 |
| `StreamExit.kt` | 2 |
| `StreamMenuBar.kt` | 2 |
| `data/AvatarImages.kt` | 2 |
| `link/LinkTester.kt` | 2 |
| `ui/ArtworkProgressBar.kt` | 2 |
| `ui/BackgroundShaders.kt` | 2 |
| `ui/ButtonCapture.kt` | 2 |
| `ui/HeroBitmap.kt` | 2 |
| `ui/LaunchStageQueue.kt` | 2 |
| `StreamMenuPopover.kt` | 1 |
| `data/BoxArt.kt` | 1 |
| `data/IgdbMetadataClient.kt` | 1 |
| `data/PcRepository.kt` | 1 |
| `data/SteamTagsClient.kt` | 1 |
| `link/LinkFormats.kt` | 1 |
| `link/LinkRecommendation.kt` | 1 |
| `screens/OscPreviewScreen.kt` | 1 |
| `ui/DialogLayer.kt` | 1 |
| **Total** | **1605** |
