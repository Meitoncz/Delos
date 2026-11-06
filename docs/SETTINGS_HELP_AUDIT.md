# Settings and help audit (2026-10-28)

## Scope and evidence

Reviewed Delos history from the Vibetemis documentation and fork identity through `8e7923b5`,
including commits co-authored by Claude Opus 5.5, subsequent Codex work, the current preference
catalog, shell rows, per-game settings, stream menu and the actual decoder/launch implementations.
This is a feature-surface and help audit, not a new line-by-line security review or a benchmark of
every device-specific option. The approved [prototype](prototype/delos-gui.html) supplies the
settings layout: controls on the left and explanatory content in the right-hand panel.

Current coverage: **105 XML settings**, **29 native shell rows**, all choices within those settings,
dynamic resolution/FPS choices, the per-game subset, and all stream-menu actions and controls.
The old 116-setting count in the Phase 6.7 document was a historical count, not today's coverage.

## History reconciled with the application

| Work | Evidence | Current user-facing result |
| --- | --- | --- |
| Fork identity, common-c and Moonlight merge | `e5810da0`, `df07176a`, `43f1ca24`, `9758dd83` | Delos identity and updated streaming platform; not extra user switches for build dependencies. |
| Artemis cleanup and low-latency audit | `69cf4527`, `d084d856`, `7824ee1f`, `ffb5e8da`, `e87e9106`, `d20527ba` | Supported controller/input/display features retained; removed SBS 3D, obsolete logging and unsupported/dead options are deliberate removals. |
| Native shell, profiles, settings and artwork | `98902f89` through `1974711d`, `dd34740f`, `bccc694c` | Shell settings and diagnostics are present; profile changes, SteamGridDB and controls previews have their own entry points. |
| Stream-menu and statistics cleanup | `35f44700`, `c6219b88`, `a34f6927` | Stream actions moved into the Delos menu. Fullscreen is permanent; the ignored host optimization switch and old overlay zoom button were intentionally removed. |
| PyroWave, HDR, brightness and 1% low FPS | `47a0509d`, `6a0cddd2`, `4158f666`, `e1c65ba8` | Codec selection, HDR, stream brightness and statistics are exposed. PyroWave has a separate decoder with different applicable controls. |
| Per-game quality/display settings | `714f21a3` | Sparse overrides use the same setting keys and explanations as profile settings. Touch settings intentionally stay at profile level. |
| Runtime bitrate, async decode, Nano Pacer and SGSR | `a7c8c22b`, `01476d51`, `999bde9a` | Saved defaults and live session controls are distinct. Supersampling quality is a stored profile and per-game setting (Display); stream menu changes last one session unless Stream menu → Upscaler → Save for this game stores the live values for that game. |
| External displays and Wi-Fi keep-alive naming | `7b6acf99`, `8e7923b5` | External output preserves configured stream resolution/FPS. Keep-alive already existed; it is not another unimplemented feature. |

## Findings and changes

1. **Choice help did not follow the choice.** The right panel repeated a general summary and the
   value label. It now starts with the selected option's actual explanation, then shared behavior
   and relevant limitations. This covers all seven pacing choices, five codecs, four SGSR choices,
   scale modes, audio layouts, touch modes, language/layout choices and native appearance choices.
   Dynamic/custom resolutions and rates receive explanations too.
2. **Latency interactions were not explained.** Warp Drive requests 2× the selected FPS and Warp 2
   requests 4×; neither increases the physical display refresh. LFR selects a Balanced variant for
   MediaCodec, except when Fast V-Sync takes precedence. Async decoding and Ultra Low Latency are
   separate MediaCodec options. The help describes load, compatibility and timing tradeoffs without
   promising a measured latency improvement.
3. **Codec labels contained “Prefer”.** Labels are now Automatic, AV1, HEVC (H.265), H.264 and
   PyroWave. The right panel explains negotiation/fallback and actual-format reporting; removing
   the word does not turn a negotiated codec request into a guarantee.
4. **Some inherited text described obsolete behavior.** Explanations now cover Vibepollo/Apollo
   virtual displays, preserved stream resolution/FPS on an external display, all supported HDR
   codec paths, current SGSR behavior, custom input, native diagnostics and completed SteamGridDB.
   Host display scaling, network resolution and local supersampling are explicitly distinguished.
