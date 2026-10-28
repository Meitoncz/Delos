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
- Resolution, FPS and non-PyroWave codec changes preserve the current bitrate. An inherited bitrate
  keeps following the profile instead of becoming a new game override. Selecting PyroWave, or
  changing resolution/FPS/HDR while it is selected, writes its recommendation as a game override.
  Leaving PyroWave preserves the current value too. Bitrate can be adjusted or reset separately.
- Advanced custom resolution/FPS definitions remain in the profile; their choices are available here.
- Audio, controls, appearance and other global options continue to come from the user profile.

`GameSettingsStore` stores sparse, typed values under `ui_game_<PC hash>_<app ID>_<setting key>` in
the user's `ui_profile_<UUID>` preferences. PC UUID plus host app ID distinguishes libraries; titles
and artwork do not affect identity. Creating a user copies these values with the other profile
settings. Later edits are independent; deleting a user deletes their overrides too.

## Launch integration

`GameLauncher.prepare` reads the effective virtual-display setting and attaches a snapshot of the
curated options to the Game intent. External-display launches carry it on the nested Game intent.
`ProfilesManager.getOverlayingSharedPreferences` applies this snapshot only when called with that
Game Activity (or a context wrapping it). No active profile is changed and no temporary global values
are written. Unrelated options retain the existing profile behavior. The snapshot survives Activity
recreation through its intent, and another game receives its own values at launch.

This is an original implementation of the per-app settings idea credited to MoreOrLessSoftware's
Moonlight X in [IDEAS_FROM_STARWIRE.md](IDEAS_FROM_STARWIRE.md).

## Verification

`GameSettingsTest` covers live inheritance, PC/game/user isolation, copying users, persistence across
store recreation, resetting one/all overrides, existing PyroWave bitrate guidance, the actual
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
