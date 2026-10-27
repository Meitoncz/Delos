# Delos native UI – implementation plan (Phase 6)

Goal: rebuild the Delos "shell" (everything except the stream itself) natively so it looks and behaves like the approved prototype.

- Vision: [GUI.md](GUI.md).
- Reference: [prototype/delos-gui.html](prototype/delos-gui.html). The prototype is the spec, and every screen is compared side by side with it on the Odin.

## Principles

- **The stream stays untouched.** `Game.java`, the decoder, input handling, overlays and the in-stream `GameMenu` keep working as today. The new shell starts a stream exactly like `AppView` does now: `ServerHelper.createStartIntent()` → `Game` activity.
- **The backend is reused, not rewritten.** The new shell uses:
  - `ComputerManagerService` (PC discovery, polling, app lists);
  - `NvHTTP` / `PairingManager` (pairing, box art, quit);
  - `PreferenceConfiguration` with the same SharedPreferences keys, so the stream reads settings unchanged;
  - `ProfilesManager`, `ShortcutHelper` and `ServerHelper`.
- **New code is Kotlin + Jetpack Compose.** Existing Java stays Java. Code shared with Moonlight (`Game.java`, `ControllerHandler.java`, decoder) is not touched, so upstream merges stay small.
- **The old UI keeps working until the new one can replace it.** During development the new UI gets its own launcher entry (debug builds only: "Delos Next"). The old `PcView` / `AppView` / `StreamSettings` are removed only in the final switch step, once nothing depends on them.
- **Controller first, touch works too.** Every screen is fully usable with D-pad / stick + A/B/X/Y and the menu buttons; tapping focuses and activates.
- **No confirmation dialogs** in the new flows (product rule). The only prompts kept are the ones that protect against data loss or pairing with an unknown PC.

## Technology

