# Rumble laboratory

Open **Settings → About → Debug info**, then scroll to **Rumble laboratory**.
Use up/down to focus a preset and A to select it; B returns. Touch works through the
same setting rows. Selection saves all parameters, marks the active row, and immediately
plays the same short **Test** sample: a 60 ms tap, 1 second of steady 50% input, a ramp,
and an explosion decay (about 2.8 seconds including pauses). **Replay Test sample** repeats
it without changing the selection. The separate Controls tab is unchanged.

The translator is **off by default**. Off preserves the existing streaming path exactly;
lab patterns use the Original mapping. Turning it on applies the saved tuning to Android
single/dual/quad vibrator paths on the next stream. USB-driver and Shield-specific rumble
remain unchanged. Existing device/OSC/fallback routing and fallback-strength scaling still
apply before translation. This is a user-profile setting, not automatic device detection or
a per-game override. It also affects external pads using those Android paths when enabled.

## Presets

These are comparison candidates, not measured or agreed device defaults. Values exactly
match the requested preset list; no numeric adjustments were made. All presets except
**Original (off)** enable translation. Selecting Original disables it and restores the
original parameter values. **Current preset** and the **Active** marker identify the selection.

| Preset | Intended feel | Gain | Curve | Floor | Kick ms | Low / high weight | Smoothing |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Original (off) | Today's unmodified response; translator disabled | 1.0 | 1.0 | 0 | 0 | 0.80 / 0.33 | 0 |
| Stronger | More overall force with the original response curve | 1.5 | 1.0 | 0 | 0 | 0.80 / 0.33 | 0 |
| Always felt | Lift weak requests to a higher minimum | 1.0 | 1.0 | 140 | 0 | 0.80 / 0.33 | 0 |
| Deep rumble | Favor low-motor rumble over high-motor buzz; compare with PXPlay | 1.0 | 0.8 | 170 | 0 | 1.00 / 0.15 | 0 |
| Sharp impacts | Emphasize impact onset with a short strong kick | 1.0 | 1.0 | 150 | 60 | 0.60 / 0.50 | 0 |
| Smooth deep | Low-motor emphasis with slower amplitude changes | 1.0 | 0.9 | 160 | 0 | 1.00 / 0.20 | 0.6 |
| Max immersion | Strong weak requests, high floor and a kick | 1.4 | 0.7 | 200 | 80 | 1.00 / 0.40 | 0.2 |
| Gentle | Less force and softer weak requests | 0.7 | 1.3 | 0 | 0 | 0.80 / 0.33 | 0 |
| Kick only | Original response after a strong start kick | 1.0 | 1.0 | 0 | 100 | 0.80 / 0.33 | 0 |
| Everything felt | High floor and a brief kick for nonzero input | 1.0 | 0.6 | 220 | 50 | 0.80 / 0.40 | 0 |

The names describe the intent, not a guarantee: a floor of 140 or 170 is below the
previously observed Odin interface-haptic threshold of about 220/255, and may still
be imperceptible. Low/high weights change the host-motor mix on a single vibrator;
they do not change the physical frequency of the motor or promise PXPlay equivalence.

## Advanced parameters

**Advanced** is collapsed whenever this screen opens. A expands/collapses it; the
existing left/right slider controls remain available inside. Editing any parameter,
the enable switch, or resetting parameters marks the selection **Custom**, even when
the resulting numbers match a named preset. A named preset becomes active again only
when explicitly selected.

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

The Advanced **Reset parameters to Original** action restores parameters as Custom
without changing the enable switch; this differs from selecting **Original (off)**.
Changes are saved immediately in the current profile (`checkbox_rumble_translator`,
`pref_rumble_preset`, `pref_rumble_gain`, `pref_rumble_curve`, `pref_rumble_min_amplitude`,
`pref_rumble_kick_ms`, `pref_rumble_low_weight`, `pref_rumble_high_weight`, `pref_rumble_smoothing`).
The preset identity and all parameter values are saved together. A stale identity whose
parameters or enable state no longer match is displayed as Custom. Existing installations
without a saved preset show Original when translation is off and Custom when it is on.
Streaming reads a snapshot at connection setup; reconnect after changing lab values.

## Comparing patterns

The original eleven comparison patterns remain below the preset list and always use
the current selection. They send the same raw percentage to both host motors: steady 25/50/75/100%
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

The preset update additionally passed standalone compilation of the preset model,
preference helper and Debug screen with real Android/Compose APIs, plus 27 permanent
regressions (17 translator, 3 preset boundary/Original, 7 preference selection/persistence).
Two disposable offline Android 13 smoke scenarios exercised every saved preset through
scheduled playback and ran the actual new Test sample coroutine through Android vibration
effects, observing the tap, steady force, rising ramp, decay and final zero amplitude.

These are not a Gradle build, lint run, visual/controller check or physical vibration
measurement. The lead owns full build/lint and Odin acceptance; the user judges the feel
and compares it with PXPlay before any device defaults are agreed. Issue #66 remains open.
