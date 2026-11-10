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

One source of truth: the **Android per-app locale**, wrapped by `shell/settings/AppLanguage.kt`. The in-app **Language** row
(`list_languages`; `default` follows the system) and the system's per-app language picker (Android 13+, which lists exactly
`locales_config.xml`) change the same value, so they always agree. `UiHelper.setLocale` and the manual `Configuration` override are gone.

* **Android 13+**: `AppLanguage.choose` calls `LocaleManager.setApplicationLocales`. The system updates the process and every
  activity; `ShellActivity` and `DelosStreamActivity` declare `locale` in `configChanges`, so they change in place, with no restart:
  Compose recomposes because `LocalConfiguration` is replaced, and `stringResource` / `textResources()` read the activity's own
  `Resources`, which the system updated. `Locale.getDefault()` (number and date formatting, Han glyph forms) follows too.
* **Android 10-12** (minSdk 29): there is no platform per-app locale. The tag is kept in the `app_language` preference file and applied
  by `AppLanguage.wrap` in `attachBaseContext` of the shell and stream activities and by `AppLanguage.initApplication` for the
  application context; choosing a language recreates the foreground activity (the shell returns to its start screen, because its
  navigation state is not restored). `AppCompatDelegate.setApplicationLocales` and its `AppLocalesMetadataHolderService` were
  considered and not used: they need an `AppCompatActivity` with an AppCompat theme, and the shell is a `ComponentActivity`.
* **Default**: no app locale set = follow the system language; the resource system then picks the app's translation of the first
  system language that has one, else English (`AppLanguage.effectiveTag` models this for the UI). `pt` resolves to `pt-BR` and `zh`
  / `zh-Hans` to `zh-CN`; `pt-PT` and `zh-TW` have no translation and show English.
* **The row is a mirror.** `ShellActivity` keeps the current profile's `list_languages` equal to the app locale
  (`AppLanguage.mirror` when it binds and on resume, so a change made in system settings shows in the row) and turns a change of that
  preference into `AppLanguage.choose` (`onPreferenceChanged`). The language is therefore app-wide, never per profile. A language
  stored in the old, stream-only preference is adopted once at first start (`initApplication`) if no app locale is set.
* **Callers**: nothing else needs to apply the language. New activities call `AppLanguage.wrap` in `attachBaseContext` (needed on
  Android 10-12 only) and declare `locale` in `configChanges` if they should not restart. Code that must re-read the language
  reads `LocalConfiguration.current` (that is what `textResources()` does).
* Fallback: an Android locale without a `values-<lang>` folder, or a key missing from one, shows the English text. This is
  intentional while languages are being filled in (lint `MissingTranslation` is off; `StringResourcesTest` enforces coverage
  rules on every folder that exists).

### Settings catalog and settings help

`SettingsCatalog` reads titles, summaries, entries and dialog messages from `res/xml/preferences.xml` and `arrays.xml` through the
resources it was given, so they come from the legacy strings of the current language (there is no `Locale.ENGLISH` override any
more). The help texts are string resources too:

* The catalog rebuilds itself when the language changes: a screen that shows labels calls
  `profiles.catalog.refresh(textResources())` before it reads `all` / `visible` (the screen already recomposes on a language change
  because `textResources()` reads the configuration). Keys, kinds, defaults and ranges do not depend on the language, so stores
  (`StreamSettingsStore`, `StreamProfileStore`) keep using the shared catalog without refreshing it.
* Help texts live in `res/values/strings_settings_help.xml`: `help_<setting key>` is the description and
  `help_<setting key>_<value>` the per-choice explanation (`help_list_fps_60`; the value is lower-cased and every character but
  letters and digits becomes `_`, so `fast-vsync` is `help_frame_pacing_fast_vsync`). `SettingsHelp.description(res, key, fallback)`
  and `SettingsHelp.choice(res, key, value)` find them with `getIdentifier` and fall back to the XML summary or to the dynamic
  texts (`help_dynamic_*`, formats with a positional argument). The stream menu help is `stream_help_<slug of the item key>`
  (`StreamMenuHelp.description(res, key)`; the keys stay the English item names, which are identifiers). Because lint cannot see
  `getIdentifier` uses, `res/raw/keep.xml` keeps `help_*` and `stream_help_*`. These strings are always formatted, so a literal
  percent sign is written `%%` there.
* Settings tabs (`SettingsTab.label(res)` / `description(res)`), appearance choices (`Palette`, `BackgroundKind`, `HomeLayout`,
  `ClockFormat`: `label(res)`), the labels `SettingsCapabilities.choices(res, spec, values)` adds (native resolutions, custom
  values, layouts) and the typed-text errors of `StreamSettingsStore.writeText(res, …)` are in `res/values/strings_settings.xml`.