| Area | Choice | Notes |
|---|---|---|
| Language | Kotlin (built into AGP 9) | Kotlin version and the Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose`) pinned together; latest stable when step 6.1 starts (2.4.x today). |
| UI | Jetpack Compose via the Compose BOM | Latest stable BOM at step 6.1 (2026.09.00 = Compose 1.12 today). Only `foundation` / `ui` / `animation`: **no Material components**, Delos has its own design system. |
| Navigation | Own small back stack + `AnimatedContent` | Console-style transitions (scale/fade, portal, dive) need full control; predictive back via `BackHandler`. No navigation library. |
| Images | Own small loader (`shell/data/BoxArt.kt`) | Server box art via `NvHTTP.getBoxArt` with the pinned certificate, into the classic UI's disk cache, plus a memory cache. Decided in 6.4 instead of Coil: no extra library for one image source; revisited for SteamGridDB in 6.8. |
| Backdrop blur | Haze, or own `RenderEffect` blur | For overlays that blur what is behind them (portal, launch loader, side menu). Android 12+; on Android 10–11 a dimmed translucent layer instead. Chosen in step 6.2 after a quick test on the Odin. |
| Animated backgrounds | Compose `Canvas` (`DrawScope`) | Aurora, Tide, Silk and Contour ported from the prototype's `drawBg()` 1:1 (same math), plus Still. AGSL shaders only if the Canvas version is not smooth at 120 Hz. |
| Fonts | Unbounded (display), Figtree (body), bundled | Both OFL. |
| Icons | Material Symbols Rounded, bundled as a **subset** variable font | Apache 2.0. Subsetting keeps the APK small (the full font is several MB); the filled/outlined focus effect crossfades two font instances. |
| Colors | Own tokens; Material You from `android.R.color.system_accent*` / `system_neutral*` | Real wallpaper palette on Android 12+, seed colors as fallback / manual choice. |
| Sounds, haptics | `SoundPool` with short bundled clips; `View.performHapticFeedback` / `VibratorManager` | Same character as the prototype's blips; both can be turned off. |
| Network (SteamGridDB) | OkHttp + Gson (already in the project) | No new HTTP stack. |

## Steps

Each step ends with a build, a test on the Odin and a commit (after approval), like in the previous phases. Each step is done in its own branch and merged into `main` after CI passes.

### 6.0 Preparation
- [x] Read the Odin's key codes (`adb shell getevent -l`). The built-in controller reports itself as "Xbox Wireless Controller" (vendor `0x2020`, product `0x0112`):
  - left top menu button = `BUTTON_SELECT` → side menu;
  - right top menu button = `BUTTON_START` → context menu;
  - L1 / R1 = `BUTTON_L1` / `BUTTON_R1`.

  System `HOME`, `BACK` and `APP_SWITCH` come as separate codes and are left to the system.
- [x] Library versions: Kotlin 2.4.21 (AGP 9.4 itself only brings 2.2.10), Compose BOM 2026.09.00, activity-compose 1.13.0, lifecycle 2.11.0, coroutines 1.11.0.
- [x] Licences of the bundled fonts: Unbounded and Figtree – SIL Open Font License 1.1; Material Symbols – Apache 2.0. Both allow bundling in the APK.

### 6.1 Build setup
- [x] Enable Kotlin and Compose in `app/build.gradle`: compose compiler plugin, `buildFeatures.compose`, BOM, `activity-compose`, `lifecycle-viewmodel-compose`, coroutines.
- [x] Empty `ShellActivity` (`com.limelight.shell`, Compose, fullscreen, immersive) as a second launcher entry "Delos Next" in debug builds (`src/debug/AndroidManifest.xml`, own task affinity).
- [x] CI still green; APK size check: debug arm64 34.7 → 51.5 MB (debug builds are not shrunk). Release is unchanged at 13.6 MB because the new UI is not declared there yet and R8 removes it; the real release impact is measured in step 6.9.

### 6.2 Design system and frame
- [x] Theme tokens: dark/light, Material You from the system palette, seed colors (`shell/ui/Colors.kt`, same formulas as the prototype; settings in `shell/settings/AppearanceStore.kt`, `ui_*` keys).
- [x] Layout units: a 1920×1080 stage like the prototype (`Stage.kt`: `px` for sizes, `tpx` for text incl. the interface scale).
- [x] Typography with the bundled fonts, icon font subset (`tools/fonts/subset_fonts.py`, icons listed in `tools/fonts/icons.txt` → `Symbols.kt`), motion curves from the prototype (`Motion.kt`).
- [x] Focus styling (ring, scale, glow, sweep, muted idle covers) as a reusable modifier (`Focus.kt`). Elements are always focusable, also in touch mode.
- [x] Animated backgrounds: Aurora, Tide, Silk, Contour, Still, blended with the game background ("hero") as in the prototype (`Backdrop.kt`); time-based, so 120 Hz screens animate at the same speed.
- [x] Backdrop blur: Haze 2.0.1 (`hazeSource` / `hazeBlur`), dimmed fallback below Android 12.
- [x] UI sounds (`tools/sounds/make_ui_sounds.py`, identical to the prototype's blips) and haptics; the system click sound is turned off. Devices with only a rumble motor (no resonant frequency reported, e.g. the Odin; on Android 13 and older it cannot be read) get full-strength pulses instead of the system ticks, tuned on the Odin: 50 ms per move, 70 ms per confirmation.
- Temporary test screen `shell/demo/DesignDemo.kt` (replaced by the real shell in 6.3).

### 6.3 Shell
- [x] Fullscreen on every screen (`WindowInsetsController`). Own **status bar** (brand, PC pill, top nav with tooltips, Wi-Fi, battery and clock from the system: `shell/system/DeviceStatus.kt`, clock follows the system 12/24 h setting) and **hint bar** with button glyphs (`ui/Bars.kt`).
- [x] Input layer: A / Enter / D-pad center activate, B = back (system fallback), Select = side menu, Start or X = context menu, L1 / R1 forwarded to the current screen (`GlobalKeys.kt`, handled in the activity before the focus dispatch). One-press directional navigation via the Compose 2D focus search; left/right adjust choice and slider rows.
- [x] Screens with the prototype's transition (fade + slight zoom), side menu sliding in from the left, context menu at the focused element, blurred scrim, toasts (`Shell.kt`, `ShellState.kt`, `ui/Layers.kt`). Layers trap the focus and return it to where it was when they close.
- [x] Interface scale (100–130 %, default 115 %): the status and hint bars are zoomed as a whole, text elsewhere grows.
- [x] The hint bar shows the menu buttons as printed on the device: a square and a play arrow on AYN handhelds, the View and menu icons elsewhere (icons in a fixed circle, letters in ExtraBold). A top nav tooltip no longer widens the bar.
- [x] Settings rows (toggle, choice, slider, action, info) as a shared component (`ui/Rows.kt`); scrolling lists fade at the edges and keep the focused row clear of the fades.
- Previews until the real screens exist: home with placeholder games, settings with the Appearance category, placeholder profile and devices screens.
- [ ] The analog stick moving the focus like the D-pad needs a check on the device (Android synthesizes D-pad keys from the stick).

### 6.4 Home
- [x] Data: `ComputerManagerService` bound through `shell/data/PcRepository.kt` (`StateFlow`s of PCs and of the active PC's library, cached app list first like `AppView`, polling only while the shell is started). Active PC = last chosen, else the first paired one (online preferred). Hidden apps share the classic UI's `HiddenApps` preferences.
- [x] Grid layout (5 columns, wide first tile, the focused row anchored under the info, top/bottom fades) and Row layout (one horizontal row, focused tile anchored left, edge fades), both with the prototype's fast scroll (200 ms ease-out). Lazy lists, so large libraries stay smooth.
- [x] Focused game: eyebrow, title and chips on top; the game background follows the focus with a short delay (no flicker while scrolling) and crossfades.
- [x] Covers: the PC's box art through the classic UI's disk cache (`shell/data/BoxArt.kt`, no extra image library needed; SteamGridDB comes in 6.8), procedural placeholder art while loading or when there is none. The running game comes first, then the last played one.
- [x] Context menu for a game: start (wired up in 6.5), hide / show again; the side menu can show hidden games.
- [x] Loading, no PC, PC offline and empty library states.
- [x] Game background over the whole screen, fading out diagonally from the top-right to the bottom-left corner (owner's choice).
- [x] Performance, measured on the Odin with `dumpsys gfxinfo` (framestats): scrolling runs at 120 fps with about 1 % late frames. What it took:
  - the game background is rendered once per game and blur into a small bitmap off the main thread (`ui/HeroBitmap.kt`), instead of blurring a full-screen layer every frame (that alone cost ~15 ms per frame while scrolling);
  - the animated background redraws at most ~60 times a second (it moves slowly), Contour lines are drawn as one batched line call per level with butt caps (stroking hundreds of path pieces per frame was slow; round caps showed dots at the joints);
  - the Haze blur source is only active while an overlay is visible;
  - focus animations are read only while drawing (no recomposition per animation frame), tile parameters are stable so a focus move recomposes just the two affected tiles, the idle cover dimming is an image color filter instead of a layer effect.

### 6.5 Launching a game
- [x] One continuous launch scene (`ui/LaunchOverlay.kt`): the game background, the ring filling around the cover, the title and the connection stage. The new UI fades it in over the home screen, then the stream window (`StreamLoader.kt`, instead of the "Starting RTSP handshake" dialog) takes it over without a visible switch: no starting window, a scale-up window animation that only fades, the system bars hidden from the first frame, the pulses and the moon going on from the same clock.
- [x] Moonlight's connection stages as playful but accurate lines under the title ("Haggling with the PC over pixels and frame rate"…), each shown at least 0.7 s.
- [x] The dive: when the stream runs, the cover gives way to it inside the ring and the ring opens past the screen edges, slowly at first, then rushing past. Pulses and the moon of the Delos logo circle the ring and fly past with it.
- [x] Running game on the PC: resume or quit directly from the home screen, without "are you sure" dialogs. This covers part of the open Phase 4 item "fewer confirmation dialogs".
- [x] Return from the stream back to the focused game.
- [x] A more spectacular dive (GUI.md): zoom blur, a light chromatic aberration and wind streaks as an AGSL shader on Android 13+ (`ui/DiveEffect.kt`; older versions just open the ring).
- [ ] Fine-tune the dive shader and the effects around the ring (more ideas to come).
- [ ] Investigate: when a game (not the desktop) is started, wait until it has loaded on the PC before connecting, so the stream does not open on the desktop with Steam still starting the game.

### 6.6 First launch, PCs and pairing
- [x] First launch (`screens/FirstRunScreens.kt`): the logo with "Get started" (and "Skip to home" when PCs are paired already), then creating a profile: avatar in circles + name (`data/Profiles.kt`).
- [x] Profiles keep all their own settings (open question 1): appearance, start settings, hidden games, last played game, the PC home shows and names given to PCs, each in its own preferences file; a new profile starts with a copy of the current one's. Profile screen: change avatar, rename, switch, add.
- [x] Start screen on every launch (`screens/DeviceScreens.kt`): the paired PCs in circles (the default one marked), the current profile in the top-left corner (switch or create one); settings "Default device" and "Skip the start screen".
- [x] PC list in circles from discovery, add a PC by address, search again (`screens/PairScreens.kt`, `data/PcSetup.kt`). Choosing an offline PC sends Wake-on-LAN and the portal waits until it is online (B cancels).
- [x] Pairing screen with the PIN, or a one-time code from Vibepollo (OTP), then the portal into home (`ui/PortalOverlay.kt`).
- [x] Devices screen: connect, client-side rename, wake, unpair, remove, pair a new PC.
- [x] The new shell handles `art://` pairing and launch links (with a security prompt) and PC shortcuts (`ShellLink.kt`). The system still sends them to the classic UI until 6.9.

