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

### Android launcher identity

The Android launcher uses the same Delos ring and orbiting moon as `LogoMark`, with the default
dark Delos background and gold/cyan accents. The ring is optically centered at (54, 54) on the 108dp
canvas; the complete mark, including the moon halo, fits inside the centered 66dp safe circle.
All layers use the same uniformly reduced geometry; the Android 13 monochrome layer preserves the ring and moon.
Run `python tools/icon/generate_launcher.py` to rebuild vectors and the 48/72/96/144/192px legacy
PNGs. Circle, squircle and rounded-square color/monochrome previews go to ignored `build/icon-preview`.
The TV channel uses the same mark; PC shortcut icons remain unchanged.

## First launch

1. The user is asked to **create a profile**: a splash screen with **circular icons** (similar in spirit to the PS5 UI).
2. In the same style, the next step automatically shows the **PCs to pair, in circles**.
3. Logging in to a PC plays the main **"portal" animation** (not the same as the PlayStation Portal one). The same kind of animation is used when connecting to a remote device elsewhere in the UI.

### Profile avatars

- A profile's avatar is one of **12 illustrated characters** (game-styled mascots with soft shading, no text), not an icon: Sailor, Voyager, Player, Fox, Spark and Calm (the original six, in this order) and Knight, Robot, Cat, Wizard, Racer and Ghost. The profile stores only the avatar's **index**, so existing profiles keep theirs and the order only ever grows at the end.
- The illustrations are transparent images (384 px WebP, character in the central ~70% because every badge is a circle) drawn over a **radial-gradient background** (`radial-gradient(circle at 35% 30%, c0, c1)`); the font icon of the old avatar is only the stand-in while the image loads. The background is the avatar's **fixed per-avatar hue gradient** (`hsl(h 80% 62%)` to `hsl(h+30 70% 38%)`, `h` = `Avatar.hue`, e.g. Sailor blue 205, Voyager purple 262) **unless** the avatar has an entry with `c0`/`c1` in `app/src/main/assets/avatars/avatars.json`; only **warm** imported characters get such an entry (rule A below), so the set may be only partly imported.
- **Where the background color comes from (rule A: computed gradient only for warm characters):** the importer finds the character's dominant color the same way the cover colors of the game tiles do (16-level RGB buckets, CAM16 chroma squared weights, grey and near-black/near-white clusters ignored, 15-degree hue groups; `HeroBitmap.dominantHues`). Only if that hue is **warm** (HCT hue 330..360 or 0..70 degrees: reds, pinks, oranges, yellows; Fox 24, Player 342) it computes a background and writes it to `avatars.json`: the hue leans to the **complement** (dominant hue + 150..210 degrees, the choice farthest from the character's other main colors) so the character stands out instead of blending in: an orange fox sits on blue. `c0` is the bright center (HSL 78% saturation, 58% lightness) and `c1` a deeper edge (75%, 40%, hue shifted 22 degrees, toward red for warm hues so the edge never turns brown). Lime and olive hues are moved to amber or emerald. Cool characters (Sailor 209, Voyager 267) and mostly grey or white ones (under 20% colorful pixels, e.g. ghost, robot) get **no entry** and keep their fixed hue gradient: a blue sailor already stands out on blue 205. The json is rewritten from scratch on every run, so the entry of a slug that is no longer warm (or no longer imported) disappears.
- The picker on the create/change-avatar screen shows all twelve at once in **two rows of six** circles with the avatar's name under each. D-pad moves spatially, left at the start of a row and right at its end continue in the previous/next row, A selects.
- The images come from an **image generator** (gpt-image-2): 1024 x 1024 RGBA PNGs named `<index>_<slug>.png` (`00_sailor.png` ... `11_ghost.png`), character on a transparent background filling about 70-75% of the canvas. Import whatever is present (any subset, re-run when more arrive):
  `python tools/avatars/import.py [INPUT_DIR]` (Pillow 11.3.0 with WebP; default input `.gradle-agent/avatars-in`) validates every PNG (RGBA, square, alpha, character centered within 6% and 60-85% of the canvas; only warns), resizes it to 384 px with Lanczos, **replaces** `app/src/main/assets/avatars/<slug>.webp`, writes the colors of the imported **warm** slugs to `avatars.json` (no entry for the others) and prints a table of what was imported or kept,
  `python tools/avatars/import.py --preview-dir DIR` writes contact sheets (280/96/72/56 px on the computed gradient and on the final background the app draws, plus a family sheet of all twelve with the final backgrounds; default `build/avatars-preview/`, ignored by git), `--preview-all` also shows the computed colors of the slugs that are not imported.
- Slugs without a generated image keep a **procedural fallback illustration** from `tools/avatars/generate.py` (`python tools/avatars/generate.py`, `--preview`, `--fast --only fox cat`); the importer overwrites the webp of every slug it imports, so run the generator only for slugs that still have no image.

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