5. **PyroWave SGSR defaults were still disabled in settings.** The original audit removed the
   blanket gate for the switch/mode and documented the native gaps then present. A01–A05 were
   subsequently completed on 2026-10-28: all four native modes, independent presentation sharpness
   and supersampling are available, with help and active status matching the renderer.
   Only MediaCodec-specific async decoding, LFR and Ultra Low Latency remain inapplicable.
6. **The live menu lacked a right-hand help panel.** Its actions and adjustable rows now update
   one. The main actions wrap into two rows to make room. Mode help tracks live selection;
   supersampling, sharpening, bitrate acceptance, brightness, statistics, clipboard and commands
   explain effects and persistence. Disconnect keeps the game running; Quit game ends it.
7. **The live mouse chooser still used a legacy list without option explanations.** It is now a
   Delos stream-menu page with per-mode help and the local-cursor toggle. It reuses the same input
   implementation, external-display restrictions and Remember mouse mode persistence policy.
8. **Long help needed passive reading.** At the user's request, overflowing text automatically
   scrolls down and back up at a constant reading speed, pausing at both ends. It restarts at the
   top when the setting or selected value changes. The heading stays fixed, both text edges fade,
   and endpoint padding lets the first/last lines rest fully visible. The panel cannot take focus
   or be scrolled manually with touch or a controller. Short text stays still. The same behavior
   is used in profile settings, per-game settings and the stream menu.
   Help text length is bounded: every setting and selectable value must fit the settings box at
   125% interface scale (about 31 characters by 7 lines) without scrolling; see `SettingsHelpFitTest`.
9. **Descriptions were inconsistently duplicated under short rows.** Explanatory text now stays in
   the right panel, while controls remain compact. Per-game source labels remain under the rows.
   Search includes option names and explanations, including unselected pacing/codec options.
10. **A filtered-out saved choice could look like another mode.** Profile and per-game rows now show
    the actual stored choice with an unavailable label, keep its explanation, and select the first
    or last supported option only when the user changes it. For example, switching to PyroWave must
    not silently display Warp 2 while Fast V-Sync is still stored.

## Coverage and remaining product boundaries

- Existing profile preference keys, types and values are preserved. UI descriptions explain when
  changes apply to the next stream and which live changes remain temporary.
- Every catalog setting must have authored help; every enumerated choice must have specific help.
  Regression tests also check dynamic choices, LFR precedence, “Prefer” removal, search and the
  PyroWave applicability rules. Appearance enums are checked against native choice help.
- No additional PC-cursor hiding, VRR or PS Remote Play implementation is implied. Those remain
  future features. The local Android cursor option is explicitly distinguished from the PC cursor.
- The approved per-game subset remains focused on quality/display. Other settings inherit from the
  user profile. Adding more per-game overrides or persistent live settings remains a product choice.
- The shell and its new help remain English, consistent with the current UI plan. Existing language
  selection still controls available translated stream messages, not the new shell.
- This audit does not establish that every latency switch improves every device, or that Wi-Fi
  keep-alive measurably improves the Odin. Those claims would require controlled measurements.

## Validation

The command and results below are historical evidence from 2026-10-28.
After flavor removal, the equivalent command is
`gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain`;
this audit does not record a run of those renamed tasks.

- Final command: `gradlew.bat assembleNonRoot_gameDebug testNonRoot_gameDebugUnitTest lintNonRoot_gameDebug --offline --console=plain`.
- **79 unit tests passed**, zero failures or errors. The final autoscroll build succeeded.
- **Lint: zero errors, 387 warnings**, matching the pre-audit warning count. The existing lint
  quick-fix exception for an unrelated Turkish translation does not fail the build.
- Installed the ARM64 debug APK on the Odin after confirming ShellActivity, rather than a stream,
  was foreground. A read-only screenshot confirms the fixed heading, selected pacing help, LFR
  context, faded text edges and absence of the retired manual-help shortcut. No AndroidRuntime
  error appeared in the inspected log.
- **The user accepted the installed Odin build on 2026-10-28.** This is overall device acceptance,
  not a separately recorded test of every control. The later external-display help layout still
  needs a dedicated TV check. No game was launched and no navigation input was injected by the agent.
- Integrated in `398e4a8e` using the agreed commit, fast-forward, `[skip ci]` push and feature-branch
  cleanup workflow. GitHub Actions were not requested or run.