### 6.7 Settings
- [x] Settings screen with icon tabs (L1/R1), rows (toggle, choice, slider, text, action) and a description panel. Each row writes the same keys as today's `preferences.xml` (116 controls in 13 categories; the earlier total of 126 included ten category keys), so `PreferenceConfiguration` and the stream keep working unchanged. See [SETTINGS.md](SETTINGS.md).
- [x] Port the device-dependent logic of `StreamSettings`, category by category:
  - resolution and refresh rate lists for the display;
  - codec and HDR capability checks;
  - options hidden on devices that do not support them;
  - resetting on-screen controls and the keyboard layout.
- [x] Quick search across all settings (type or pick a letter with the controller, jump straight to the row).
- [x] New Delos settings: Appearance (theme, Material You, colors, background, home layout, game background blur/opacity, interface scale, animations) and a Covers information tab (SteamGridDB implementation remains 6.8).
- [x] Stream settings per user profile: each profile gets its own Artemis settings profile, switched together with it (open question 1). New profiles copy stream settings and editable control layouts from the current profile (approved 2026-10-23).
- [x] The user started a stream on the Odin and confirmed that the changed resolution was applied (2026-10-23).
- [x] Y opens search and appears in the hint bar. Verified on the Odin together with controller search/jump, single feedback per tab switch and complete section headings on upward focus navigation.

