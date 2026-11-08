# Per-game stream settings

Open a game's context menu on the dashboard and choose **Game settings**. The screen is the approved
prototype's variant B (hero banner), built from the app's own components (`GameSettingsScreen.kt`):

- **Banner** (top 270 stage px): the game's artwork blurred like the app's game background (the
  Appearance hero blur, the cached lens bitmap of the dashboard) and fading into the page; the hero,
  else the cover or the PC's art as on the dashboard, else the generated placeholder art. A scrim keeps the
  text legible on bright art. On it: the eyebrow "GAME SETTINGS · <PC>", the game's name, a chip
  that says how many settings are custom for this game ("3 custom settings") or "Follows your profile",
  and the **Reset all** pill.
- **Tabs**: Stream / Display, centred at y=140 like the global settings (L1/R1 and a tap; never focusable),
  each with the number of its custom settings.
- **List** (from y=218, 1080 wide): the shared rows with the gliding plate. **Panel** on the right: the
  global settings panel (520 wide) with the focused option's icon, name, a source box (**Custom for this
  game** with the profile's value, or **From profile** with the current value), its help (scrolling) and
  the note "Saved automatically · applies next stream". Nothing focused: how many settings are custom.
- L1/R1 change tabs, left/right adjust choices and sliders, A changes a value, B returns to the dashboard.
  Touch uses the same controls.

A setting this game overrides is marked on its row: a violet (accent3) tint, a **CUSTOM** badge and
"Profile: <value>" instead of "From profile". An override equal to the profile value is still an override.

Resetting never asks for confirmation; each action shows a toast with **Undo** (Y on the controller or a
tap, while the toast is visible; it writes back only what the reset removed, including the codec bitrate
slots, and keeps anything changed since):

- **X** on a focused custom row resets it to the profile value ("Back to the profile value"); a pill
  "Reset to profile value" with the X glyph fades in on that row and the hint bar shows X. The right
  menu button still opens the row's context menu with the same **Reset to profile value** (custom rows
  only). With touch, a round reset button is shown on every custom row (the input of the last event
  decides: pointer press = touch, any key = controller).
- **Reset all** is the pill on the banner. It clears all of this game's overrides ("All settings follow
  the profile again"), is dimmed and skipped by the D-pad while the game has no custom settings, and
  after it acts the focus returns to the row last focused.

Focus order: the list is entered on its first row. **Up** from the first row (a fresh press) goes to
the Reset all pill (the only focusable thing above the list; tabs are touch only as in the global
settings); **Down** from the pill returns to the row last focused. Rows are left and entered with
Up/Down as before; Left/Right adjust the focused row.

## Scope and inheritance

- Stream: resolution, FPS, bitrate, codec and frame pacing, then the gyroscope rows: External gamepad gyroscope
  (`checkbox_gamepad_motion_sensors`), Built-in device gyroscope (`checkbox_gamepad_motion_fallback`), Always use built-in
  device gyroscope (`checkbox_force_device_motion`) and Gyro smoothing (`seekbar_gyro_smoothing`). They are hidden where
  motion is unavailable, and Gyro smoothing is disabled while neither source is on in this game's effective values. A
  launch reads them through the snapshot like the other keys (sensor setup and the live `gyroSmoothing` included).
  Stream menu → More → Gyro smoothing applies live and, like the upscaler (next section), is kept for this game
  automatically as its `seekbar_gyro_smoothing` override; the profile is never written from the menu.
- Display: HDR, full color range, scaling mode, resolution scale, virtual display, enforced display
  mode and the upscaler (on/off `checkbox_sgsr`, mode `list_sgsr_quality`, sharpness
  `seekbar_sgsr_sharpness` and supersampling quality `list_sgsr_supersampling_level`, 1 = 1x, 2 = 2x;
  also a profile setting, default 1x). Hardware rules and dependencies match the existing settings screen.
- Each row says **From profile** or **Custom for this game**. Unchanged values follow later edits to
  the user profile. Changes are saved immediately and apply when the next stream starts or resumes.
- Each selected codec keeps its own bitrate for this user, PC and game. Switching away saves the
  current bitrate only when the game owns a bitrate override; inherited profile values are never
  captured. Switching to a codec with a saved value restores it as a game override.
- With no saved value, first use of PyroWave always writes its recommended bitrate, creating an
  override when necessary. First use of an ordinary codec from another ordinary codec keeps the
  current bitrate and inheritance. Leaving PyroWave for an ordinary codec with no saved game value
  removes the bitrate override, so the game follows the profile's current bitrate again.
- Hand edits change the current bitrate but update its codec slot only when switching away.
  Returning to PyroWave restores its saved value, including a hand-edited value. Resolution/FPS/HDR
  changes while PyroWave is selected still recalculate its recommendation; other codecs keep their
  bitrate on quality edits. Recalculation becomes the saved PyroWave value when leaving that codec.
- With effective codec PyroWave, the bitrate row and live help also show the recommendation from the
  effective (inherited or overridden) resolution/FPS/HDR, separately from the chosen bitrate. The
  row keeps its inheritance label; other codecs show no additional recommendation.
- Resetting the bitrate row removes that game's current override and all its codec bitrate slots.
  Reset all also removes all slots, without changing other games or the profile.
- The upscaler rows (SGSR) are unavailable for H.264, HEVC and AV1 on devices below OpenGL ES 3.1.
  An unknown stored supersampling level is read as 1x; a saved 2x that does not fit falls back to 1x
  like the live choice.
- Advanced custom resolution/FPS definitions remain in the profile; their choices are available here.
- Audio, other controls, appearance and other global options continue to come from the user profile.

