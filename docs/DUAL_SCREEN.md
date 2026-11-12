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

## What the companion shows (variant A, "Details and actions")

Approved prototype: `.gradle-agent/companion-proto/companion-A.html`. Header (Delos mark, PC name), then one of three states. All text is at least
28 stage px and every touch target at least 64 stage px. The stage is planned from the companion's own display: 1240 x 1080 at 420 dpi is a
1099 x 957 stage, 960 x 720 at 320 dpi is 1116 x 837, 1024 x 768 at 320 dpi is 1191 x 893. Below 920 stage px of height the stream state is
"tight" (no small caps lines, tiles lay out flat with the icon beside the name).

| State | Content |
|---|---|
| **Shell** | the game focused on the main panel, live: banner (hero, else cover, else the placeholder hues) with name and summary, genre and theme pills, HLTB, native HDR, three impact chips (GPU, latency, network: the highest level of the game's effective stream settings, an estimate like the settings rows' chips), and big buttons. With nothing running: Start, Game settings. This game runs: Resume, Game settings. Another game runs: Resume that game, Quit it and start this one (red), Game settings. On a settings screen (Settings, a game's settings, Debug info) the **description of the focused row** instead, large (next section). No focused game and no settings screen (start screen): the Delos mark and a hint. |
| **Stream** | heading (game, picture: resolution, frame rate, codec, HDR), FPS, network, decoding and the bitrate asked for, and the nine tiles of the stream menu in its order (Resume, Keyboard, Overlay, Brightness, Bitrate, Upscaler, More, Disconnect, Quit game); the last row is set apart. Resume tucks the tiles away (a quiet screen with Open stream menu). Brightness, Bitrate, Upscaler and More open a page over the tiles (sliders and steppers for a thumb; More: send keys, commands, clipboard, task manager, zoom, mouse mode, on-screen controls, Android keyboard, touch sensitivity). No confirmation dialogs anywhere. |
| **Typing** | the Keyboard tile: five rows (digits, letters, Shift, #+= page, Space, arrows), Enter, Backspace, an echo of the newest 40 typed characters and Stream menu to go back. |

### The rule, and the settings help

The user's rule: *the smaller display is not for the important screens; the second panel is something EXTRA that helps the main one.* The shell
(and so every list and every setting) stays on the main panel; the second panel carries what helps it: on Home the focused game's details, on the
settings screens **the description of the focused row**, large, which is the box that used to take a third of the main screen.

| Main panel | Second panel |
|---|---|
| Home | the focused game: banner, pills, impact chips, Start / Resume / Game settings |
| Settings (any tab) | the focused row: icon disc, name, the value's own detail ("720p", "Requests a 1280 x 720 stream."), the help; "Unavailable with the current settings or on this device" for a row that cannot be changed; the three impact chips of the row with their levels and "Cost of the current value: 1 bar low, 2 medium, 3 high"; a text row's text in the accent color; the three palette swatches for Color palette; the game-background preview for the three game-background rows. With nothing focused the tab's own description |
| A game's settings | the same, plus where the value comes from (the violet "Custom for this game · Profile value: 720p · press X to go back to it" or "From profile · Currently 720p …", the value in bold) and "Saved automatically · applies next stream"; with Reset all focused the game's summary ("Your game, your settings", the count of custom settings) |
| Debug info | the focused row's name, description and monospace details |
| Settings search, Profile, stream, anything else | unchanged (the game, else the mark) |

**State.** `CompanionState.Shell(pcName, game, help)`: `help` is an immutable `CompanionHelp` (identity = the row's key, icon, title, body, optional
source block, unavailable flag, text value, impact, swatches, game-background preview data, details, saved note), published by the settings screens
with `PublishCompanionHelp` (a `SideEffect` like `PublishCompanionGame`, `ShellState.companionHelp`) and cleared when the screen leaves (a screen that
is being replaced clears only what it published itself, so the next screen's help survives the transition). No composable crosses to the companion's
composition: everything, down to the swatch colors and the preview bitmap, is data. **Which state wins** is the pure `shellView`: help over game over the
idle mark (the settings screen is on top).

**The page** (`HelpContent`, `CompanionHelpContent.kt`): under the usual header (mark, PC) one glass card with 48 px outside margins and 24 px between blocks, ONE
scrolling column inside (finger scroll, a fade at an edge only while the page continues behind it, and a slim scroll bar at the right, because a next line
that still lies out of sight leaves nothing to fade). The disc is 112 px, the name 40 px, the text 30 px with a 1.45 line height, everything else 28 px (the
companion's minimum). It follows the focus live with a short crossfade between rows (`Motion.FADE`; snapped with reduced motion); the update of one row (a
value that changed) does not fade. It does not dim for five minutes (a new row's help restarts the time; the game details dim after one) because on a
settings screen it is the only place the description is.

**The main panel while a window shows the help**: the description (the 520 px panel at 16:9, the dock when compact) is not drawn and the list takes its room
(full content width, or full height down to the hint bar), with the screen's own margins; when the window goes (display removed, swapped, closed) the panel comes
back at once. "Showing" is real: `CompanionSession.showsHelp` is snapshot state set by the window's own composition (`HelpContent`, keyed by the activity,
cleared when the page leaves, the help is gone or the window is destroyed; a late clear from an old window never clears the next one's), and the screens ask
`hidesDescription(windowShowsHelp, help)`, so a second display that exists but has no window yet, a refused launch and a window on its way out all keep the
description on the main panel. A device with no companion display never differs from before (identity checked at 1920 x 1080 @ 369).

### State in, commands out

One-way, immutable state in: `CompanionState.Shell(pcName, game: CompanionGame?)` and `CompanionState.Stream(pcName, gameName, stats, commands)`.
The host reads the owner's lambda inside composition, so snapshot state held in it updates the window live.

- **Shell**: `ShellState.companionGame` is published by `PublishCompanionGame` (one line in `HomeScreen.GameLibrary`, next to the focused game's
  metadata and artwork it already has) and cleared when Home leaves composition. `CompanionGame.onAction(ShellButton)` runs the controller's own
  paths: `GameActions.start` (starts or resumes), `quitAndStart`, `Screen.GameSettings`. The companion never launches anything by itself.
- **Stream**: `StreamCommands(menu, sendKey)`. `menu` is the **same `StreamMenuHost`** the stream menu on the main panel is built on
  (`StreamMenu.companionMenu()` is `GameHost` without a menu window: dismiss and blur do nothing, brightness applies to the stream's window), so every tile calls the
  handler of its twin. The menu's getters are not observable: the screen reads them again after every command and once a second. Keys go
  through `KeyBoardLayoutController.sendKeyEvent` (made static), the one path every Full keyboard key takes: Down when the finger lands, Up when it
  lifts, Shift held around a shifted character (US layout, `KeyMap`). `DelosStreamActivity` binds it (`companionHost.bindStream(streamMenu)`).
- **Statistics**: `DelosStreamActivity.onPerfStats` hands the snapshot the panel already gets to `CompanionHost.onStats` on the decoder thread; it is
  dropped there when no companion window exists and when it comes sooner than 2 Hz after the last one (`StatsThrottle`), then posted to the main thread.
  Nothing is computed again.

### Burn-in policy (AMOLED panels)

`CompanionBurnIn`, deliberately simple: (1) **pixel shift**: all content moves by 3 dp every 2 minutes along a fixed serpentine path over a 3 x 3 grid (an
18-minute cycle), so no edge stays on the same pixels; (2) **dim**: in the **shell state** the content fades to 22 % after 60 s without a touch; the touch that wakes it is
swallowed (waking never presses a button). A stream is not dimmed: someone is looking at the menu, and the statistics change.
The dim timer is armed from the last touch (no polling). Unverified: that this is enough for a real AMOLED over months.
While the settings help shows the dim comes after five minutes instead of one (`CompanionBurnIn.dimAfter`), and a new row's help restarts the time: on a
settings screen the page is the only place the description is, so it is not to go dark under someone reading it with a controller in hand.

### Debug previews

`--es preview_screen companion --es preview_state <shell|shell_running|shell_other|idle|stream|typing|help|help_long|help_short|help_game|help_debug|help_palette|help_background> [--es preview_sheet <brightness|bitrate|upscaler|more|keys|commands|mouse>]`
on `ShellActivity`, combined with `--es preview_shape <W>x<H>@<dpi>` for the panel's shape (fake data, `CompanionPreview` in `src/debug`). To see the **real**
companion window on the emulator's second display with the normal shell on the main one: `--es companion_fake <state>` on the launch of `ShellActivity`
(the owner then hands the window that fake state), with Second display = Part of the device as below. The screenshot of that display is
`adb -s <device> emu screenrecord screenshot --display 1 <dir>` (`screencap -d` does not take the virtual display's id).
The screen previews (`preview_screen settings`, `game_options`, `debug_info`, ...) run the real shell on a state of their own: with a real second display
the window shows what **that** shell publishes (`CompanionPreview.liveShell`), so the settings help can be driven live with the D-pad of the main display
(`adb -s <device> shell input keyevent KEYCODE_DPAD_DOWN`), with the night mode on (`cmd uimode night yes`) when the companion should be dark like the
previews' shell. `--es companion_fake help_long` (and the other `help*` states) puts a made-up help on the window instead.

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