### 6.7b Additions requested on 2026-10-23
Built on the branch `ui/review-fixes`; the user tried them all on the Odin (2026-10-24).
- [x] The Delos stream menu (`shell/StreamMenu.kt`, the prototype's quick menu): Resume, Keyboard, Performance (later Overlay), More (the classic Artemis quick menu), Disconnect, Quit game. Opens with L3 + R3, or with buttons recorded in Settings → Controls → Stream menu (e.g. the Odin's rear buttons), or with a swipe up from the bottom edge (can be turned off); every way the classic menu opened leads here too. Start + Back + L1 + R1 no longer ends the stream.
- [x] Clock: System, 12-hour or 24-hour.
- [x] Battery in the style of iOS: light outline, green charge (red at 20 % and below), percentage beside it, a bolt and a pulsing green while charging.
- [x] Material You uses only the system's wallpaper colors; with it off a color palette can be chosen (Delos, Lagoon, Dusk, Ember, Moss, Iris and the new Rose, Sunset, Mint, Gold).
- [x] Deleting a profile (profile screen; asks once, since its settings are lost).
- [x] Review fixes after 6.7: the stream window reads the profile's appearance without side effects; the welcome replayed from the settings returns there with B.
- [x] Start screen: the profile at the top center; menus open without a clipped frame (blur source on in the same frame, full-screen animation layer); the profile icon is always the generic user icon.
- [x] The animated background takes the main color of the game's background image (cover now, SteamGridDB hero from 6.8), as the setting "Match the game's colors" (palettes only).
- [x] Stream menu "More" as a Delos page (send keys, clipboard, server commands, zoom, mouse mode, on-screen controls…); toggles keep the menu open and show On / Off.
- [x] Removed for good: the Artemis back menu switch, floating menu button, Back as Meta / Guide and the overlay opening the menu.
- [x] The start screen's pulse around the focused game on the home screen.
- [x] Recording custom stream menu buttons (Settings → Controls → Stream menu buttons), tried on the Odin.
- [x] The performance statistics overlay in the Delos style (the user: the current one looks like a debug view). Built on `ui/stream-stats`: the prototype's panel, top left by default, the corner can be chosen in Settings → Display → Statistics position.
- [x] Small fixes (2026-10-24, branch `ui/small-fixes`): the game background preview only for its own settings rows; frosted glass (backdrop blur) for the focused settings row, the top bar, the description panel and every control right over the animated background (circles, pills, settings tabs, text and PIN fields, top bar labels), not inside overlays; game background blur in 5 px steps.
- [x] Settings audit, first part: no "(Experimental)" / "(Beta)" in titles, device-dependent features say so in their descriptions; the post-stream latency message removed with its code; "Move overlay to bottom" hidden in the new settings (Statistics position replaces it).
- [x] Statistics with everything the classic overlay shows (decoder, incoming / rendering FPS, network latency and variance, PC processing min / average / max, decoding, bandwidth, lost frames); Compact mode (the former Lite mode) is one line at the top center; the stream menu's Performance is now Overlay and cycles On → Compact → Off.
- [x] The stream menu shortcut closes the menu too; "Use L3 + R3 again" removed. The 12-hour clock shows AM / PM.
- [x] Settings audit, second part: removed Enable Full Screen (always immersive), Optimize game settings (GFE only, now always off), the overlay zoom button (Zoom stays in the stream menu's More) and the Software Update / Follow Update links (the repository is private).
- [ ] The user decides which items the stream menu keeps.
- [x] Leaving the stream (Disconnect / Quit game) with the prototype's effect (`shell/StreamExit.kt`, branch `ui/stream-exit`, tried on the Odin): the portal opens over the stream ("Back to Delos" / "Closing <game>", the PC below; in a window of its own, so the system blurs the stream behind it at 50 px; the stream zooms in by 12 % with an ease-out) and bursts after 2.1 s; the new UI then comes to the front with a scale-up fade instead of Android's slide, finishes the burst and keeps the focus on the game. The game gets no input meanwhile.
- [x] The stream menu's Overlay choice (On / Compact / Off) is kept in the profile for the next streams.
- [ ] Later (the user's decision on 2026-10-24: kept as they are for now): the other ways a stream ends (connection lost, the game closed on the PC, errors) still slide back and show the classic messages; give them the portal and Delos-style screens.

### 6.8 SteamGridDB
Built on the branch `ui/steamgriddb` (`shell/data/SteamGridDb.kt`, `screens/ArtworkScreen.kt`); the user tried it on the Odin (2026-10-24).
- [x] API key entry (the user's own key, Settings → Covers), kept on the device for every profile.
- [x] Match games by name (search API); the remote desktop is left out.
- [x] Download the portrait cover, wide cover, hero and logo, the best-rated of each, for all games at once: automatically for games never looked up (can be turned off), "Download missing artwork" and "Download all artwork again" (hand-picked images stay). Kept in the app's files, not the cache.
- [x] The artwork screen (a game's options → Artwork): a tab per kind (L1 / R1), "Currently used" with its source, every SteamGridDB image to choose from (or none: the PC's artwork, the name instead of a logo), "Change game" to fix the match, also under another name.
- [x] Home: the SteamGridDB cover on the tiles, the wide cover on the first one, the hero as the game background, the logo instead of the name; without a logo the name has no icon and its gradient takes the game background's color. The stream's loader uses the SteamGridDB cover.
- [x] Progress: a spinner on each game being downloaded and a bar with the count above the button hints.
- [ ] Open question from GUI.md: syncing the choices with Vibepollo (Vibepollo has no API for it yet; later).

### 6.9 Switch-over and cleanup
Built on the branch `ui/switch-over` (2026-10-24). Delos is still not released; releases are the user's decision.
- [x] The new shell is the only launcher activity (phone, multi-window, TV/leanback and OUYA categories), in every build type; the debug-only "Delos Next" entry is gone.
- [x] Links and shortcuts go to the new shell: the `art://` intent filter moved from `AddComputerManually` to `ShellActivity`, and PC shortcuts (`ShortcutTrampoline`) open the shell with the PC's UUID. App shortcuts still start the stream directly.
- [x] Old UI removed: `PcView`, `AppView`, the grid adapters, `AdapterFragment`, `ProfilesActivity` / `EditProfileActivity` (the profile data, `ProfilesManager`, stays: the stream reads it), `StreamSettings`, `AddComputerManually` and the custom preference classes; their layouts, drawables, menus and about 190 strings and other values in every language; their tests. `preferences.xml` stays as the settings catalog of the new UI.
- [x] Debug info is a Delos screen (Settings → About → Debug info): the device, a vibration test with its strength, the connected controllers (ids, motion sensors, vibration, full details in the panel).
- [x] The classic-only settings are gone: the resume dialog, small icons and "Move overlay to bottom".
- [x] Dependencies only the old UI used: CardView and RecyclerView. AppCompat and Material stay (the stream and the help / shortcut activities use them).
- [x] Kept: `HelpActivity` (help pages from stream errors on devices without a browser), `ShortcutTrampoline`, the TV channel and launcher shortcuts the stream reports.
- [ ] Strings: new UI strings in English; existing translations reused wherever a setting keeps its string key (with the translations later).

### 6.10 Polish and testing
- [x] External display: Delos loader and stream menu, configured stream resolution/FPS, HDR, controller navigation from the built-in Odin controls, and clean return to the shell. See [EXTERNAL_DISPLAY.md](EXTERNAL_DISPLAY.md).
- [ ] Side-by-side comparison with the prototype on the Odin, screen by screen.
- [ ] Check with the user on the Odin what could not be tried in 6.6 yet: PIN pairing after unpairing, OTP pairing, adding a PC by address, waking an offline PC from the start screen, removing a PC, "Skip the start screen".
- [ ] Smooth at the Odin's refresh rate; memory use with many covers.
- [ ] Touch, Android 10–12 fallbacks (no blur), Android 15+ edge-to-edge (other devices / later).
- [ ] "Check for updates" in Settings → About, in the Delos style, once the repository is public (replaces the removed Software Update / Follow Update links).

### 6.11 Colors and backgrounds (2026-10-24, branch `ui/feedback-colors`)
- [x] Every palette has three colors: main (buttons, focus, selection), secondary (rings, pulses, gradients) and accent (labels above titles, the setting rows' icons and arrows, the download progress, a third light in the animated backgrounds); Material You takes the wallpaper's tertiary color as the accent. The palette row shows the three colors in the description panel (only there).
- [x] Ten new animated backgrounds to choose from: Nebula, Mesh, Plasma, Lava, Caustics, Grid, Bokeh, Orbit, Starfield (shaders, Android 13+, Aurora instead below or if one does not compile) and Constellation (canvas). The user picks the ones to keep.
- [x] "Match the game's colors" takes the focused game's three main colors from its cover (SteamGridDB, else the PC's), with their saturation, in place of the palette's three on every background; the previous game's colors stay until the next one's are known, so nothing flashes. The colors: pixels grouped into color buckets, judged in CAM16 (Material Color Utilities' Hct): near-black, near-white and grey tones left out, weight = share × chroma² summed per 15° of hue, the three strongest hue groups at least 30° apart; one- or two-color covers get close shades. Material You's own quantizer (Wu) merges covers of a few flat colors into one average, so it is not used. Unit tests in `GameColorsTest`.
- [x] Desktop gets the Artwork option (chosen by hand; never looked up automatically by its name).
- [x] The user picked the backgrounds: Nebula, Plasma, Lava, Caustics, Bokeh and Orbit stay; Mesh, Grid, Constellation and Starfield are gone. Tide now fills the lower two thirds; Contour is a shader on Android 13+, so its lines are smooth curves (the canvas version below).
- [x] Status bar: while charging, a ring pulses out from the battery like the one around the focused cover; Wi-Fi and the battery percentage in the clock's color.
- [x] Row layout: the row stops at its end with the last cover as far from the right edge as the first game is from the left one (both focused; computed from the cover sizes and the focus scale); left on the first game and right on the last one go round to the other end: a far one fades out for a moment and jumps 12 covers short, the rest glides in 0.36 s at full speed into a spring that overshoots the end and settles back.
- [x] The library is ordered by when each game was last played (per PC and profile), the running game first, never played ones after them in the PC's order.
- [ ] Responsive layouts for other screen shapes (the stage is 1920 × 1080 and only scales); with the 6.10 device checks.
- [ ] Built-in artwork for Desktop and similar apps from the user's PNGs (cover 600 × 900, wide 920 × 430, background 1920 × 1080, logo transparent PNG about 1800 × 400), once the user names the apps.
- [x] New on-screen controls graphics (`OscStyle`, tried on the emulator and the Odin): thin (1 dp) light outlines over a faint dark glass, Figtree labels in capitals, whatever is pressed filled with the profile's main color (clearly, whatever the controls' opacity); circles for face buttons and L3 / R3, pills for shoulders, triggers, Back / Guide / Start and the trackpad, a D-pad cross with chevrons, sticks with a reach ring and a knob; the keyboard layout's keys in the same look. The classic skin setting and its 29 images are gone. Back, Guide and Start sit in a row centred at the bottom, the trackpad centred above them (L3 / R3 overlapping the D-pad and the right stick is kept on purpose; anyone can move them).
- [x] Debug info → Preview on-screen controls: the controls without a stream (nothing is sent), with the layout editor.

Later (Phase 6 backlog; the user on 2026-10-24: not urgent, for the first "final" version): restyle the classic in-stream quick menu (`GameMenu`) and the virtual controls editor in the new design. The stream menu's items stay as they are. Next after 6.9 the focus is on core features: PyroWave and VRR, PS Remote Play (docs/FUTURE_PLANS.md).

## Open questions

1. **Profiles.** Decided (2026-10-23): a user profile keeps **all** of its settings.
   - Since 6.6 each profile has its own appearance, start settings, hidden games, last played game and names given to PCs; a new profile starts with a copy of the current one's.
   - In 6.7 the stream settings follow: each user profile gets its own Artemis *settings profile*, switched together with it.
2. **Languages.** The new UI starts in English. Translations come later, reusing the existing ones where possible.
3. **SteamGridDB ↔ Vibepollo sync** (see GUI.md).
