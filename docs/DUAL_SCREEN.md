# Dual-screen and second displays

Issue [#76](https://github.com/Meitoncz/Delos/issues/76). This page covers what Delos knows about the displays of a device
(`DisplayInventory`, `DisplayClassifier`; phases 1 and 2 of the plan), the companion window that goes on a built-in second panel
(phase 3: activity host, lifecycle, the Main screen setting) and how to test it. The content of the companion screen is still a placeholder
(phase 4).

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
can hide them through the insets controller. `CompanionContent(state)` is the one place the content is decided; today it is a dim,
centred Delos mark with the PC name in the shell and the word "Streaming" in a stream. Step 4 (variant A, "Details and actions": game
details with Start, Resume and Game settings in the shell, statistics and the stream-menu tiles in a stream, the keyboard when typing)
replaces that function without touching the host. A keyboard variant needs a focusable window and so the focus question above again.

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
  display N` in the diagnostic log). Ten overlay add/remove cycles left no extra activity or window behind.
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
AMOLED panel (it is dimmed, the policy is open).
