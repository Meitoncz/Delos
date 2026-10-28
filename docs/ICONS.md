# Interface icons

The user selected **Phosphor Regular + Fill** on 2026-10-28, replacing Material Symbols Rounded.
The shared `Symbol` component crossfades between the two bundled fonts over 350 ms on focus.
The fonts use the same code points and advance widths, so the transition does not move labels.
Colors, icon sizes, controller navigation and actions keep their existing behavior.

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

## Verification

Current checkpoint (2026-10-28): ARM64 build installed on the Odin and accepted by the user.
The final requested adjustment uses the Palette icon for the color-palette selector in both
the app and prototype; it applies consistently while cycling through individual palettes.
Local build and all 80 unit tests pass. Lint reports zero errors and 387 pre-existing warnings.
All 105 catalog settings have explicit icon mappings. All 200 semantic mappings resolve to nonempty
glyphs in both subsets with matching widths. Regeneration is byte-identical. The browser check loads
the local fonts and verifies generated icons and the Regular/Fill focus states in the real prototype.

- Build, unit tests and lint for `nonRoot_gameDebug`.
- Validate all semantic mappings against both fonts, including their code points and metrics.
- Check prototype rendering and focus variants with the same local font subsets.
- Physical Odin: check icons in the shell, settings, context/side menus and stream menu; verify
  focus transitions and controller hints. Device acceptance is required before integration.
