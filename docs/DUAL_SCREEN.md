# Dual-screen and second displays

Issue [#76](https://github.com/Meitoncz/Delos/issues/76). This page covers what Delos knows about the displays of a device
(`DisplayInventory`, `DisplayClassifier`; phases 1 and 2 of the plan) and how to test it. The companion screen itself
(content, lifecycle) is not built yet.

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

## Not built yet

The companion itself (Presentation host for the shell and the stream, which panel is main, swap setting, content) is phase 3
and 4 of #76; this page grows with it.
