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
Built on the branch `ui/review-fixes`; the user tried them on the Odin (2026-10-24), except recording custom stream menu buttons.
- [x] The Delos stream menu (`shell/StreamMenu.kt`, the prototype's quick menu): Resume, Keyboard, Performance, More (the classic Artemis quick menu), Disconnect, Quit game. Opens with L3 + R3, or with buttons recorded in Settings → Controls → Stream menu (e.g. the Odin's rear buttons), or with a swipe up from the bottom edge (can be turned off); every way the classic menu opened leads here too. Start + Back + L1 + R1 no longer ends the stream.
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
- [ ] Recording custom stream menu buttons (Settings → Controls → Stream menu buttons), tried on the Odin.
- [ ] The performance statistics overlay in the Delos style (the user: the current one looks like a debug view).
- [ ] The user decides which items the stream menu keeps.

### 6.8 SteamGridDB
- [ ] API key entry (the user's own key), stored locally.
- [ ] Match games by name (search API), with a manual override per game.
- [ ] Download the portrait cover, wide cover, hero and logo for one game or for all games at once; cache them; refresh on demand.
- [ ] Open question from GUI.md: syncing the choices with Vibepollo.

### 6.9 Switch-over and cleanup
- [ ] The new shell becomes the only launcher activity (phone, TV/leanback categories kept).
- [ ] Links and shortcuts go to the new shell: the `art://` intent filter moves from `AddComputerManually` to `ShellActivity`, and `ShortcutTrampoline` falls back to the shell (with `AppView.UUID_EXTRA`) instead of `PcView` / `AppView`. The shell already handles them (`ShellLink.kt`).
- [ ] Remove the old UI once nothing depends on it:
  - `PcView`, `AppView`, the grid adapters, `AdapterFragment`, the old layouts and unused resources;
  - `StreamSettings` and the custom preference classes;
  - dependencies only the old UI used (preference, cardview, recyclerview, possibly appcompat/material – `Game.java` is checked first).
- [ ] Strings: new UI strings in English; existing translations reused wherever a setting keeps its string key.

### 6.10 Polish and testing
- [ ] Side-by-side comparison with the prototype on the Odin, screen by screen.
- [ ] Check with the user on the Odin what could not be tried in 6.6 yet: PIN pairing after unpairing, OTP pairing, adding a PC by address, waking an offline PC from the start screen, removing a PC, "Skip the start screen".
- [ ] Smooth at the Odin's refresh rate; memory use with many covers.
- [ ] Touch, Android 10–12 fallbacks (no blur), Android 15+ edge-to-edge (other devices / later).

Later (Phase 6 backlog, after the switch): restyle the in-stream quick menu (`GameMenu`) in the new design, virtual controls editor.

## Open questions

1. **Profiles.** Decided (2026-10-23): a user profile keeps **all** of its settings.
   - Since 6.6 each profile has its own appearance, start settings, hidden games, last played game and names given to PCs; a new profile starts with a copy of the current one's.
   - In 6.7 the stream settings follow: each user profile gets its own Artemis *settings profile*, switched together with it.
2. **Languages.** The new UI starts in English. Translations come later, reusing the existing ones where possible.
3. **SteamGridDB ↔ Vibepollo sync** (see GUI.md).
