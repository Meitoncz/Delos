# Dual-screen and second displays

Issue [#76](https://github.com/Meitoncz/Delos/issues/76). This page covers what Delos knows about the displays of a device
(`DisplayInventory`, `DisplayClassifier`; phases 1 and 2 of the plan), the companion window that goes on a built-in second panel
(phase 3: activity host, lifecycle, the Main screen setting), what it shows (phase 4: variant A, "Details and actions") and how to test it.

Two different things must never be mixed up:

- an **external display**: a monitor, a TV or a wireless display, attached for a while (the Odin to a TV over USB-C). The
  external-display mode streams to it ([EXTERNAL_DISPLAY.md](EXTERNAL_DISPLAY.md));
- a **built-in companion**: a second panel that is part of the device and always there (the bottom panel of the AYN Thor,
  the top panel of the Ayaneo Pocket DS). The external-display mode must never choose it.

## Roles

`DisplayInventory.list(context)` returns every display `DisplayManager.getDisplays()` shows, lowest id first, each with a
`DisplayRole`:

| Role | Meaning | May be the stream target of the external-display mode |
|---|---|---|
| `MAIN` | the default display, id 0 | no (it is the device's own screen) |
| `BUILT_IN_COMPANION` | a second panel of the device | **no** |
| `EXTERNAL` | monitor, TV, wireless display | **yes** (the only role) |
| `VIRTUAL` | overlay display, app-created virtual display, private display | **no** |

`ServerHelper.getExternalDisplay` (formerly `getSecondaryDisplay`, which returned the first display with an id other than 0)
is `DisplayInventory.external`: the first display with the role `EXTERNAL`. `getActiveDisplay`, `createStartIntent`, the
display listener of `DelosStreamActivity` and `ExternalDisplayControlActivity` all go through it. With a real monitor or TV
the behaviour is the same as before.

## Classification rules

`DisplayClassifier.classify` is a pure function of `DisplayFacts` (read from the public `Display` API) and the user's
override; `DisplayClassifierTest` is the table. For a display that is not the default one, the first rule that applies wins:

1. Override "Part of the device": `BUILT_IN_COMPANION`, whatever the display is.
2. `FLAG_PRIVATE`: `VIRTUAL`.
3. The `type` field of `Display.toString()`: OVERLAY or VIRTUAL give `VIRTUAL`, WIFI and EXTERNAL (HDMI on Android 10) give
   `EXTERNAL`, INTERNAL (BUILT_IN on Android 10) gives `BUILT_IN_COMPANION`.
4. The product info (`Display.getDeviceProductInfo().getConnectionToSinkType()`, **API 31+**, not 34): BUILT_IN gives
   `BUILT_IN_COMPANION`, DIRECT and TRANSITIVE give `EXTERNAL`; UNKNOWN and "no product info" say nothing.
5. The name contains "overlay" or "virtual" (English only): `VIRTUAL`.
6. No `FLAG_PRESENTATION`: `BUILT_IN_COMPANION`.
7. Otherwise `EXTERNAL`.

With the override "External display", a `BUILT_IN_COMPANION` result becomes `EXTERNAL`; a `VIRTUAL` display stays `VIRTUAL`
(the override can never make an overlay, a virtual or a private display a stream target). The default is built so that
**anything not clearly built-in and not clearly virtual is external**: the external-display mode was accepted on a real
monitor and TV, and a wrong "external" on a panel is cured by the override, while a wrong "companion" on a monitor would break
the feature without a visible reason.

### What is known, what is a guess

| Signal | Status |
|---|---|
| An internal local display gets **no** `FLAG_PRESENTATION`; a non-internal one always does; so do overlay and wireless displays. A private virtual display and the OEM-configured private ports get `FLAG_PRIVATE`. | **Known**: AOSP `LocalDisplayAdapter` (Android 13 source) and the emulator below. |
| Names: "Built-in Screen" for **every** internal display, "HDMI Screen" for external ones, "Overlay #N". | **Known, but locale-bound**: the system builds them from string resources, translated into the system language ("Integrierter Bildschirm", "Překryvná vrstva č. 1"). A name can prove "virtual" only in English and never proves "built-in", so it decides nothing else. A real monitor's name is not its EDID name. |
| `type` in `Display.toString()` (`type INTERNAL`, `type EXTERNAL`, `type OVERLAY`, ...). | **Best effort, absent on the API 33 emulator**: debug text of `DisplayInfo`, not API. The AOSP sources of Android 10, 12 and 14 print `, type <NAME>` there, but the text an app gets from `Display.toString()` on the emulator stops at `committedState` (observed), so the hint is "unknown" there and the other rules decide. `dumpsys display` shows the type, which is how the rules were checked. Debug info shows what the device returns ("type text"). |
| `getDeviceProductInfo()` / connection to sink | **Known to exist from API 31**; what a given panel or monitor reports was **not seen on a real device**. The emulator's internal display has product info with sink type UNKNOWN (0), an overlay display has none. |
| Display `FLAG_SECURE`, `FLAG_ROUND`, size, density, refresh rate, state | Listed in Debug info, **not used** as evidence (no size separates a small panel from a small monitor; a closed lid is a state). |
| How the AYN Thor, the Ayaneo Pocket DS and the Retroid add-on report their second panel; whether a vendor builds it as a normal internal display | **Unknown**: no such device was available. The rules assume AOSP behaviour; the override and the Debug info listing are the way to correct and to report it. |
| What the Odin reports for a monitor on USB-C | **Not seen in this work** (no ADB to the Odin). AOSP gives it `type EXTERNAL`, `FLAG_PRESENTATION` and EDID product info, which all three rules classify as `EXTERNAL`. Confirm once with a monitor attached: Debug info > Displays must show it as External. |

## The override

Settings > Display > **Second display** (device-wide, key `display_second_role` in the default shared preferences, which are
excluded from backup, so it never travels to another device):

| Entry | Effect |
|---|---|
| Automatic (default) | the rules above |
| External display | every display other than the default one that is not virtual counts as external |
| Part of the device | every display other than the default one counts as a built-in companion (an overlay too, so it can stand in for a second panel when testing) |

## Where to see it

- **Settings > About > Debug info > Displays**: one row per display with its name, the role as the value, and in the panel on
  the right the id, size, density, refresh rate, state, flags, the two raw signals (the type text and the product info sink
  type) and the reason for the role. The list follows displays that come and go.
- **Diagnostic log** (tag `Display`): one line per display at process start and one for every display added, changed (only when
  the line differs) or removed, for example
  `Display present: id 4 "Overlay #1" 1240x1080 420 dpi 60.0 Hz, state ON, flags PRESENTATION: VIRTUAL (the name says overlay or virtual)`
  (as written on the emulator).
  It enters every shared report. A wireless display's name (a name somebody gave it) is replaced by "wireless display"; the
  unique id of a display is never read.

## How to test with an overlay display

Emulator or device, layout and classification checks only, never for streaming or performance:

```
adb -s <device> shell settings put global overlay_display_devices "1240x1080/420"   # the Thor's bottom panel
adb -s <device> shell settings delete global overlay_display_devices                # remove it
```

(`put ... ""` is rejected by the shell: use `delete`.) The overlay display appears as "Overlay #1", 1240x1080 at 420 dpi.
Expected: `VIRTUAL` in Automatic and in External display, `BUILT_IN_COMPANION` with Part of the device, and the external-display
mode with "Fully external display mode" on never moves the stream to it. A real external monitor with Part of the device
shows the other half: it becomes a companion and the external-display mode ignores it.

### What the Android 13 emulator reports (AVD `delos-layout`, API 33, `dumpsys display`)

Display 0, no overlay:

```
DisplayDeviceInfo{"Built-in Screen": uniqueId="local:4619827259835644672", 1920 x 1080, ..., density 420, touch INTERNAL, rotation 0, type INTERNAL, address {port=0, ...}, deviceProductInfo DeviceProductInfo{name=EMU_display_0, manufacturerPnpId=GGL, productId=1, ..., connectionToSinkType=0}, ...
mBaseDisplayInfo=DisplayInfo{"Built-in Screen", displayId 0", displayGroupId 0, FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_TRUSTED, real 1920 x 1080, ..., type INTERNAL, uniqueId "local:4619827259835644672", ...
```

With `overlay_display_devices` set to `1240x1080/420`:

```
DisplayDeviceInfo{"Overlay #1": uniqueId="overlay:1", 1240 x 1080, ..., density 420, 420.0 x 420.0 dpi, ..., touch VIRTUAL, rotation 0, type OVERLAY, deviceProductInfo null, state ON, ..., FLAG_PRESENTATION, installOrientation 0}
mBaseDisplayInfo=DisplayInfo{"Overlay #1", displayId 3", displayGroupId 0, FLAG_PRESENTATION, FLAG_TRUSTED, real 1240 x 1080, ..., type OVERLAY, uniqueId "overlay:1", app 1240 x 1080, density 420 (420.0 x 420.0) dpi, layerStack 3, ..., deviceProductInfo null, ...}
```

So an overlay display is `type OVERLAY`, `FLAG_PRESENTATION` and no product info, and it is the only second display the emulator
offers: it cannot show what a built-in second panel looks like. The built-in side of the table (no `FLAG_PRESENTATION`, `type
INTERNAL`) comes from the AOSP source and is covered by unit tests, not by an observation.

## The companion screen

On a device with a display classified `BUILT_IN_COMPANION` the two panels have different jobs: the **main** panel shows the
shell and the stream, the other one shows the **companion screen**. With no such display (every single-screen device, a TV or
monitor, an overlay in Automatic or External display mode) none of this exists and nothing behaves differently: `DualScreen.plan`
returns null, `placeOnMain` leaves the launch options alone, `mainDisplayId` is the default display.

### Which panel is main (Settings > Display > Main screen)

`DualScreenPlanner.plan` (pure, `DualScreenPlannerTest`) takes the display list and the setting `display_main_screen` (device-wide,
default shared preferences, not backed up) and returns the pair of panels:

- the two panels are the default display (role `MAIN`) and the built-in companion (the lowest id if there are several);
  external and virtual displays are never part of the pair;
- **Automatic**: the panel with more pixels is main; equal panels: the default display (where the launcher put the app);
- **Swapped**: the other panel.

The row exists only while the device has a companion display (`rememberDualScreenPlan`, which follows displays being added or
removed and both display settings), so it appears when an overlay display is switched on and set to "Part of the device", and goes
when it is switched off. Debug info > Displays adds a `panel: main` / `panel: companion` line to the display's details.

### Where the shell and the stream open

- **Stream** (`Shell.kt` launch, `StreamExit`, `StreamResult`): the stream's `ActivityOptions` get `setLaunchDisplayId(main)` through
  `DualScreen.placeOnMain`, and so does the shell when a stream ends. `ServerHelper.getActiveDisplay` (modes, refresh rate) answers with
  the main panel instead of display 0. A stream in the external-display mode is unchanged: it goes to the external display, and
  its end brings the shell back to the main panel.
- **Shell** (`ShellPlacement`): the launcher opens it on the display the user tapped. If that is the companion panel the shell
  starts itself again on the main panel (new task there, `EXTRA_PLACED` stops a second move) and ends; a link or stream result
  in the intent travels along. While it runs the shell watches the displays and the two display settings: changing Main screen
  (or a display appearing) moves it, and the new shell opens on the Display tab of the settings, where the user was. A launch that
  the display refuses is not repeated within 3 s. A shell on neither panel (an external monitor, DeX) is left alone.

### The companion window (`shell/companion/`)

The companion is a `CompanionActivity` on the panel that is **not** the owner's (`PanelPair.otherThan(ownDisplay)`; null, so nothing,
when the owner is on an external monitor: the external-display mode is unchanged), started by `CompanionHost` for the running
`ShellActivity` (`CompanionState.Shell(pcName)`) or `DelosStreamActivity` (`CompanionState.Stream`, built by `CompanionHost.forStream`).
It has a task of its own (`taskAffinity=""`, `FLAG_ACTIVITY_NEW_TASK | MULTIPLE_TASK`, `setLaunchDisplayId`), `excludeFromRecents`, no
window animations, and never starts without an owner: `CompanionSession` holds the owner and its content, and an activity that finds
no owner (the process was restarted with the task) or finds itself on another display than it was started for finishes at once.

**Why not a `Presentation`** (the first design, built and tested, then removed): WindowManager only takes a `TYPE_PRESENTATION` window on a
display with `FLAG_PRESENTATION`. Observed on the Android 13 emulator with the companion planned for display 0: `WindowManager:
Attempted to add presentation window to a non-suitable display. Aborting.` (`InvalidDisplayException`; AOSP `WindowManagerService.addWindow`
needs `Display.isPublicPresentation()`). The default display never has the flag, so a Presentation could never serve "Swapped", and an
internal local display gets none by AOSP rule, so probably not the Thor's bottom panel either. An activity can be launched on any display
(checked on display 0 and on two kinds of second display below).

| Event | What happens |
|---|---|
| owner `onResume` | `start()`: attach to `CompanionSession`, register the display and preference listeners, start the window if there is a companion display |
| display added / removed / changed, Second display or Main screen changed | reconcile: start when a target appeared, finish when it went, finish and start on the other panel when the target changed; nothing for events that change neither |
| display removed while shown | the system moves the task to the default display; the activity sees the configuration change, finds itself off its target and finishes (`CompanionActivity.closeUnlessOnTarget`), the host's reconcile finishes it too |
| owner `onStop` / `onDestroy` | `stop()`: unregister, detach; the window finishes unless the next owner has already attached. Not `onPause`: when the shell hands over to the stream the stream resumes (and attaches) before the shell stops, so the same window stays and only its content changes |
| launch refused or landed on the wrong display | the activity closes itself and the display is remembered as refused until an owner attaches again; no retry loop |
| configuration change | the activity handles `orientation|screenSize|...` in place |

**Key focus.** The window is `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_FULLSCREEN`, so it never takes key focus and touches still
reach it (`dumpsys input` lists it as a touchable window of its display; `input -d <display> tap` is dispatched without an ANR). But
NOT_FOCUSABLE alone is **not enough**: starting an activity on the other display makes that display the *top focused display*
(`mTopFocusedDisplayId`), it has no focused window, and controller and keyboard keys are dropped (observed: the main panel stopped reacting
and Android showed "Delos Debug isn't responding"). `moveTaskToFront` of the owner does not help either (the owner is already the front task
of its display, so nothing changes; observed). What works (observed: `mTopFocusedDisplayId=0` and the shell's window focused with the
companion resumed on the other display, keys reach the shell): once the companion has resumed, the owner is started again with
`FLAG_ACTIVITY_NEW_TASK | REORDER_TO_FRONT` (`CompanionSession.returnFocusToOwner`, 250 ms after the companion's `onResume`), which delivers an
intent to the running instance (`ShellActivity.onNewIntent` handles a plain intent as nothing) and makes its display the focused one again.
`DelosStreamActivity` has no `onNewIntent`, so the stream ignores it. Not verified: whether a real finger on the companion moves the top
focus back to its display (an injected tap does not), and on the stream the effect of the re-delivered intent on a running stream.
If a real touch does move the focus, the fallback is that the companion forwards keys to the owner.

Compose runs in the activity (own Stage, so `LocalStage` is planned from **the companion display's** size and density: the Thor's 1240 x
1080 panel at 420 dpi is a Compact stage while the main panel keeps 1920 x 1080). `CompanionTheme` applies the profile's colors and
reduced-motion setting. The system bars are hidden through `FLAG_FULLSCREEN` and the legacy visibility flags, because only a focused window
can hide them through the insets controller. `CompanionContent(state)` is the one place the content is decided (next section).
The keyboard needs no key focus: it injects key events, it takes none.

## What the companion shows (issue #76: useful extra content on EVERY screen, never scrolling)

Approved prototype: `.gradle-agent/companion-proto2/companion-A2.html` with its `NOTES.md` (the A prototype, `companion-A.html`, stays the visual base of the stream
state). The user's rule behind it: *the second display is EXTRA room for what helps the main screen, never a place for the main screens*, so the companion shows the
focused item's details, an explanation, or a few touch buttons that run the controller's own paths, and **there is always something**: a screen without content of its own
gets the fallback status card, never an empty "choose a game" card.

### Rule 0: it never needs scrolling

Every state fits completely in its panel, at every companion panel size (1240 x 1080 @ 420, 960 x 720 @ 300, 1024 x 768 @ 320, 720 x 720 @ 255, 960 x 640 @ 330),
with text of at least 28 stage px, touch targets of at least 64 stage px and 48 px margins. There is no scroll container, scroll bar, edge fade or auto-scroll anywhere in
the companion (the old scrolling help card is gone). What does not fit is **dropped by priority, shortened at a sentence boundary, or counted** ("+3 genres"); a visual
shrinks in ratio (artwork keeps its own aspect and is never cropped). Two layers do it:

1. **`CompanionFit`** (pure, `CompanionFitTest` with worst-case data on all five shapes): the budgets of a panel (`FitBudget`: tall panels, 900 stage px of height and more
   *after dividing by the interface scale*, get 5 body lines, 6 help lines, 4 genres, 5 hints, 6 facts and 5 error lines; short ones 3 / 3 / 2 / 2 / 4 / 2; the source block and
   the recovery sentence are 2 lines everywhere), `summary` (the longest run of **complete leading sentences** that fits the line budget, else the authored short form, else
   "See the main screen for details."), `chips` (first N and a count, one row), `top`, `title`/`alias` (a name that does not fit one line becomes "PC · 7C2A").
   Text is measured with the real fonts (`rememberFit`), so a longer translation simply gets fewer sentences.
2. **`FitColumn`** (`CompanionLayout.kt`), the net under the budgets: a page's blocks are measured, the optional ones (`Modifier.optional(priority)`) with the lowest
   priority are dropped while the sum is taller than the card, a `Filler` or a `fills` block (hero, artwork) takes what is left. A page can never overflow, whatever the
   language, the interface scale or the panel.

The panel is the companion's own stage (`LocalStage`, planned from its display) divided by the interface scale (text grows with it): 1240 x 1080 at 420 dpi is a 1099 x 957
stage, at the default 115 % a 956 x 832 panel for the fit, i.e. a *short* one.

### Pages, priorities and the fallback

The shell's companion window shows ONE page: `CompanionState.Shell(pcName, published, context)` carries what the shell's screens have published, `chooseShellPage` takes
the page of the highest `PagePriority` (overlay 300: launch, portal, result, problem, report; modal 200: the filter sheets; screen 100: the focused item of the screen and
the settings help; game 50: Home's focused game) and, with nothing published, the **welcome card** (no PC is paired) or the **fallback card**. Pages are immutable data with
the callbacks of their touch buttons (owner side, the same code paths as the controller); a page draws itself (`CompanionPage.Content`), and a new key crossfades
(`Motion.FADE`, snapped with reduced motion) while the same key is an update (a value, a clock) and does not.

| Screen on the main panel | Page | What it shows (data from the screen's own state), buttons |
|---|---|---|
| **Start**, **Devices** (focused PC circle) | `PcPage` | the PC's name (or its alias), status (online / asleep and wakes up / offline), connected and default flags, host software (derived: GeForce Experience, Vibepollo when it advertises PyroWave, else Sunshine-based; version and last seen are "unavailable": the app does not store them), the host name when the user renamed the PC, the running game, the number of apps (cached ones for an offline PC), saved MAC yes/no; 6 tall / 4 short facts, each unknown one carries its own label. Buttons: Library (connect, primary) or Wake PC (offline/asleep with a saved MAC, `DeviceActions.wake`) or Search (no MAC: `rescan`, and the sentence that says why), Rename, Set/Clear default, Unpair (Devices, online only) and, below a rule, Remove PC (immediate, no confirmation); the line "Main: A Connect · N more hints" is built from the main hint bar |
| **Pair a PC** | `PairHostPage`, `PairInfoPage` | the focused host (discovered / paired; software only when known) with Pair with PIN (`actions.pair`) and Use code (`pairWithCode`); the address and search circles explain themselves (Search again runs `rescan`) |
| **Pairing** (`Screen.Pin`) | `PinPage`, `CodePage`, `PairResultPage` | the PIN in very large digits (scaled to the box, never wrapped) with the instruction and the PC's name, readable across a desk; Cancel pairing; the one-time-code flow shows the instruction and "the companion never repeats the passphrase" (the passphrase is never published); after the PC accepted: Pairing complete with Open library / Back to hosts (visible for the 900 ms the screen stays) |
| **Home** with games | `GamePage` | the focused game: banner (hero, else cover, else placeholder hues), name, genres (4 tall / 2 short and a count), HLTB and native HDR, the three impact chips, Start / Resume / Settings (or Resume that game, Quit and start, Settings) |
| **Home** without games | `PcPage`, `LibraryNoticePage` | offline library: the PC card with Wake or Search and Rename; loading: "Reading the library" with Back to PCs; no PC; an empty library: "No applications" with Reconnect PC and PC details (a filtered-out library is the filter page's) |
| **Artwork** | `ArtworkPage` | the focused image large at its own ratio (the thumbnail the tile already decoded, never cropped) with size · type, score, author, added, In use and Use this image (the tile's own `apply`); the first choice shows its source lines, built-in artwork the wide image |
| **Library filters**, **Artwork filters** (sheets) | `FilterPage` | the active filters as up to 3 chips and "+N", the count of matching items, "Change criteria on the main display." and Reset filters (primary, only while filters are active; the sheet's own reset, that tab only; the sheet stays open) |
| **Profile**, **Create profile**, **Boot** | `ProfilePage`, `CreateProfilePage`, `BootPage` | avatar, name, custom settings and per-game overrides (counted once per profile, off the main thread), default PC / the live draft ("not saved") / the explanation of the first-run step with its real hints (B only when Boot can go back); read-only, edits stay on the main display |
| **Launch** (portal, `shell.launch`), **stream start** | `LoaderPage`, `PortalPage` | "Starting <game>", the PC and the requested picture, named steps with done / current / pending marks (Host found, Launch request accepted, Waiting for video, Stream ready; no invented percentage; short panels fold trailing steps into "+1 pending"); Cancel only while cancellation really exists. The loader never dims (`keepsAwake`) and has the same key in the shell and in the stream activity (`CompanionHost.forStream`: `onLaunchAccepted` / `onConnectionStarted` / `onLoaderDone`), so the hand-over is an update, not a fade |
| **Stream result**, **problem**, **report card** | `ResultPage`, `ProblemPage`, `ReportPage` | the result's title and sentence cut at a sentence boundary (never claims the game stopped), the first line of the details (stage, error code), the recovery sentence, Reconnect / Try again and Back (the very callbacks of `StreamResultLayer`), Report / Dismiss of the report card |
| **Settings**, **a game's settings**, **Debug info** (focused row) | `SettingsHelpPage` | see "The settings help" |
| **Any other screen** (Settings search, the on-screen controls preview, and every route added later) | `FallbackPage` | the screen's title and one-line purpose, the connected PC with its status and running game, the clock and the battery, and what the controller does here: the entries of the **very list the main hint bar draws** (`ShellState.companionContext`, published by `PublishCompanionContext` from `Shell`; 5 tall / 2 short, then "More hints on the main display: N") |
| **No PC paired yet** | `WelcomePage` | a short welcome and the one thing to do |
| **Stream** | `StreamContent`, `TypingContent` | statistics, the nine stream-menu tiles and their pages, the keyboard (below) |

`contentKind(screen)`, `screenTitle` and `screenPurpose` are exhaustive `when`s over `Screen`: a route added to the shell does not compile until it is placed, and
`ContentKind.FALLBACK` is a valid place (`CompanionRoutesTest`), so a new screen cannot silently leave the companion empty.

### The settings help (Settings, a game's settings, Debug info)

`SettingsHelpPage` (`CompanionHelpContent.kt`), published by the three settings screens with `PublishCompanionHelp(CompanionHelp)` (identity = the row's key, so a changed
value updates the same page and does not fade). One glass card, no scrolling: heading (disc with the row's icon, the row's name, the small line "Settings · Display" /
"Game settings" / "Debug info"; a title too long for one line moves the section into the heading and the name under it), the **source block** of a per-game row (violet "Custom for
this game · Profile value: 720p …" or neutral "From profile …", 2 lines, **always kept**), the body, the text value in the accent color, the palette's three swatches, the
game-background preview (a proportional box that takes the leftover height), Debug info's monospace details (bounded, "+N lines"), "Unavailable with the current settings or on this
device", "Saved automatically · applies next stream", and the **three impact chips always at the bottom** (dashes for a row without a stream cost). The body is
`CompanionFit.summary(full, short, helpLines, …)`: complete leading sentences within 6 lines (3 on a short panel), else `CompanionHelp.short` (an authored short form the screens
derive from the catalog summary, the first help sentence or the row title), else "See the main screen for details." `CompanionHelp.notice` sets the body in the warm notice color (a setting
whose Off state costs something: idle dimming Off). The full text stays on the main panel's own description.

**The main panel while a window shows the help** is unchanged: the description (the 520 px panel at 16:9, the dock when compact) is not drawn and the list takes its room; "showing" is
real: `CompanionSession.showsHelp` is set by the window's own composition (`PageHost`, only while the *shown page* is a settings help, keyed by the activity and cleared when the page
leaves, the help is gone or the window is destroyed), and the screens ask `hidesDescription(windowShowsHelp, help)`. A second display that exists but has no window yet, a refused launch and a
window on its way out all keep the description on the main panel. A device with no companion display never differs from before.

### State in, commands out

One-way, immutable state in: `CompanionState.Shell(pcName, published, context)` and `CompanionState.Stream(pcName, gameName, stats, commands, start)`. The host reads the owner's
lambda inside composition, so snapshot state held in it updates the window live.

- **Shell**: every screen or layer publishes its page with `PublishCompanionPage(page)` (a token per publisher, so a screen that is being replaced clears only what it published
  itself, never the next screen's page); `ShellState.companionPages` holds them, `ShellState.companionContext` the title, purpose, hints and connected PC for the fallback card.
  `CompanionGame.onAction(ShellButton)` runs the controller's own paths: `GameActions.start` (starts or resumes), `quitAndStart`, `Screen.GameSettings`; the PC pages run `DeviceActions`
  (`connect`, `wake`, `rename`, `setDefault`, `unpair`, `remove`, `rescan`, `pair`, `pairWithCode`, `paired`), the filter pages the sheets' own reset, the result pages `retryStream` /
  `dismissResult`. The companion never launches anything by itself and asks nothing: no confirmation dialogs anywhere.
- **Stream**: `StreamCommands(menu, sendKey)`. `menu` is the **same `StreamMenuHost`** the stream menu on the main panel is built on (`StreamMenu.companionMenu()` is `GameHost`
  without a menu window: dismiss and blur do nothing, brightness applies to the stream's window), so every tile calls the handler of its twin. The menu's getters are not
  observable: the screen reads them again after every command and once a second. Keys go through `KeyBoardLayoutController.sendKeyEvent` (made static), the one path every Full
  keyboard key takes: Down when the finger lands, Up when it lifts, Shift held around a shifted character (US layout, `KeyMap`). `DelosStreamActivity` binds it
  (`companionHost.bindStream(streamMenu)`). The stream's own pages follow Rule 0 too: the tiles are a 3 x 3 grid (flat tiles on a short panel), the sheets (Brightness, Bitrate,
  Upscaler, More, Send keys, Commands, Mouse) are the same card with the optional caption lines dropped first, and long lists (the 30 special keys, the PC's commands, More) are
  **paged** with Previous / Next and "2 / 3" (`CompanionPaging`), never scrolled; the keyboard keeps 64 px keys and a one-line echo.
- **Statistics**: `DelosStreamActivity.onPerfStats` hands the snapshot the panel already gets to `CompanionHost.onStats` on the decoder thread; it is
  dropped there when no companion window exists and when it comes sooner than 2 Hz after the last one (`StatsThrottle`), then posted to the main thread.
  Nothing is computed again. Until the first statistics (or the loader's end) the stream state shows the loader page.

### Burn-in policy (AMOLED panels) and the idle-dimming setting

`CompanionBurnIn`, deliberately simple: (1) **pixel shift**: all content moves by 3 dp every 2 minutes along a fixed serpentine path over a 3 x 3 grid (an
18-minute cycle), so no edge stays on the same pixels; it is **always on**, whatever the setting says; (2) **dim**: in the **shell state** the content fades to 22 % after the user's
delay without a touch; the touch that wakes it is swallowed (waking never presses a button). A stream is not dimmed (someone is looking at the menu and the statistics change) and
neither is a loader that is still waited at (`CompanionPage.keepsAwake`). The dim timer is armed from the last touch (no polling); a new page counts like a touch, so a controller
moving the focus on the main panel keeps the second panel bright. Unverified: that this is enough for a real AMOLED over months.

**Settings > Display** (only while a companion display exists, the same visibility as the Main screen row it sits next to): *Dim the second screen when idle* (On / Off, default
**On** because of OLED burn-in; its help says that Off can burn the panel in and that a touch wakes it without pressing a button) and *Dim after* (1 / 2 / 5 / 10 minutes,
default 1, hidden while Off), replacing the old hard-coded 60 s / 5 min. Device-wide, in the default shared preferences like the other display settings
(`display_companion_dim`, `display_companion_dim_minutes`, `CompanionDim`); the window follows them live (`rememberDimPolicy`); Debug info > Displays shows
`companion dimming: On, after N min` / `Off` in the companion display's details. `CompanionLogicTest` and `CompanionDimSettingTest` cover both settings.

### Debug previews

`--es preview_screen companion --es preview_state <name> [--es preview_sheet <brightness|bitrate|upscaler|more|keys|commands|mouse>]` on `ShellActivity`, combined with
`--es preview_shape <W>x<H>@<dpi>` for the panel's shape, draws the companion's content alone with made-up data (`CompanionPreview` and `CompanionFakes*` in `src/debug`). The names:

| Area | `preview_state` / `companion_fake` names |
|---|---|
| PCs | `start_online` `start_asleep` `start_offline` `start_offline_wake` `devices` `worst_pc` `pair_list` `pair_pin` `pair_code` `pair_result` `home_offline` `home_empty` `home_loading` |
| Content | `artwork` `artwork_filter` `library_filter` `library_filter_none` `profile` `create_profile` `boot` |
| Launch and results | `loader` `loader_short` `loader_started` `loader_launching` `portal` `portal_closing` `result` `result_disconnected` `problem` `worst_error` `report` |
| Home, help, stream | `shell` `shell_running` `shell_other` `worst_genres` `help` `help_long` `help_short` `help_game` `help_unavailable` `help_debug` `help_palette` `help_background` `worst_help` `stream` `typing` |
| Neither | `fallback` (also `idle`) `welcome` |

To see the **real** companion window on the emulator's second display with the normal shell on the main one: `--es companion_fake <name>` on the launch of `ShellActivity`
(the owner then hands the window that fake state), with Second display = Part of the device as below; the fakes' buttons write to `adb logcat -s CompanionPreview` instead of
doing anything. The screenshot of that display is `adb -s <device> emu screenrecord screenshot --display 1 <dir>` (`screencap -d` does not take the virtual display's id).
The screen previews (`preview_screen start|devices|pair|pair_pc|artwork|filter|profile|create_profile|boot|loader|result|problem|settings|game_options|debug_info|home`, ...) run the real
shell on a state of their own (`loader` and `result` publish through the same overlay publisher): with a real second display the window shows what **that** shell publishes
(`CompanionPreview.liveShell`) in the appearance of that shell (`CompanionPreview.liveAppearance`: theme, palette, Material You and scale follow the preview's own Settings live, as
the real shell's window follows the real store), so every screen can be driven live with the D-pad of the main display (`adb -s <device> shell input keyevent KEYCODE_DPAD_DOWN`).
The Odin's **Screen shape simulator** (Settings > About > Debug info) draws the very same `CompanionContent` next to the shell in a second panel's shape (Thor, RG DDS, ...), fed with
the pages the simulated shell publishes.

## How to test the companion on the emulator

### The emulator's real second display (main test)

The AVD `delos-layout2` has a second display in `~/.android/avd/delos-layout2.avd/config.ini` (`hw.display1.width=1240`, `height=1080`,
`density=420`, `flag=0`, offsets set by hand in Extended controls > Displays > Arrangement). It is created by the emulator's multidisplay
service shortly after boot. `dumpsys display` shows it as `"Emulator 2D Display"`, `FLAG_PRESENTATION | FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS |
FLAG_TRUSTED`, 1240 x 1080 at 420 dpi, `type VIRTUAL`, no product info, unique id `virtual:com.android.emulator.multidisplay:...`, so the
classifier says `VIRTUAL` (type) and "Part of the device" is needed to make it a companion, exactly as for the overlay display. It is not the
Thor's plain internal panel (it carries `FLAG_PRESENTATION`), but since the companion is an activity the flag no longer matters. Its id
changes every time it is removed and added. Remove and add it with `adb -s <device> emu multidisplay del 1` and `emu multidisplay add 1 1240 1080
420 0`. Display 1 (`--display 1`) of the emulator's own screenshot is the second display; display 0's `screencap` shows only the main one:

```
adb -s <device> emu screenrecord screenshot <dir>                 # the main display
adb -s <device> emu screenrecord screenshot --display 1 <dir>     # the second display
```

### Setting the preference

The real Settings screen cannot be opened on the emulator (the native library of this build dies with SIGILL on the emulator CPU as soon
as the stream settings are read; a stream cannot run there for the same reason), so write the device-wide preference into the debuggable
app, with the app stopped (the file does not exist before the first write):

```
adb -s <device> shell am force-stop io.github.meitoncz.delos.debug
adb -s <device> shell "run-as io.github.meitoncz.delos.debug sh -c 'cat > shared_prefs/io.github.meitoncz.delos.debug_preferences.xml'" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="display_second_role">built_in</string>
    <string name="display_main_screen">automatic</string>
</map>
XML
```

(`display_main_screen` is `automatic` or `swapped`; `run-as ... rm -f shared_prefs/io.github.meitoncz.delos.debug_preferences.xml` undoes
it.) The Main screen row can be seen in the debug preview `--es preview_screen settings` (Display tab; its preferences are in memory, so choose
"Part of the device" there with the D-pad and then add the display).

### Overlay display (second test)

```
adb -s <device> shell settings put global overlay_display_devices "1240x1080/420"   # the Thor's bottom panel
adb -s <device> shell settings delete global overlay_display_devices                # remove it
```

The emulator draws the overlay as a small window in the top left corner of display 0, so a normal `screencap` shows it (`screencap -d` cannot
take it). While an overlay display exists, the emulator's touch input to display 0 stops working (`input tap`); keys still work.

### What to expect (checked this way)

- Automatic, display present: the shell on display 0, the companion activity on the other display (dim mark, the PC name once a PC is
  chosen), `mTopFocusedDisplayId=0`, the shell's window focused, keys reach the shell.
- Removing the display: no crash, the companion is gone and the shell untouched; adding it again starts a new companion (`Companion started on
  display N` in the diagnostic log). Six remove/add cycles of the real second display left the same two activities and two windows (shell and one companion) after a GC, one `Companion started` per cycle, no crash, and the top focused display stayed 0.
- Swapped (preference set before the shell starts): the shell opens on the second display (it moves itself there from display 0 and ends),
  the companion is an activity on display 0; with the preference already swapped, adding the display while the shell runs moves the shell, and
  removing it lets the system bring the shell back to display 0 (it moves again when the display returns).
- The shell sent to Home: the companion finishes; a display added while the shell is stopped starts nothing; the shell back in front starts it.

## What is verified, what is not

Verified on the emulator (the real second display and the overlay): the display list and roles, the classification, the plan (unit tests),
the companion activity with its own stage on the other display, its removal and return, the swap in both directions, no crash, no ANR, the key
focus staying with the shell, the companion absent from the running shell's task and from Recents' UI (`excludeFromRecents`; the system's internal
list shows the live task only). **Not run on the emulator**: a stream (the native library dies with SIGILL there), so the stream's companion
(`CompanionHost.forStream`, `CompanionState.Stream`), the stream launch on the main panel and the shell-to-stream-to-shell hand-off are only
compiled, as is the PC name in the shell's companion (the emulator has no PC). **Not verified without a dual-screen device**: how the
Thor, the Pocket DS or the Retroid add-on report their panels (so whether Automatic picks the right companion and the shell may open on
it), whether a real second panel's touch reaches the companion and whether a finger on it moves the top focus (see above), whether
launching an activity on the other built-in panel is allowed on those devices' firmware, and whether an always-on companion burns in an
AMOLED panel (the policy above is dimming and a slow pixel shift, not proven on a panel).

**Companion content (step 4), verified on the emulator** (real second display, `companion_fake`): the shell state with a fake focused game (nothing running and another
game running), the stream state and the keyboard (a real tap on the Keyboard tile switched to it), no crash, the main panel's window still the focused one
(`mTopFocusedDisplayId=0`) after the touches; the three states at 1240 x 1080, 960 x 720 and 1024 x 768 through the shape simulator. **Not verified**: anything
that needs a stream (the emulator cannot run one): the tiles' effect (overlay, brightness, bitrate, upscaler, More, Disconnect, Quit), the keys reaching the PC
and the statistics arriving in the window; the shell's buttons with a real PC (Start, Resume, Quit and start, Game settings) and the live follow of the focused game,
real artwork and metadata in the banner; the layout on the real 4:3 panels beyond the simulated shape; touch focus behaviour on a real second panel.
Left out of the stream's More page: rumble feel and gyro smoothing (the controller's own pages on the main panel).

**Settings help (issue #76, the rule above), verified on the emulator** (real second display, Second display = Part of the device, the screen previews of
Settings, a game's settings and Debug info on the main panel at 1920 x 1080, the D-pad driving the focus): the second panel follows every focus move (rows with
impact chips, plain rows, an unavailable row, a text row, the palette and the game-background rows, a custom and a plain per-game row, the per-game screen with Reset all
focused, Debug info's device and display rows), finger scroll and the scroll bar on a page longer than the card, the page in the simulator at 1240 x 1080 @ 420,
960 x 720 @ 300 and 1024 x 768 @ 320; the main panel without its description (16:9 and compact) while the window shows the help; removing the display brings the panel back
and adding it takes it away again, the keys reach the main panel in both states (`mTopFocusedDisplayId=0`), no crash; without a second display the three screens at
1920 x 1080 @ 369 are identical to the build before this change (the only differences are the clock and the animated background's phase). **Not verified**: a real dual-screen
device; a finger on the real second panel while a controller moves the focus on the main one; the crossfade between rows (only settled screenshots were looked at, no frame of the fade itself); the
game-background preview with a real game's art (the previews have no hero, so only the empty frame with the name was seen).