- Android 13+ launch uses the approved **hyperspace tunnel**: fixed-center symmetric lens curvature, traveling ribs/bands, **24 streaks + 24 independently distributed dark clouds**, moving peripheral bloom and a high-contrast palette-aware gradient. `HyperspaceConfig.RENDER_SCALE` is **0.6** (1152×648 at 1920×1080); the shader and brush belong only to the active overlay and animate in drawing, not composition.
- The shell, shortcut splash and stream loader share the scene clock, last drawn progress and inherited connection line. The game’s existing SteamGridDB **logo** replaces the cover, with Unbounded title fallback; below it are read-only **effective resolution · codec · FPS**, a real connection-stage line, a thin progress track and percentage. Before codec negotiation, automatic selection is labeled **Auto**; the loader then uses the active codec and decoded dimensions.
- Launch-parameter inspection is strictly read-only: games without overrides read the global profile, games with overrides read their effective game view, and both suppress legacy preference-normalization writes. The per-game editor still rejects non-per-game keys; loading a label never migrates or modifies user settings.
- Each real witty connection line receives its **1200 ms** visible dwell, including its remaining time across window handoff; each line queued behind the current one is told in order while the connection runs; when the game opens, the lines still queued are dropped (the game never waits for them). Network and decoder initialization continue independently.
- After connection readiness (without waiting for queued lines), the loader waits for a **presented video frame**, not merely `connectionStarted`, with the existing **3 s** bound. Its **2200 ms** exit expands the tunnel and opens a soft central aperture during **68–88%** of the exit; the information block fades during the first **34%**. The revealed image is the actual underlying video surface, not a screenshot.
- Reduced motion uses a static dark gradient, the same information block and a **500 ms** fade; the animation clock is not started. Android versions below 13 retain the previous ring/cover renderer as the visual fallback, with the same new information block.
- The approved visual reference is the local, uncommitted `build/launchfx-proto/b_hyperspace_balanced.html` (preserved locally under `.gradle-agent/keep/`), not the legacy launch-loader drawing in `docs/prototype/delos-gui.html`. The latter is not a simple shader replacement; the native constants and shader are committed in `shell/ui/HyperspaceConfig.kt` and `HyperspaceTunnel.kt`. See [#62](https://github.com/Meitoncz/Delos/issues/62).
- The **real stream image** arrives at **118% zoom with blur**, elastic spatial warp/skew and temporary RGB separation, then settles to exactly **100%, sharp, without residual effects**. Resources warm early, but output takeover starts only at **55%** of the exit; readiness is checked at **60%** with a **1.5 s** bound and completion has a **1 s** bound. Static/low-FPS sources reuse their latest real GPU frame for animation. Runtime failure is logged and cancels the effect rather than holding the loader indefinitely.
- Direct MediaCodec returns to its original surface after the temporary GLES pass; existing SGSR and PyroWave paths apply arrival in their GPU presentation context. Ten-bit/HDR keeps FP16/P010/PQ metadata without SDR conversion. Normal low-latency presentation and the accepted shell/exit/snapshot handover resume after cleanup; reduced motion does not activate stream effects. See [native paths, bounds, verification and device limits](LAUNCH_TRANSITION.md).
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
- Desktop, Remote Input, Remote Monitor and Steam Big Picture use fixed bundled cover/wide/hero
  artwork and render their names instead of logos. These images always win over old local choices,
  SteamGridDB and host artwork; existing user files are ignored, never deleted. Their context menu
  has neither Artwork nor Metadata. A stale artwork route is a read-only preview with a Back button.
- `BundledArtwork.kind(App)` is the shared identity policy: Remote Input/Monitor use the fixed host
  UUIDs even after renaming, with name fallbacks for older hosts; Desktop and Steam Big Picture
  use trimmed case-insensitive names (including `Steam BigPicture`). Their installation-specific
  generated UUIDs are not assumed constant. Built-ins never fetch game metadata or create its cache,
  show no genre/HDR/HLTB badges, and appear only while all library filters are inactive.
- Rebuild assets with `python tools/artwork/generate.py --preview` (Pillow 11.3.0, fonttools 4.67.0).
  Pinned, checksum-verified Phosphor Fill sources are cached in ignored `build/phosphor-icons`;
  Steam uses the checksum-verified canonical Valve geometry in `tools/artwork/steam-emblem.svg`.
  The script writes 600×900 covers, 920×430 wide covers and text-free 1920×1080 heroes; previews stay in `build/artwork-preview`.

## Game information and library filters

The information area under the game title/logo is a small label and one row in two groups. The label is
`GAME INFO` (`APP INFO` for the built-in apps) in the style of the library footer's `LIBRARY` label (Figtree
SemiBold, 17 px, tracking .14 em, `colors.text3`) with an 8 px gap to the pills; it is shown only while at
least one pill is. The **facts** come first (`Running on <PC>` while the game runs, native HDR/SDR, the
HowLongToBeat headline duration, `Virtual display`; the PC's name is no chip while nothing runs), then a
divider (a 2 px wide, 28 px tall rounded bar in `colors.text3` at 55 % alpha, 16 px margins on both sides) and
the **tags**: the game's genre pill and theme pill. **Each pill shows up to two entries** inside one pill,
set apart by a thin vertical bar in the pill's own text color (1.5 px wide, 18 px tall, rounded, 55 % alpha,
12 px margins on both sides): `[icon Shooter | icon Adventure] [icon Horror | icon Sci-fi]`. Every entry has
its own icon and name, the icon-to-text spacing is that of the other pills; with one entry the pill shows just
that entry (no bar), and with none the pill is omitted. IGDB's complete genre and theme lists stay in the
metadata and keep feeding the Y filter: **Steam tags only drive the card pills**, never the filter. Without a
genre or theme pill the divider goes away too; built-in apps and games without metadata show the facts only.
The facts keep their usual pill look; the tag pills have exactly the same height, padding, font and icon size.
The row wraps at group boundaries (facts as before, the tags as one group, a pill is never split): when the
tags do not fit beside the facts they move to the next line as a whole and the divider stays out, also at
125 % interface scale.

