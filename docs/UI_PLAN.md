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
| Images | Coil 3 | Custom fetcher for server box art (`NvHTTP.getBoxArt` with the pinned certificate) and plain HTTPS for SteamGridDB; memory + disk cache. |
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
- [ ] Theme tokens: dark/light, Material You from the system palette, seed colors.
- [ ] Typography with the bundled fonts, icon font subset, shapes, motion specs (springs, durations from the prototype).
- [ ] Focus styling (ring, scale, glow, sweep) as reusable modifiers.
- [ ] Animated backgrounds: Aurora, Tide, Silk, Contour, Still; blended with the game hero image.
- [ ] Backdrop blur decision (Haze vs. own) and fallback below Android 12.
- [ ] UI sounds and haptics.

### 6.3 Shell
- [ ] Fullscreen on every screen (`WindowInsetsController`). Own **status bar** (brand, connected PC pill, top nav, Wi-Fi, battery, clock) and **hint bar**.
- [ ] Input layer: gamepad mapping (A/B/X/Y, L1/R1, both menu buttons), stick with key repeat, one-press directional navigation everywhere (Compose 2D focus search, tuned where needed).
- [ ] Navigation stack and screen transitions; side menu sliding in from the left (left menu button, View/Select) and context menu (right menu button, Start); toasts.
- [ ] Interface scale (100–130 %, default 115 %) applied to the status bar, hint bar and text.

### 6.4 Home
- [ ] Data: `ComputerManagerService` bound through a Kotlin repository (`StateFlow` of PCs and the app list of the active PC, cached list first like `AppView`).
- [ ] Grid layout (wide first tile, vertical scroll, top/bottom fades) and Row layout (one horizontal row, focused tile anchored left, edge fades). Fast scroll animation (200 ms).
- [ ] Focused game: logo, eyebrow and chips on top; hero background crossfade with configurable blur and opacity.
- [ ] Covers: server box art for now (SteamGridDB comes in 6.8), with a generated placeholder while loading.
- [ ] Context menu for a game (start, start with/without virtual display, hide, details).

### 6.5 Launching a game
- [ ] Circular loader with the cover, blurred home behind it, then the "dive" transition into `Game`.
- [ ] Running game on the PC: resume or quit directly from the home screen, without "are you sure" dialogs. This covers part of the open Phase 4 item "fewer confirmation dialogs".
- [ ] Return from the stream back to the focused game.

### 6.6 First launch, PCs and pairing
- [ ] Profile creation (name + avatar in circles).
- [ ] PC list in circles from discovery, add a PC manually, wake-on-LAN.
- [ ] Pairing screen with the PIN (and OTP pairing for Vibepollo), then the portal animation into home.
- [ ] Devices screen: client-side rename, wake, unpair, remove.
- [ ] `art://` links, pinned shortcuts and `ShortcutTrampoline` open the new shell.

### 6.7 Settings
- [ ] Settings screen with icon tabs (L1/R1), rows (toggle, choice, slider, action) and a description panel. Each row writes the same keys as today's `preferences.xml` (126 keys, 13 categories), so `PreferenceConfiguration` and the stream keep working unchanged.
- [ ] Port the device-dependent logic of `StreamSettings`, category by category:
  - resolution and refresh rate lists for the display;
  - codec and HDR capability checks;
  - options hidden on devices that do not support them;
  - resetting on-screen controls and the keyboard layout.
- [ ] Quick search across all settings (type or pick a letter with the controller, jump straight to the row).
- [ ] New Delos settings: Appearance (theme, Material You, colors, background, home layout, game background blur/opacity, interface scale, animations) and Covers (SteamGridDB).
- [ ] Settings profiles (Artemis feature) in the new UI – see open questions.

### 6.8 SteamGridDB
- [ ] API key entry (the user's own key), stored locally.
- [ ] Match games by name (search API), with a manual override per game.
- [ ] Download the portrait cover, wide cover, hero and logo for one game or for all games at once; cache them; refresh on demand.
- [ ] Open question from GUI.md: syncing the choices with Vibepollo.

### 6.9 Switch-over and cleanup
- [ ] The new shell becomes the only launcher activity (phone, TV/leanback categories kept).
- [ ] Remove the old UI once nothing depends on it:
  - `PcView`, `AppView`, the grid adapters, `AdapterFragment`, the old layouts and unused resources;
  - `StreamSettings` and the custom preference classes;
  - dependencies only the old UI used (preference, cardview, recyclerview, possibly appcompat/material – `Game.java` is checked first).
- [ ] Strings: new UI strings in English; existing translations reused wherever a setting keeps its string key.

### 6.10 Polish and testing
- [ ] Side-by-side comparison with the prototype on the Odin, screen by screen.
- [ ] Smooth at the Odin's refresh rate; memory use with many covers.
- [ ] Touch, Android 10–12 fallbacks (no blur), Android 15+ edge-to-edge (other devices / later).

Later (Phase 6 backlog, after the switch): restyle the in-stream quick menu (`GameMenu`) in the new design, virtual controls editor.

## Open questions

1. **Profiles.** The prototype has a *user profile* (name + avatar). Artemis has *settings profiles* (sets of stream settings). Options:
   - keep both;
   - attach a settings profile to the user profile.
   To be decided in step 6.7.
2. **Languages.** The new UI starts in English. Translations come later, reusing the existing ones where possible.
3. **SteamGridDB ↔ Vibepollo sync** (see GUI.md).
