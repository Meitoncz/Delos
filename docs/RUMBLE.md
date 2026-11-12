# Rumble feel and the rumble laboratory

## Rumble feel (Settings)

**Settings → Controls → Gamepad** (next to *Enable Rumble*) has a **Rumble feel** row with four choices.
It is shown only where the other vibration settings are (not on a device without a vibration motor) and is
dimmed while *Enable Rumble* is off. It applies from the next stream, is profile-scoped like the rumble
keys and has **no per-game override** (the other rumble settings have none either).

| Choice | What it does | Parameters |
| --- | --- | --- |
| Original | The game's rumble exactly as it sends it (translator off) | gain 1.0, curve 1.0, floor 0, kick 0 ms, low/high 0.80 / 0.33 |
| Soft | A weaker, rounded rumble | gain 0.7, curve 1.3, floor 0, kick 0 ms, low/high 0.80 / 0.33 |
| Kick-start | A strong short kick at the start of each rumble, then the normal level | gain 1.0, curve 1.0, floor 0, kick 100 ms, low/high 0.80 / 0.33 |
| Pulsing | A slow pulsed rumble that feels deeper | gain 1.0, curve 0.8, floor 50, no kick, low/high 1.00 / 0.35, pulse 20 ms on / 20 ms off |

The row has no value of its own: it shows `RumblePreferences.feelValue` of the saved rumble keys and writes
through `RumblePreferences.applyPreset`, so it always agrees with the laboratory and the stream menu. It
shows **Custom** (not selectable) after an Advanced edit in the laboratory, or when a profile was saved under a
preset that no longer exists; picking a choice replaces it. The stored identities are the enum names of
`RumblePresets` (`ORIGINAL`, `GENTLE`, `KICK_ONLY`, `PULSE_25`) and must not be renamed.

## Rumble laboratory

Open **Settings → About → Debug info**, then scroll to **Rumble laboratory**.
Use up/down to focus a preset and A to select it; B returns. Touch works through the
same setting rows. Selection saves all parameters, marks the active row, and immediately
plays the same short **Test** sample: a 60 ms tap, 1 second of steady 50% input, a ramp,
and an explosion decay (about 2.8 seconds including pauses). **Replay Test sample** repeats
it without changing the selection. The laboratory lists the same four presets as Settings.

The translator is **off by default**. Off plays the game's rumble with the Original mapping (the values the legacy code
computed, kept bit for bit by the tests); lab patterns use the Original mapping. Turning it on applies the saved tuning to Android
single/dual/quad vibrator paths on the next stream. USB-driver and Shield-specific rumble
remain unchanged. Existing device/OSC/fallback routing and fallback-strength scaling still
apply before translation. This is a user-profile setting, not automatic device detection or
a per-game override. It also affects external pads using those Android paths when enabled.

## Rumble feel in Settings and in the stream

**Settings:** Controls > Gamepad > **Rumble feel** (see [SETTINGS.md](SETTINGS.md)).

**Stream menu:** open the stream menu (**More** tile) and choose **Rumble feel**; the row sits after
**Touch sensitivity**, the last row of More, and shows the active name. The page lists the same four presets (Original, Soft,
Kick-start, Pulsing). A preset the hardware cannot run is marked **Problem** with the reason in the help line and does
nothing; if the active preset's technique fails during the stream the row shows **Problem** too, with the reason.
A on a preset applies and saves it exactly like the laboratory (same `RumblePreferences.applyPreset`), marks it
**Active**, plays no sample and leaves the menu open so presets can be tried back to back. B returns to More.
`ControllerHandler` follows `PreferenceConfiguration.rumbleTranslatorEnabled`/`rumbleProfile` at every rumble
event, so the new preset is used from the next rumble the game sends (the previous output is cancelled
when the preset changes; a rumble the game keeps holding stays silent until its next update).

The Debug laboratory (Settings > About > Debug info) stays as a developer tool for tuning the parameters.

## Presets

The four presets are the ones chosen on the Odin out of eighteen comparison candidates; their values are unchanged
(see the table above). **Original** disables the translator and restores the original parameter values; the others
enable it. **Current preset** and the **Active** marker identify the selection.