* `SettingsHelp.LINE_CHARS` / `MAX_LINES` and the tests that check help length describe the **English** text in the 432 px box
  and run against the base `values/` resources (`qualifiers = "en"`). Translations are not held to them (see "Text fit"); the box
  scrolls.
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
  `Locale.ENGLISH` formatter (`ArtworkFilters`, `DeviceStatus`, `LinkResult` and `DiagnosticReport` still do or say English; stage 1 fixes them).
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
| French | informal *tu* | space before `: ; ! ?` is a **no-break space U+00A0** (not U+202F: the bundled fonts lack it, see "Fonts and scripts"); „Annuler“, „Enregistrer“; infinitive for buttons |
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
* Fonts: see "Fonts and scripts" below (Cyrillic and CJK are drawn by the system fallback fonts; line heights are fixed only where
  the style sets `lineHeight`).

## Fonts and scripts

Findings from the shipped files (`res/font`, read with fontTools; `FontCoverageTest` re-reads their `cmap` and fails if this
table stops being true):

| Script | Figtree (body, variable 400-800) | Unbounded (titles, variable 400-700) | Drawn by |
|---|---|---|---|
| Basic Latin, Latin-1 | yes (no soft hyphen) | yes (no µ) | bundled |
| Latin Extended-A (cs, es, pt, fr, de letters) | yes (19 of the 128 are missing, letters no language here uses: Ĕ Ĩ Ĭ Ŏ Ţ Ŧ Ũ ĸ Ŀ ŉ ſ …) | all 128 | bundled |
| Typography `… · × – — ’ ‘ “ ” „ « » ° U+00A0` | yes | yes | bundled |
| `≈ ≤ ≥ ✓ ✕ ● ★`, U+202F, U+2009, U+200B | no | no | system fallback (avoid in text) |
| Ukrainian Cyrillic (А-Я, ґ є і ї, `ʼ`) | **no** (U+2019 `’` yes, U+02BC no) | **yes** (68 of 68 letters) | titles: Unbounded; body: system sans (Roboto) |
| Japanese kana and kanji, CJK punctuation | no | no | system CJK font |
| Korean Hangul | no | no | system CJK font |
| Simplified Chinese hanzi | no | no | system CJK font |
| Phosphor icons (`phosphor_regular`, `phosphor_fill`) | private-use code points U+E000-U+EE74, nothing else | | bundled |

**Decision: no per-language `FontFamily`, no extra font files.** `Figtree` and `Unbounded` (`shell/ui/Type.kt`) are built from
`Font(R.font…)`; Compose turns these into platform typefaces (`Typeface.Builder` / `createFromResources`), and a typeface built from
a font file ends in the system default fallback chain (checked in the Android 13 source: `Typeface.Builder.build` goes through
`CustomFallbackBuilder`, whose `build()` appends `getSystemDefaultTypeface`; API 29 and up). So a character the bundled font lacks is
drawn, glyph by glyph, by the system sans (Roboto or the OEM font, which has Cyrillic) or the system CJK font; Latin text is
untouched: same fonts, same metrics, which keeps English and the Latin translations pixel-identical. Han characters take their
Japanese / Korean / Simplified Chinese forms from the app locale (the process default locale: the platform sets it from the app locale on
Android 13+, `AppLanguage` on 10-12), so a Japanese app on an English phone still draws Japanese kanji. A per-language family was rejected: Compose cannot
fall back per glyph between two of its own families, so it would replace Figtree for the whole run and make Latin brand names and numbers in
a Ukrainian or Japanese sentence a different font from the rest of the app.

What changes for non-Latin text, and what to look at on a device (not verified here: no device was used):

* A line that contains fallback glyphs is as tall as the taller font (Noto Sans CJK is about 1.45 em tall, Figtree 1.2 em; the
  system sans is 1.17 em). Every style with an explicit `lineHeight` (most multi-line text, the stream menu, toasts, the icon style
  in `Type.kt`) keeps its height exactly; single-line labels without one grow slightly in ja/ko/zh and stay centered in their boxes.
* The system fonts have their own weights: Medium, SemiBold and Bold map to the nearest weight the fallback font offers.
* Tracking: styles with positive `letterSpacing` (the small caption labels, .14 em) also space out CJK glyphs; they should not be
  uppercased or tracked in translations that have no case. A visible gap is a bug to fix in that style, not by changing the font.
* Mixed Ukrainian text shows Cyrillic in the system sans and Latin brand names, digits and units in Figtree; titles in Unbounded are
  one font. Latin-only ASCII and punctuation (`…`, `·`, `×`, `’`) is always Figtree.
