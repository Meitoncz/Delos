# Rumble laboratory

Open **Settings → About → Debug info**, then scroll to **Rumble laboratory**.
Use up/down to focus a row, left/right to adjust a slider, A to toggle or play a pattern,
and B to return. Touch works through the same setting rows. The separate Controls tab is unchanged.

The translator is **off by default**. Off preserves the existing streaming path exactly;
lab patterns use the Original mapping. Turning it on applies the saved tuning to Android
single/dual/quad vibrator paths on the next stream. USB-driver and Shield-specific rumble
remain unchanged. Existing device/OSC/fallback routing and fallback-strength scaling still
apply before translation. This is a user-profile setting, not automatic device detection or
a per-game override. It also affects external pads using those Android paths when enabled.

## Parameters

| Parameter | Range | Original | Meaning |
| --- | --- | --- | --- |
| Gain | 50–300% | 100% | Multiplies the shaped amplitude, capped at 255. |
| Curve | 50–300% | 100% | Exponent applied to normalized amplitude: below 100% boosts weak rumble, above 100% softens it. |
| Minimum amplitude | 0–255 | 0 | Floor for any nonzero raw request, even values in the discarded low byte. Raw zero always stops immediately. |
| Start kick | 0–120 ms | 0 ms | Full amplitude 255 on a transition from raw zero, then settle; another nonzero packet does not restart it. A zero packet cancels it immediately. |
| Low motor weight | 0–100% | 80% | Low-frequency motor contribution to a single vibrator. |
| High motor weight | 0–100% | 33% | High-frequency motor contribution to a single vibrator. |
| Smoothing | 0–100% | 0% | Zero responds immediately. Otherwise limits amplitude change to 255 / smoothing units per second, with smoothing expressed as 0–1; 100% takes one second for a full-scale change. Initial nonzero input starts immediately. |

Original drops each host motor's low byte, then computes
`min(255, (int)(lowMSB * 0.80 + highMSB * 0.33))` for a single vibrator.
Dual/quad motors independently use `raw >> 8`; mix weights do not apply there.
Gain/curve/floor shape these amplitudes before kick/smoothing. Scheduled updates finish a
kick or slew even when no further host packet arrives. Each physical output/channel has
independent reusable state. A kick with smoothing enabled slews down from 255 after its deadline.
The weights preserve the original double-literal math even after loading float preferences.

**Reset to Original** restores parameters but does not change the enable switch.
Changes are saved immediately in the current profile (`checkbox_rumble_translator` and
`pref_rumble_gain`, `pref_rumble_curve`, `pref_rumble_min_amplitude`, `pref_rumble_kick_ms`,
`pref_rumble_low_weight`, `pref_rumble_high_weight`, `pref_rumble_smoothing`).
Streaming reads a snapshot at connection setup; reconnect after changing lab values.

## Comparing patterns

The patterns send the same raw percentage to both host motors: steady 25/50/75/100%
for two seconds, acceleration ramp, four 30/60/100 ms taps, heartbeat pairs, explosion
decay, and rapid flutter. Their labels describe the simulated event, not guaranteed feel.
**Live amplitude** and pattern details show the computed 0–255 output, including kick
and smoothing; input percentage is not output percentage.

The laboratory uses the same `RumblePlayback` single-vibrator path as translated streaming,
with the device vibrator as the target. It does not launch a game or send host input.
Selecting another pattern replaces the current one. Parameter changes stop the pattern.
Stop vibration, screen disposal and controller destruction cancel scheduled work so no
settling tick can restart a stopped output. Existing continuous tests and patterns are
mutually exclusive. The laboratory does not change the separate interface-haptic setting.

The Odin's existing interface-haptic tuning indicates a high perceptibility threshold
(about 86% amplitude and 60 ms), but no new threshold/default is selected here. This layer
adjusts amplitude and onset timing, not the hardware's physical resonant frequency.

## Verification (2026-11-06)

Standalone Java compilation covered the translator, playback, preference loader,
PreferenceConfiguration and ControllerHandler against the cached Android/app contracts.
The Debug screen compiled with the real Compose compiler/API jars. Seventeen translator
regressions passed, including the full mixed MSB grid and all 65,536 individual raw values.
Three disposable offline Robolectric Android 13 scenarios exercised delayed kick settling,
zero/terminal cancellation, independent hardware histories, replacement playback and saved
profile values. Temporary smoke sources/classes were not retained in the repository.

These are not a Gradle build, lint run, visual/controller check or physical vibration
measurement. The lead owns full build/lint and Odin acceptance; the user judges the feel
and compares it with PXPlay before any device defaults are agreed. Issue #66 remains open.