The Odin 2 Portal Max has **one** vibrator with amplitude control but no frequency control, so the amplitude presets
all share the same high buzz. **Pulsing** changes the *technique*: a repeating on/off amplitude waveform
(`VibrationEffect.createWaveform`, repeating from index 0) whose on and off durations are part of the profile
(`pulseOnMs`, `pulseOffMs`; saved as `pref_rumble_pulse_on_ms`/`pref_rumble_pulse_off_ms`). The on phase plays the translated
amplitude (never below the floor), the off phase amplitude 0; the waveform is replaced when the amplitude changes (also at the end
of a start kick) and cancelled at zero. It applies to the single-vibrator path (the Odin); dual/quad vibrators keep the
amplitude path and record that as a problem.

**Removed presets:** Stronger, Always felt, Deep rumble, Sharp impacts, Smooth deep, Max immersion, Everything felt and
the pulse variants (kick, 20/15/60/40 Hz, gentle, heavy) were not chosen and are gone. A profile saved under one of them
loads with its saved values and shows as **Custom**. The PULSE technique stays (Pulsing needs it); a profile saved by an
earlier build under `PULSE_40`/`PULSE_60` without on/off keys keeps its timings (13/12, 8/8).

**Removed techniques:** the Thud burst and Low-tick burst presets (compositions of 12 `PRIMITIVE_THUD`/`PRIMITIVE_LOW_TICK`) were
tried on the Odin and did nothing the user could feel, so the code, the primitive capability probe and the presets are gone.
Profiles saved under those names load as the plain amplitude path and show as Custom; `HAPTIC_WAVE` and unknown names load the same
way.

**Removed technique:** a haptic audio channel (`AudioTrack` with `CHANNEL_OUT_HAPTIC_A`, the old Deep wave 40/60/80 Hz presets) was
tried and removed because it cannot work here: on the Odin (Android 13, API 33) `AudioTrack` rejects haptic channel masks for apps
("Channel configuration features unsupported channels"), so those presets silently played the plain amplitude path.

**Problems are never silent:** if a technique cannot run (no amplitude control, dual/quad vibrators, an
exception from the vibrator service), the plain amplitude path is used for that output **and** the reason is recorded
(`RumblePlayback.getProblem`). The lab's **Device haptic capabilities** block shows it in **Last problem** (plus vibrator count
and amplitude control), the preset row says `Problem: <reason>` in its help text and the stream menu's preset row says
**Problem** with `Problem: <reason>` in its help line. Presets the device cannot run at all (checked from the capability block) are listed
as **Problem** and cannot be selected.

The names describe the intent, not a guarantee: a floor of 50 is well below the
previously observed Odin interface-haptic threshold of about 220/255. Low/high weights change the host-motor mix on a single vibrator;
they do not change the physical frequency of the motor or promise PXPlay equivalence.

## What ends a rumble

The host sends a rumble packet only when the game changes the motors and never repeats it, and the client keeps no clock that
ends a rumble of its own. On the device a host value plays like this:

- **Amplitude path** (every feel but Pulsing, and the fallback of a feel that cannot run): each host value is a one-shot of
  **60 s** at the translated amplitude; the next host value replaces it. A translated amplitude of 0 (a host zero, or a value
  below one amplitude step) cancels instead.
- **Pulse path** (Pulsing) and the PWM path of a vibrator without amplitude control: a waveform that **repeats until it is
  cancelled**.
- Besides the next host value only `RumblePlayback.stop` (the stream ends, the feel is switched live) and a destroyed controller
  context (a device change) end it. Both cancel through the vibrator object that played the effect: the system compares the
  binder token of that object (`VibratorManagerService.cancelVibrate(usageFilter, token)`), so a `cancel()` through another
  vibrator object does not reach it.

`ControllerHandler.stop()` stops the playback for good: a rumble event that races it can no longer vibrate, because the playback
checks its stopped flag under the lock that also covers the vibrate call, and `stopped` is volatile (the native rumble thread
reads it while the UI thread writes it). USB pads stop their own rumble in their drivers' `stop()`.

