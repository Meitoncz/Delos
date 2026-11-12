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
- `tools/fonts/icons.txt` maps stable Delos semantic names to Phosphor names or local names declared
  in `tools/fonts/custom.json`. Existing callers do not need to know the source family's names.
  New entries describe their actual action or setting.
- `tools/fonts/subset_fonts.py` downloads the versioned Regular and Fill TTFs, the code-point CSS
  and license. SHA-256 checks validate cached and downloaded sources before generating anything.
- Generated files: `res/font/phosphor_regular.ttf`, `res/font/phosphor_fill.ttf`, `Symbols.kt`
  and `docs/prototype/phosphor-icons.css`. Commit these along with mapping/generator changes.
- The prototype reads the Android fonts through relative paths; icon rendering needs no CDN.
  Its `data-icon` names use the generated CSS and the same Regular/Fill crossfade.
- The Regular subset is 100,524 bytes and Fill is 87,784 bytes (188,308 bytes combined, 341 semantic
  names including the selected custom Logo glyph), replacing the 299,768-byte Material Symbols subset.
  No new runtime library is needed.

Rebuild icons on Windows (Python with `fonttools` installed):

```powershell
python tools/fonts/subset_fonts.py --icons-only --work build/phosphor-icons
```

Without `--icons-only`, the existing Unbounded/Figtree generation also runs. A normal Gradle
build uses the committed subsets and needs neither Python nor a font download.

## Choosing icons

Settings use an icon for their meaning rather than inheriting one icon for a whole category.
Every icon of the app is listed in [ICON_AUDIT.md](ICON_AUDIT.md) with its meaning; the rule is
one glyph per meaning (a glyph is repeated only where the meaning is really identical, and the
allowed repeats are named there) and no two rows of one settings tab share a glyph
(`SettingIconsTest`). Add a glyph by giving it its own semantic name in `tools/fonts/icons.txt`
and re-running the generator; any Phosphor 2.1.2 icon is available, not only the bundled subset.
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
the app and prototype; it applies consistently while cycling through individual palettes.
(The icon audit later gave the Appearance tab its own brush, so the palette stands for the
palette selector only.) The final
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

**Decision (user, 2026-11-07):** both candidates are kept. When the PS Remote Play work needs the glyph, import BOTH A (Open seam) and B (Swept wing), Regular and Fill, into the fonts as two semantic entries (for example `CONSOLE` for B, the earlier favourite, and `CONSOLE_ALT` for A), so the user can compare them inside the real app (shell, device pills, settings) and choose one. The loser is then removed from the font, the generator and this note. Until then neither is part of the bundled fonts or UI.

For console integration, reuse the local custom-glyph mechanism below; neither console candidate
is imported yet. Assign each its own unused code point and mapping, then regenerate both fonts.
Validate bounds, matching advances and Regular/Fill crossfade before using the new symbols.

## Custom glyphs and the Logo tab

The user selected **A — Wordmark plate** for the Artwork Logo tab. `logo = delos-wordmark-plate`
resolves to **U+F000** in both subsets; `Symbols.LOGO` callers are unchanged. The old `trademark`
glyph is omitted because no semantic entry uses it. Sources are
`tools/fonts/logo/a-wordmark-plate-{regular,fill}.svg`; the rejected review alternatives are not imported.

`tools/fonts/custom.json` declares a local source name, explicit hexadecimal `codepoint`, and
`regular`/`fill` SVG paths relative to `tools/fonts/`. The existing `icons.txt` syntax and upstream
name parser remain unchanged. To add a glyph:

1. Supply closed paths on a 256×256 SVG grid. Resolve overlaps and contour winding before import;
   counters must have opposite winding to their surrounding contour. SVG even-odd attributes alone
   do not encode font counter semantics.
2. Add a manifest entry with an unused BMP private-use point (U+E000–U+F8FF). The generator checks
   every Unicode cmap of BOTH complete upstream fonts and rejects collisions, duplicate local points
   and names shadowing upstream icons. U+F000 was unused in both pinned full fonts.
3. Map a semantic name in `icons.txt` to that manifest name. Only referenced custom entries are imported.
4. Run the normal `--icons-only` command. Import scales to units-per-em, flips Y onto the source
   font's ascent/baseline convention, converts cubic curves to quadratic contours, and sets the shared
   1024-unit advance with zero origin. The existing bearing correction still preserves every origin.
   Cached upstream files remain untouched; import occurs in memory before subsetting.
5. Review generated fonts, symbols and CSS together, render Regular/Fill at actual use sizes and
   verify Android crossfade/physical appearance before accepting integration.

Verification for Logo: cached-source and fresh-download generation both completed. All 340 other
semantic code points, and all 333 retained upstream glyphs' compiled contours and horizontal metrics
per font, are unchanged. Both Logo glyphs have bounds (96,128)–(928,768), advance 1024 and origin zero.
Pillow renders at 32/96 px beside Cover/Hero were visually inspected: the title and counters remain
visible, with no clipped Logo paths and matching alignment between weights. Evidence is in ignored
`build/logo-integration/`. Android/Odin rendering and focus crossfade remain for the lead to verify;
no Gradle, ADB or commit was performed by this integration task.
