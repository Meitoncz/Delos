# External display support

Implemented and accepted on the AYN Odin 2 Portal Max on 2026-10-27.

Only a display that Delos classifies as **external** (a monitor, a TV or a wireless display) is a target of this mode. A second panel that is part of the device (a dual-screen handheld), an overlay display and a virtual display are never chosen; the classification, its signals and the manual override are in [DUAL_SCREEN.md](DUAL_SCREEN.md).

## Behavior

- The Delos launch scene continues onto the external display without the legacy Moonlight loading UI or Android's side transition.
- The host request and stream surface keep the resolution and frame rate selected in Delos. The TV's native mode is the output canvas, not a replacement stream configuration.
- The Delos stream menu is shown on the external display. The built-in Odin controller supports the configured menu shortcut, D-pad, analog navigation, A/B activation and back, and left/right adjustment of choices and sliders. Its bar and popover keep 48 stage pixels from every screen edge (overscan, docs/GUI.md "Stream menu").
- HDR continues through both MediaCodec and PyroWave paths.
- Removing the external display closes its menu and controller activity before returning to the shell. The first game context menu waits for the refreshed running-game state, so Resume and Quit are immediately available.
- A stream on the external display that fails to start, loses its connection or is closed by the PC ends without the classic error dialog (which only showed on the TV): `StreamOutcome` opens the shell on the device's own screen (`setLaunchDisplayId(DEFAULT_DISPLAY)`) with the same result overlay or "The PC ended the stream" toast as any other stream (docs/GUI.md, "Stream exits").
- The obsolete external-display notification, its runtime permission and its receiver were removed; focus changes go through `ExternalDisplayFocus`.
- The stream menu's brightness slider sets the brightness of the device's own screen (the controller activity), because the TV ignores window brightness. After the screen dims from inactivity it returns to that value on the next touch.

## Input architecture

Android treats gamepads and keyboards as untargeted input. On a normal multi-display device they go to the globally focused display, which is the Odin screen in this layout, while the stream menu is rendered by the `DelosStreamActivity` activity on the TV. Android does not dynamically route the built-in controller to that second display.

`ExternalDisplayControlActivity` is therefore the single bridge for external-display input:

1. Normal controller keys enter its `dispatchKeyEvent()` path.
2. Keys claimed by an IME enter the supported `ExternalControllerView.onKeyPreIme()` path first. A key that route leaves unhandled continues to the IME and then to `dispatchKeyEvent()`; `onKeyPreIme()` does not hand keys to the stream itself.
3. Both paths call `DelosStreamActivity.handleExternalDisplayKeyEvent()`; a key it handles is not routed again.
4. With the menu closed, the original `KeyEvent` or `MotionEvent` continues to the existing stream input handlers unchanged.
5. With the menu open, `StreamMenu` owns every key event, keyboard and controller alike, and nothing reaches the stream. It emits semantic UI actions: move focus, activate, back (B, Back or Esc), or adjust the focused control. It does not inject fabricated D-pad events into the TV window.

`FocusTracker` exposes the focused control's current activation and adjustment callbacks to this route. Analog navigation has an explicit edge and repeat policy. The original `InputConnection` and `commitText` behavior remain enabled for the Android keyboard and future controller UI.

The accessibility keyboard service uses the same routes: the controller activity's router when the external controller activity has focus, and `DelosStreamActivity.dispatchKeyEvent()` (menu shortcut first) when the stream window on the device has focus, so enabling it can never bypass the menu.

## Verification

The following is historical evidence from 2026-10-27, before flavor removal.
Current equivalents are `:app:assembleDebug`, `:app:testDebugUnitTest` and
`:app:lintDebug`; the recorded results do not claim a run of those renamed tasks.

- `assembleNonRoot_gameDebug`
- `testNonRoot_gameDebugUnitTest` (68 tests)
- `lintNonRoot_gameDebug` (0 errors)
- Physical-device acceptance on the Odin with an external TV: loading transition, configured resolution, HDR, menu opening, D-pad, analog, A/B, adjustable controls and return behavior.

Regression tests cover analog edge/repeat behavior, pre-IME gamepad routing, and retained `commitText` support.