## The menu keeps the live upscaler and gyro smoothing for the game

There is no save button. The same applies to More → Gyro smoothing, which writes only `seekbar_gyro_smoothing`
(`gyroSmoothingSaveValues`; the external/built-in gyroscope toggles are not in the menu). In the stream menu → Upscaler every change (on/off, mode, sharpness, supersampling quality)
applies live and is written automatically as this game's overrides of the four upscaler keys, in the active user's profile
for this PC and host app ID (`MenuAutoSave`, `GameSettingsStore.save`). A slider drag or held D-pad writes once, 500 ms
after the last change; closing the menu or ending the stream writes at once, so the last value set wins. Opening the menu
writes nothing, and nothing else is written: no bitrate, no other display setting, no profile value, no other game, no
profile is activated. No toast is shown. The values then show as **Custom for this game** in Game settings → Display (and
the game's cover badge), and X / Reset to profile value / Reset all remove them like any override.

The saved values apply to future launches only; the running stream keeps its own (the launch snapshot is fixed). A stream
started without its launch identity (no snapshot, PC or app ID in the intent) has no per-game store and stays
session-only. Both renderers (MediaCodec SGSR and PyroWave) read the same live values. The supersampling level saved is the
one in effect, so a 2x that PyroWave already fell back from is saved as 1x. The mode is saved by its stored value, so Sharpen
only (`sharpen`) is saved and restored like the four SGSR modes; the level is still written, but Sharpen only ignores it.

`GameSettingsStore` stores sparse, typed values under `ui_game_<PC hash>_<app ID>_<setting key>` in
the user's `ui_profile_<UUID>` preferences. PC UUID plus host app ID distinguishes libraries; titles
and artwork do not affect identity. Creating a user copies these values with the other profile
settings, including private codec bitrate slots. Later edits are independent; deleting a user deletes
their overrides/slots too (after the 10 s undo period of the profile toast, see `ProfileStore.delete`;
`ProfileDeleteUndoTest` covers it), and removing a PC in Devices deletes both for every user, also in
profiles that are only waiting to be purged. Slot keys use the
same per-game prefix and never inherit the profile's codec slots or appear in effective-value maps,
the settings catalog or the stream's launch snapshot.

## Launch integration

`GameLauncher.startIntent` is the one place that builds a game's start intent: it reads the effective
virtual-display setting and attaches a snapshot of the curated options to the Game intent. The Delos UI
(`GameLauncher.prepare`) and launcher/PC shortcuts (`ShortcutTrampoline`) both use it, so every way to
start a game streams with that game's overrides. External-display launches carry the snapshot on the nested
Game intent. `ProfilesManager.getOverlayingSharedPreferences` applies this snapshot only when called with
that Game Activity (or a context wrapping it). No active profile is changed and no temporary global values
are written. Unrelated options retain the existing profile behavior. The snapshot survives Activity
recreation through its intent, and another game receives its own values at launch.

This is an original implementation of the per-app settings idea credited to MoreOrLessSoftware's
Moonlight X; see the preserved [feature provenance](../README.md#feature-provenance).

## Verification

`GameSettingsTest` covers live inheritance, PC/game/user isolation, copying and deleting users, forgetting a
PC, persistence across store recreation, resetting one/all overrides, PyroWave bitrate guidance, the actual
`PreferenceConfiguration` stream reader, wrapped contexts and external-display intent forwarding. It also
covers the menu's automatic save: exactly the four upscaler overrides (`upscalerSaveValues`) are written for that game only, a later
launch of it (and not of another game) reads them, including supersampling level 2, resets remove them,
an unknown stored level reads as 1x, the clamping of the saved values, and which launches can save. The
Undo of a reset (`backup` / `restoreMissing`) is covered too: it restores the removed values and codec
bitrate slots, keeps a value changed since, never writes a foreign key and touches no other game.
`MenuAutoSaveTest` covers the debounce with an injected clock (one write after the last change, flush, nothing on open, per-setting merge). `GameSettingsLabelTest` covers the pure screen rules (chip text, per-tab custom counts, the panel
summary, the "Profile: …" value labels). Debug preview of the look with fixed data:
`--es preview_screen game_settings [--ez preview_touch true] [--ez preview_auto true]`.

Device acceptance: open settings from a game, check both tabs, change its resolution to 720p and
start/resume its stream manually. Verify 1280x720 in statistics, then check that the profile and
another game retain their resolution. Use X on a custom row (and the round button with touch), the row's
Reset to profile value in the right-menu, and the Reset all pill, then Undo (Y); check this game follows the
profile again. Check the banner (hero blur against the dashboard, a game without artwork, bright art under
the title), Up from the first row to Reset all and Down back to the same row, Reset all dimmed without
custom settings, tab switches after scrolling, one feedback event per switch, touch
adjustment, help scrolling and focus returning to the dashboard. The user drives the
Odin; agents do not launch games or send navigation input.

Device acceptance for the menu's automatic save: in a stream, open Stream menu → Upscaler, switch the upscaler
on and change mode, sharpness and supersampling quality; close the menu (no save button, no toast). End the stream and
open Game settings → Display: the four rows say Custom for this game (the profile's Display settings unchanged). Start
the game again and check the upscaler comes up as set last (status line, statistics); another game keeps the profile
values. Opening the menu alone must not make any row custom. Reset all returns it.

2026-10-25: all 51 unit tests passed, including seven new per-game tests. Debug build and Android
lint passed (zero errors, 380 warnings). The ARM64 build was installed on the Odin. The user confirmed
the requested appearance/controller and per-game resolution/isolation checks worked. A screenshot of
the Display tab was also inspected against the existing settings design.