The Debug laboratory's Continuous tests and the pad test start repeating waveforms, so they cancel the very vibrator objects they
started (an input device's vibrator object is replaced when the system refreshes the device) and stop when the app goes to the
background.

## Rumble diagnostics

Every rumble decision is one line that starts with `RumblePlayback` (`LimeLog.info`: logcat and the app's diagnostic log, Debug
info → Share diagnostic log). The host event itself is the line `Rumble on gamepad N: low high` (hex, `DelosStreamActivity`)
that comes first. Read the lines of one stream like this:

| Line | Meaning |
| --- | --- |
| `RumblePlayback started: translator on, profile technique=PULSE pulse on/off=20/20 ms gain=1.0 ...` | The profile in effect for the stream, and again after each live feel switch. Shows whether the translator was really on. |
| `RumblePlayback system: media vibration intensity 1 (0 off, 1 low, 2 medium, 3 high, -1 not set)` | The system setting that scales a MEDIA vibration before it plays: `dumpsys vibrator_manager` shows the scaled amplitude in `effect:` and the app's own in `originalEffect:`; compare with the `amp` of the host lines using `originalEffect:`. |
| `RumblePlayback route: controller context 'name' (input device 7, external=false, enableDeviceRumble=false) rumbles via the device vibrator (single#1a2b3c)` | Once per controller: which vibrator object its rumble goes to. `single#` / `manager#` and the hash are the labels of the outputs below. |
| `RumblePlayback output single#1a2b3c id=0 created: amplitudeControl=true requested=PULSE using=PULSE` | A vibrator that rumble reached for the first time, and the technique that really runs (a fallback is logged as `falls back from PULSE to AMPLITUDE`). |
| `RumblePlayback host 1400 0000 -> single#1a2b3c id=0 translated amp 16: vibrate one-shot 60000 ms amp 16 via Vibrator.vibrate(VibrationEffect, VibrationAttributes) [VibrationAttributes usage MEDIA]` | One host event: the raw motors, the translated amplitude (one per channel for a dual or quad manager: `translated amps [64,128]`) and the call that was made. |
| `... vibrate pulse waveform on/off 20/20 ms amp 50 repeating until cancelled ...` / `... no vibrator call, the pulse waveform at amp 50 is already playing ...` | The Pulsing feel: a new waveform, or an event at the same amplitude that changed nothing. |
| `RumblePlayback host 0000 0000 -> single#1a2b3c id=0 translated amp 0: cancel via Vibrator.cancel() requested by host zero` | A cancel and who asked: `host zero`, `host value translated to zero` (the motors were not 0 but the mix is below one step), `settling tick reached zero` (kick or smoothing), `technique ... failed`. |
| `RumblePlayback stop requested by stream teardown (ControllerHandler.stop): cancelling 1 output(s)` and `RumblePlayback cancel single#1a2b3c via Vibrator.cancel() requested by ...` | The stream ended (`stream teardown`) or the feel was switched (`rumble preset changed`); one `cancel` line per output. |
| `RumblePlayback teardown: controller context 'name' is destroyed, its vibrator single#1a2b3c is cancelled` and `... requested by output discarded (device context destroyed)` | A device change or removal cancelled the vibrator. |
| `RumblePlayback host 0000 0000 for controller 0 drove no output: matching context false, input contexts 1, USB contexts 0` | The event reached no vibrator at all (no controller context with that number, or one without a vibrator), a host zero included. |
| `RumblePlayback host 8000 0000 -> single#1a2b3c: ignored, the playback is stopped` | An event that arrived after the stop. |

A vibration that goes on after the game let go is one of three things, and the lines tell which: the last `Rumble on gamepad`
line before it is not zero and no zero follows (the host never cleared the value, so nothing on the client ends it); a zero
arrived but is followed by `drove no output` (it reached no vibrator); or a `cancel ... requested by host zero` line is there
but `dumpsys vibrator_manager` still shows the effect (the cancel did not reach it). The native layer logs `Failed to queue async
callback` when its queue of 30 pending host callbacks overflows and drops the newest packet: that is where a host zero could be
lost before it reaches Java.

`RumblePlaybackTest` runs random host sequences (seeded, every feel, single/dual/quad) against a model of the system's
vibrator service and checks that a final host zero always leaves nothing vibrating and no timer restarts it.

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
without changing the enable switch; this differs from selecting **Original**.
Changes are saved immediately in the current profile (`checkbox_rumble_translator`,
`pref_rumble_preset`, `pref_rumble_gain`, `pref_rumble_curve`, `pref_rumble_min_amplitude`,
`pref_rumble_kick_ms`, `pref_rumble_low_weight`, `pref_rumble_high_weight`, `pref_rumble_smoothing`).
The preset identity and all parameter values are saved together. A stale identity whose
parameters or enable state no longer match is displayed as Custom. Existing installations
without a saved preset show Original when translation is off and Custom when it is on.
Streaming reads the saved values at connection setup; the stream menu's live switch (above) overrides them for the running stream. Lab edits still need a reconnect.

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