- **Steam tags first** (`topGenres`, `topThemes` in `shell/ui/GameTags.kt`, the mapping in
  `shell/ui/SteamTags.kt`). A game with a Steam app id (the first one IGDB lists) gets its Steam *user tags*,
  most voted first, from the metadata Worker (`GET /v1/steam/tags?app=<id>`, which reads the public store
  page; this is **unofficial and may change or be blocked**). Of the first 15 tags, every tag the mapping table
  knows becomes a genre or a theme entry in vote order, without duplicates; features, modes, moods, art styles
  and meta tags (Singleplayer, Co-op, Indie, Atmospheric, Third Person, ...) are ignored. The table maps each
  Steam tag name (case-insensitive) to exactly one IGDB genre or theme, so the icons and names are IGDB's
  (Platformer -> Platform, Action-Adventure -> Adventure, Cooking -> Simulator, Funny -> Comedy, Open World ->
  Open world, ...). A few "weak" mappings are only a fallback: Roguelike/Roguelite -> Role-playing joins the
  genres only when fewer than two strong genres exist, and Action/Superhero -> the Action theme only when the
  game has no other theme (the rule IGDB's Action theme follows too). When Steam gives one entry the second
  slot is filled from IGDB's ranking (below) with the first entry not shown yet; Indie and Action never fill.
- **IGDB ladder** is used when a game has no Steam id, the Steam fetch failed or the store lists no mappable
  tag. Genres are ordered by this ladder, and the pill shows the first two: Shooter, Fighting, Racing,
  Role-playing, Real Time Strategy, Turn-based strategy, Tactical, Hack and slash/Beat 'em up, Platform,
  Adventure, Puzzle, MOBA, Music, Visual Novel, Card & Board Game, Quiz/Trivia, Pinball, Point-and-click,
  Sport, Simulator, Strategy, Arcade; genres IGDB adds later come after these in IGDB's order. Indie is a
  business model, not a genre: it only shows when it is the game's only entry. Themes skip Erotic entirely
  and Action unless it is the game's only usable theme (Life is Strange: Double Exposure lists Action alone
  and shows it), then follow: Horror, Survival, Open world, Science fiction, Fantasy, Stealth, Warfare,
  Sandbox, Mystery, Thriller, Party, 4X, Historical, Comedy, Drama, Romance, Business, Educational, Kids,
  Non-fiction; later additions come after these. `mainGenre`/`mainTheme` are the first entries of this
  ranking (`rankedGenres`, `rankedThemes`).
- Examples. From Steam tags: Cronos (Survival Horror, Horror, Sci-fi, Third-Person Shooter, ...) shows
  `Shooter | Adventure` (the second genre comes from IGDB's Shooter, Puzzle, Adventure) and
  `Horror | Science fiction`; LEGO Batman (Co-op, Open World, Superhero, ..., Action-Adventure, Funny, ...,
  3D Platformer, Adventure) `Adventure | Platform` and `Open world | Comedy`; Forza Horizon (Racing, Open
  World, Driving, ..., Automobile Sim, ...) `Racing | Simulator` and `Open world`. From IGDB alone: LEGO
  Batman (Puzzle, Adventure) shows `Adventure | Puzzle`, Forza (Racing, Simulator, Sport, Arcade)
  `Racing | Sport`, Overcooked (Simulator, Strategy, Indie) `Simulator | Strategy`.
- The **genre pill** is the accent of the row: filled with a tint of the game's main color from its cover
  (`HeroBitmaps.coverHues`, the game background's while the cover's are unknown) over the glass:
  `hsl(hue, 0.8 × vividness, 34 %)` at 35 % alpha, a 1 px `hsl(hue, …, 55 %)` border at 55 % alpha, and
  the icon and text in the same hue at 85 % lightness (a light theme uses darker shades). With *Match the
  game's colors* off, with Material You, or while the colors are not known, the palette's accent hue is used.
  The **theme pill** uses the game's *second* cover color (`hues[1]`; the main hue shifted by +40° when
  the game has only one, the palette's accent (third) hue while the colors are off or unknown) but fainter, so it stays behind
  the genre: `hsl(hue2, 0.7 × the genre's saturation, 30 %)` at 16 % alpha over the glass, a 1 px
  `hsl(hue2, …, 55 %)` border at 45 % alpha, icon and text at 80 % lightness and 90 % alpha.
  Neither pill animates; like the facts they appear when the metadata is there.
- Every genre and theme has its own Phosphor icon (`genreIcon`, `themeIcon` in `shell/ui/GenreIcons.kt`;
  the neutral tag stands in for ones IGDB adds later). The theme icons come from `tools/fonts/icons.txt`
  like every other icon (see `docs/ICONS.md`).

Native HDR uses a gradient badge instead of a rainbow: a diagonal linear gradient from cyan `#22D3EE` over
indigo `#6366F1` to fuchsia `#D946EF` under a 12 % black scrim (keeps the white text readable on the cyan
end), the sparkle icon, white text, a 1 px border of white at 35 % alpha and a very soft static glow (8 px
blur, 35 % alpha) in the indigo behind the pill; it has the size of every info pill. Confirmed native-SDR
games use a neutral SDR chip. A muted `HDR/SDR unverified` chip means native HDR/SDR could not be verified:
the wiki has no page, evidence is unknown/ambiguous, or claimed HDR is broken, disabled, simulated
or platform/edition-restricted. Only successfully evaluated unknown records (`updatedAt > 0` and
`pcgwFailedAt == 0`) show this third state. Missing records and failed/backed-off unknown lookups
show no badge; previously confirmed HDR/SDR evidence remains visible during a later refresh failure.
The host's streaming HDR flag is not native game support. Special K, ReShade, Auto HDR and RTX HDR
do not qualify as native game HDR.

**Y** on the home screen opens the **Filter library** modal (Y or B closes it; Done as well). It is a
centered glass panel with the title, a live result line as a pill (`27 of 55 games`, or `All 55 games` without a filter)
and four sections of chips, each with a large Unbounded heading and its icon in an accent-tinted circle, separated by thin dividers: **Genre** (`All` plus every genre any game in the downloaded metadata lists, each with its icon and number of games), **Theme** (the same for IGDB themes, single-select, shown while the library has any theme), **HDR
support** (`All`, `Native HDR`, `SDR`, `HDR/SDR unverified`, with the sparkle, sun and question-mark icons of
the home badges) and **Duration** from HowLongToBeat (Any, <10 h, 10–30 h, 30–60 h, 60+ h, Unknown). The chosen chip is
filled with the accent; a choice applies at once (no confirmation) and the library behind the modal
updates with it. D-pad up/down moves between the sections and the buttons, left/right within a row; a
long genre list scrolls the panel (its top and bottom edges fade out) and keeps the focused chip in view.
The focused chip is kept 150 px from the top edge, so the first row scrolls the heading into view; the
bottom padding keeps the last row's focus ring outside the fade, including at 125% text scale. **X** resets (the Reset button is
disabled while nothing is filtered), and closing returns the focus to the game that was focused. While
filters are active the home hint reads `Y Filter • N active`. Filters follow the active profile (genre, theme,
HDR and duration are stored per profile) and combine across categories. The Genre and Theme filters match a
game when *any* of its genres/themes is the chosen one, not only the main genre and theme the home screen
shows, so the chip lists can hold entries no tile displays as its main tag. Both lists and their counts are
read from the metadata store while the modal is open, so they follow records as they arrive and after a
cache-schema refetch. Empty results say to press Y and keep a controller/touch reset action.
Original library order, running-game state, artwork and cover layout are preserved.
The `HDR/SDR unverified` filter retains unknown-support matching, including pending/failed records without a
badge; the display rule above does not change filter membership.

