# Logo image glyph candidates

The user selected **A — Wordmark plate**. It is now imported by `tools/fonts/custom.json` at U+F000 in both generated fonts, without changing callers of `Symbols.LOGO`. B and C remain review-only alternatives. No company logo or actual game wordmark is used. The comparison below records the candidate review; final Android appearance remains unverified.

## Candidates

- **A — Wordmark plate:** a wide title plate, abstract A-like lettering, two unequal title strokes and a curved underline. Recommended of these three: lettering and swash distinguish title artwork from Cover/Hero. Weakness: at 24 px it can also suggest a text card or document; the letter counter is small, though still visible in the inspected Chromium capture.
- **B — Title over a scene:** landscape plus a separate heavy title strip. The strip survives at 24/32 px in Regular and as transparent negative space in Fill. Weakness: its landscape is deliberately close to the Cover/Hero visual vocabulary and is therefore the least distinct tab candidate. It is kept as the requested scene-based alternative, not presented as solving that ambiguity.
- **C — Emblem + title:** an original shield with a diamond-shaped counter, two title bars and a baseline. It has the most different silhouette from the image tabs. Weakness: the badge may suggest achievement/security rather than title artwork; the separated emblem/title spacing is quite fine at 24 px. It contains no checkmark.

## Construction

All six SVGs use the 256-unit Phosphor grid and currentColor, with closed filled paths and even-odd winding. Regular uses expanded 16-unit round strokes. Contour intersections were unioned before export to avoid accidental holes. Fill uses transparent counters for title/scene/emblem details; C's title strokes are 24 units for visual balance beside its solid badge. Conversion used isolated Skia tooling only during authoring; the checked-in review generator needs the Python standard library only.

## Reproduce

From any directory, pass absolute paths:

```sh
python3 /Volumes/Data/Vibecoding/Delos-wt/logo-icon/tools/fonts/logo/review.py --font-dir /Volumes/Data/Vibecoding/Delos/build/phosphor-icons --output /Volumes/Data/Vibecoding/Delos-wt/logo-icon/build/logo-icon-proto/index.html
```

The default font directory is the worktree's build/phosphor-icons. The explicit directory above supplies the existing full upstream fonts without copying or modifying them. The generated HTML embeds both real fonts and all candidate paths: it is offline and requires no server. Comparisons use image, rectangle (the app's aspect_ratio mapping), panorama (wallpaper mapping), and trademark.

## Exercised evidence

Rendered the actual HTML with headless Chromium, with both embedded fonts confirmed loaded. Captured build/logo-icon-proto/review.png at deviceScaleFactor 1, plus individual candidate PNGs. Opened and visually inspected all three individual 1344-pixel-wide captures at native resolution, including 24/32 px Regular/Fill, gold selected tab pills and the light surface. The title bars/scene strip/emblem counter remain separated; no accidental overlapping-contour holes remain. Regular stroke weight is comparable to the adjacent real Phosphor glyphs; Fill retains its negative-space details. A's dense lettering and C's narrow spacing are the weakest small-size details, not claims of universal readability.

The page has no horizontal overflow. No application code, mappings, bundled fonts, Gradle, ADB, commits or integration were changed/run. Unverified: actual font import/winding conversion, Android rasterization, Regular/Fill crossfade, physical display readability, and whether users identify the intended meaning without the Logo label. Final candidate choice belongs to the user.

## Selected A integration evidence

The generator completed with both cached upstream sources and a fresh source download. Both imported glyphs have advance 1024, origin zero and bounds (96,128)–(928,768). All 340 other semantic mappings and 333 retained upstream glyphs per font preserve their code points, compiled contours and horizontal metrics. The unused trademark point is absent. Pillow rendered the actual resulting TTFs at 32/96 px beside Cover/Hero; the PNG was opened and inspected, with the counters, scale and baseline preserved. See `build/logo-integration/font-render.png` and `verification.json`. Font import is now exercised, but Android rasterization, focus crossfade and physical display readability still require the lead's device check. No Gradle, ADB or commit was run.
