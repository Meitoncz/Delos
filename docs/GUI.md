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
- Games are laid out as **one horizontally scrolling row of portrait covers** with the logo, hero background and info above it; the **first game is wide** (landscape SteamGridDB cover). The focused game stays near the left edge and the row fades out at the edges.
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
- The hero image alone uses lens-style shader blur on Android 13+ and a calm, seamless,
  symmetric 3D tilt around its center: a 25 s loop, 15° amplitude, camera distance 2.2× the longer
  side (far edge shifts about 92 px at the extreme on the 2100 px hero area), 2 % overscan (computed: 1.0065 is
  the minimum that keeps all four viewport edges covered) keeps image edges outside the viewport.
  Reduced motion holds the hero still. The foreground animated/procedural background retains
  its existing shader, timing and blending; logos, covers, text and controls do not tilt or blur.
- **Hero scaling** (`ui/HeroGeometry.kt`, pure and unit tested; one geometry for every path that draws the hero: the cached lens
  bitmap, the legacy launch / stream-exit snapshot, the sharp blur-0 hero and the playing animation, so the still → animated fade
  does not jump). A wide hero (SteamGridDB 1920×620 / 3840×1240 / 1600×650, wide covers) is **scaled to fill the width** of the
  2100×1260 hero area and **aligned to the top** (1920×620 → 2100×678 px) instead of being cropped to 5:3, which lost about half of it. Its
  bottom edge **fades softly over 160 px** (a smoothstep, `HeroGeometry.FADE_STOPS`) into an **extension**: a copy of the same image
  stretched to cover the whole area, blurred (40 px), then blended 55 % toward the dark background color (14, 18, 36), so no bar stays
  empty. The user's blur (Appearance → game background), saturate(1.15), the diagonal fade and the tilt with its overscan come on top,
  unchanged. An image whose width-fit height reaches the area's height (aspect 5:3 or less: box art or a cover used as the background)
  fills the area and is cropped as before; the placeholder art is unchanged. The cached bitmap is still 420×252 (the area at 1/5; the
  extension is a 210×126 bitmap of its own, at most 24 kept) and is rendered only on a cache fill; its cache key carries a composition
  version, so bitmaps of an older layout are never reused. The game's colors are still taken from the plain crop, not the darkened
  composition. The live paths draw the extension from that small bitmap and the band at full resolution with the same fade as a mask.
- **Frame cost of the home screen** (the render thread, not the main thread, is the bottleneck: every full-screen offscreen layer whose
  content changes is rendered again on every display frame, and the hero's tilt changes it every frame). With the 120 Hz display the
  rules are: (1) the game background's opacity is **not** a group layer: it multiplies each draw of the hero
  (`CompositingStrategy.ModulateAlpha`), and the fade into the theme color (`alpha .9` gradients) is drawn over the hero at its own
  opacity (the theme color is also what lies under the hero, so the picture is the same); (2) the cross-fade to the next game's
  background is Compose's `Crossfade` (900 ms), which reuses a hero that comes back (focus moving back and forth between two games;
  a hand-made cross-fade kept the superseded copy of the same game playing too, one decoder and set of layers per step, and made
  fast listing janky), and the **animated hero starts only after the game has stayed focused for 500 ms**
  (`AnimatedPlayback.HERO_DWELL_MS`, the still hero shows meanwhile) and ends at once when the focus leaves; (3) the animated background repaints at most 30 times a second while a game background
  is shown (60 otherwise); (4) a tile's focus glow (the blurred `dropShadow`) exists only while the tile is focused or recently was (it
  stays attached 2.5 s after the focus leaves, so focus moving back and forth between two tiles never attaches and detaches it).
  Measured on the Odin with automated RIGHT/LEFT focus changes between two games with animated covers and hero (0.3 s apart): the
  design above (Compose `Crossfade`, 500 ms dwell, low-resolution live hero) holds about 3 % janky frames, p50 6 ms, the same as a
  still hero.
- **Artwork page** (game options → Artwork, `ArtworkScreen`; approved prototype variant B): no side column. Header and tab pills
  (`SelectionPillHost`, touch only, L1 / R1) over a toolbar with the **SteamGridDB game chip** (name, year, id, image count of the tab),
  **Search for another name** (X) and **Filter** (Y, with a count badge of the active filters); under it the result line
  ("10 of 41 covers", the "of N" only when the unfiltered list is loaded) and the **active filters as removable chips** plus Reset
  (they share one `RowGlideHost` plate, 520 ms); then the full-width grid (7 covers, 4 wide covers, 3 backgrounds, 5 logos per row;
  first tile "From your PC" / "Name only"; `focusEffects` tile focus; "In use" badge on the tile in use; a play badge "Animated",
  icon only on covers) and a strip about the **focused** image (style, Static / Animated, In use, size, file format, score and author,
  date; only what SteamGridDB sent). Initial focus is the tile in use (the first tile when none is). Grid scroll is `FocusScroller`
  with the margin bring-into-view, the entrance is the staggered rise of the first eight tiles.
  **Search** opens the shared text dialog (then the context menu of the games found; choosing one re-matches the artwork).
  **Filter sheet:** Y or the Filter pill drops it down under the toolbar (`popEnter` / `popExit` from the pill, dimmed page, the
  grid stays visible and updates live); A sets an option, X resets, B or Y closes and focus returns to where it was (the focus is held
  inside while it is open). Filters: **Type** (All / Static / Animated; not on logos), **Style** (covers and wide covers: alternate,
  blurred, white logo, material, no logo; backgrounds: alternate, blurred, material; logos: official, white, black, custom),
  **Dimensions** (covers 600×900 / 660×930 / 342×482, wide covers 920×430 / 460×215, backgrounds 1920×620 / 3840×1240 / 1600×650;
  none for logos), **Sort** (highest score, newest, oldest). NSFW and humor images are never requested and never shown. Type, style
  and dimensions are SteamGridDB query parameters (`types`, `styles`, `dimensions`, `mimes`); "All" is two requests, still plus
  animated, because the answers do not mark animation; sorting is client side (no per-option counts: they would need extra
  requests). Filters are kept per tab in memory and reset when the screen is left. Logic: `data/ArtworkFilters.kt`
  (`ArtFilterRules`, `ArtworkInfo`), parsing: `SgdbImage.parse`.