* French uses U+00A0 before `: ; ! ?` and inside `« »`; U+202F would be drawn by a fallback font with a different width.
* `FontCoverageTest` also scans every `strings*.xml` of the base and of each language folder: Latin languages must stay inside
  Figtree's characters, Ukrainian Cyrillic inside Unbounded's, Japanese / Korean / Chinese may add CJK blocks. A translation that uses
  anything else (a stray `≈`, U+202F, a zero-width space) fails the test and should be rewritten or discussed.

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

## Terminology: Spanish

Neutral Spanish (Spain and Latin America), informal *tú*, infinitive for buttons, "vídeo" with accent as in the RAE.

| English | Spanish |
|---|---|
| PC / host | PC (host only in technical error texts) |
| stream (noun/verb) | stream / transmitir |
| pair / unpair / pairing | emparejar / desemparejar / emparejamiento |
| settings | ajustes |
| library | biblioteca |
| controller / gamepad | mando |
| rumble / haptics | vibración / vibración háptica |
| artwork / cover / wide cover / background | ilustraciones / portada / portada panorámica / fondo |
| profile | perfil |
| game quit / resume | cerrar juego / reanudar |
| wake (a PC) | reactivar |
| offline / online | sin conexión / en línea |
| upscaler / supersampling / sharpness | escalador / supermuestreo / nitidez |
| codec / bitrate / frame pacing | códec / bitrate / frame pacing |
| Reset / Cancel / Done / Back | Restablecer / Cancelar / Hecho / Atrás |
| Link test | Probar enlace / prueba de enlace |
| Custom / Default | Personalizado / Predeterminado |
| Debug info | Información de depuración |
## Terminology: Portuguese (Brazil)

Register: *você*, Brazilian vocabulary; buttons and labels in the infinitive (Salvar, Cancelar), notes in plain *você* phrasing.
Delos is always written as „o app Delos“ / „do app Delos“.

| English | pt-BR |
|---|---|
| stream (noun) / to stream | stream / transmitir, transmissão |
| gamepad, controller | controle |
| host (PC), server | PC, servidor |
| pair / pairing | parear / pareamento |
| library, profile | biblioteca, perfil |
| settings | configurações |
| Cancel, Save, Back, Delete, Remove, Add | Cancelar, Salvar, Voltar, Excluir, Remover, Adicionar |
| Quit, Resume, Wake | Sair, Retomar, Ativar (Wake-on-LAN kept) |
| On / Off, Enable / Disable | Ativado / Desativado, Ativar / Desativar |
| on-screen controls, touchscreen | controles na tela, tela touch |
| frame rate, refresh rate, latency | taxa de quadros, taxa de atualização, latência |
| bandwidth, packet loss | largura de banda, perda de pacotes |
| rumble, gyro | vibração, giroscópio |
| sharpness, upscaler, supersampling, frame pacing, bitrate, codec | nitidez, upscaler, supersampling, frame pacing, bitrate, codec |
| cover, hero, artwork | capa, hero (banner), arte |
| clipboard, stylus, deadzone, stick | área de transferência, caneta, zona morta, analógico |
| toast | aviso |
## Terminology: French

Register: informal *tu*; buttons and menu items in the infinitive; U+00A0 before `: ; ! ? %` and inside « ».

| English | French |
|---|---|
| stream (noun/verb) | stream / streamer |
| host / PC | PC (technical error texts: hôte) |
| pair / unpair | associer / dissocier |
| bitrate | débit |
| frame rate / refresh rate | fréquence d’images / fréquence d’écran |
| gamepad, controller | manette |
| rumble | vibration |
| cover / wide cover / hero (background) | jaquette / jaquette large / arrière-plan |
| artwork | visuels |
| library | bibliothèque |
| profile | profil |
| settings | réglages |
| sharpness / Sharpen only | netteté / Netteté seule |
| upscaler, supersampling, frame pacing, overlay | kept in English |
| on-screen controls | commandes à l’écran |
| clipboard | presse-papiers |
| link (network path) / Test link | liaison / Tester la liaison |
| Back (button) | Retour |
## Terminology: German

Informal *du*; nouns capitalized; hyphenated compounds with brands (*Delos-App*, *Vibepollo-Weboberfläche*). English gamer terms stay where the glossary says so.

| English | German |
|---|---|
| pair / unpair | koppeln / Kopplung aufheben |
| stream (noun) | Stream |
| library | Bibliothek |
| profile | Profil |
| gamepad / controller | Gamepad (legacy settings) / Controller |
| rumble | Rumble (technical settings), Vibration (UI) |
| cover / wide cover / background | Cover / breites Cover / Hintergrund |
| artwork, metadata | Artwork, Metadaten |
| settings, device(s) | Einstellungen, Gerät(e) |
| appearance (tab) | Design |
| about (tab) | Über |
| on-screen controls | Bildschirmsteuerung |
| metered network | getaktetes Netzwerk |
| link (network) / link test | Verbindung / Verbindungstest |
| sharpness / sharpen only | Schärfe / Nur schärfen |
| Apply / Resume / Disconnect | Anwenden / Fortsetzen / Trennen |
| custom | Eigene(r/s) |
| web UI | Weboberfläche |
## Terminology: Japanese

