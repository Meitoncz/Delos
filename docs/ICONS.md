# Interface icons

The user selected **Phosphor Regular + Fill** on 2026-10-28, replacing Material Symbols Rounded.
The shared `Symbol` component crossfades between the two bundled fonts over 350 ms on focus.
The fonts use the same code points and advance widths, so the transition does not move labels.
Colors, icon sizes, controller navigation and actions keep their existing behavior.

Implemented and integrated as **`cbd16c9b`** on `main` and `origin/main`, including the user's
Palette adjustment. The completed feature branch was deleted; the push used `[skip ci]`.

## Sources and generation

- Official source: [phosphor-icons/web v2.1.2](https://github.com/phosphor-icons/web/tree/v2.1.2).
- License: MIT, included in `app/src/main/assets/licenses/Phosphor-LICENSE.txt`.
- `tools/fonts/icons.txt` maps stable Delos semantic names to Phosphor names. Existing callers do
  not need to know the source family's names. New entries describe their actual action or setting.
- `tools/fonts/subset_fonts.py` downloads the versioned Regular and Fill TTFs, the code-point CSS
  and license. SHA-256 checks validate cached and downloaded sources before generating anything.
- Generated files: `res/font/phosphor_regular.ttf`, `res/font/phosphor_fill.ttf`, `Symbols.kt`
  and `docs/prototype/phosphor-icons.css`. Commit these along with mapping/generator changes.
- The prototype reads the Android fonts through relative paths; icon rendering needs no CDN.
  Its `data-icon` names use the generated CSS and the same Regular/Fill crossfade.
- The Regular subset is 53,928 bytes and Fill is 46,636 bytes (100,564 bytes combined), replacing
  the 299,768-byte Material Symbols subset. No new runtime library is needed.

Rebuild icons on Windows (Python with `fonttools` installed):

```powershell
python tools/fonts/subset_fonts.py --icons-only --work build/phosphor-icons
```

Without `--icons-only`, the existing Unbounded/Figtree generation also runs. A normal Gradle
build uses the committed subsets and needs neither Python nor a font download.

## Choosing icons

Settings use an icon for their meaning rather than inheriting one icon for a whole category.
Examples include USB, motion sensors, cursor, gestures, horizontal/vertical sensitivity, key layout,
import/export, clipboard privacy, asynchronous decoding, upscaler mode and sharpness.

Stream menu actions distinguish keyboard/layout controls, task manager/statistics, sending/receiving,
and touchscreen/cursor. Resume uses Play/Pause; starting a game uses Play. Identical actions and
closely related settings deliberately share a symbol across screens, including per-game settings.

The battery body remains the existing custom drawing with a proportional charge fill; only its
charging bolt comes from the icon font. Controller letters and the Delos logo remain custom too.

## Alignment correction

The user reported a shared leftward shift after the replacement. The cause was in font subsetting,
not Compose alignment or prototype CSS: upstream Phosphor glyphs have loose stored `xMin` bounds.
Saving the subset recalculated tight bounds without adjusting the horizontal side bearing.
This changed the TrueType origin (`xMin - lsb`) and pulled the artwork left within its 1024-unit advance.
For example, Plus changed from a centered design to a 128-unit leftward shift.

The generator now explicitly recalculates icon bounds and adjusts each left side bearing to preserve
the source origin. Advance widths, contours and intentional asymmetry stay unchanged.
Both Regular and Fill assets are regenerated; the shared Android component and prototype consume
the same corrected fonts without offsets or per-icon layout patches.

Verification on the physical Odin: the debug `IconFontInstrumentation` rasterized Plus, Gear,
Desktop and User Circle from both bundled fonts at 24/48/96 px. Every measured horizontal center
error was 0 px. Its raster proof includes center guides. The actual PC-selection screen was also
captured and visually checked. Chromium measured matching 96 px advances and 48 px ink centers
for the same icons in both prototype fonts. All 191 glyph origins per font match the source;
regeneration is byte-identical and subset sizes remain unchanged.

```powershell
adb -s <device> shell am instrument -w io.github.meitoncz.delos.debug/com.limelight.shell.ui.IconFontInstrumentation
```

The ARM64 APK with corrected metrics is installed. Local build, all 80 unit tests and lint pass
(0 errors, 387 existing warnings). Evidence is under ignored `build/phosphor-icons/`:
`odin-icon-centering.png` is the real Android raster proof and `odin-shell-centered.png` is the
actual application screen. No game was launched or controller/menu navigation injected.

## Original replacement verification

Current checkpoint (2026-10-28): ARM64 build installed on the Odin and accepted by the user.
The final requested adjustment uses the Palette icon for the color-palette selector in both
the app and prototype; it applies consistently while cycling through individual palettes. The final
APK containing that adjustment was rebuilt and installed after checking that no stream was active.
Local build and all 80 unit tests pass. Lint reports zero errors and 387 pre-existing warnings.
All 105 catalog settings have explicit icon mappings. All 200 semantic mappings resolve to nonempty
glyphs in both subsets with matching widths. Regeneration is byte-identical. The browser check loads
the local fonts and verifies generated icons and the Regular/Fill focus states in the real prototype.

Local evidence is kept under ignored `build/phosphor-icons/`: `palette-check.log` is the final
build/unit/lint run, `browser-result.html` records the prototype check, and `odin-icons.png` captures
the real-device shell. These checks belong to the implementation; documentation-only updates do
not require reinstalling the APK or launching a game.

- For current builds, run `:app:assembleDebug`, `:app:testDebugUnitTest` and `:app:lintDebug`.
- Validate all semantic mappings against both fonts, including their code points and metrics.
- Check prototype rendering and focus variants with the same local font subsets.
- Physical Odin: check icons in the shell, settings, context/side menus and stream menu; verify
  focus transitions and controller hints. Device acceptance is required before integration.

## Console glyph review assets (2026-11-06)

`tools/fonts/console/` contains two original, unapproved standing-console candidates:
**A — Open seam** and **B — Swept wing**, each in Regular and Fill. They use the Phosphor
256-unit design grid; Regular's 16-unit round strokes are already expanded into closed paths.
Fill keeps the rear-wing separation and disc slot as transparent negative space. Neither design
contains a logo or wordmark. These assets are not part of the bundled fonts or app UI.

Generate the self-contained, offline comparison sheet with the standard-library-only script:

```sh
python3 tools/fonts/console/review.py --output build/ps5-icon-proto/round4/index.html
```

It embeds the actual bundled Phosphor fonts and candidate SVGs at 24, 32, 48 and 96 px, with
a selected device-pill context and a light-surface check. The review PNG is a headless Chromium
capture of this HTML, not an independently reconstructed drawing. In that renderer both candidates
retain separate rear/front ink runs at all four sizes. B emphasizes the curved shell; choosing a
candidate remains a user decision.

For later integration after selection, extend `subset_fonts.py` with a local custom-glyph source
before subsetting. Reserve one unused private-use code point after checking both full upstream
cmaps, import the selected Regular/Fill paths into the respective fonts, and preserve their shared
advance and origin. Map the 256-unit SVG coordinates to the font's actual units-per-em, flip the
Y axis to its baseline convention, and keep counters/negative-space winding. Add a semantic
`CONSOLE` entry through `icons.txt` and the generator so `Symbols.kt` and prototype CSS are generated
together. Regenerate both committed TTF subsets and validate their bounds, matching advances and
Regular/Fill crossfade before using `Symbols.CONSOLE`. The current mapping parser accepts only
upstream Phosphor names, so adding a mapping alone is not sufficient. A vector drawable is an
alternative, but font integration preserves the existing shared `Symbol` focus crossfade.
