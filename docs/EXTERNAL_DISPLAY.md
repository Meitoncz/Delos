# External display support

Implemented and accepted on the AYN Odin 2 Portal Max on 2026-10-27.

## Behavior

- The Delos launch scene continues onto the external display without the legacy Moonlight loading UI or Android's side transition.
- The host request and stream surface keep the resolution and frame rate selected in Delos. The TV's native mode is the output canvas, not a replacement stream configuration.
- The Delos stream menu is shown on the external display. The built-in Odin controller supports the configured menu shortcut, D-pad, analog navigation, A/B activation and back, and left/right adjustment of choices and sliders.
- HDR continues through both MediaCodec and PyroWave paths.
- Removing the external display closes its menu and controller activity before returning to the shell. The first game context menu waits for the refreshed running-game state, so Resume and Quit are immediately available.
- The obsolete external-display notification and its runtime permission were removed.

## Input architecture

Android treats gamepads and keyboards as untargeted input. On a normal multi-display device they go to the globally focused display, which is the Odin screen in this layout, while the stream menu is rendered by the `Game` activity on the TV. Android does not dynamically route the built-in controller to that second display.

`ExternalDisplayControlActivity` is therefore the single bridge for external-display input:

1. Normal controller keys enter its `dispatchKeyEvent()` path.
2. Keys claimed by an IME enter the supported `ExternalControllerView.onKeyPreIme()` path first.
3. Both paths call `Game.handleExternalDisplayKeyEvent()` exactly once.
4. With the menu closed, the original `KeyEvent` or `MotionEvent` continues to the existing stream input handlers unchanged.
5. With the menu open, `StreamMenu` emits semantic UI actions: move focus, activate, back, or adjust the focused control. It does not inject fabricated D-pad events into the TV window.

`FocusTracker` exposes the focused control's current activation and adjustment callbacks to this route. Analog navigation has an explicit edge and repeat policy. The original `InputConnection` and `commitText` behavior remain enabled for the Android keyboard and future controller UI.

The accessibility keyboard service uses the same route when the external controller activity has focus, so enabling it later cannot bypass the menu.

## Verification

- `assembleNonRoot_gameDebug`
- `testNonRoot_gameDebugUnitTest` (68 tests)
- `lintNonRoot_gameDebug` (0 errors)
- Physical-device acceptance on the Odin with an external TV: loading transition, configured resolution, HDR, menu opening, D-pad, analog, A/B, adjustable controls and return behavior.

Regression tests cover analog edge/repeat behavior, pre-IME gamepad routing, and retained `commitText` support.