Polite です/ます in sentences, plain noun/verb phrases on labels and buttons, full-width punctuation. `Delos` is preceded by a half-width space after a Japanese character (the test requires a non-letter before the token) and followed by a particle (`Delos を`-style spacing is not needed).

| English | 日本語 |
|---|---|
| stream / streaming | ストリーム / ストリーミング |
| host, PC | PC (ホスト where protocol-level) |
| gamepad, controller | コントローラー |
| pair / unpair | ペアリング / ペアリング解除 |
| bitrate, frame rate, resolution | ビットレート, フレームレート, 解像度 |
| latency, bandwidth, packet loss | 遅延, 帯域, パケットロス |
| upscaler, supersampling, sharpness | アップスケーラー, スーパーサンプリング, シャープネス |
| frame pacing | フレームペーシング |
| rumble / vibration | 振動 |
| on-screen controls (OSC) | 画面上のコントロール (OSC kept where English abbreviates) |
| library, profile | ライブラリ, プロフィール |
| cover / wide cover / background / logo | カバー / ワイドカバー / 背景 / ロゴ |
| settings / About / Debug info | 設定 / バージョン情報 / デバッグ情報 |
| stream menu | ストリームメニュー |
| Apply / Resume / Quit game | 適用 / 再開 / ゲームを終了 |
| keep diagnostic logs | 診断ログを保存 |
## Terminology: Korean

Polite 해요체 in sentences; buttons, tabs and labels as noun or verb stem. Particles on variable names use the 은(는) / 이(가) / 을(를) / 과(와) / (으)로 forms; `Delos` takes particles directly.

| English | Korean |
|---|---|
| stream | 스트림 / 스트리밍 |
| pair / unpair | 페어링 / 페어링 해제 |
| library | 라이브러리 |
| profile | 프로필 |
| settings (tabs: Stream, Display, Audio, Controls, Appearance, Covers, Devices, About) | 설정 (스트림, 디스플레이, 오디오, 컨트롤, 외관, 커버, 기기, 정보) |
| cover / wide cover / background / logo | 커버 / 와이드 커버 / 배경 / 로고 |
| artwork / metadata | 아트워크 / 메타데이터 |
| gamepad / controller | 게임패드 / 컨트롤러 |
| rumble / vibration / haptics | 럼블 / 진동 / 햅틱 |
| gyroscope | 자이로스코프 (자이로) |
| bitrate / codec / frame rate | 비트레이트 / 코덱 / 프레임 레이트 |
| upscaler / supersampling / sharpness | 업스케일러 / 슈퍼샘플링 / 선명도 |
| frame pacing | 프레임 페이싱 |
| on-screen controls | 화면 컨트롤 |
| stream menu | 스트림 메뉴 |
| Cancel / Save / Done / Reset / Undo | 취소 / 저장 / 완료 / 초기화 / 실행 취소 |
| On / Off | 켜짐 / 꺼짐 |
| offline / online | 오프라인 / 온라인 |
| Debug info | 디버그 정보 |
## Terminology: Simplified Chinese

Neutral register, no 您, full-width punctuation, no spaces between CJK and Latin. Latin tokens (brands, codecs, units, key and button names) stay as written.

| English | zh-CN |
|---|---|
| stream | 串流 |
| host | 电脑 / PC |
| pair / unpair | 配对 / 取消配对 |
| bitrate, frame rate, refresh rate | 码率, 帧率, 刷新率 |
| resolution, latency, packet loss | 分辨率, 延迟, 丢包 |
| codec, decoder | 编解码器, 解码器 |
| upscaler, supersampling, sharpness | 超分, 超采样, 锐度 |
| frame pacing | 帧节奏 |
| controller / gamepad | 手柄 |
| gyroscope, rumble, deadzone | 陀螺仪, 震动, 死区 |
| analog stick, trackpad | 摇杆, 触控板 |
| on-screen controls | 屏幕按键 |
| library, cover, hero | 游戏库, 封面, 主视觉图 |
| profile, avatar | 个人资料, 头像 |
| overlay, statistics | 浮层, 统计信息 |
| clipboard, log | 剪贴板, 日志 |
| Fit / Fill / Stretch | 适应 / 填充 / 拉伸 |

Arrows and ≈ ≤ ≥ ¼ are not in the allowed character set: menu paths use `>`, ranges use 至, ¼ is written 1/4.
