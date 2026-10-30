# Delos GUI – vision

The starting point for the new Delos UI (Phase 6). Written by the project owner; layout ideas may borrow from PS5, SteamOS and Switch, **the visual design must be original**.

This is a design/specification document, not a task tracker. The approved
[HTML prototype](prototype/delos-gui.html) remains the visual reference; all unfinished intentions,
decisions and verification status live in [GitHub Issues](https://github.com/Meitoncz/Delos/issues).

## Experience

- The UI is one continuous **experience from the very first launch**, not a set of separate screens.
- **Console-like layout**; modern and minimalist, with **lots of animations and effects** and especially **polished focus animations** everywhere (menus and anywhere else).
- **Several types of dynamic backgrounds.**
- **Light / dark mode**, plus an extra **Material You** switch that takes the overall color palette from the system. With Material You on, **all dynamic backgrounds use only the system palette** (no other colors mixed in); the "System" wallpaper color is the default.
- Overlays such as the **portal and the game loader blur the content behind them**.
- Slightly **rounded** design with generous paddings and margins.
- An **interface scale** setting enlarges text, the status bar and the button hints (the covers keep their size).
- Heavy use of **Phosphor icons**, bundled offline under the MIT license: Regular at rest and Fill
  on focus, with a smooth crossfade. Choose icons by the meaning of each action or setting; identical
  actions stay consistent across screens. The color-palette selector uses **Palette** for every
  palette choice. See [ICONS.md](ICONS.md) for the approved implementation and generation workflow.

## First launch

1. The user is asked to **create a profile**: a splash screen with **circular icons** (similar in spirit to the PS5 UI).
2. In the same style, the next step automatically shows the **PCs to pair, in circles**.
3. Logging in to a PC plays the main **"portal" animation** (not the same as the PlayStation Portal one). The same kind of animation is used when connecting to a remote device elsewhere in the UI.

## Start screen (every launch)

- Every time Delos starts, it opens on a **start screen with the remote devices** (in the same circle style as the first launch).
- The **current / last used profile** is shown in the top-left corner, with a quick way to **switch profiles or create a new one**.
- Settings: a **"Default device to connect to automatically"** picker and a switch to **skip the start screen** – Delos then connects straight to the default device.

## Home

- After connecting to a PC, the main layout shows the **apps / games with the correct aspect ratio for SteamGridDB covers** (portrait).
- Games are laid out **side by side in portrait covers**; the **first game is wide** (landscape SteamGridDB cover). Scrolling is **vertical**.
- A second home layout, switchable in the settings: **one horizontally scrolling row** with everything the grid has (logo, hero background, info, wide first tile), placed a bit lower so more of the game background is visible; the focused game stays near the left edge and the row fades out at the edges.
- **Top center bar** with icons for **settings, profile and devices** (e.g. renaming a PC on the client side). On the **right**, separately: **battery, Wi-Fi** and other status (Delos draws its own status bar – the system bars are hidden).

## Launching a game

- Starting a game shows an **animated circular loader**, then the UI **"dives" into the streamed game**.
- The dive uses the existing shader/ring effect. Optional visual refinement requires agreement and
  is recorded in [#23](https://github.com/Meitoncz/Delos/issues/23), not permission to redesign.
- Game-window readiness behavior and its proposed host protocol are recorded in
  [#13](https://github.com/Meitoncz/Delos/issues/13) and [#14](https://github.com/Meitoncz/Delos/issues/14).
  A visible game window is not a guarantee that gameplay loading has finished.

## SteamGridDB

- **Built-in SteamGridDB integration**: the user enters their own API key and images are downloaded on the client side.
- For every game Delos downloads **four images**: the **portrait cover**, the **wide cover**, a **large background (hero)** and the **logo**.
- A committed artwork selection invalidates existing image readers even if leaving the picker
  cancels its coroutine during the download. Selected hero artwork takes precedence over the
  SteamGridDB cover and PC fallback; clearing it restores that fallback order.
- Must also run **automatically for all games at once**.
- When a game is focused on the home screen, its **logo is shown in the top area** (the covers sit a bit lower) and the **background changes to the game's hero image** with a fade in/out and a **blur effect**. The game background covers the whole screen and **fades out diagonally from the top-right to the bottom-left corner**. **Blur amount and opacity are configurable.** The hero image **blends with the dynamic background** – the animated effects (aurora, waves, silk, contour lines…) light it up instead of covering it.
- Host artwork synchronization decisions are recorded in [#15](https://github.com/Meitoncz/Delos/issues/15).

## Game information and library filters

The existing information area under the game title/logo also shows IGDB genres, native HDR/SDR,
and the HowLongToBeat headline duration. HDR uses a readable rainbow badge; confirmed native-SDR
games use a neutral SDR chip. Unknown/unmatched/ambiguous evidence has no HDR/SDR badge. The host's
streaming HDR flag is not native game support. Special K, ReShade, Auto HDR, RTX HDR and enabling
workarounds do not qualify as native out-of-the-box HDR. File-enabling instructions, conflicting
claims, and platform/edition limitations remain unknown rather than producing a positive badge.

The left menu filters by genre, native HDR/SDR/unknown, and headline length (<10, 10–30, 30–60,
60+ hours, or unknown). Filters follow the active profile and combine across categories. Empty
results retain a controller/touch reset action. Original library order, running-game state,
artwork and cover layout are preserved.

Game options → Metadata provides IGDB search/match selection, refresh and reset. Automatic matching
compares titles tolerantly (case, punctuation, diacritics, a leading "The", "&"/"and", Roman/Arabic
sequel numbers, a bracketed note such as "(Steam)" and trailing edition qualifiers such as "Definitive
Edition", "GOTY" or "Director's Cut"); subtitles and different sequel numbers stay distinct. IGDB
entries with the same tolerant name are narrowed by the exact name, a year written in the host name,
a Steam listing, the PC platform, and finally the newest release; if still tied the game stays
unmatched until the user picks a match. IGDB search is PC-focused: console-only entries are dropped,
but entries without platform data are kept. IGDB IDs, Steam AppIDs and host app IDs are distinct.
Metadata matches never overwrite manual SteamGridDB artwork matches. Data/matches are cached in
application files; missing provider data stays missing, and genres are IGDB genres only (IGDB themes
share no ID space with genres and are not substituted). A fully answered record is refreshed after
seven days and an unmatched one after six hours. Each provider's failure time is stored in the
record: a failing provider is not asked about that game again for 30 minutes, across focus changes
and restarts, unless the user chooses Refresh. A record stamped in the future is treated as stale.
Explicit match operations belong to the shell, so changing a match can finish even if active filters
remove its tile. Refresh/Reset report "No IGDB match found" when the re-match finds nothing.

When a library opens, every cached record is loaded and published first (so filters and the genre
list are complete immediately); stale games are then refreshed with at most two concurrent lookups.

Provider calls run off the UI thread, are paced, and are interrupted when their coroutine is
cancelled. IGDB authenticates through Twitch client credentials; PCGamingWiki uses bot-password
login and Cargo plus Video-template notes. Neither credentialed client follows redirects, so a
307/308 can never replay a secret-carrying POST elsewhere. A refused PCGamingWiki login is latched
for the process instead of being retried per game. PCGamingWiki notes are reduced to reader text
(references, templates' named arguments, URLs and link markup removed) before the native-HDR
heuristics run; one Steam ID or several, and one wiki page or several, only yield a result when the
pages agree. The unofficial HLTB client uses its website search/detail data, specifically positive
`comp_all` with submissions, not `comp_main`, `comp_all_avg`, or an invented average. One lookup
reads at most two search pages and five detail pages, paced, skips a detail page that cannot be
read, and stops on an HTTP error; a failed search-endpoint discovery is remembered for five minutes
and the discovered endpoint must itself be a search API. The chip preserves the
rendered **HowLongToBeat** headline text, including half-hours; unrounded seconds drive the filters.
Unavailable/changed provider responses are reported rather than replaced with another provider's
metric. Cancelled work stops before further provider requests or publishing a resolved result.

Developer builds read `DELOS_IGDB_CLIENT_ID`, `DELOS_IGDB_CLIENT_SECRET`,
`DELOS_PCGW_BOT_USERNAME`, and `DELOS_PCGW_BOT_PASSWORD` from environment variables (an empty or blank
variable is ignored) or ignored `local.properties`. The values are plain `BuildConfig` string constants:
`-dontobfuscate` is set and R8 does not encrypt strings, so anyone holding such an APK can read them with
`strings` or apktool. The user explicitly accepted this direct-in-APK exposure; there is no backend or
end-user key entry. Mitigate by using a minimally privileged PCGamingWiki bot password, rotating both
credentials on any distribution, and never attaching a credentialed APK to a public artifact (CI builds
carry no credentials, so the feature is inert there). Never commit credentials or log tokens/passwords.
Provider attribution is visible in Settings → About, and the adapted HLTB client's MIT notice is bundled
in assets.

## Menus and settings

- Menus and settings follow a **PS5-like layout**, but the main settings screen also shows the **main categories as icon tabs** that can be switched.

## Controls

- Primary input: **D-pad or left stick + A, B, X, Y**.
- The **two menu buttons at the top left and top right** of the handheld are used in the UI: the **left one (View/Select)** slides the **side menu** in from the left, the **right one (Start)** opens the **context menu** for the focused item. The hints show the buttons as they look on the device: on AYN handhelds (Odin, Thor) a square (left) and a play arrow (right).
- Navigation must be **intuitive and direct**: pressing a direction takes you where you want to go, ideally in **one press** – never left, down, down just to reach something.

## Stream

- SGSR upscaling and independent sharpening are implemented. Codec-specific behavior and measured
  results are documented in [STREAM_QUALITY.md](STREAM_QUALITY.md) and [SGSR2_VIDEO.md](SGSR2_VIDEO.md);
  current quality/performance acceptance is tracked only in [#19](https://github.com/Meitoncz/Delos/issues/19).
- The Upscaler page of the stream menu changes a row with one press. Its supersampling quality, mode
  and sharpness rows show the value just requested, not the one the renderer applied on its last frame
  (a fallback from 2× to 1× corrects the row afterwards).

## Native shell architecture and design constraints

These preserve implementation/design context from the removed UI plan, not phase checklists.

- `ShellActivity` is the launcher; `ShellLink` handles external links and PC shortcuts. Kotlin and
  Compose implement the shell. Existing Java/backend behavior is reused rather than rewritten:
  `ComputerManagerService`, `NvHTTP` / `PairingManager`, `PreferenceConfiguration`, `ProfilesManager`,
  `ShortcutHelper` and `ServerHelper`. Streams still start through `ServerHelper.createStartIntent()`
  into `Game`, wrapped by `GameLauncher.startIntent` (shared with launcher shortcuts) to attach per-game settings. Keep upstream-shared changes small.
- Use Compose foundation/ui/animation and the Delos design system, not Material components. The
  shell owns its small navigation stack, animated transitions and overlay focus. Fonts are bundled
  Unbounded/Figtree (OFL); Phosphor icon generation/licensing is documented in [ICONS.md](ICONS.md).
- `Stage` provides the prototype's 1920×1080 design coordinates and interface scale. The reusable
  focus modifier applies to touch as well as controller elements. `GlobalKeys` handles input before
  focus dispatch; left/right adjust choice/slider rows. A/Enter/D-pad center activate, B goes back,
  Select opens the side menu, Start/X opens context actions, L1/R1 go to the screen and Y opens search.
  System HOME/BACK/APP_SWITCH remain Android functions.
- The Odin's built-in controller identifies as Xbox Wireless Controller, vendor `0x2020`, product
  `0x0112`. AYN hints use the square/play-arrow menu glyphs. Rumble-only devices use the established
  full-strength 50 ms navigation and 70 ms confirmation feedback; sound/haptics remain configurable.
- Animated backgrounds are time-based. Shader backgrounds require Android 13+ and use the existing
  fallback on older systems. Overlay blur has a dim/translucent fallback where blur is unavailable.
  Each palette has main, secondary and accent colors; wallpaper colors use the tertiary accent.
- Cover-derived palettes reject near-black/white/grey, group hue in 15° buckets, weight share by
  chroma squared and keep the three selected hue groups at least 30° apart. Preserve this deliberate
  CAM16/Hct behavior rather than substituting Wu quantization.
- `PcRepository` binds the computer service and exposes StateFlows. Profile preference files isolate
  appearance, startup, hidden games, last-played state and PC names. Hidden apps reuse the existing
  `HiddenApps` preferences. The running game sorts before last-played ordering.
- Preserve library performance constraints: hero blur uses a small bitmap off the main thread;
  animated-background invalidation is limited, Contour uses batched lines, Haze is active only for
  visible overlays, focus animation values are read during drawing and idle-cover dim uses a filter.
  Row navigation retains edge wrapping and the existing fade/glide behavior.
- LaunchOverlay and StreamLoader form one scene. AGSL dive effects use the older-device ring
  fallback. `StreamMenu` provides the configured button combo/edge gesture and On/Compact/Off overlay
  cycle; `StreamExit` owns the accepted normal exit scene. No ordinary-flow confirmation dialogs;
  legitimate security prompts for external input remain. Further layout changes need user agreement.
- SteamGridDB supplies portrait/wide covers, hero and logo; the API key is device-local. Artwork
  lives in application storage, and hand-picked files survive bulk redownloads. Desktop is manually
  selected. Dashboard logos use the accepted 800 ms slide/fade with no scale; reduced motion shows
  them immediately. Keep the reserved title area and the accepted LIBRARY footer styling.
- Virtual controls use `OscStyle`: thin glass outlines, Figtree capitals, filled pressed state,
  circular face/stick buttons, shoulder/menu pills and a chevron D-pad. The stick-button overlap is
  intentional. Debug info provides a preview without starting a stream.
