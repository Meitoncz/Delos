# Native settings (Phase 6.7)

The new shell edits the same keys and value types as `app/src/main/res/xml/preferences.xml`.
The XML currently contains **116 controls in 13 categories**. The earlier count of 126 included
ten category keys; category keys are headings, not ten additional options.

## Tabs

Approved on 2026-10-23. Categories that cover several subjects are split by setting.

| Tab | Contents |
| --- | --- |
| Stream | Resolution, frame rate, bitrate, frame pacing, latency, codec, host optimization and packet loss prevention |
| Display | Scaling, orientation, display modes, virtual display, HDR, color range and performance overlay |
| Audio | Channels, audio effects, playback on the PC and interface sounds |
| Controls | Gamepad, mouse, touch, keyboard, clipboard, virtual controls, editable layouts and interface haptics |
| Appearance | Theme, wallpaper colors, backgrounds, home layout, interface scale, animation and the remaining interface options |
| Covers | Information about existing PC artwork and upcoming SteamGridDB support; downloads remain step 6.8 |
| Devices | Default PC, skipping the start screen, device management and pairing |
| About | Version, project information, welcome screen, language, diagnostics and project links |

L1/R1 switch tabs. Left/right adjust a choice or slider; A toggles, cycles or opens an editor/action.
Sliders also support tapping and dragging, and commit a drag when the finger is released.
Assigning focus after a tab switch is silent, so the switch has one sound/haptic response. The list
reserves space above the focused row for its section heading, including when returning to the top.
Unavailable dependencies remain visible, dimmed and searchable. Hardware-inapplicable controls are
hidden, following the classic screen. The description panel can receive focus and scroll with up/down.

Y opens search from settings; the hint bar also shows this shortcut. Search accepts typed text or
letters selected with the controller. Results span all tabs and selecting
one scrolls to and focuses the actual setting, including disabled dependencies.

## Implementation

- `shell/settings/SettingsCatalog.kt` reads titles, summaries, defaults, dependencies, choices, types,
  ranges, units and action URLs directly from the existing XML. It uses English resources while the
  shell is English-only. `list_languages` remains available for the stream/classic interface.
- `SettingsCapabilities.kt` contains the display, codec, HDR, sensors, USB, vibration, PiP and
  touchscreen rules ported from `StreamSettings`. Native and custom resolutions/frame rates are
  added to the original choices. Explicitly unavailable codec choices are omitted.
- `StreamSettingsStore.kt` writes typed preferences. Resolution/FPS changes reset bitrate as in the
  classic screen. Custom numeric input rejects invalid and non-finite values before writing.
- `screens/SettingsScreen.kt`, `NativeSettings.kt` and `SettingsActions.kt` render the design system's
  rows, description panel, tab bar and search, and handle editing, reset, import/export and links.
  Resets run immediately and report a toast. Android's document picker handles file selection.

## User profiles

All shell and stream preferences are stored in `ui_profile_<user UUID>`. On first use, the existing
effective stream values and the XML defaults are snapshotted. A new user copies the current user's
preferences, including stream settings; later changes are independent.

`StreamProfileStore` projects the stream keys into a regular Artemis `SettingsProfile`, using the same
UUID and a name of `Delos · <user name>`. It activates this profile when the user changes. Consequently
`PreferenceConfiguration` and the stream still read through the existing `ProfilesManager` overlay.
The old profile list/editor remains available. Changes it makes to a linked profile are picked up
when the shell resumes; unrelated Artemis profiles are preserved.

`ProfileLayouts` also gives each user private copies of OSC, the five keyboard layouts and custom
special keys. New users copy these layouts. Small, commented `Delos:` hooks in the existing layout
loaders and `GameMenu` select that storage, and the keyboard layout selector reads the active overlay.
Classic Artemis profiles that are not linked to shell users keep their original layout storage.

## Verification

`ShellSettingsTest` checks XML coverage, category assignment, numeric validation, nested dependencies,
device choices, copying/isolation/persistence of users, layout copying/reset and the actual
`PreferenceConfiguration` reader. Run the full unit suite with the debug build:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
.\gradlew.bat assembleNonRoot_gameDebug testNonRoot_gameDebugUnitTest lintNonRoot_gameDebug --console=plain
```

Device checks use the Odin, never an emulator. Final acceptance also requires the user to start a
stream and verify a changed resolution or bitrate in the performance overlay. Agents must not launch
games on the PC themselves.

2026-10-23: the user confirmed that a changed resolution was applied in a real stream. All eight tabs,
controller search/jump, the Y shortcut/hint, single feedback per tab switch and complete headings after
upward D-pad navigation were checked on the Odin. Local validation passed all 63 unit tests and lint
(zero errors). The reviewed Experimental labels remain exactly as established in `f4ac11aa`.
