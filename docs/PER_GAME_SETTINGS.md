# Per-game stream settings

Open a game's context menu on the dashboard and choose **Game settings**. The screen uses the
existing settings rows and Stream / Display tabs, with the game's cover and focused option's help on
the right. L1/R1 change tabs, left/right adjust choices and sliders, A changes a value, and B returns
to the dashboard. Touch uses the same controls. The right menu button on a row offers **Use profile
value**; the same action is beside the cover. **Use profile for all** clears this game's overrides
immediately and shows a toast.

## Scope and inheritance

- Stream: resolution, FPS, bitrate, codec and frame pacing.
- Display: HDR, full color range, scaling mode, resolution scale, virtual display and enforced display
  mode. Hardware rules and dependencies match the existing settings screen.
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
- Resetting the bitrate row removes that game's current override and all its codec bitrate slots.
  Use profile for all also removes all slots, without changing other games or the profile.
- The upscaler rows (SGSR) are unavailable for H.264, HEVC and AV1 on devices below OpenGL ES 3.1.
- Advanced custom resolution/FPS definitions remain in the profile; their choices are available here.
- Audio, controls, appearance and other global options continue to come from the user profile.

`GameSettingsStore` stores sparse, typed values under `ui_game_<PC hash>_<app ID>_<setting key>` in
the user's `ui_profile_<UUID>` preferences. PC UUID plus host app ID distinguishes libraries; titles
and artwork do not affect identity. Creating a user copies these values with the other profile
settings, including private codec bitrate slots. Later edits are independent; deleting a user deletes
their overrides/slots too, and removing a PC in Devices deletes both for every user. Slot keys use the
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
`PreferenceConfiguration` stream reader, wrapped contexts and external-display intent forwarding.

Device acceptance: open settings from a game, check both tabs, change its resolution to 720p and
start/resume its stream manually. Verify 1280x720 in statistics, then check that the profile and
another game retain their resolution. Return this game to the profile value and check it follows
the profile again. Also check tab switches after scrolling, one feedback event per switch, touch
adjustment, cover display, help scrolling and focus returning to the dashboard. The user drives the
Odin; agents do not launch games or send navigation input.

2026-10-25: all 51 unit tests passed, including seven new per-game tests. Debug build and Android
lint passed (zero errors, 380 warnings). The ARM64 build was installed on the Odin. The user confirmed
the requested appearance/controller and per-game resolution/isolation checks worked. A screenshot of
the Display tab was also inspected against the existing settings design.