Game options → Metadata provides IGDB search/match selection, refresh and reset. Automatic matching
compares titles tolerantly (case, punctuation, diacritics, a leading "The", "&"/"and", Roman/Arabic
sequel numbers, a bracketed note such as "(Steam)" and trailing edition qualifiers such as "Definitive
Edition", "GOTY" or "Director's Cut"); different sequel numbers stay distinct, and a subtitle stays
significant except that a host name without its subtitle ("Planet of Lana II") matches the one PC/Steam
IGDB entry that extends it ("Planet of Lana II: Children of the Leaf") when nothing has the exact
name. IGDB entries with the same tolerant name are narrowed by the exact name, a year written in the
host name, a Steam listing, the PC platform, the newest release, and finally an entry with at least
three times the IGDB rating count of every other; if still tied the game stays
unmatched until the user picks a match. The IGDB query drops symbol characters such as "®", "©" and
"™" (IGDB returns nothing at all for a query containing "®" or "©", which Steam titles like
"LEGO® Batman™: Legacy of the Dark Knight" contain). IGDB search is PC-focused: console-only entries
are dropped, but entries without platform data are kept. IGDB IDs, Steam AppIDs and host app IDs are distinct.
Metadata matches never overwrite manual SteamGridDB artwork matches. Data/matches are cached in
application files; missing provider data stays missing, and filter genres are IGDB genres only (IGDB themes
share no ID space with genres and are not substituted; Steam tags never feed the filter). A fully answered record is refreshed after
seven days and an unmatched one after six hours. Each provider's failure time is stored in the
record: a failing provider is not asked about that game again for 30 minutes, across focus changes
and restarts, unless the user chooses Refresh. A record stamped in the future is treated as stale.
Explicit match operations belong to the shell, so changing a match can finish even if active filters
remove its tile. Refresh/Reset report "No IGDB match found" when the re-match finds nothing.

When a library opens, every cached record is loaded and published first (so filters and the genre
list are complete immediately); stale games are then refreshed with at most two concurrent lookups.

Network metadata lookups reuse the artwork progress pill above the button hints, labeled
"Downloading metadata" with completed/total games. Library batches count only games needing a
provider refresh; focused lookups, Refresh/Reset, match selection and IGDB search use a single-item
batch. Cached-only reads and failure-backoff skips do not show progress. Updates are serialized on
main, and each batch is removed in `finally` on completion, error or cancellation. Overlapping
metadata operations display the largest active batch; finishing one cannot clear another's counter.
Artwork takes priority while both stores are busy, so there is only one pill. Both use the same
existing artwork animation/reduced-motion behavior, retaining the last count and label during exit.

Provider calls run off the UI thread, are paced, and are interrupted when their coroutine is
cancelled. IGDB and PCGamingWiki are reached through the Delos metadata Worker (`tools/metadata-worker`),
which holds their credentials, authenticates (Twitch app token, PCGamingWiki bot session), builds the
upstream queries itself from validated parameters and returns the providers' raw JSON; the app keeps all
matching, parsing, caching and backoff. The app clients never follow redirects, and an HTTP 429/503 from
the Worker (its rate limit or an upstream throttle), any other non-2xx answer, malformed JSON or a network
error is a provider failure that uses the per-provider backoff, never an empty or absent result. The Steam
tags (`steamTags` and `steamFailedAt` in the record, only asked for games with a Steam id, after the other
secondary lookups) follow the same rules, with one difference: a store page without tags is a successful empty
list. A failed Steam fetch keeps the tags stored earlier, is not shown as an error (without earlier tags the
pills use IGDB), and the record is retried after the 30-minute backoff.
PCGamingWiki notes are reduced to reader text
(references, templates' named arguments, URLs and link markup removed) before the native-HDR
heuristics run. The wiki's own HDR value decides: `false` and `hackable` are SDR. Notes never promote
`hackable`, even when they describe native HDR output via Engine.ini. `unknown` and a missing value
stay unknown. `true`, `always on` and `limited` (native HDR with flaws, e.g. No Man's Sky) are HDR.
A note withdraws a native value only when it says HDR needs Special K/ReShade/Auto HDR/RTX HDR/a mod, is console/edition
specific, "not native"/"not supported", or that HDR is currently broken, disabled, simulated or does not
work; config files, command lines, improvement mods and "limited" brightness are caveats, not
retractions. A game is looked up by every IGDB Steam AppID; when no page lists one (or IGDB lists none),
the game's title is used: a page named exactly that, the target of a wiki redirect of that name ("Sea of
Stars: Sunset Edition" is a redirect to "Sea of Stars"), or a page whose title differs only in case and
punctuation ("The Witcher 3: Wild Hunt: Remastered"). After a Steam miss only a page whose Cargo row
names no Steam app at all counts (Euro Truck Simulator 2's row has an empty AppID although the page is
its own); a page naming another Steam app is a different product. One Steam ID or several, and one
wiki page or several, only yield a result when the pages agree. The unofficial HLTB client uses its
website search/detail data, specifically positive
`comp_all` with submissions, not `comp_main`, `comp_all_avg`, or an invented average. Without a Steam
ID the name decides: exactly, or the plain game for an edition title ("Sea of Stars: Sunset Edition" is
listed as "Sea of Stars"), never an edition entry for a plain title. One lookup
reads at most two search pages and five detail pages, paced, skips a detail page that cannot be
read, and stops on an HTTP error; a failed search-endpoint discovery is remembered for five minutes
and the discovered endpoint must itself be a search API. The chip preserves the
rendered **HowLongToBeat** headline text, including half-hours (pages without the banner, such as some
co-op games, get the same text from `comp_all` by the website's rounding); unrounded seconds drive the filters.
The cache schema is bumped whenever matching or evidence rules change so records classified by older
rules are looked up again instead of being trusted for a week.
Unavailable/changed provider responses are reported rather than replaced with another provider's
metric. Cancelled work stops before further provider requests or publishing a resolved result.

Game metadata needs the Worker's base URL, a non-secret `DELOS_METADATA_URL` `BuildConfig` string read
from the environment variable of that name (an empty or blank variable is ignored) or the ignored
`local.properties`; it defaults to empty, and then the app reports "Game metadata is not available in
this build" without contacting any provider (CI builds carry no URL, so the feature is inert there).
The IGDB client secret and the PCGamingWiki bot password are Worker secrets and never ship in the APK
(deploy and secret handling: [tools/metadata-worker/README.md](../tools/metadata-worker/README.md)).
Privacy: the Worker sees the game names the app looks up and the client IP address (Cloudflare's
request metadata); it logs no query text, only a short hash on failures. HowLongToBeat has no
credentials and is still queried directly from the device, so it sees the device's IP and game names.
Never commit credentials or log tokens/passwords.
Provider attribution is visible in Settings → About, and the adapted HLTB client's MIT notice is bundled
in assets.

## Menus and settings

- Menus and settings follow a **PS5-like layout**, but the main settings screen also shows the **main categories as icon tabs** that can be switched.
- The per-game settings screen mirrors the global settings layout (title, tabs and list at the same positions); the game and a chip with the number of custom settings share the title's line. The right column has a 260x390 cover at the top of the list, the focused option's help and the autosave note. "Reset all settings" is the last row of the list, after a gap. Details in [PER_GAME_SETTINGS.md](PER_GAME_SETTINGS.md).

## Controls

- Primary input: **D-pad or left stick + A, B, X, Y**.
- The **two menu buttons at the top left and top right** of the handheld are used in the UI: the **left one (View/Select)** slides the **side menu** in from the left, the **right one (Start)** opens the **context menu** for the focused item. The hints show the buttons as they look on the device: on AYN handhelds (Odin, Thor) a square (left) and a play arrow (right).
- Navigation must be **intuitive and direct**: pressing a direction takes you where you want to go, ideally in **one press** – never left, down, down just to reach something.

## Motion

One motion language for the whole UI. It was designed for the stream menu (below) and is now what every screen animates with, so
one press, one focus and one menu feel the same everywhere. The numbers, easings and pure arithmetic are `Motion` in
`shell/ui/Motion.kt` (no Compose runtime in it; `MotionTest` pins them and the stream menu's values); the building blocks are in
`shell/ui/MotionKit.kt`. `StreamMenuMotion` keeps only what is particular to the dock (its entrance clock, the page morph).

- **Rules.** Focus and press are springs (`EaseSpring`, 420 / 120 ms); things **enter** by rising 18 px and fading in, one after the
  other (46 ms apart, 420 ms each, `EaseOut`); everything **leaves faster than it came** (240 ms, ease-in); a menu **grows out of its
  anchor** (from 0.86 with the spring, its glass fading in faster, 336 ms; it shrinks back in 240 ms); a **changed value slides in**
  22 px from the side the key came from while the chevron it went toward nudges 7 px; colors and opacities use CSS `ease` (280 ms).
  **Reduced motion** drops every transform (rise, scale, slide, glide) and keeps the fades; springs snap. Nothing ever delays a
  control: elements are focusable and usable while they enter, an entrance is only how they appear, a screen's elements finish
  within about a second (at most the first 8 places wait for the ones before them), and an element that shows up later (data
  arrives, a lazy list scrolls) just appears.
- **Building blocks.** `Modifier.staggerEntrance()` (fade + rise by place; the place comes from the surrounding `EntranceScope`, from
  an index, or from the counter of a stream menu page); `focusEffects` (focus spring, **press squeeze** to 0.97 for 130 ms, ring, glow;
  `pressScale = 1f` turns the squeeze off); `GlideRingHost` + `Modifier.glideTarget` (**one ring that glides** to the focused
  element, 560 ms with a slight overshoot, `Circles` uses it); `MotionSwitch` (knob springs across, gradient track fades in);
  `ChoiceMotion` with `valueSlide` and `chevronBump` (**value slide** of choice rows); `animateKnobScale` (a focused slider's knob
  grows to 1.18); `popEnter` / `popExit` (grow from an anchor), `dialogEnter` / `dialogExit`, `sideEnter` / `sideExit` and
  `screenTransition` (the central crossfade of `Shell`: the new screen fades in while it settles from a 1.035 zoom, the old one
  leaves in 240 ms).
- **Where.** *Stream menu*: unchanged, built from the same blocks. *Start, Devices, Profile, pairing*: `CenterColumn` is an
  `EntranceScope`, so the headline and then every PC circle rise in turn; one gold ring glides between the circles; the check badge
  of the default PC pops in when the choice changes. *Home*: the first covers rise in turn (covers a scroll brings in later do not
  animate), the empty and offline messages rise; every cover squeezes when activated. *Settings, game settings, side menu*:
  `SettingRow` rises in turn, its glass fades in and out with the focus, a choice slides its new value in with the chevron nudge,
  a slider's knob grows on focus, the switch is the stream menu's. *Context menus* grow out of the focused element's corner (or
  under its button), their title and items rise in turn, the focused item fills with a fade. *Side menu*: slides in over 560 ms,
  header and rows rise in turn, leaves in 240 ms. *Dialogs and the filter modal*: settle in from 0.96, title, note, fields and
  buttons rise in turn, leave in 240 ms. *Toasts* leave in 240 ms.

## Color roles

Color is a seasoning, not a coating: the neutral text and surfaces carry the screen and the palette's three colors split
the accents about **60 / 25 / 15** by visual weight (`colors.accent`, `accent2`, `accent3`; Delos: gold 40°, cyan 190°,
indigo 250°, a split complement). Material You derives the same three from the wallpaper (main, +52°, tertiary) and
follows the same roles. Light themes use darker shades (accent3 is dark there); content on an accent3 fill uses `onAccent3`
(dark in the dark theme, white in the light one); a toast icon and a cover badge use `accent3OnInverse` / `accent3OnDark`
and `accent3Line` is the faint hairline of the palette's third color, all in `ui/Colors.kt`.

- **Text stays neutral**: labels of buttons and hints, titles, kickers and values are white or the grey text colors in
  every palette. Color goes into shapes and icons.
- `accent` (main, ~60 %): the focus ring and its glow, filled focus, on states (switch and slider fill, tiles that are
  on), primary buttons, the selected tab, the gold of the active label in the stream menu.
- `accent2` (secondary, ~25 %): icons of rows in the settings and the stream menu, arrows, the second half of every
  gradient (switch, slider, progress), the check badge of a selected circle, spinners.
- `accent3` (accent, ~15 %): the cherry on top, small and never text: the dot under the current page in the top bar, the
  slider halo of the stream menu, the toast icon, the `Running` and cover badge icons, the ring ripple of the portal
  overlay and the end dot of the launch ring.

The animated backgrounds follow the same weights: the main color about 60 % of the lights, the secondary one a quarter,
the accent the rest and fainter.

## Stream

- SGSR upscaling and independent sharpening are implemented. Codec-specific behavior and measured
  results are documented in [STREAM_QUALITY.md](STREAM_QUALITY.md) and [SGSR2_VIDEO.md](SGSR2_VIDEO.md);
  current quality/performance acceptance is tracked only in [#19](https://github.com/Meitoncz/Delos/issues/19).
- The Upscaler page of the stream menu changes a row with one press. Its supersampling quality, mode
  and sharpness rows show the value just requested, not the one the renderer applied on its last frame
  (a fallback from 2× to 1× corrects the row afterwards).
- Its last row, **Save for this game**, saves the live on/off, mode, sharpness and supersampling quality
  as this game's overrides for future launches, with a check and "Saved" on the row (for 2.5 seconds) and no
  confirmation dialog. It is hidden for a stream without its launch identity.

### Stream menu

"Console tiles" (prototype B, approved): **nine glass tiles** in one row over the lower part of the stream, the game's
name and the A / B hints above the row's left end, the **complete help text** of the focused tile under it, and the
pages behind the tiles in a **popover above the tile that opened it**, a caret on its bottom edge pointing at that
tile. While the menu is open the player looks at the menu, not at the game, so it is generous: big tiles, the whole
help text in large type, an unhurried entrance. The stream is never dimmed as a whole: only a soft gradient sits
behind the dock (no card behind the tiles). Sizes are stage pixels (1920 × 1080, the dock bottom-aligned on it); the
numbers and the pure geometry live in `StreamMenuLayout` (the CSS variables of `b.html` one for one), the durations and
easings in the shared `Motion` (the dock's own delays in `StreamMenuMotion`), the dock in `StreamMenuBar`, the popover and its rows in
`StreamMenuPopover`, the whole
menu in one composable (`StreamMenuRoot`, so a window of its own can host it), the stream behind it in `StreamMenuHost`
(`StreamMenu` implements it with the live `Game`, the debug preview with fake state). The prototype's `#quickmenu` /
`#qmpop` show the same design; `build/streammenu-proto/b.html` with its screenshots is the specification.

`StreamMenu` owns every stream-menu entry point, including controller, touch and Back.
There is no classic dialog fallback. `GameMenu` remains only as the special-key action
helper and storage constants used by the stream menu and keyboard layouts; it does not
create a menu window. TV poster cache/provider paths are independent of the removed
classic grid asset adapters.

Delayed shortcut releases, pointer recapture after focus and USB attachment handling use
explicit main-Looper handlers, retaining their 25 ms, 500 ms and 1000 ms delays respectively.
The stream window keeps its existing immersive/multi-window system-UI flags and display
selection: these are compatibility behavior on Android 10+, not interchangeable with
app-window bounds or the shell's separate modern insets controller.

- **Tiles**: 150 px glass, corner 42, gap 40 (9 × 150 + 8 × 40 = 1670, 125 on each side), the top edge at y 628, icon 64
  (Phosphor, filled on focus), label 24 SemiBold 24 below the tile. Resume, Keyboard, Overlay, Brightness, Bitrate,
  Upscaler (Supersampling), More, Disconnect, Quit game. Toggles (Overlay, Upscaler) have an **On / Off pill**
  (18 bold, 34 high) at the tile's top right corner (right −12, top −14; Overlay says Compact, the Upscaler its status
  word) and turn accent: accent glass and icon, accent pill. **Focus** scales the tile to 1.12 (spring, 420 ms), lights
  its glass, adds a glow and turns the label gold, 8 px lower; one **gold ring** (box 186, corner 56, 4 px, outer glow
  and a soft glow inside that breathes over 2.6 s) glides under the focused tile. While a popover is open the ring rests
  on the tile that opened it and the other tiles dim to 46 % (labels 40 %).
- **Head**: the game's name (Unbounded 36, one line) and, stacked under it, the **A Select** / **B Back** hints
  (44 px glyphs, label 24), their bottom edge 46 above the tiles, at the tile row's left edge.
- **Caption**: the focused tile's name is not repeated; its **complete help** is centered under the labels, 28 with line
  height 1.4, at most 1100 wide, balanced line breaks, in a box of exactly three lines at the current interface scale. It
  never grows, scrolls or runs as a marquee: `StreamMenuLayoutTest` checks every text it can show (all tiles, every
  upscaler status) at scales 1.0, 1.15 and 1.3 against the characters of a line; a text that does not fit fails the
  test and is shortened in `StreamMenuHelp`. A new text slides up 18 px out of a 6 px blur (420 ms); it is gone while a
  popover is open.
- **Gradient and scrim**: transparent down to 34 % of the height, then 22 % at 48 %, 55 % at 66 %, 80 % at 84 % and 90 %
  black at the bottom edge; an open popover adds a 22 % scrim. Glass is drawn as the prototype's tints and white
  gradients over a dark undercoat (28 % under the tiles, 30 % under the card): the stream is a separate window (a
  `SurfaceView`), so the frosted blur of the prototype is not available, and the undercoat keeps the glass readable
  over a bright picture. A window that can blur its backdrop replaces the undercoat later (`TILE_BACKING_ALPHA`,
  `CARD_BACKING_ALPHA`).
- **Popover**: a glass card (tint rgba 34/44/84 at 50 %, white gradients, corner 44, shadow 0 40 90 −20) **above its own
  tile**, centered on the tile and kept between 110 and 1810, its box ending 322 above the stage's bottom edge, with the
  **caret** (60 × 24, rounded, ONE path with the card so the glass is continuous) at the tile's center, at least 72 from
  the card's corners. Width per page: Brightness 780, Bitrate 860, Upscaler 980, More and the pages opened from it 920.
  Padding 40 × 36 (28 below), header 64 (the back circle, focusable, B does the same, and the title in Unbounded 30),
  rows 72 tall with 8 between them (corner 28, icon 32 in a 56 column, label 24 SemiBold, values 24), a 78 × 44 switch, a
  240 × 12 slider with a 32 knob, choices between two chevrons. The card is as tall as its content, at most 686; only
  More (12 rows) and a long list of keys scroll inside, with faded edges. The help of the focused row stands under the
  rows in a box of two lines (three from interface scale 1.15 up), complete, never scrolling. The Upscaler page, with
  Supersampling quality and **Save for this game**, fits at every interface scale (a test pins the sum). The pages
  opened from More (Send keys, Commands, Mouse mode) are anchored over the More tile.
- **Navigation**: Left/Right move along the tiles. A on a toggle or action tile acts at once; A on Brightness, Bitrate,
  Upscaler or More opens its popover and puts the focus on its first row (the focus cannot leave the popover with the
  D-pad). B in a popover closes it and returns the focus to its tile; Send keys, Commands and Mouse mode are opened from
  More, so B there goes back to that row of More first. B on the dock resumes the stream. The open and close buttons (L3
  + R3 by default, the swipe up) and the external display's input routing are unchanged. **Touch**: a tap on a tile is A
  (a tap on another tile with a page while a popover is open morphs the popover to that page, a tap on the same tile
  closes it; the tiles without a page do nothing then); a tap outside the popover closes the popover only and never
  resumes the stream (Resume and B do).
- **Motion** (every number is a `--d-*` / `--ease-*` variable of `b.html`, copied to the shared `Motion` and `StreamMenuMotion`): the **entrance**
  runs on one clock (about 1.3 s): each tile rises 90 px from a 0.8 scale with the overshooting spring (620 ms), 42 ms
  after the one before, starting 100 ms in; labels fade in 140 ms after their tile; the head rises 24 px from 60 ms; the
  ring scales in from 0.7, 260 ms after the first focused tile; the caption rises 20 px from 520 ms. The **exit** (240 ms,
  ease-in) drops the tiles 46 px to a 0.92 scale and fades everything, the last tile first (8 ms apart); the window
  closes 320 ms after it starts. Focus: tile and icon springs, ring glide 560 ms with a slight overshoot
  (`--ease-glide`), label gold. **Popover**: the dock sinks 160 px over 640 ms; the card grows from 0.86 around the
  caret's tip with the spring (560 ms) while its glass fades in over 336 ms; its rows rise 18 px and fade in 46 ms apart;
  it shrinks back to the caret in 240 ms. Another tile's page **morphs** the card: x, width, caret and height follow
  their targets with a spring (stiffness 190, damping ratio .8), the old rows rise out in 140 ms and the new ones, the
  title and the help enter after 150 ms. A switch knob springs across, a choice's chevron nudges 7 px and its new value
  slides in 22 px, a slider's knob grows to 1.18 when focused, a pill that changes pops in from 0.6. With reduced motion
  every transform is dropped: only fades (and colors) remain, the ring and the dock jump, the geometry snaps.

### Stream menu preview (debug builds)

The real menu can be drawn without a stream, over a sunset "game scene", with made-up values (`FakeStreamMenuHost` in
`src/debug`; `ShellActivity` hands the intent to `StreamMenuPreview`, which `src/release` replaces by a stub):

```
adb shell am start -n io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity --es preview_screen stream_menu \
  --es preview_page <main|upscaler|bitrate|brightness|more|keys|commands|mouse> --ei preview_focus <tile 0..8> \
  [--ei preview_row <row of the page to focus>] [--ez preview_animate true]
```

The menu starts already open at that page without its entrance; `--ez preview_animate true` plays the entrance, so a
screenshot after about 1.4 s shows the settled result. No profile is needed. Resume or B close the menu; a tap on the
scene opens it again.

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
- **Stream exits.** The player's own Disconnect / Quit game keeps the `StreamExit` portal. A stream that
  ends by itself goes through `StreamOutcome` (no classic error dialog): `Game` ends, opens
  `ShellActivity` on the stream's PC (Home, with the PC selected) with a `StreamResult` in the intent
  extras, and the shell takes it once (`StreamResult.take` removes the extras, so a recreated or
  resumed activity never shows it again; external-display streams open the shell on the device's own
  screen). Three outcomes:
  1. **Failed to start** (a connection stage failed, the decoder cannot start, a shortcut's PC is
     offline/unpaired) and 2. **Connection lost** (terminated with an error) show the **result
     overlay** (`StreamResultLayer`): full-screen glass over Home, a sad face (Phosphor `smiley-sad`,
     `Symbols.SENTIMENT_DISSATISFIED`) in an accent-tinted circle that settles in and floats gently
     (fade only with reduced motion), a short Unbounded title ("Couldn't start the stream", "Connection
     lost", "The PC closed the stream"), one calm Figtree sentence, and two pills: **Try again**
     (primary, focused first: the same game on the same PC through `GameActions.start` →
     `GameLauncher`, so the per-game settings are read again; an offline PC or a game that is gone
     gets a toast) and **Back to library**. A / Enter activate, **B = Back to library**, D-pad
     left/right moves between the pills and **down** reaches **Details** (never open by default), which
     expands the technical text: stage, error code and the former dialogs' hints (permission, timeout,
     firewall ports, blocked network, DRM, HDR). The focus stays inside the overlay; the menu buttons
     do nothing; no confirmation.
  3. **Host closed** (the game exited, the PC ended the session normally): no overlay; Home with the
     toast "The PC ended the stream".

  `describeStreamFailure` (pure, `StreamResult.kt`) maps stage / MoonBridge error code / ports / blocked
  network to title, sentence and details; `StreamResultTest` covers each case. Every start (Home,
  shortcut, `.art`, link) shows the Delos loader: `ShortcutTrampoline` shows the launch scene itself
  (`ShortcutSplash`, the same `LaunchOverlay` Home shows) while it finds and wakes the PC and then
  hands the progress to `StreamLoader`. Shortcut inputs that name no known PC (bad UUID, PC not
  found, unreadable `.art`) end with a toast.
- **Deleting a profile asks nothing.** The profile disappears at once and the next one takes over (the
  first-launch screens return when it was the last). A toast offers **Undo** (`Y` or a tap) for 10 s:
  `ProfileStore` keeps the profile's settings, per-game overrides and control layouts until then and
  restores them in the old place (current again unless another profile was chosen meanwhile). They are
  purged when the 10 s end, or at the next start if Delos was closed meanwhile; a restart never
  resurrects a deleted profile. Another toast replaces the Undo toast; the profile then stays deleted.
- The keyboard editor's **Clear All** hides the keys without asking and the button becomes
  **Restore** until editing ends or keys are added; the layout is saved as before.
- A PC shortcut or `.art` launch of another game while one runs never quits the running game and
  asks nothing: Delos opens that PC's games with a toast naming the running game ("quit it from
  Delos first"). Quitting stays the explicit "Quit X and start" choice in Delos' own game menu
  (`ShortcutTrampoline` cannot tell a pinned shortcut from an intent of another app). `art://launch`
  links keep their security prompt.
- SteamGridDB supplies portrait/wide covers, hero and logo; the API key is device-local. Artwork
  lives in application storage, and hand-picked files survive bulk redownloads. Desktop is manually
  selected. Dashboard logos use the accepted 800 ms slide/fade with no scale; reduced motion shows
  them immediately. Keep the reserved title area and the accepted LIBRARY footer styling.
- Virtual controls use `OscStyle`: thin glass outlines, Figtree capitals, filled pressed state,
  circular face/stick buttons, shoulder/menu pills and a chevron D-pad. The stick-button overlap is
  intentional. Debug info provides a preview without starting a stream.