- **Animated artwork:** animated SteamGridDB images are **animated WebP only** (WebM and GIF are not requested; a WebM entry is skipped).
  Choosing an image stores the **downloaded bytes as they are** for every kind (cover, wide cover, hero, logo: the animation stays in
  the artwork store's file, nothing is re-encoded or downscaled); its first frame is what the still paths decode (BitmapFactory), so
  everything that does not animate keeps working and the still is always the animation's first frame. `ArtworkStore.animatedFile`
  answers with that file when it is an animated WebP (header check, kept per file size and date). There is **no setting**:
  - *Hero:* the home screen's game background plays the chosen animated hero automatically (`GameArt.animated`, `HeroLive` in
    `ui/Backdrop.kt`) through `ImageDecoder` / `AnimatedImageDrawable` (frames decoded on demand, no wider than they are drawn and at most
    1050 px, one frame in memory, canvas ≤ 100 million pixels), on Android 12+ (below it the first frame stays). **Performance
    pipeline** (the blur makes the full 2100 × 1260 resolution pointless, and a `RenderEffect` on a rotating full-size layer is
    re-run by the render thread on every display frame, which measured at 48 % janky frames on the Odin): the band, its soft
    bottom edge, the extension, the platform blur (`RenderEffect`, σ scaled with the layer, saturate 1.15) and the diagonal fade are
    drawn into a **small offscreen layer** (`HeroGeometry.liveScale`: a quarter of the area from a blur of 8 stage px up, half from 4,
    the full area for a sharper hero; σ never below 2 layer pixels), which HWUI keeps as a texture and renders again **only when a
    new animation frame arrives** (the animation's own rate). Every display frame (120 Hz) the tilt just draws that texture,
    scaled up and rotated, with no effect, no mask layer and no recomposition (the orbit is read in the layer block). The diagonal
    fade is part of the layer, so it tilts with the image like the cached still hero's. The live hero fades in over the still
    lens-blurred hero (the lens shader renders a cached bitmap and cannot run per frame); the still then leaves composition (with its
    second tilt clock) until the animation stops.
    It plays only while the hero is visible and the window is resumed (`repeatOnLifecycle`), and **not** during the launch loader / portal, in the
    background, in a stream (a separate resumed activity), or with reduced motion or the system's animator scale 0 (then the first
    frame, like the tilt). Pure rules: `AnimatedPlayback.shouldPlay`.
  - *Home screen tiles, logo and game page:* the chosen animated **cover** (portrait tiles), **wide cover** (the wide first tile) and
    **logo** loop wherever they show on the dashboard: the row tiles (`GameTile`) and the logo in the game info. The animation is an `AnimatedArtLayer` over the still image (which keeps showing until the decoder is
    ready; the animation then fades in over it with the app's fade), drawn with the same crop (`ContentScale.Crop` cover, fitted and
    start-aligned logo) and the same color filter (the idle filter of an unfocused cover) as the still. **Only visible tiles play**
    (the keys of the row's `visibleItemsInfo`, no count cap); a tile that scrolls out releases its decoder and holds the
    still again. The frames are **decoded at the displayed size** (tile width × height at its focused scale, a logo 900 × 180 stage px;
    `AnimatedPlayback.decodeSize` with the view's size: cover or fit, never enlarged), never the full canvas, from the file on disk (no
    heap copy). The home screen has **one `AnimationTicker`** for all of it (`DashboardArt` in `screens/DashboardArt.kt`); it runs only
    while something may play. Nothing plays (still frames) while a dialog, the side menu or the filter sheet covers the screen (a **context menu does not stop it**: the covers stay visible beside its card, dimmed, and keep playing without a restart; `layerStopsDashboardArt`), during the launch
    loader, the portal, the stream exit or its result, in a stream or the background, with reduced motion or the system's animations off
    (`rememberArtworkMotion`, `AnimatedPlayback.shouldPlay`). The launch loader's card and the stream exit keep the
    first frame.
  - *Picker tiles:* every **visible** animated tile loops (no count cap); its animation (the animated thumbnail, else the full WebP) is
    fetched once into a file in the app's cache (never the heap) and decoded from there at 520 px
    across at most; off-screen tiles, all tiles while the filter sheet or a dialog covers the grid, in the background, or with reduced
    motion show the first frame and hold no decoder. All tiles share **one `AnimationTicker`**: the drawables report new frames, the
    ticker turns them into one redraw per frame, and it does not run while nothing plays.
  - Not animated: the launch loader's card and the stream exit (first frame only), animated GIF/WebM files.
  Older Android versions retain the supported cached hero rendering.
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
  The names shown come from `res/values/strings_tags.xml` by IGDB id (`genreName`, `themeName`), so they follow the app
  language; the Steam tag names and the ranking stay English data (see `docs/LOCALIZATION.md`).
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
support** (`All`, `Native HDR`, `SDR`, `HDR/SDR unverified`, with the sparkle, dim-sun and sealed-question-mark icons of
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
- **List focus motion** (`RowGlideHost` / `rowGlideTarget`, or `focusEffects(glide = true)`): ordinary lists share one moving plate over **520 ms** (`Motion.ROW_FOCUS`), not independently fading focus layers. This applies to settings and per-game/debug rows, side-menu quick settings, context menus (including home Options and device/profile/artwork/metadata choices), settings-search results, filter chips, first-run avatar choices, and stream-popover rows and key/command pills. Each host owns its local coordinates, scroll viewport, clipping and palette; fill, ring, corner, scale and optional shadow decoration match that surface. Context menus have an accent fill (17 %) with a 3 px ring and a soft glow, red on destructive rows; filters have only a ring; stream rows keep their inset ring/shadow and add no outer glow. The plate tracks current target bounds plus a decaying carry on both axes, so scrolling and unequal item sizes do not restart its travel. Frame state is read during drawing; shadow paths/paint are reused. First focus and reduced motion place it directly on the target. Existing cover/artwork-grid motion, circle-menu glide, dock ring, top/settings pills, keyboard key focus and toast motion remain separate; isolated form actions and small Save/Cancel groups retain their existing treatment.
- **Context menu** (`ui/ContextMenuLayer.kt`, placement and submenu logic in `ui/ContextMenuPlacement.kt`; variant A of the approved prototype, issue #78, `docs/prototype/delos-gui.html` shows it): an anchored **glass card** (corner 34; the prototype's `.cm-glass`: the screen under it blurred **46** and saturated **1.6** (Haze `colorFilter` + `tint`), a tint rgba(34, 44, 84, **.50**), a white sheen from the top (13 % fading out at 52 %), a 1 px rim at 11 % white, a 1.5 px lit top edge at 26 % white, shadow `0 40 90 -20` black 66 %; 470..640 wide, as wide as its longest row) with the prototype's **caret** (72 wide, 30 out of the card, rounded tip, plain glass like the stream popover's), over a light 26 % scrim (no blur); the covers beside the card dim (saturate .6, brightness .5) and the info block above them to 40 %, the focused cover keeps its colors. The element a menu belongs to **keeps its focus look** (ring, scale, glow, pulse, plate: `focusEffects` draws `focused || held`, and `onLookChange` tells callers that draw their own colors) while its menu is open, although the keyboard focus is in the menu: `FocusTracker.hold()` is called when the menu opens, the hold ends when the element has the focus again (no gap, no second animation) and `FocusTracker.release()` is the safety net 150 ms after the menu closed; `onFocusChange`, the focus sounds and the hero / info follow the real focus only. The anchor is the element's bounds as they are lit (the focused cover's 5.5 % growth included: a centered cover grows on all sides, the wide first cover only to the left, so its right edge stays), the focus ring adds 10 stage pixels around the cover, so the caret's tip sits **5** stage pixels inside the cover's edge (`TIP_GAP = -5`): half of the 30 px caret is over the cover with its ring, for both. The gap between covers is 44 stage pixels (`ROW_GAP`; the wide cover is three covers plus two gaps wide). It opens beside the element (to its right, caret on the card's left; flipped to its left when it would leave the stage) vertically centered on it, or **below** it with the caret on top when the menu says so (`ContextMenu.below`: the PC circles, the start screen's profile chip; above it with the caret on the bottom when it does not fit below). The card keeps the stream popover's 120 px margin from the stage's top and bottom (so it is at most 840 tall and scrolls inside) and 60 px from its sides, and the caret keeps 72 px from the card's corners (`placeContextMenu`, unit tested). The **header** shows a 52 x 78 rounded tile (corner 14) in the game's **main color** (the first of the cover's `GameColors`, drawn as hsl(hue, saturation x 80 %, 52 %) at 85 % with a faint white sheen from the top; the placeholder art's hue while the game has no artwork; the theme's accent while the game colors are off or the cover is grey. Every `GameTile` hands its cover to `HeroBitmaps.analyzeCover` as soon as it is decoded, off the main thread, once per image (`CoverColorCache`, in memory), so the color is there from the menu's first frame; in the short time before it is, `HeaderTint.Pending` shows the neutral chip of the submenu's back button (white at 10 %), which fades to the color once over `Motion.FADE`, never the accent first) holding a filled controller glyph (near black on a light color, white on a dark one), a small status line (a green dot while the game runs), the game's name as the title and one line of facts (Last played, another game running, Custom settings, Hidden); other menus pass no `MenuHeader` and show just the title. **Rows** are 64 px with the icon, the label and, for a submenu, a chevron; there is no button glyph on a row (hints stay in the hint strip: A Select, B Close / Back). **Destructive rows** (`MenuItem.danger`: Quit game, Quit X and start, Remove from Delos) are red and the shared plate (`RowGlideHost`, 520 ms) turns red while one is focused: choosing them is the confirmation, there are no dialogs. **Submenus** (`MenuItem.children`, path in `ShellState.menuPath`) swap inside the same card (rows stagger in again, the card's height glides 520 ms, the plate starts on the first row); B goes back one level (focus returns to the row that opened it), and on touch the header's back chip does. The items and their order are whatever the caller builds (home game menu, PC and device menus, profiles, pair, artwork and metadata lists, a setting's reset); the opening point of the pop is the caret's tip. Scrolling, focus and containment are unchanged: `InitialFocus` on the first row, `focusTrap`, `rememberFocusedListNavigation` (a held D-pad stops at the first / last row), `rememberMarginBringIntoView` with `FocusScroller` / `ListScrollDriver` (600 / 950 ms), 34 px fades at the card's list edges while it scrolls, focus back on the element when the menu closes, reduced motion fades only.
- **List containment**: eager ordered lists reuse `FocusedListNavigation` so held navigation cannot outrun layout or escape through an uncomposed neighbour. Closed menus, side-menu lists and search results stop even on fresh input at their first/last row; regular settings retain their deliberate fresh-Up exit. A horizontal host can use the same ordered guard on Left/Right. Filter and key/command chip rows never wrap: each is one row (`ScrollRow`) that scrolls sideways with an edge fade when its chips do not fit, and the chip rows of the filter modal keep spatial two-dimensional navigation and their existing outer containment instead of being flattened into a linear order. Context menus now bound long lists to the available height and use the same animated margin-scroll driver (**600/950 ms**); search results eagerly compose their bounded catalog (at most 110 rows).
- **Settings list scroll** (`MarginBringIntoView` / `FocusScroller` in `ui/Stage.kt`, `ListScrollMotion` and `ListScrollDriver` in `ui/Motion.kt`; shared by settings, per-game settings, artwork, debug info, filter, stream-menu popover and `Layers` lists): moving focus scrolls only when the row would leave the safe margin. Foundation 1.12 ignores the old custom `BringIntoViewSpec.scrollAnimationSpec`, so the spec measures and returns zero framework distance while `FocusScroller` drives the scroll. Its critically damped spring settles to 2% in **600 ms** for a row-sized step (90 px or less), growing to **950 ms** from 700 px. Retargeting adds distance to what remains and preserves velocity; fast nearby retargets brake to avoid overshoot. A drag or list endpoint stops it; reduced motion scrolls immediately.
- **Settings focus order** (`FocusedListNavigation.kt` / `FocusedListPolicy.kt`): global settings, per-game settings and debug info use eager `Column.verticalScroll` lists, not viewport-limited lazy focus targets. The largest global tab has 41 visible rows (71 even with all hidden phone rows restored), so all neighbours remain composed and measurable while visual scrolling catches up. Native Up/Down events follow keyed row order instead of spatial search through animated rows. Holding Up reaches the first row and stops; only a **fresh Up press already on the first row** may leave toward the top menu. Down stops at the last row. Explicit neighbouring focus requesters and a group exit guard also prevent semantic focus moves escaping vertically. Dynamic debug rows retain stable requesters; collapsing/removing the focused row restores its nearest surviving predecessor. Tab selection, L1/R1 and row Left/Right adjustments are unchanged.
- **Settings row focus** (`SettingRowList` / `SettingRow` in `ui/Rows.kt`): the accepted shared plate remains unchanged: palette `surface2` translucent fill without Haze blur, accent ring (3 px, no gap, corner 24, scale 1.02), and glow (20 / 50 / −24). Hosted rows draw **no local glass, ring or glow**. Side-menu settings now use this same host rather than separate frosted focus layers. Row scale, icon and arrow/value colours retain their 520 ms transitions; press squeeze (120 ms), adjustments and value changes remain immediate. Entry fade stays 120 ms; a 70 ms gap before the 280 ms exit fade prevents blinking between rows. The host's size and `fadeEdges` apply to the complete plate.
- **Impact chips** (issue #40, `ui/ImpactChips.kt`, `SettingRow(impact = …)`; approved prototype variant B, layout revised after trying it on the Odin: chips beside the name covered it): the cost-bearing settings rows (video resolution, frame rate, bitrate, codec, HDR, PyroWave chroma, frame pacing, upscaler on/off, mode, sharpness, supersampling) carry three tiny chips on **their own line above the title**, left-aligned to the title's x, so each such row reads icon | chips / title / secondary line (description, or the CUSTOM badge and Profile value on the per-game screen) | value, and the title keeps the full width. A chip is a glyph (25 tpx: GPU `memory`, Latency timer, Network wifi) and a 3-step bar indicator (bars 6 px wide, 9 / 15 / 21 px high, 26 px between chips). Low fills one bar in the palette's secondary colour, Medium two in the main colour, High three in the warm red (`LowRed` of the battery); a level of none dims its chip to 40 %. **Row height**: a plain row is 92 px; label (26 tpx) + a second line (19 tpx) + the chips line (about 28 px) do not fit in it, so **every row with chips reserves 120 px** (`IMPACT_ROW_MIN_HEIGHT` in `ui/Rows.kt`, also when it has no second line, so all cost-bearing rows of a list are one height; larger text scales grow it as they do any row). Rows without chips are unchanged. Scrolling and the gliding plate measure rows, so they need no change. While such a row is focused, the description panel shows a **legend** under the scrolling help (outside the scroll area): the three glyphs with their names and "Cost of the current value: 1 bar low, 2 medium, 3 high". The levels come from the pure `impact(key, values)` (`settings/SettingsImpact.kt`) and follow the current values live; see docs/SETTINGS.md.
- **Settings list preview:** `--es preview_screen settings_list [--ez preview_auto true]` renders 40 real rows with the shared focus tracker, eager focus order, scroll driver and plate. Automatic playback is finite: 12 slow downward steps (650 ms), three fast steps (150 ms), then **40 Up key-downs at 50 ms** with increasing native repeat counts, one key-up, and an assertion that focus ended on the first row. It never forces focus back to the first row after the hold.
- **Tabs are touch only** (settings, per-game settings, artwork; `TabPill` in `screens/SettingsScreen.kt`): they are never focusable, so the D-pad and the stick pass over them (Up from the first row does not reach the tab row, Left/Right inside rows is unchanged) and they have no focus ring. A tap selects a tab (select cue, a short squeeze); **L1 / R1** step through the tabs as before (the L1 / R1 labels beside the row stay). The selected state is shown only by the pill.
- **Tab pill:** the accent pill (`colors.accent`, the main color of the focus ring and filled focus) behind the selected tab (`SelectionPillHost` in `ui/SelectionPill.kt`; the selected tab's icon (filled) and label are dark, `colors.onAccent`, and fade to `colors.text2` when another tab is selected) glides to the newly selected tab with the geometry spring (`GEO_STIFFNESS`/`GEO_DAMPING`, its leading edge 1.5× stiffer and trailing edge 0.75× so it stretches and squashes with the gap and follows the new tab's width), wraps across the bar, starts in place on first composition and jumps with reduced motion; the colors cross-fade over `FADE`. Debug preview: `--es preview_screen settings_tabs [--ez preview_auto true]` (L1 / R1 and taps only).
- **Top menu pill** (the capsule of round Settings / Profile / Devices buttons in the status bar, `TopNav` in `ui/Bars.kt`): the buttons stay focusable in the same order with the same scale (1.12), glow and accent fill, and the focus is **cyclic** like the stream menu's tile bar (Right on the last button goes to the first, Left on the first to the last, through `focusProperties` on the first and last button; with one button there is no wrap; Up and Down are unchanged). Only while the focus moves from one of these buttons to another, wrap-around included (D-pad, stick or touch; a gain within 100 ms of a loss, `TransientPillMotion`), a transient accent pill (`TransientPillHost` / `TransientPill` in `ui/SelectionPill.kt`, sharing `PillGeometry` and the edge springs with the tab pill) glides from the old button to the new one. It is one shape all the way: it starts as the old button looks focused (the circle scaled by 1.12), stretches between the two (leading edge 1.5× stiffer, trailing 0.75×) and arrives at the new button's focused look, never smaller than it. Once both edges are within 2 px and the button's own focus scale has settled (`FOCUS` after the gain) the button's own accent fill fades in over the pill (`HANDOVER_MS` 140 ms, same bounds and colour, so no pop) and the pill is removed; the button's glow and scale are untouched, so nothing pulses twice. Icon colours cross-fade over `FADE`. Nothing extra is drawn at rest, when the focus enters the menu from the game row or leaves it, or with reduced motion. Debug preview: `--es preview_screen top_menu [--ez preview_auto true]` (auto: eight steps, 1.2 s apart: right, right, right (wraps), left (wraps), left, left, right, right, then it rests).
- **Custom-settings badge** (`GameTile` in `HomeScreen.kt`): a game whose current profile stores at least one real per-game setting (a key of `GameSettingsStore.KEYS`, the same notion as the "N custom settings" chip; private codec bitrate slots alone do not count) shows a small round icon in its cover's **top-right** corner: the filled `Symbols.TUNE` glyph (30 tpx, `accent3OnDark`) on the dark translucent disc of the artwork busy spinner (56 px, `#8C050A14`, 16 px from the corner), drawn above the idle-filtered art, in the wide first tile and the normal covers alike, scaling with the tile. TalkBack reads "Custom settings for this game". While the artwork busy spinner shows, it takes the corner and the icon waits. The top-left Running / Last played badge is unaffected. `HomeScreen` computes the set of custom app ids once per library, profile or stored-settings change (`rememberCustomGames`: a `SharedPreferences` change listener on the profile's settings bumps a version, `GameSettingsStore.customAppIds` reads the map once), and passes a Boolean to each tile.
- **Per-game settings** (`GameSettingsScreen.kt`, variant B "hero banner" of the approved prototype; details, focus order and reset in [PER_GAME_SETTINGS.md](PER_GAME_SETTINGS.md)): no cover. A **hero banner** (`GameBanner`, 290 stage px tall, `GameSettingsLayout`) draws the game's artwork like the app's hero (hero, else cover, else the PC's art; `HeroBitmaps.renderLens` with the Appearance hero blur, the same cached bitmap as the dashboard background, its stage-sized top; the placeholder art while there is none), a scrim from the left and from the top for legibility, and fades into the page (`fadeMask`, opaque to 35 %). On it, below the persistent status bar (which owns y 0..104: the DELOS logo at the left, Wi-Fi / battery / clock at the right), the header block at y 106..198 (the name has a 1.35 line height so descenders are never cut): the eyebrow `GAME SETTINGS · <PC>`, the game's name (Unbounded 46), the shared `Chip` ("N custom settings" / "Follows your profile") and the **Reset all** `PillButton`. The Stream / Display `SelectionPillHost` tabs are at y 206..272, exactly where the global settings' tabs are, with the number of custom settings of each tab as a quiet accent3 count (`TabPill(badge)`); the shared `SettingRowList` (gliding plate, `FocusScroller`, entrance rise) starts at y 296 (14 px top fade, 30 px padding inside, so the first row begins at y 326 like the global list's and its ring and scale are fully visible) and is 1080 wide, rows from x 150 as the title; with the 120 px rows of the impact chips about five rows fit (688 px list height minus the 70 px bottom fade); the description panel starts at y 312 like the global one; the description panel on the right is the global one (`SettingsPanelFrame`: 520 wide, 44 padding, 34 radius, 120 px icon) with a source box. A custom row carries the override marker of `SettingRow` (`RowOverride`): accent3 tint with a faint ring, a **CUSTOM** badge and "Profile: <value>"; X resets it directly (a compact pill "Reset" + X glyph fades/pops in on the focused row; it takes **no layout width**: it is drawn over the free space left of the value, so the title column never changes width and the title stays on one line; the long wording "Reset to profile value" stays in the context menu, the hint bar and the accessibility label; touch: a 52 px round button on every custom row, fade only with reduced motion). Reset and Reset all toast with **Undo** (Y or a tap) and never ask. Debug preview with fixed data: `--es preview_screen game_settings [--ez preview_touch true] [--ez preview_auto true]`.

- **Link guidance** (every codec; PyroWave also gets the in-stream hint and Link block, [STREAM_QUALITY.md](STREAM_QUALITY.md#pyrowave-link-guidance-18); `shell/screens/LinkGuidanceRows.kt`, no new visual language): the Stream tab's bitrate row hint reads `Recommended: 225 Mbps · link carries up to 665 Mbps` (the reason: not tested, link carries, the PC's link, drops frames). For an ordinary codec the rows appear when the PC can test or a result exists, and two ordinary action rows follow it with the same plate: **Test link** (its line says `Tested yesterday on Wi-Fi`, `Not tested on Wi-Fi yet` or why the PC cannot test; A starts the test) and **Use recommended bitrate** (fills the bitrate on A, with a toast; disabled while the bitrate already equals it). The test uses the existing **portal** (`Portal`, cancellable like a PC wake-up: B stops it, the hint bar says Cancel): orb with the network-check icon, "Testing the link to <PC>" and a line `Testing 250 Mbps · stay on Wi-Fi · B cancels`; the result is the ordinary toast (success, or the reason in one sentence) and the focus returns to the row that started it. It is only reachable from Settings, never over a stream, and never starts by itself. The per-game screen shows the same hint on its bitrate row and in its help, without Test link.

## Controls

- Primary input: **D-pad or left stick + A, B, X, Y**.
- The **two menu buttons at the top left and top right** of the handheld are used in the UI: the **left one (View/Select)** slides the **side menu** in from the left, the **right one (Start)** opens the **context menu** for the focused item. Only elements with something to offer have one (game tiles, unpaired PCs on the pairing screen, PC circles on the start screen, customised rows in game settings); there is no generic fallback menu, and the **Options** hint shows only while the focused element has a menu. Where A already opens the same menu (the profile chip, PC circles on Devices) Options does nothing. The hints show the buttons as they look on the device: on AYN handhelds (Odin, Thor) a square (left) and a play arrow (right).
- Navigation must be **intuitive and direct**: pressing a direction takes you where you want to go, ideally in **one press** – never left, down, down just to reach something.

### Text input

**Text is typed in one place: the dialog (`DialogLayer`, a `DialogSpec` with `DialogField`s)**, for everything from the pairing PIN and the PC's name to the API keys and a profile's name. The profile pages (first run "Who's playing?", new and edit profile) do not have an input: they show the name in a labelled `ValuePill`, and **Change name** (D-pad order: avatars, Change name, Create / Save) opens the very same dialog as Rename profile on the profile screen (`openProfileNameDialog`, title "Profile name"; a blank name gets the same toast). Choosing an avatar moves the focus to Change name, accepting the dialog shows the new name and moves the focus on to Create / Save, and Create without a name opens the dialog. The one exception is the settings search, which has its own letter pad next to the field. Every text field in the shell is the one `TextFieldPill` (dialogs and the settings search), built on the `TextFieldState` flavour of `BasicTextField` with `showKeyboardOnFocus` off. **Android's keyboard opens only when the field is activated**: a tap, or A / the D-pad centre key. Moving the focus onto the field (D-pad, stick, or code such as the dialog's first field when it opens) never opens it, and it closes when the focus leaves. After Back / B closes the keyboard the field keeps focus and the keyboard stays closed until the next tap or A. Do not use the `value: String` overload of `BasicTextField` or any other text widget: it starts the input method on every focus, which shows the keyboard. `ShellActivity` also has `windowSoftInputMode="stateHidden|adjustResize"`: `stateHidden` so the keyboard does not come back by itself when the window regains focus, `adjustResize` so the keyboard's inset reaches the app on every API level (the shell is edge to edge, so the window itself is never resized). The stream's own keyboards (Full keyboard, More → Open Android keyboard) are separate and unchanged.

**The keyboard never covers the field being edited: the editing field moves to the top quarter of the screen, regardless of the keyboard.** One mechanism, `ImeAvoidance` in `shell/ui/ImeAvoidance.kt` (no screen has its own `imePadding`). **Only what is marked moves**: `Modifier.moveAboveIme()` is on the dialog column in `DialogLayer` (card, shadow and blur; the scrim, the status bar, the background and the bars stay put). A text field outside such a container (the settings search, which is already high) does not move anything. `TextFieldPill` is *engaged* from A / the D-pad centre key or a tap until the focus leaves it. While it is engaged **and the keyboard is visible**, its mover glides up so the field's bottom edge is at most `EDIT_BAND_BOTTOM_FRACTION` = 0.25 of the window height (0.20 in a portrait window) from the top, with the shared `Motion.GEO_*` spring (a snap with reduced motion); a field already in the band does not move it. The moment the keyboard is hidden (SwiftKey's chevron, Back) the mover glides back to its place, the field keeps the focus, and A or a tap opens the keyboard again and the mover glides up again. "Visible" is the system's flag (`rootWindowInsets.isVisible(ime())`) or any measured keyboard height from the sources below: the flag is reliable where the height is not (the Odin's SwiftKey reports 111 px while it covers 58% of the screen, but the flag follows the keyboard). The band rule never uses the height, because keyboards can be resized, floated, split or switched at any moment and may live in a transparent full-screen window. What moves keeps `TOP_MARGIN` = 48 stage px from the window's top edge (a dialog card never touches or crosses it), and the field wins over that margin: it must end up at or above `EDIT_FLOOR_FRACTION` = 0.40 of the window height in any case, so the shift is `min(band, max(moverTop - TOP_MARGIN, fieldBottom - 0.40 * H))`. On the Odin (1080 px): the Rename profile dialog (card top 324, field 514..598) moves 276 px (card 48 px below the edge, field bottom at 322). A dialog taller than the free area lets its top leave the screen, because the field wins; the buttons may end up behind the keyboard (its Done calls the same `onDone`, and D-pad Down leaves the field and closes it). The keyboard is still measured (Compose's `WindowInsets.ime`, the root window insets, the visible display frame when it lost more than 15% of the window; the largest wins) and the shift is the larger of the band's and the measured one, so a keyboard that is measured to cover more can only push the field higher (that part is capped by the same margin, except for what keeps the field itself above the keyboard); the dialog card is brought in with the field as far as the measured keyboard asks. A layout listener and a 60 ms poll while a field has focus catch a keyboard that shows without an inset event. Places are window pixels measured against the window as it is now, with the mover's own offset left out (the shift is a layout offset, so the node before it keeps its place), so a window that `adjustResize` really shrank is never shifted twice; the stage scale only enters in the margin. Debug builds log every change under the tag `DelosIme` (`adb logcat -s DelosIme`: inset and its visible flag, compose, visible frame, keyboard, engaged, visible, band, window size and whether it was resized, the field's bounds and live top, the shift, `softInputMode`). A new text field needs nothing; new text entry is a new `DialogSpec`. It lives in `Shell` only, so the stream, the stream menu and `ExternalDisplayControlActivity` are untouched. The arithmetic is `ImeShift` (`ImeShiftTest`).

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
  element, 560 ms with a slight overshoot, `Circles` uses it); `RowGlideHost` + `Modifier.rowGlideTarget` / `SettingRowList` (the same for
  a **scrolling list of settings rows**, 520 ms, see "Settings row focus"); `MotionSwitch` (knob springs across, gradient track fades in);
  `ChoiceMotion` with `valueSlide` and `chevronBump` (**value slide** of choice rows); `animateKnobScale` (a focused slider's knob
  grows to 1.18); `popEnter` / `popExit` (grow from an anchor), `dialogEnter` / `dialogExit`, `sideEnter` / `sideExit` and
  `screenTransition` (the central crossfade of `Shell`: the new screen fades in while it settles from a 1.035 zoom, the old one
  leaves in 240 ms).
- **Where.** *Stream menu*: unchanged, built from the same blocks. *Start, Devices, Profile, pairing*: `CenterColumn` is an
  `EntranceScope`, so the headline and then every PC circle rise in turn; one gold ring glides between the circles; the check badge
  of the default PC pops in when the choice changes. *Home*: the first covers rise in turn (covers a scroll brings in later do not
  animate), the empty and offline messages rise; every cover squeezes when activated. *Settings, game settings, side menu*:
  `SettingRow` rises in turn, one ring glides between the rows and their glass fades with the focus (the side menu's rows fade their own), a choice slides its new value in with the chevron nudge,
  a slider's knob grows on focus, the switch is the stream menu's. *Context menus* grow out of their caret's tip (`popEnter` / `popExit`),
  the header and rows rise in turn, the plate glides from row to row. *Side menu*: slides in over 560 ms,
  header and rows rise in turn, leaves in 240 ms. *Dialogs and the filter modal*: settle in from 0.96, title, note, fields and
  buttons rise in turn, leave in 240 ms. *Toasts* (the console notification): see [Toasts](#toasts).

### Toasts

Every app message is the same **console notification** (approved prototype B: `build/toast-proto`, local scratch), in the shell, over the stream and
on the external display: `ui/ToastHost.kt`, the one composable, with the pure logic in `ui/ToastLogic.kt`. It sits at the **top right**, 112 stage
pixels from the top (below the shell's status bar) and 44 from the right edge. Over a stream it covers the game's corner (a mini-map, the
stats strip); the user accepted that. The card is flat and opaque (`#17223C` → `#0E1628`, radius 20, a faint inset ring), so it stays readable over a
white game frame; it has **no blur** (the stream is a `SurfaceView`).

- **Kinds**: `INFO` (the palette's cyan, `accent2`), `SUCCESS` (mint), `WARNING` (orange), `ERROR` (coral) and `ACTION` (the palette's indigo, light), each a solid
  icon block with its icon (info, check circle, warning triangle, exclamation, trash), the word **INFO / DONE / WARNING / ERROR / UNDO** above the text and the
  kind colour on the glow and the block. Mint, orange and coral are fixed semantic colours; the others follow the palette. A caller passes the kind
  (`ShellState.showToast(…, kind = ToastKind.ERROR)`, `DelosToast.show(context, text, DelosToast.LONG, ToastKind.WARNING)`); without one it is read from the
  icon (`Symbols.ERROR` / `WIFI_OFF` / `LINK_OFF` → error, `CHECK_CIRCLE` / `CHECK` → success, `WARNING` → warning, everything else → information; `toastKindFor`),
  and a toast that carries an action is always `ACTION`. A toast without an icon of its own shows its kind's.
- **Text**: Figtree 600 at 25 × interface scale (100 / 115 / 130 %), **two lines at most, then an ellipsis** (no marquee: a toast is never focused, and a
  scrolling line cannot be stopped). The text is at most `min(560, stage width − 360) × scale` wide and the card is as wide as the widest line
  (measured once per toast). Everything on the card scales with the interface scale; 16:9, 21:9 and 4:3 only change the stage width.
- **Undo**: a toast with an action shows the gold chip with the white `Y` disc and the label (like the hint glyphs). **Y** on the controller
  (`GlobalKeys` → `ShellState.runToastAction`) or a tap runs it, as before; the chip squeezes for `PRESS_HOLD` before the card leaves. It exists only in the shell: the
  stream and controller windows host the card without any input.
- **Time** (`ToastTiming`, one place): the stream's hints are 4 s (short) and 6.5 s (long), a shell toast 4.5 s, one with an action 10 s. The **gold line** along the
  bottom edge (one 4 px bar, no track, no glow) is the remaining time: it draws in (`EaseOut` 420 ms from +280) and then drains.
- **Motion** (the numbers are in `Motion` and `ToastTiming`): the card **springs in** from outside the right edge (damping ratio 0.62, stiffness 150: it first reaches its place after
  233 ms, overshoots by 8.4 % of the travel at 327 ms and is within 1 % after 627 ms, with a single overshoot) while it grows from 94 % to 100 % around its right edge; it fades over
  `FADE` (280). Everything else is timed against the landing (`LANDING_MS` 230): the icon block pops from `POP_FROM_PILL` with a 50° turn (`PILL_POP`, `EaseSpring`) at +130, the word, the
  text and the chip rise `RISE` one `STAGGER_ROW` after the other from +140 (`ROW_IN`), a soft kind-coloured halo (peak 45 % at the edge, reach 36 px) flashes at +230 and settles at 30 % of
  its peak (`Ease`, 900 ms), one light sweep crosses the card (`EaseOut`, 620 ms from +250) and the timer line draws in from +280. It leaves 72 px to the right and fades in `EXIT`
  (240 ms, `In`). **A new toast replaces the one on the stage, never a queue**: the old card leaves at once and the new one enters `ROW_DELAY` (120 ms) later, so the two cross; a toast
  that arrives in that gap replaces the waiting one (it never shows). **Reduced motion**: only the fades remain (no slide, scale, rise, pop, sweep).
- **Cost**: one clock ticks only while a toast is on the stage (an idle host draws and invalidates nothing); every animated value is read in the draw phase
  (`graphicsLayer` / `drawBehind`), nothing recomposes per frame. The halo, the drop shadow (peak 30 %, reach 44 px) and the sweep are many-stop eased gradients (four edge strips
  and four corner quadrants per halo, a smoothstep falloff), built once per toast and faded by the `alpha` of the draw call: smooth at any opacity, no blur, no `Path`, no per-frame allocation.
- **Accessibility**: the newest card is a live region (polite, assertive for an error) with the kind word and the text (and the Undo label) as its description, so
  TalkBack announces it although the card can never take focus, touch or key input.
- **Hosts**: `DelosToast` routes a message to the shell's own state or to a non-interactive decor overlay (`DelosToastOverlay`) in the stream and external-display
  controller windows; the overlay rejects touch, key and generic-motion dispatch, blocks descendant focus, is padded by the display cutout and sits below the stream-menu dialog.
  Hosts are weakly registered for their Activity lifecycle, including pending initialization messages; service/helper messages use the visible foreground host. The
  only system-toast fallback is when no Delos host is visible (`DelosToast`). Closing the separate stream-menu dialog does not remove the stream toast host or discard a result
  delivered after stream focus returns. **The windows that blur or cover the stream** (the stream menu, the exit) would blur the decor overlay behind them, so each gets a second host as the
  topmost layer of its own window (`DelosToastOverlay.coverView`; above the tiles, the popover and the statistics strip, sharp, same non-interactive rules): both hosts draw one shared `ToastStage`
  (slots, start time, clock), only the newest covering window's host is active (`ToastCovers`; it takes over after its first drawn frame, the stream window's host takes over again when it is gone), so the
  toast carries on without restarting. The controller window has no blurring window.
- **Preview** (debug builds, `ToastPreview` in `src/debug`): `adb shell am start -n io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity --es preview_screen toast --es preview_kind <info|success|warning|error|action> [--ei preview_double 1] [--ei preview_long 1]`
  shows the toast on the drawn game scene (`double`: a second toast 350 ms later, to watch the hand-over; `long`: a message that needs the ellipsis); a tap on the scene or `Y` shows it
  again, and `Y` is Undo on the `action` toast.

### Problem report card

After a crash, an ANR, an unclean exit or a failed stream, Delos offers a report once (logic, markers and privacy rules: [DIAGNOSTICS.md](DIAGNOSTICS.md)).
`ui/ProblemOfferCard.kt` is a **glass card** (corner 34, the shared `glass` fill, a 1 px `line` border, 36 px padding, 780 px wide) in the **top right
corner** of the start screen and the home screen, 230 px from the top and 64 px from the right edge (under the toast's place). It shows an accent disc with
the bug icon, a title (Unbounded 28: *Delos had a problem*, or *A stream could not run*), one sentence for the kind of problem (Figtree 22), the one-line
technical summary (18, at most two lines, ellipsis) and the note that **nothing is sent automatically** and what Report does (20). Two `PillButton`s
(compact): **Report this problem** (primary, GitHub logo, an **A** glyph) and **Dismiss** (close icon, a **B** glyph).

- **Not a dialog:** no scrim, no blur of the screen, no confirmation. It shows only while no layer, portal, launch loader or stream result is up and the
  shell is on Start or Home (`ShellState.offerShown`), so the result screen of a failed stream comes first and the card follows it.
- **Controller:** the focus starts on Report this problem (silently, like a layer); A activates the focused button, left/right moves between the two,
  **B is Dismiss from anywhere** while the card shows (a back handler registered after the layers'), and the D-pad can leave the card for the dashboard.
  Dismiss returns the focus to the screen. Touch: tap either button.
- **Motion:** `popEnter` / `popExit` from the top right corner (`Motion.POP_IN` with the overshooting spring, `Motion.EXIT` out; reduced motion: fades).
- **Report** saves the cleaned log to Downloads, shows a toast (`Log saved to Downloads as ... Attach it to the issue.`) and opens the prefilled GitHub
  issue in the browser 0.9 s later.

**Debug info → Diagnostics** (the first section after the device rows, `DebugInfoScreen`): *Diagnostic log* (an info row with the size and line count of
what Share sends; its panel previews the scrubbed text), *Share diagnostic log*, *Copy diagnostic summary*, *Clear diagnostic logs* and, in debug builds
only, *Trigger test crash*. **Settings → About → Diagnostics → Keep diagnostic logs** is the on/off switch (native row, `about_diag_logs`).

## Color roles

Color is a seasoning, not a coating: the neutral text and surfaces carry the screen and the palette's three colors split
the accents about **60 / 25 / 15** by visual weight (`colors.accent`, `accent2`, `accent3`; Delos: gold 40°, cyan 190°,
indigo 250°, a split complement). Material You derives the same three from the wallpaper (main, +52°, tertiary) and
follows the same roles. Light themes use darker shades (accent3 is dark there); content on an accent3 fill uses `onAccent3`
(dark in the dark theme, white in the light one); a cover badge uses `accent3OnDark`
and `accent3Line` is the faint hairline of the palette's third color, all in `ui/Colors.kt`.

- **Text stays neutral**: labels of buttons and hints, titles, kickers and values are white or the grey text colors in
  every palette. Color goes into shapes and icons.
- `accent` (main, ~60 %): the focus ring and its glow, filled focus, on states (switch and slider fill, tiles that are
  on), primary buttons, the selected tab, the gold of the active label in the stream menu.
- `accent2` (secondary, ~25 %): icons of rows in the settings and the stream menu, arrows, the second half of every
  gradient (switch, slider, progress), the check badge of a selected circle, spinners.
- `accent3` (accent, ~15 %): the cherry on top, small and never text: the dot under the current page in the top bar, the
  slider halo of the stream menu, the `Running` and cover badge icons, the ring ripple of the portal
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
- Rows of the Upscaler page: **Enabled** (the on/off toggle for the upscaler and Sharpen only alike), **Supersampling
  quality** (only while the stream matches the display and the mode scales), **Mode** (SGSR 1 (Normal), SGSR 1 High
  Quality, SGSR2-DV, SGSR2-DV (Quality), **Sharpen only**), and **Sharpness**. In
  **Sharpen only** the Supersampling quality row is hidden (nothing is scaled, so it has no meaning), the page is
  titled Upscaler and the Sharpness slider is the whole effect; the tile's status reads "On: Sharpen only" in the help.
  Mode and Sharpness change live. Switching Mode to or from Sharpen only rebuilds the output stage the way the Enabled
  toggle does (the picture may flicker once), because Sharpen only draws at the stream's size.
- **Bitrate page, recommendation row** (PyroWave always; an ordinary codec once a link test exists): a row above Apply, `Recommended: 135 Mbps · drops frames at 225 Mbps` (icon thumbs up, the glyph of every "recommended" row; value **Fill**, a scrolling label like every row), refreshed once a second from the stream's own counters and the last link test. A fills the slider with the recommendation; only Apply sends it. Its help says what the number rests on and, for an untested link, that testing is done in Settings before connecting (the host refuses probes during a stream, so there is no Test link here). An ordinary codec without a test shows only the slider and Apply.
- **Statistics panel, Link block** (full panel, PyroWave only, after Backlog): **Receiving** (`212 of 225 Mb/s`, the app's whole received rate against the bitrate in force), **Partial / lost** (frames per second) and **Verdict** (`Link keeps up at 225 Mbps`, `Link drops frames at 225 Mbps, try 135 Mbps`, `Device skips frames; the link is fine`, `Measuring`).
- **Statistics panel, SGSR output rows** (full panel, MediaCodec streams whose output goes through the upscaler or Sharpen only; same label/value style as the other rows):
  **Upscaling** is the GPU time per frame read from the frame fences (the CPU submit time until the first fence is read).
  **Frame queue** (after Upscaling; `1.0 avg / 1 max waiting · age 0.6 / 1.9 ms · 0.0 skipped /s`) says how many decoder frames were waiting when
  a draw started (1 = the output kept up), how long the newest one had waited, and how many older frames per second were skipped
  because a newer one was ready (non-zero = the output thread cannot keep up with the decoder). When the GPU already had two frames of
  work in flight, the row ends with `· 30.0 GPU busy /s`: frames per second that waited for it and were replaced by a newer one without
  being drawn (the GPU work per frame is longer than the frame interval). After two seconds of that, one toast per stream and upscaler
  mode says "Upscaler is too heavy for this frame rate: lower the supersampling or the frame rate"; no setting changes by itself. **Render latency**
  (before Total latency; `6.1 / 7.9 / 9.3 ms avg/p95/max`) is the time from the frame reaching the output stage to the display presenting it, over the
  last second. It is shown only while the statistics overlay is on and the platform reports the display's present time, and it
  does not include the controller, network, host or decoding, so it is not the delay between a press and the changed picture
  (see [Latency](STREAM_QUALITY.md#latency-75)).
- There is no save button: every change on the page applies live and is kept for this game automatically (about 500 ms
  after the last change, or when the menu closes or the stream ends; `MenuAutoSave`), as that game's overrides of the four
  upscaler settings. The profile is never written from the menu, nothing is written while the menu merely opens, there is no
  toast, and a stream without its launch identity stays session-only.

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
(`StreamMenu` implements it with the live `DelosStreamActivity`, the debug preview with fake state). The prototype's `#quickmenu` /
`#qmpop` show the same design; `build/streammenu-proto/b.html` with its screenshots is the specification.

The focus on the dock **wraps** (Right on the last tile goes to the first, Left on the first to the last; the ring glides across the bar like any move); the popover's rows do not wrap.

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

The custom and full on-screen keyboards resolve `ActiveKeyboardInput` for each event,
not a static Activity or a cached session. Its narrow target is weakly held, available
only while connected, and owner-cleared when the stream Activity is destroyed; an old
owner cannot clear a replacement stream. Accessibility focus/key forwarding and external
controller window ownership keep their existing paths.

- **Tiles**: 150 px glass, corner 42, gap 40 (9 × 150 + 8 × 40 = 1670, 125 on each side), the top edge at y 628, icon 64
  (Phosphor, filled on focus), label 24 SemiBold 24 below the tile. Resume, Keyboard, Overlay, Brightness, Bitrate,
  Upscaler (Supersampling), More, Disconnect, Quit game. The **Keyboard** tile is the Delos Full keyboard (A closes the menu,
  then toggles it) with the plain keyboard icon (`Symbols.KEYBOARD`, the one of the Settings > Controls virtual-keys row; Android's system keyboard in the More page has its own text-cursor icon); it turns accent
  while the keyboard is up but has **no On / Off pill**. Android's system keyboard is the More row Open Android keyboard.
  The other toggles (Overlay, Upscaler) have an **On / Off pill**
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
  tile**, centered on the tile and kept 120 from the stage's left and right edges, its box ending 322 above the stage's bottom edge, with the
  **caret** (60 × 24, rounded, ONE path with the card so the glass is continuous) at the tile's center, at least 72 from
  the card's corners. Width per page: Brightness 780, Bitrate 860, Upscaler 980, More and the pages opened from it 920.
  Padding 40 × 30 (24 below), header 64 (the back circle, focusable, B does the same, and the title in Unbounded 30),
  rows 72 tall with 8 between them (corner 28, icon 32 in a 56 column, label 24 SemiBold, values 24), a 78 × 44 switch, a
  240 × 12 slider with a 32 knob, choices between two chevrons. **One margin M = 120** (`POPOVER_MARGIN`) is the card's gap to
  the left, right and top edges of the stage: it is the gap More (the rightmost popover, 920 wide centered on its tile, its
  right edge at 1800) always had to the right edge. Every page keeps its content's
  height up to **one shared maximum**, the space from the card's bottom (734) up to M: 614 (`POPOVER_MAX_HEIGHT`; small
  pages stay small). Beyond it, the rows scroll inside the card with faded edges (D-pad focus scrolls the focused row into
  view); the header and the help stay fixed. More (11 rows) and a long list of keys scroll, and the Upscaler page with
  Supersampling (601.6 tall) only at interface scale above 1.15. The card stays clear of the compact statistics line (bottom edge 61.2, at
  interface scale 1.3 68.8, by `compactStatsBottom`). The help of the focused row stands under the
  rows in a box of two lines (three from interface scale 1.15 up), complete, never scrolling. A card pushed against the right
  edge keeps M there too (the top gap and the side gap are equal) and the caret still points at its tile (the card is
  clamped, the caret stays inside its corners). The pages
  opened from More (Send keys, Commands, Mouse mode) are anchored over the More tile. More lists Send keys, Send
  clipboard, Get clipboard, Commands, Task manager, Zoom, Mouse mode (when allowed), On-screen controls, **Open Android
  keyboard** (an action: closes the menu, then opens or closes Android's system keyboard), Touch sensitivity, Rumble feel and **Gyro smoothing** (a 0 to 100 % slider in steps of 5, reading "0 % (off)" at 0; shown only where the Settings row is shown, dimmed with its reason in the help line while neither the external nor the built-in gyroscope is on; it applies at once to the running sensors and is kept for this game automatically like the upscaler's settings (about 500 ms after the last change, or when the menu closes or the stream ends; as this game's `seekbar_gyro_smoothing` override, never the profile, which is changed in Settings), see [STREAM_QUALITY.md](STREAM_QUALITY.md#gyro-smoothing-77)). **Key layout** and
  **Rotate** are not offered: the key-layout overlay is hidden (its code and preferences stay) and a rotation
  re-lays out the overlays.
- **Popover row focus**: one local `RowGlideHost` inside the rows viewport carries the accent 17% fill, 3 px inset ring, corner 28 and inner shadow between rows in 520 ms. A remembered painter reuses its paths, paint and stroke; no row-local focus tint/ring/shadow fades behind it. Keys and Commands use a capsule plate while retaining their one-row (scrolling when it does not fit) layout and static white 10% pill base (the base now composites above the travelling tint). Back stays fixed outside the plate and is the first ordered target on linear pages; held Up stops there and Down stops at the last row. More entry-row restoration, B/back and immediate Left/Right choice/slider adjustment are unchanged.
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

### Full keyboard

The on-screen keyboard over the stream (stream menu → the Keyboard tile, a four-finger tap, the button on the external
display's control screen) is the approved **Flat console** prototype (`build/fullkeyboard-proto/b.html`, local scratch, with
its screenshots): a solid bar docked to the bottom edge, flat keys, a hint strip under them. It is one Compose
layer (`shell/keyboard`: `FullKeyboard`, `FullKeyboardState`, the key table in `FullKeyboardKeys`, the pure logic in
`FullKeyboardInput`) inside a `ComposeView` that `KeyBoardLayoutController` (still the class `DelosStreamActivity` and
`ExternalDisplayControlActivity` use: `show`, `hide`, `toggleVisibility`, `refreshLayout`, `isKeyboardVisible`, `shown`)
adds to the stream's layout, bottom-docked and as wide as the layout. It never takes the focus. Where nothing of it is
drawn nothing is touchable: a finger on the game area goes to the stream even while a key is held.

- **Keys**: the same 80 keys, six rows and weights as the old XML layout (Esc, F1–F12, Ins, Del / ~` 1–0 _- += Back / Tab
  QWERTY… {[ }] |\ / Caps ASDF… :; "' Enter / Shift ZXCV… <, >. ?/ ↑ Hide / Ctrl Win Alt Space Home End PgUp PgDn ← ↓ →; `FullKeyboardKeysTest`
  pins them). **The keys show what they type** (`keyLabel`, US layout): letters are lower case, upper case while Shift is held
  (touch or latched, also X on the controller) or Caps Lock is on (both: lower again); the number row shows `!@#$%^&*()` while
  Shift is down; the punctuation keys stack their two characters (the other one small above, the typed one below) and Shift swaps
  them. The labels crossfade (280 ms, no layout change) and update at once when Shift latches, unlatches or the keyboard
  hides. Caps Lock is tracked by the keyboard (toggled on every press of the Caps key; the host's real state is not readable)
  and survives hiding; the stream's QWERTY setting only changes the codes sent, not the labels. The popup shows the typed
  character too. Space is a thin bar, the arrows are drawn strokes, Home/End/PgUp/PgDn
  have a smaller legend (.8), Hide is the Phosphor close icon (second accent, filled on focus) over "HIDE". Colors come from
  the theme (the Delos palette gives the prototype's `#131c31`→`#0d1424` bar, `#1f2b46` keys, `#151e35` function and modifier
  keys, a plum-tinted Hide key); the light theme gets a light bar. Legend: Figtree SemiBold at .38 of the key height.
- **Look**: key corner 11, gap 6, 12 more around Esc, Ins and Space, bar padding 22 above and 78 below the rows. The
  **focused** key turns white with a dark legend and the **gold ring** (4 px, 3 px dark gap) around it; **pressed** is the second
  accent (cyan) with a dark legend and squeezes to .97; **sticky** is solid gold with a bar under the legend (state never
  relies on color alone). The key popup (white bubble, 1.15 keys tall, 10 above the key, the character typed) shows for
  character keys only and stays 75 ms after the release. The hint strip says **A Press, B Close, L3 Shift, X Delete, Y Space** (36 px glyph circles, `L3` a
  two-letter glyph in the same circle, label 22) and "ON-SCREEN KEYBOARD" at its right end (left out when the keys are narrower than
  1100 px, so the strip stays one line); it only shows while a controller drives the keyboard (touching hides
  it, the ring and the focused key's white; the next D-pad press brings them back). The stream darkens a little behind the
  bar (the prototype's `#shade`, 0 to .38 from half the height down).
- **Size and settings** (stage px; the design is laid out on the 1080-high stage like the rest of the UI): by default the
  keys span the width less 24 each side, at most 1840, and are 3.65 % of that wide (44 to 68 tall), so 16:9 gives a bar of about
  half the screen (532 of 1080), 21:9 centers a keyboard of the same width and 4:3 gets smaller keys instead of a taller
  keyboard. **Virtual Full Keyboard Disable Autofit** (`onscreen_keyboard_autofit`, the setting that turns the fit off) makes the
  width and height settings apply: **Width** (`seekbar_onscreen_keyboard_width`, dp, 1000 = full width, never wider than the
  default) is the width of the keys, **Height** (`seekbar_onscreen_keyboard_height`, dp) the height of the whole bar, from which the
  key height follows (24 to 130); **Align Mode** (`list_onscreen_keyboard_align_mode`) puts the keys left, center or right inside
  the bar, which stays full width. **Opacity** (`seekbar_keyboard_axi_opacity`) dims the bar, keys and ring together (not the hints,
  not the popup). **Sticky modifier keys** and **vibration** (`checkbox_vibrate_keyboard`) work as before.
- **Touch** is what it always was: every key reads its own finger (so Shift + a letter, WASD, chords work), Down sends the key
  down, up or cancel sends it up, a finger that wanders off its key still holds it until it lifts. With sticky modifiers on, a
  modifier held 300 ms latches (its release is not sent), touching a latched one releases it. Hide acts on release. Keys
  go to the host through `KeyBoardLayoutController.sendKeyEvent` → `ActiveKeyboardInput` exactly as before (source 0, haptics
  on down, release and latch). A physical keyboard keeps typing into the PC; the keyboard never sees it.
- **Controller** (handheld first): **while the keyboard is up it operates the keyboard and nothing of the controller reaches
  the game**; the D-pad and the left stick move the focus, **A** presses the focused key (held for as long as A is held; a
  modifier latches and unlatches, because a controller cannot hold one key while pressing another, whatever the sticky setting
  says), **B** (and Back) closes it, **L3** (the left stick click) toggles Shift as a latch (Shift down on the host while latched, labels shifted;
  L3 again releases it; the stick click never moves the focus), **X** types **Backspace** and **Y** **Space** (down on press, up on
  release, repeating after 350 ms every 100 ms while held, `PAD_REPEAT_DELAY_MS` / `PAD_REPEAT_INTERVAL_MS`; the keyboard repeats them
  itself and ignores the system's repeats). X and Y go through the same send path as a touch (haptics, popup) and flash the Back
  and Space keys even when the layout hides them; closing the keyboard lets go of both. Moves are row-aware like the prototype: left/right stay in the row and
  stop at its ends (the ring nudges 7 px against the edge), up/down go to the key of the next row under the *column* of the last
  horizontal move, else the nearest (`KeyGrid`, `FullKeyboardInputTest`). The first focus is Q, then the last key. **The D-pad
  works in both shapes a pad reports it**: as `DPAD_*` key events (the system repeats them) and as the hat axes
  `AXIS_HAT_X` / `AXIS_HAT_Y` of the joystick motion event, which is what most handhelds (the Odin) send and which never
  reaches the keys. `DpadTracker` (pure, tested) turns the hat into moves: a newly pressed direction moves at once, a timer
  repeats the last one after 350 ms every 100 ms until the hat is back at the center, and a device that sends both the hat
  and the key moves once per press. A, B, X, Y and L3 are the raw Android codes (`BUTTON_A` 96, `BUTTON_B` 97, `BUTTON_X` 99, `BUTTON_Y` 100, `BUTTON_THUMBL` 106), also
  when a handheld's built-in pad reports them with a plain keyboard source. The
  controller's other buttons are swallowed. The stream menu's open/close buttons keep working (they are handled first);
  rumble keeps running. **No stuck input**: `ControllerHandler.setInputSuspended` lets go of everything the physical
  controllers hold (a neutral packet each) when the keyboard opens and again when it closes, and sends nothing in between (also
  from the USB driver path); keys the keyboard itself holds are released when it is hidden, and so is **every latched
  modifier** (Shift, Ctrl, Alt, Win: each gets its key up and the latch is cleared, `ModifierLatch.releaseAll`), whichever way the
  keyboard closes (B, Hide, the menu toggle, PiP), so nothing stays down on the PC. The interception is `DelosStreamActivity.handleKeyDown/handleKeyUp/handleMotionEvent` (`visibleFullKeyboard()`), for
  controller sources only. On the external display the same hooks work, because `ExternalDisplayControlActivity` already routes
  the controller's events to `DelosStreamActivity`; the keyboard there is the activity's own `KeyBoardLayoutController`.
- **Motion** (all from `Motion.kt` / `MotionKit.kt`, like the stream menu): the rows **rise** 18 px and fade in, the first after 120 ms,
  then 46 ms apart, 420 ms each (ease-out); the bar's glass fades in over 336 ms; everything **leaves** in 240 ms (ease-in,
  no stagger). **Focus** is not a scale here: one **gliding ring** (`GlideRingHost`, 560 ms with the slight overshoot) moves between the
  keys, follows the keys exactly while they are still rising (900 ms) and appears 560 ms after opening; the ring is drawn over the
  keys (their neighbours lie closer than the ring is wide: `inFront`) and squeezes with the key under it. **Press** squeezes the key to
  .97 in 120 ms and holds it 130 ms (a tap is always seen); colors and opacities take 280 ms; the popup settles with the
  spring in 120 ms. At the keyboard's edge the ring bumps 7 px and returns (the choices' chevron nudge). Reduced motion: only fades.
- **Visibility** rules are unchanged: the state is kept across rotation and PiP (`hide(true)` / `show()`), `DelosStreamActivity.isFullKeyboardShown()` feeds the stream
  menu's Keyboard tile (the tile is the Delos keyboard; Android's system keyboard is the More row **Open Android keyboard**); a rotation only re-reads the settings (`refreshLayout`), it does not rebuild the keyboard.

#### Full keyboard preview (debug builds)

The real keyboard on the sunset scene, no stream or profile needed (`FullKeyboardPreview` in `src/debug`, a stub in `src/release`):

```
adb shell am start -n io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity --es preview_screen full_keyboard \
  [--ei preview_opacity 0..100] [--es preview_align left|center|right] \
  [--ez preview_custom_size true --ei preview_height <dp> --ei preview_width <dp>]
```

Touch, D-pad, left stick, A, B and X work as in a stream; the keys that would go to the PC are listed at the top left. After
B or the Hide key a tap on the scene opens the keyboard again.

### Context menu preview (debug builds)

The real context menu over a drawn dashboard (hero, game tiles, a PC card), without the shell (`ContextMenuPreview` in `src/debug`;
`ShellActivity` hands the intent to it, `src/release` replaces it by a stub):

```
adb shell am start -n io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity --es preview_screen context_menu \
  [--es preview_state <idle|running|other|options|long|pc>]
```

The menu opens 700 ms after the start with its entrance and stays (a tap outside closes it). `idle`: the game menu of a game
(nothing runs), `running`: the game runs (Resume, Quit game in red), `other`: another game runs, `options`: the Options submenu
is open (the mechanism no real menu uses yet), `long`: the 12 matches of a pick list that scroll inside the card, `pc`: the PC
menu below its card with the caret on top.

### Stream menu preview (debug builds)

The real menu can be drawn without a stream, over a sunset "game scene", with made-up values (`FakeStreamMenuHost` in
`src/debug`; `ShellActivity` hands the intent to `StreamMenuPreview`, which `src/release` replaces by a stub):

```
adb shell am start -n io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity --es preview_screen stream_menu \
  --es preview_page <main|upscaler|bitrate|brightness|more|keys|commands|mouse|rumble> --ei preview_focus <tile 0..8> \
  [--ei preview_upscaler_mode <0..4; 4 = Sharpen only, whose page has no Supersampling quality row>] \
  [--ei preview_row <row of the page to focus>] [--ez preview_animate true]
```

The menu starts already open at that page without its entrance; `--ez preview_animate true` plays the entrance, so a
screenshot after about 1.4 s shows the settled result. No profile is needed. Resume or B close the menu; a tap on the
scene opens it again.

## Screen shapes

Issue #9. The shell is **landscape only** (`sensorLandscape` on `ShellActivity` and on `ShortcutTrampoline`, which shows the launch scene; the stream
activity keeps its own orientation logic) and supports windows from 1:1 up to 21:9. The 16:9 1080p look of the prototype stays pixel-identical.

- **Stage planner** (`ui/StagePlanner.kt`, `StagePlannerTest`): `Stage()` hands the window size in dp to `StagePlanner.plan`, which returns the
  dp per stage pixel, the stage size in stage pixels and a `StageShape`; `LocalStage` (`StageMetrics`) carries `stageWidth`, `stageHeight`, `shape`
  and the cutout `safe` insets next to `dpPerPx`. The scale is today's `min(w / 1920, h / 1080)` **unless** that would be under the **legibility
  floor of 0.43 dp per stage px**: the AYN Odin 2 Portal (1920 × 1080 at the 369 dpi it reports = 832.5 × 468.3 dp) has 0.4336 dp per stage px,
  the floor sits 0.8 % below it, so text is never physically smaller than there. Under the floor the scale stays at 0.43 and the **stage shrinks**
  (fewer stage pixels across), which is reflow, not scaling. So every 16:9 window of 826 dp width or more (the Odin, the Thor's top panel, every
  1080p panel at or below about 372 dpi) gets exactly 1920 × 1080 and the same `dpPerPx` as before; 4:3 is 1920 × 1440 and 21:9 is 2520 × 1080 as before.
- **Classes** (`StageShape`): *Standard* is 16:9 within 3 %; *Wide* is wider; *Tall* is narrower down to 4:3 (about 1.29 with the tolerance);
  *Compact* is a stage under **1500 stage px wide** (small panels, split-screen halves) or squarish (narrower than 4:3, 1:1). A shrunk stage can be
  Standard in aspect with fewer than 1920 stage px: screens drawn for 1920 × 1080 do not fit it until they are made adaptive.
  Existing screens still read `px` / `tpx` as before; the Wide, Tall and Compact layouts are later phases.
- **Compact settings family** (issue #9, phase C; `StageShape.COMPACT`, 1:1 up to small 16:9 panels, stage about 1050 to 1490 px wide): text keeps its
  stage size (the legibility floor), the room around it shrinks. `ui/CompactRows.kt` holds the one scale factor of non-text elements,
  `compactScale = clamp(min(w / 1920, h / 1080), 0.7, 1)` (1 on every other shape), as `cpx` (stage px × scale) and `targetPx` (never under 64 px for a
  touched or focused element, never above the original); the outer margin is the 48 px floor plus the cutout (`SettingsLayout.margin`), gaps between
  blocks stay at 16 px or more.
  - *Settings, per-game settings, Debug info*: no 520 px panel. The list is full width and the description is a **dock** under it (`HelpDock`, 168 px:
    icon, name, help that auto-scrolls when long, the impact legend beside the name and its sentence at the end of the help; per game the dock also
    carries the "From profile / Custom" source line). The title goes (Settings) so the 8 tabs fit: only the selected tab keeps its label, L1 / R1
    still step through them, the search is an icon button at the end of the row. Per-game plain rows drop "From profile" (the dock says it);
    custom rows keep their CUSTOM badge and profile line. The banner is 200 px, the search letter picker 60 px keys with the results beside it.
  - *Artwork*: the title and the collapsed tabs share a row; the grid keeps each kind's tile shape (2:3, 920:430, 1920:620, 2:1) and picks the column
    count from the 1920 tile scaled by `compactScale` (`ArtTab.compactColumns`); the game chip drops year and id. The filter sheet and the library
    filter modal use the full stage width, scaled paddings and chip gaps, and scroll when they do not fit.
  - The keyboard (`FullKeyboard`) was only checked: it is width-aware already.
- **Safe area**: backgrounds still draw edge to edge, the status bar and the hint bar are padded by the display cutout (`WindowInsets.displayCutout`
  in stage pixels, `StageMetrics.safe`; zero on the Odin, so nothing moves there). Other edge-anchored content can read `LocalStage.current.safe`.
- **Companion screen** (issue #76, [DUAL_SCREEN.md](DUAL_SCREEN.md)): the second panel of a dual-screen device is a `CompanionActivity` on that display (own task, no key focus) with its own `Stage()`, so
  `LocalStage` there is planned from that display's size and density (the Thor's 1240 × 1080 panel is a Compact stage), independent of the main panel's.
  Its content is variant A of the approved prototype (focused-game details and buttons, statistics and the nine stream-menu tiles, a touch keyboard), built from the
  shared tokens (glass cards, Phosphor icons, gold primary, impact chips) with text at least 28 and targets at least 64 stage px; a stage under 920 px high lays the tiles out flat.
  Content, state and command flow, burn-in policy and previews: [DUAL_SCREEN.md](DUAL_SCREEN.md), "What the companion shows".
- **Shape simulator** (debug builds, `ShapeSimulator` in `src/debug`): `--es preview_shape <W>x<H>@<dpi>` draws the whole shell, or any `preview_screen`,
  in a centered box of that pixel size (scaled down uniformly when it does not fit; the density is scaled with it, so the dp maths is the panel's)
  with `LocalDensity` set to the simulated density. Outside the box it is black; a caption at the bottom-left names the size, dpi, dp size, stage and
  class. `--es preview_cutout <L>,<T>,<R>,<B>` adds a display cutout (insets in simulated pixels, drawn as a notch). It combines with `preview_screen`
  and `preview_state`:

  ```
  adb shell am start -S -n io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity --es preview_shape 1240x1080@420 \
    --es preview_screen home
  ```

- **Screen previews with fake data** (debug builds, `ScreenPreviews` in `src/debug`, no PC or network): `--es preview_screen <home|artwork|devices|start|pair|filter|dialog|loader|result|settings|settings_search|debug_info|profile|game_options|side_menu|home_context|problem|stream_exit|boot|create_profile|pair_pc|stream_stats|stream_osc|stream_video>`,
  `home`, `side_menu` and `home_context` (the game menu opened by the Start key 3 s after the start), `stream_stats` with `--es preview_state <full|compact>`
  and `--es preview_position <TOP_LEFT|…>`; they run next to the older previews (`context_menu`, `top_menu`, `settings_tabs`, `settings_list`,
  `game_settings`, `stream_menu`, `full_keyboard`, `toast`). `stream_osc` and `stream_video` draw the stream's own geometry (see "Stream side" below). `settings`, `settings_search`, `debug_info`, `profile` and `game_options` are the real screens (`game_settings` is the older component preview of the per-game list); the preview tells the settings capability probe there is no PyroWave, because its native Vulkan probe dies on the emulator's SwiftShader.
- **Contact sheets** (`tools/layout/`): `shapes.txt` lists the shapes (names, `W x H @ dpi`, optional cutout), `screens.txt` the previews. `sheets.sh` needs the
  fail-closed adb wrapper and the stream gate helper:

  ```
  tools/layout/sheets.sh --adb <adb wrapper> --gate <stream gate> shapes <label>     # every screen in every shape -> build/layout-sheets/<label>/
  tools/layout/sheets.sh --adb <adb wrapper> --gate <stream gate> baseline [label]   # every screen natively (today's 16:9), for the identity check
  tools/layout/sheets.sh compare <labelA> <labelB>                                   # pixel diff of two runs; exit 1 when a screen differs
  ```

  It takes a screenshot first and refuses to run when the gate reports a stream (asked again before every launch); it starts previews by intent only,
  never presses anything, and starts the normal shell again at the end. Contact sheets land in `<label>/sheets/by-screen/` and `by-shape/`.
  `compare` ignores the status bar's Wi-Fi, battery and clock by default (`--mask none` compares everything, `--tolerance N` allows small differences).
- The displays of the target handhelds and the class each gets: [HANDHELD_DEVICES.md](HANDHELD_DEVICES.md).
- **Stream side** (issue #9, phase D; `OscGeometry`, `OscDefaultLayout`, `VideoFit`, `KeyBoardGeometry`, `StreamWindowController.requestedOrientation`: pure, covered by
  `OscGeometryTest`, `KeyBoardGeometryTest`, `VideoFitTest`, `StreamWindowControllerTest` over 1:1, 5:4, 31:27, 4:3, 3:2, 16:10, 16:9 (four resolutions), 20:9, 21:9, 32:9 and
  portrait). Every 16:9 output is pinned against the old arithmetic (copied into the tests), so 16:9 at any resolution is identical; so is every shape of 3:2 and wider.
  - *On-screen controls* (`VirtualController`, default layout on the 128 × 72 grid, one unit is height / 72): the grid is the old one from **3:2 up** (108 units wide or
    more): left cluster against the left edge, right cluster against the right edge, Back / Guide / Start and the trackpad centered, so a wider panel only widens the gap in
    the middle (what the old `rightDisplacement` did, but it went negative and the clusters crossed below 16:9). **Squarer than 3:2** (4:3, 5:4, 31:27, 1:1) the whole layout
    shrinks by `width / (1.5 × height)` (8/9 at 4:3, 2/3 at 1:1) so the clusters and the middle row still fit side by side; the upper controls (sticks, face buttons,
    triggers) keep to the top edge, the lower ones (D-pad, right stick, L3 / R3, Back / Start, trackpad) to the bottom edge; buttons keep their proportions and nothing
    overlaps that did not at 16:9. Smallest face button at 1:1: 6.7 units, 67 px on a 720 px square (about 6.6 mm at the RG Cube's 257 ppi); there is no minimum size.
  - *Saved layouts* were absolute pixels of the panel they were saved on (not relative), so a layout from the Odin was off-screen on a 960 px panel. Each saved element now
    also stores the panel size (`REF_W`, `REF_H`); loading on another size scales by `min(width / REF_W, height / REF_H)` (a button stays as round as it was) and keeps the
    distance to the nearest edge (left, right or middle by thirds of the old width, top, bottom or middle by thirds of the old height), so a layout made on 16:9 keeps its
    right-hand controls on the right edge of a 21:9 panel. Layouts saved before this (no panel size) keep their pixels but are pulled onto the screen. The result is always on
    the panel. `--es preview_saved_from <W>x<H>` shows this on the emulator.
  - *Key grid* (`KeyBoardController`, the editable key overlay; hidden in the new UI, code kept): same saved-layout handling; the keys were already limited to an eighteenth of the
    width, the D-pad is anchored to the right edge and both it and the stick are now clamped onto the panel. At 1:1 a key is 4 units, 40 px on 720 (about 4 mm): too small to
    use by touch; the **Full keyboard** (Compose, stage based) is the keyboard to use there and was checked at 1:1, 4:3, 31:27 and 21:9.
  - *Orientation*: `isSquarishScreen` keeps its threshold (long side / short side under 1.3: 1:1, 5:4, the 31:27 panels; 4:3 at 1.333 is not squarish and keeps the
    "lock to the current orientation" rule). A squarish panel shows a stream about as large in either orientation, so it now always follows the **shape of the video**
    (landscape for 16:9 or square, portrait for a taller stream): it used to be `FULL_USER` (any orientation, flipping with the accelerometer of a tilted handheld) unless the
    on-screen controls or a native resolution asked for a lock; a square native stream (the RG Cube's 720 × 720) used to lock **portrait**, now landscape. The on-screen
    controls still force landscape unless the resolution is native.
  - *Video rectangle* (`VideoFit.measure`, used by `StreamContainer` and `StreamView`): **Fit** keeps all of the picture and bars the long side of what is left over
    (16:9 on 4:3 is 960 × 540 with 90 px bars, on 1:1 720 × 405 with 157 / 158 px bars, on 21:9 pillarbox 300 px each side), **Fill** covers the panel and the parent crops the
    overflow evenly (16:9 on 1:1 keeps the middle 720 of 1280 px, 56 %), **Stretch** fills it whatever the aspect. There is no integer-scale or 1:1 pixel mode in this app.
    `--es preview_screen stream_video --es preview_state <fit|fill|stretch> [--es preview_video <W>x<H>]` draws it.
  - *Stream shell parts*: the loader, result card, exit portal and statistics panel already run on the stage and were checked at 1:1 and 4:3 (contact sheets); in the
    compact statistics line at 1:1 the last figure wraps onto a second line. The two backdrop blur radii (`StreamExit`, `StreamMenu`) are `50 × min(width / 1920, height / 1080)`
    px, which is the stage scale (not the legibility floor), left as it is; nothing else in `binding/` or the stream shell hard-codes 1920 / 1080 / 16:9.
  - *Not verified* (the emulator cannot run a stream: the native library dies with SIGILL): the stream itself on any panel, the on-screen controls touched for real (previews draw the real
    views without touch), the orientation lock on a device, the cutout / grip-covered edges of a phone in a grip (the window is the display metrics; no safe-area insets are applied to the
    controls), and the physical size of controls on the real panels (needs the Odin, an RG Cube / 406H, a Thor).
- **Screens on a wider or taller stage** (phase B, screens): every absolute 1920 / 1080 of a screen is now `LocalStage.current.stageWidth` / `stageHeight` with the
  same value at 1920 × 1080. Settings: the Search pill keeps its distance from the right edge (x = stage width − 390). Per-game settings: the header is the
  stage width less the two 150 px margins and the list runs from x 132 to 38 px short of the right-anchored 520 px panel (stage width − 840 wide). Artwork:
  the tabs, toolbar, filter strip and info strip are anchored to the edges (the info strip 194 px above the bottom), the grid takes the height between the
  strips and the columns scale with its width (7 / 4 / 3 / 5 per 1720 px). The filter modal's height follows the stage (stage height − 260). Circle rows (`Circles`: Devices, Start,
  Pair, Profile) stay one centred row that scrolls sideways with an edge fade when the circles do not fit, on every shape (while they fit it is the plain centred row); the other rows that used to wrap (the filter modal's chips, the Artwork sheet's options, the stream popover's key/command pills) do the same through `ScrollRow` (`ui/Controls.kt`), which gives its row its own focus-scroll margin instead of the surrounding list's. Nothing wraps into a second line any more except text and the info row's non-focusable facts and tags. Debug info already filled the stage (weighted list, edge padding).
- **Compact stages** (phase C, shell screens; `ui/Compact.kt`, only when `stage.shape == COMPACT`, every other shape is untouched): the stage is 1000–1500 stage px
  wide and 720–1100 high with the text as large as on the Odin, so non-text elements shrink and everything else reflows. `StageMetrics.compactScale` is the one
  scale of covers, discs, avatars and gaps (stage height / 1080, 0.62 at the least); margins never go under 48 px (`Compact.MARGIN`; the cutout comes on top) and
  neighbouring blocks keep 16–24 px; targets stay at least 64 px. Status bar: no wordmark under 1400 px, the PC pill is cut by an ellipsis to its side of the
  centred navigation. Hint bar: the note with its label, the note alone, no note, then glyphs with only the first label, the first that fits. Centred screens
  (`CenterColumn`) keep clear of both bars and scroll when still too tall; headlines are 48 (not 64), circles scale with `compactScale` (items 170 wide, labels
  cut by an ellipsis), the avatar picker has 4 columns. Home row: covers 0.6–0.85 of the prototype (2:3 and the wide cover's ratio kept, the gap and corner
  scale with them), the first one 72 px from the edge, the block above them without the "Game info" label and with a 120 px title box (title 60/50/44). The
  context menu keeps 48 px from every edge. Stream menu (`DockGeometry` in `StreamMenuLayout.kt`): the nine tiles shrink to fit the width between 48 px
  margins (about 85–137 px, gap 20), the ring, corners and icons with them; below 130 px only the focused tile shows its label; the dock sits 48 px above the
  bottom (the sunk dock keeps its label there), the popover is at most as wide as the room between the margins and as tall as the room above the sunk dock.
  Problem card and toast: 48 px from the edge, under the status bar.

## Native shell architecture and design constraints

These preserve implementation/design context from the removed UI plan, not phase checklists.

- `ShellActivity` is the launcher; `ShellLink` handles external links and PC shortcuts. Kotlin and
  Compose implement the shell. Existing Java/backend behavior is reused rather than rewritten:
  `ComputerManagerService`, `NvHTTP` / `PairingManager`, `PreferenceConfiguration`, `ProfilesManager`,
  `ShortcutHelper` and `ServerHelper`. Streams still start through `ServerHelper.createStartIntent()`
  into `DelosStreamActivity`, wrapped by `GameLauncher.startIntent` (shared with launcher shortcuts) to attach per-game settings. Keep upstream-shared changes small.
- Use Compose foundation/ui/animation and the Delos design system, not Material components. The
  shell owns its small navigation stack, animated transitions and overlay focus. Fonts are bundled
  Unbounded/Figtree (OFL); Phosphor icon generation/licensing is documented in [ICONS.md](ICONS.md).
- `Stage` provides the prototype's 1920×1080 design coordinates (planned per window, see "Screen shapes") and interface scale. The reusable
  focus modifier applies to touch as well as controller elements. `GlobalKeys` handles input before
  focus dispatch; left/right adjust choice/slider rows. A/Enter/D-pad center activate, B goes back,
  Select opens the side menu, Start/X opens the focused element's context menu (if it has one), L1/R1 go to the screen and Y opens search.
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
  ends by itself goes through `StreamOutcome` (no classic error dialog): `DelosStreamActivity` ends, opens
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
- **`DelosStreamActivity` structure** ([#29](https://github.com/Meitoncz/Delos/issues/29)). `DelosStreamActivity` is the streaming
  activity and keeps the lifecycle, the `onCreate` orchestration and thin delegates for its external
  callers (`StreamMenu`, `StreamLoader`, `StreamContainer`, `ExternalDisplayControlActivity`,
  `KeyboardAccessibilityService`). Self-contained parts live in helpers it creates: `StreamIntentParams`
  (launch intent extras, certificate, `NvHTTP` creation; the `EXTRA_*` keys stay on `DelosStreamActivity`),
  `binding/input/CommitTextSender` (IME commit text in ≤512-byte UTF-8 chunks, Backspace for deleted
  text), `utils/StreamClipboardSync` (smart and manual clipboard sync), `utils/WifiLockHolder`
  (high-performance and low-latency Wi-Fi locks for the stream's lifetime),
  `binding/video/StreamDisplayPlanner` (pure: display mode, refresh rate, Cap FPS pacing adjustment,
  aspect ratio and the surface frame rate from plain display and preference data; `DelosStreamActivity` reads the
  `Display` before the `StreamConfiguration` is built and applies the plan to the window and
  `StreamContainer`), `ui/StreamWindowController` (requested orientation, picture-in-picture
  auto-enter and parameters, hiding the overlays and sensors in PiP, multi-window and the immersive
  system UI; `DelosStreamActivity` forwards the Activity callbacks and the USB permission prompt's PiP suppression
  to it and provides a `Host` with the views and on-screen controls) and
  `binding/video/StreamSurfaceController` (the stream surface's buffer size for the upscaler, the
  decoder side of the surface callbacks, the live upscaler and supersampling controls and the launch
  arrival's decoder link; `DelosStreamActivity` forwards the `StreamContainer` callbacks and starts and stops the
  connection through its `Host`; `StreamMenu`, `StreamLoader` and `StreamExit` call it via
  `DelosStreamActivity.getSurfaceController()`). The renderers reach the surface through the narrow
  `StreamSurfaceHost` interface, not the activity. Input lives in `binding/input`:
  `StreamKeyHandler` routes keys (the stream menu, the Full keyboard and gamepad buttons first, then
  the keyboard), tracks the modifiers and the keys it has down on the host (released there when the
  window the keys come from loses focus; the modifier state is cleared on focus gain too), runs the client-side
  Ctrl+Alt+Shift+Z/Q/C combos once every modifier is up and sends the menus' special-key
  combinations (`GameMenu` and `StreamMenu` call it via `DelosStreamActivity.getKeyHandler()`).
  `StreamPointerHandler` handles the pointer sources left after `DelosStreamActivity`'s gamepad passthrough: mice
  (captured or not), styluses and erasers, touchpads and the touchscreen with its touch contexts,
  the 3/4/5-finger taps, the touch sensitivity and the pan and zoom mode (it owns the
  `PanZoomHandler`; `StreamMenu` and `ExternalDisplayControlActivity` call it via
  `DelosStreamActivity.getPointerHandler()`). `MouseModeController` picks the touchscreen's mouse mode (the
  profile's mode, the external display's trackpad override, remote input's forced trackpad, the
  stream menu's switch and its optional persistence) and installs the matching touch contexts in
  the pointer handler (`StreamMenu` calls it via `DelosStreamActivity.getMouseModeController()`). `DelosStreamActivity` keeps the
  Activity and view input callbacks as one-line delegates, the input grab and local cursor, the
  evdev mouse callbacks and the controller wiring, and reaches each handler through a small `Host`
  it implements.
