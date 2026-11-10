# Icon audit

Every icon of the app, screen by screen, with what it means and whether it was kept or replaced by the audit
(this branch). Rules: [ICONS.md](ICONS.md). Glyph names are Phosphor 2.1.2 (Regular, with the Fill twin used on
focus); the semantic names (`Symbols.X`) are in `tools/fonts/icons.txt`. The Settings rows are tested
(`SettingIconsTest`): every catalogue row has its own entry in `settingIcon()`, no two rows of one tab share a
glyph (except the named repeats below) and no row reuses the glyph of a tab pill.

## Rules

1. One glyph, one meaning, app-wide. The same concept may appear in two places with the same glyph (the stream
   menu tile and its settings row, a chip and its filter section); different concepts never share a glyph.
2. The icon font holds every Phosphor glyph the generator is asked for: adding one is a new line in
   `tools/fonts/icons.txt` and a run of `tools/fonts/subset_fonts.py --icons-only` against the cached sources (no
   download was needed; nothing in the fonts was swapped for another family). The same family, weight and
   Regular/Fill pairing are used everywhere.
3. Not changed: the controller letters (A/B/X/Y glyph buttons), the Delos logo and the battery body (custom
   drawing), the toast kind rules (`toastKindFor` still reads ERROR, WIFI_OFF, LINK_OFF, CHECK_CIRCLE, CHECK, WARNING).
4. Layout, sizes, colours and motion are untouched. Only the glyph a call site names changed.

## Intentional repeats

| Glyph | Where | Why it is one meaning |
|---|---|---|
| `drop` (opacity) | Keyboard opacity, OSC opacity, Free stick opacity (Controls); Game background opacity (Appearance) | the same property on different targets |
| `arrow-u-up-left` (reset) | Reset on-screen controls / Reset keyboard layout, Reset all, Reset to profile value, Reset filters, Reset metadata match, toasts | the same action |
| `file-arrow-down` (import) | Import keyboard layout, Import special buttons | the same action |
| `floppy-disk` (remember) | Remember mouse mode (Controls), Remember zoom/pan (Appearance) | the same behaviour |
| `arrows-down-up` (swap axes) | Invert video resolution (Display), Swap trackpad axes (Controls) | swapping two axes |
| `timer` | Ultra low latency, Latency impact chip | latency |
| `wifi-high` | status bar, Network impact chip | the network connection |
| `sliders-horizontal` (custom) | custom-settings badge, "N custom settings" chip, per-game "Custom for this game" | a value that differs from the profile |
| `bezier-curve` (smoothing) | Gyro smoothing, stream menu gyro row, Debug smoothing | smoothing |
| `boot` (kick) | Kick-start preset, Debug Start kick | the start kick |
| `vibrate` | Enable rumble, More > Rumble, Debug Vibration motor | rumble / the vibration motor |
| `device-mobile-speaker` | Emulate rumble with vibration, Debug Vibrate once | the phone buzzing |
| `faders` | Vibration strength, Debug Strength | strength |
| `clock-countdown` | HLTB chip, HowLongToBeat provider row, Duration filter | completion time |
| `sparkle` | HDR setting, native HDR chip, HDR filter chip and section, metadata badge | HDR |
| `game-controller` | Controls tab, button-capture dialog, Debug controllers, Player avatar, and a game's placeholder tile and candidate lists | a controller; a game without artwork has nothing but its controller to show |
| `play` | Start game, right menu button hint on the AYN (a physical button printed with that triangle) | the hint copies the hardware |
| `list` / `square` / `copy` | menu button hints | hardware glyphs, not features |
| `pencil-simple` | Rename (devices, profile, dialog field) | rename |
| `trash` | Delete profile, Remove from Delos, undo toast default | remove |
| `gear-six` | Settings nav button, Game settings, side menu "All settings" | settings |
| `power` | Quit game on the context menu, bar tile, portal | quit |
| `wifi-high` / `wifi-medium` ... | status bar levels | one glyph family; level 3 and "full" are the same code point |

## Glyphs wanted but not available

Phosphor has no dedicated glyph for these; the closest one is used and named in the table:
HDR (a sparkle stands in), sharpness / unsharp mask (aperture), bitrate (gauge), video codec (video file),
gyroscope (compass), frame pacing (metronome), supersampling (high definition), haptic feedback and rumble motor
(a bell and three wave shapes next to the generic vibrate glyph), metered connection (cell-signal), a PlayStation
or Xbox Guide button (a plain disc), IGDB / HowLongToBeat / PCGamingWiki logos (database, countdown clock, article;
only the Windows, Steam and Android logos exist). Nothing needed a font from another family.

## Device checklist (eyeball on the Odin)

1. Settings: every tab (Stream, Display, Audio, Controls, Appearance, Covers, Devices, About): row glyphs read, tab pills
   differ from every row, Controls is the longest list (about 65 rows).
2. Per-game settings: the same rows on Stream/Display, the custom badge and reset glyphs.
3. Side menu rows and the status bar (haptics bell, battery bolt, wifi).
4. Home: the context menu (Resume, Quit, Quit and start, Artwork, Metadata, Game settings, Hide), the game info chips
   (Running, HDR, SDR, unverified HDR, HLTB, Virtual display), genre and theme pills, bundled tiles (Desktop, Remote
   input, Remote monitor, Steam Big Picture) while their artwork loads.
5. Filter modal: four sections and the HDR chips; metadata and artwork screens (tabs, sheet rows, badges, loading).
6. Devices / Start / Pair PC / Profile screens, dialogs (PIN, passphrase, address, rename), toasts (wake, link test).
7. Stream menu: bar tiles, More page rows, Bitrate and Upscaler pages, Rumble presets, Mouse page.
8. Debug info: every section (device, haptics, rumble laboratory presets and patterns, advanced rows, controllers).
9. Stream result (failure face, Try again, Back to library) and the exit/connect portals.

## Inventory

`Was` is the glyph before the audit, `Now` after it; `keep` rows are listed too so that nothing is unreviewed.

# Settings tabs (header pills, also the per-game settings tab pills)

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| Stream tab | `screencast` | `screencast` | keep |  |
| Display tab | `monitor` | `monitor` | keep |  |
| Audio tab | `waveform` | `waveform` | keep | the waveform was also used by two vibration rows; those moved |
| Controls tab | `game-controller` | `game-controller` | keep | was also on Joy-Con fix, Stream menu buttons and two About rows |
| Appearance tab | `palette` | `paint-brush` | **replace** | the palette glyph also stood for the Color palette row and the PyroWave chroma row; the tab now has the brush |
| Covers tab | `images` | `images` | keep | artwork; "Choose artwork for one game" and "Back to library" no longer reuse it |
| Devices tab | `desktop` | `desktop-tower` | **replace** | the PC glyph of the device cards (monitor-only desktop is now the bundled Desktop app) |
| About tab | `info` | `info` | keep |  |

# Settings > Stream

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| Resolution | `rectangle` | `four-k` | **replace** | a plain rectangle was also the Wide cover tab; "4K" says resolution |
| Custom resolution | `ruler` | `ruler` | keep |  |
| Frame rate | `speedometer` | `film-strip` | **replace** | the gauge was shared with Unlock FPS, the stream menu Fill row and Use recommended bitrate; film strip = frames |
| Unlock FPS limit | `speedometer` | `lock-open` | **replace** | open padlock |
| Custom refresh rate | `pencil-line` | `wave-sine` | **replace** | sine wave = Hz (was a pencil) |
| Bitrate | `gauge` | `gauge` | keep | gauge = bitrate on the slider, the stream menu tile and page |
| Manual bitrate entry | `gauge` | `pencil-line` | **replace** | typing a value, no longer the slider's glyph |
| Metered network bitrate | `cell-signal-full` | `cell-signal-full` | keep |  |
| Frame pacing | `timer` | `metronome` | **replace** | metronome = even frame intervals; the timer now means latency only |
| Async decoding | `queue` | `queue` | keep |  |
| Low-latency frame balance | `sliders-horizontal` | `scales` | **replace** | scales; the sliders glyph is reserved for "custom settings" |
| Ultra low latency | `lightning` | `timer` | **replace** | the bolt is the battery charging glyph; the timer is the Latency impact glyph too (same meaning) |
| Video codec | `film-slate` | `file-video` | **replace** | a video file format; the film slate stays on the Artwork "Type" row |
| PyroWave chroma | `palette` | `gradient` | **replace** | colour gradient, not the theme palette |
| Prevent packet loss | `heartbeat` | `broadcast` | **replace** | broadcast (was a heartbeat, now the Debug heartbeat pattern) |
| Test link (guidance row) | `wifi-high` | `network` | **replace** | network graph; wifi is the status bar and the Network impact axis |
| Use recommended bitrate | `speedometer` | `thumbs-up` | **replace** | thumbs up = recommendation |

# Settings > Display

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| Upscaler | `frame-corners` | `frame-corners` | keep | same glyph as the stream menu tile |
| Upscaler mode | `stack` | `stack` | keep |  |
| Sharpness | `magic-wand` | `aperture` | **replace** | aperture = focus/sharpness; the magic wand is the Wizard avatar and Fantasy theme again |
| Supersampling quality | `high-definition` | `high-definition` | keep |  |
| Virtual display | `monitor-play` | `monitor-play` | keep | same as the Virtual display chip |
| Auto orientation | `device-rotate` | `device-rotate` | keep |  |
| Invert video resolution | `arrows-down-up` | `arrows-down-up` | keep (repeat) | repeat: swapping two axes, same meaning as Controls > Swap trackpad axes; other tab |
| View at top centre | `align-top` | `align-top` | keep |  |
| Resolution scale | `arrows-out` | `arrows-out` | keep | the interface scale had the same glyph |
| Video scale mode | `resize` | `resize` | keep |  |
| Enforce display mode | `scan` | `scan` | keep |  |
| HDR | `sun` | `sparkle` | **replace** | sparkle everywhere HDR is meant (setting, native HDR badge, filter); was a sun on the setting and a sparkle on the badge |
| External display | `monitor-arrow-up` | `monitor-arrow-up` | keep |  |
| Performance overlay | `chart-bar` | `chart-bar` | keep | same as the stream menu tile |
| Compact overlay | `list-bullets` | `list-bullets` | keep |  |
| Statistics position | `layout` | `layout` | keep |  |
| Reduce refresh rate | `clock` | `trend-down` | **replace** | trending down; the clock is the Clock row |
| Full colour range | `circle-half` | `circle-half` | keep |  |

# Settings > Audio

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| Audio configuration | `speaker-hifi` | `speaker-hifi` | keep |  |
| Audio effects | `equalizer` | `equalizer` | keep |  |
| Play audio on the PC | `speaker-high` | `speaker-high` | keep |  |
| Interface sounds | `music-note` | `music-note` | keep | the Music genre moved to piano keys |

# Settings > Controls: gamepad

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| Stick deadzone | `target` | `target` | keep | the target also stood for L3/R3 and auto touch sensitivity |
| Multiple controllers | `plugs-connected` | `plugs-connected` | keep |  |
| USB driver | `usb` | `usb` | keep |  |
| USB override | `plug` | `plug` | keep |  |
| Mouse emulation | `mouse` | `mouse` | keep |  |
| Analog scrolling | `mouse-scroll` | `mouse-scroll` | keep |  |
| Flip face buttons | `circles-four` | `circles-four` | keep |  |
| Gamepad touchpad as mouse | `hand-pointing` | `hand-pointing` | keep |  |
| Motion sensors | `compass` | `compass` | keep | the compass also stood for the Adventure genre |
| Motion fallback | `navigation-arrow` | `navigation-arrow` | keep |  |
| Force device motion | `device-mobile` | `device-mobile` | keep |  |
| Gyro smoothing | `sliders-horizontal` | `bezier-curve` | **replace** | bezier curve = smoothing (also the stream menu gyro row and the Debug smoothing row) |
| Joy-Con fix | `game-controller` | `wrench` | **replace** | wrench |
| Controller battery report | `battery-full` | `battery-full` | keep |  |
| Enable rumble | `vibrate` | `vibrate` | keep | the vibrate glyph is rumble; the haptics rows have their own |
| Rumble feel | `vibrate` | `playlist` | **replace** | playlist = a chosen preset (same as the Debug current preset) |
| Emulate rumble with vibration | `vibrate` | `device-mobile-speaker` | **replace** | the phone buzzing (same as Debug "Vibrate once") |
| Force vibration on device | `vibrate` | `hand-fist` | **replace** | it was the same Phosphor glyph as the plain vibrate; the fist = forced |
| Vibration strength | `waveform` | `faders` | **replace** | faders = strength (same as the Debug strength row) |

# Settings > Controls: touch, mouse and trackpad

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| Touchscreen mode | `hand-tap` | `hand-tap` | keep | same as the stream menu row |
| Remember mouse mode | `floppy-disk` | `floppy-disk` | keep (repeat) | repeat: Remember zoom/pan on the Appearance tab, same meaning |
| Local mouse cursor | `cursor` | `cursor` | keep | same as the stream menu row |
| Absolute mouse mode | `cursor` | `gps-fix` | **replace** | GPS fix = absolute position |
| Multi-touch gestures | `hand-swipe-left` | `hand-swipe-left` | keep |  |
| Mouse navigation buttons | `arrows-left-right` | `arrow-bend-double-up-left` | **replace** | back/forward; the horizontal arrows are the Adjust hint and Quit-and-start |
| Trackpad sensitivity X | `arrows-horizontal` | `arrows-horizontal` | keep |  |
| Trackpad sensitivity Y | `arrows-vertical` | `arrows-vertical` | keep |  |
| Trackpad drag-drop vibration | `hand-grabbing` | `hand-grabbing` | keep |  |
| Trackpad drag-drop threshold | `hourglass` | `hourglass` | keep | the hourglass also stood for Turn-based strategy |
| Swap trackpad axes | `arrows-down-up` | `arrows-down-up` | keep (repeat) | repeat: swapping two axes, see Invert video resolution |
| Touch sensitivity | `hand-tap` | `fingerprint-simple` | **replace** | fingerprint; was the touchscreen-mode glyph (same as the stream menu row) |
| Global touch sensitivity | `globe` | `globe` | keep |  |
| Auto touch sensitivity | `target` | `arrows-counter-clockwise` | **replace** | counter-clockwise arrows = follows the rotation |
| Touch sensitivity X | `arrows-horizontal` | `arrows-in-line-horizontal` | **replace** | each of the three X/Y pairs has its own arrows |
| Touch sensitivity Y | `arrows-vertical` | `arrows-in-line-vertical` | **replace** | see above |
| Touchpad sensitivity X | `arrows-horizontal` | `arrow-line-right` | **replace** | see above |
| Touchpad sensitivity Y | `arrows-vertical` | `arrow-line-up` | **replace** | see above |

# Settings > Controls: keyboard, clipboard, on-screen controls

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| Force QWERTY | `grid-nine` | `text-t` | **replace** | the grid is the keyboard layout scheme row |
| Right Alt as Meta | `command` | `command` | keep | the row is labelled "Right Alt as Command (Mac)", so the Command glyph is the right one (the same glyph names the stream menu "Send keys" row, a different screen). The Windows logo glyph was tried and reverted |
| Ignore synthetic events | `shield-check` | `shield-check` | keep |  |
| Commit text | `chat-text` | `chat-text` | keep |  |
| Smart clipboard sync | `clipboard-text` | `clipboard-text` | keep |  |
| Clipboard toast | `notification` | `notification` | keep |  |
| Hide clipboard content | `lock-simple` | `lock-simple` | keep |  |
| Keyboard opacity | `drop` | `drop` | keep (repeat) | repeat: same property on three rows |
| Keyboard autofit | `corners-in` | `corners-in` | keep |  |
| Keyboard height | `arrows-out-line-vertical` | `arrows-out-line-vertical` | keep |  |
| Keyboard width | `arrows-out-line-horizontal` | `arrows-out-line-horizontal` | keep |  |
| Keyboard alignment | `align-center-horizontal` | `align-center-horizontal` | keep |  |
| Sticky modifier keys | `push-pin` | `push-pin` | keep |  |
| Show on-screen controls | `joystick` | `joystick` | keep | same as the stream menu row and Debug preview |
| Hide controls when a gamepad is present | `eye-slash` | `eye-slash` | keep | same meaning as Hide game |
| On-screen controls vibration | `vibrate` | `wave-sawtooth` | **replace** | the three tap-feedback rows each get one wave |
| Only show L3/R3 | `target` | `radio-button` | **replace** | radio button = a stick click |
| Show guide button | `house` | `disc` | **replace** | the house is the Home portal; the disc is the centre button |
| Free analog stick | `arrows-out-cardinal` | `arrows-out-cardinal` | keep |  |
| OSC opacity | `drop` | `drop` | keep (repeat) | repeat: see Keyboard opacity |
| Free stick opacity | `drop` | `drop` | keep (repeat) | repeat: see Keyboard opacity |
| Reset on-screen controls | `arrow-u-up-left` | `arrow-u-up-left` | keep (repeat) | repeat: same action as Reset keyboard layout |
| Show virtual keys | `keyboard` | `keyboard` | keep | same glyph as the stream menu Keyboard tile: the Delos keyboard |
| Clear default special buttons | `key` | `broom` | **replace** | broom; the key is the SteamGridDB API key |
| Keyboard haptics | `vibrate` | `wave-square` | **replace** | see On-screen controls vibration |
| Default layout scheme | `grid-nine` | `grid-nine` | keep |  |
| Square buttons | `square` | `selection` | **replace** | the filled square is the AYN menu-button hint |
| Reset keyboard layout | `arrow-u-up-left` | `arrow-u-up-left` | keep (repeat) | repeat: see Reset on-screen controls |
| Import keyboard layout | `file-arrow-down` | `file-arrow-down` | keep (repeat) | repeat: same action as Import special buttons |
| Export keyboard layout | `file-arrow-up` | `file-arrow-up` | keep |  |
| Import special buttons | `file-arrow-down` | `file-arrow-down` | keep (repeat) | repeat: see above |
| Special key help | `question` | `question` | keep |  |
| Interface haptics | `vibrate` | `bell-ringing` | **replace** | bell ringing = haptic feedback of the interface (also the side menu row) |
| Stream menu buttons | `game-controller` | `squares-four` | **replace** | a grid of tiles = the stream menu |
| Swipe up to open the menu | `hand-arrow-up` | `hand-arrow-up` | keep |  |

# Settings > Appearance

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| Theme | `moon` | `moon` | keep | also the side menu row |
| Material You colours | `swatches` | `swatches` | keep | also the side menu row |
| Colour palette | `palette` | `palette` | keep | the user's choice; the tab and the chroma row gave theirs up |
| Match the game's colours | `paint-brush` | `eyedropper` | **replace** | eyedropper = picks colours from the game (was a brush, which is the tab now) |
| Clock | `clock` | `clock` | keep |  |
| Animated background | `sparkle` | `waves` | **replace** | waves = flowing shader (sparkle moved to HDR) |
| Home layout | `cards-three` | `cards-three` | keep | also the side menu row |
| Game background | `image` | `panorama` | **replace** | the Artwork "Background" glyph; the picture is the Cover |
| Game background blur | `drop-half` | `drop-half` | keep |  |
| Game background opacity | `drop` | `drop` | keep (repeat) | repeat: opacity is one property; other tab than the control opacities |
| Interface scale | `arrows-out` | `text-aa` | **replace** | text size; the arrows are the resolution scale |
| Animations | `record` | `pinwheel` | **replace** | pinwheel = motion; the record dot read as recording |
| Disable warnings | `warning` | `warning` | keep |  |
| Remember zoom/pan | `floppy-disk` | `floppy-disk` | keep (repeat) | repeat: see Remember mouse mode |
| Picture in picture | `picture-in-picture` | `picture-in-picture` | keep |  |

# Settings > Covers

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| SteamGridDB API key | `key` | `key` | keep | also the API key dialog field and the "Add your key" message |
| Download automatically | `arrows-counter-clockwise` | `cloud-arrow-down` | **replace** | cloud download = automatic; the counter-clockwise arrows are now Auto touch sensitivity |
| Download missing artwork | `download-simple` | `download-simple` | keep |  |
| Download all again | `arrows-clockwise` | `arrows-clockwise` | keep |  |
| Choose artwork for one game | `images` | `image-square` | **replace** | one picture; the library glyph is the tab |

# Settings > Devices

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| Manage devices | `devices` | `devices` | keep |  |
| Pair a new PC | `link` | `link` | keep | also the Devices and Start pair button |
| Default device | `star` | `star` | keep | also the Start context menu and toast |
| Skip the start screen | `fast-forward` | `fast-forward` | keep | same as "Skip to home" |

# Settings > About

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| Version | `cube` | `package` | **replace** | package |
| Based on | `tree-structure` | `tree-structure` | keep |  |
| Replay the welcome | `arrow-counter-clockwise` | `arrow-counter-clockwise` | keep | also Debug "Replay Test sample" |
| Game metadata · IGDB | `game-controller` | `database` | **replace** | database |
| Technical data · PCGamingWiki | `sun` | `article` | **replace** | article |
| Genre and theme tags · Steam | `tag` | `steam-logo` | **replace** | Steam logo (also the Steam Big Picture tile) |
| Completion times · HowLongToBeat | `game-controller` | `clock-countdown` | **replace** | countdown clock (also the HLTB chip and the Duration filter) |
| Language | `translate` | `translate` | keep |  |
| Debug info | `bug` | `bug` | keep |  |

# Debug info

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| Android | `cube` | `android-logo` | **replace** | brand glyph |
| Kernel | `cpu` | `cpu` | keep |  |
| Device | `devices` | `device-tablet` | **replace** | the model; the devices glyph is Manage devices |
| Vibration motor | `vibrate` | `vibrate` | keep |  |
| Vibrators | `vibrate` | `list-numbers` | **replace** | a count |
| Amplitude control | `sliders-horizontal` | `chart-line` | **replace** | chart line |
| Last problem | `info` | `warning` | **replace** | a problem is a warning (info is the toast and About tab glyph) |
| Vibrate once | `vibrate` | `device-mobile-speaker` | **replace** | same meaning as Emulate rumble with vibration |
| Continuous vibration | `waveform` | `infinity` | **replace** | infinity |
| Strength | `sliders-horizontal` | `faders` | **replace** | same as the vibration strength setting |
| Current preset | `vibrate` | `playlist` | **replace** | same as Rumble feel |
| Preset Original | `vibrate` | `circle-dashed` | **replace** | every preset has its own glyph (also the stream menu page) |
| Preset Soft | `vibrate` | `cloud` | **replace** | see above |
| Preset Kick-start | `vibrate` | `boot` | **replace** | see above (also the Start kick row) |
| Preset Pulsing | `vibrate` | `wave-triangle` | **replace** | see above |
| Live amplitude | `waveform` | `chart-bar-horizontal` | **replace** | bar chart |
| Stop vibration | `vibrate` | `stop` | **replace** | stop |
| Replay Test sample | `vibrate` | `arrow-counter-clockwise` | **replace** | replay |
| Pattern Steady 25 % | `vibrate` | `engine` | **replace** | engine; the eleven patterns each get their own glyph |
| Pattern Steady 50 % | `vibrate` | `steering-wheel` | **replace** | steering wheel |
| Pattern Steady 75 % | `vibrate` | `jeep` | **replace** | jeep |
| Pattern Steady 100 % | `vibrate` | `rocket` | **replace** | rocket |
| Pattern Ramp up | `vibrate` | `trend-up` | **replace** | trend up |
| Pattern 30 ms taps | `vibrate` | `feather` | **replace** | feather |
| Pattern 60 ms taps | `vibrate` | `crosshair-simple` | **replace** | crosshair |
| Pattern 100 ms taps | `vibrate` | `hammer` | **replace** | hammer |
| Pattern Heartbeat | `vibrate` | `heartbeat` | **replace** | heartbeat |
| Pattern Explosion | `vibrate` | `bomb` | **replace** | bomb |
| Pattern Flutter | `vibrate` | `fan` | **replace** | fan |
| Advanced | `sliders-horizontal` | `gear-fine` | **replace** | fine gear |
| Enable rumble translator | `vibrate` | `function` | **replace** | function |
| Gain | `sliders-horizontal` | `plus-minus` | **replace** | plus-minus |
| Curve | `sliders-horizontal` | `chart-line-up` | **replace** | rising curve |
| Minimum amplitude | `sliders-horizontal` | `arrow-line-down` | **replace** | line down |
| Start kick | `sliders-horizontal` | `boot` | **replace** | boot (repeat: same concept as the Kick-start preset) |
| Low motor weight | `sliders-horizontal` | `arrow-fat-down` | **replace** | arrow down |
| High motor weight | `sliders-horizontal` | `arrow-fat-up` | **replace** | arrow up |
| Smoothing | `sliders-horizontal` | `bezier-curve` | **replace** | same as Gyro smoothing |
| Reset parameters | `sliders-horizontal` | `arrow-u-up-left` | **replace** | it is a reset |
| Preview on-screen controls | `joystick` | `joystick` | keep |  |
| No controller / each controller | `game-controller` | `game-controller` | keep (repeat) | repeat: one glyph per connected controller, same meaning |
| Continuous vibration on controllers | `waveform` | `repeat` | **replace** | repeat arrows (the device row has the infinity) |

# Header, side menu, nav bar and button hints

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| Settings nav button | `gear-six` | `gear-six` | keep | also Game settings and the side menu "All settings" |
| Profile nav button | `user-circle` | `user-circle` | keep | the profile concept everywhere |
| Devices nav button | `devices` | `devices` | keep |  |
| Status bar wifi | `wifi-high` | `wifi-high` | keep | the connection concept; levels use the same family |
| Status bar charging bolt | `lightning` | `lightning` | keep | the bolt now means charging only |
| Left menu button hint | `square` | `square` | keep | a physical button glyph; AYN only |
| Right menu button hint | `play` | `play` | keep (repeat) | a physical button glyph; AYN only; repeat of Start game |
| Adjust hint | `arrows-left-right` | `arrows-left-right` | keep | left/right adjust |
| Side menu Theme / Material You / Background / Home layout / Sounds / All settings | settings rows | settings rows | keep | same glyphs as the Settings rows |
| Side menu Haptics | `vibrate` | `bell-ringing` | **replace** | follows Interface haptics |
| Side menu Show hidden games | `eye` | `eye` | keep |  |

# Start, Devices, Pair PC and Profile

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| PC card | `desktop-tower` | `desktop-tower` | keep | PC tower; Start, Devices, Pair and the connecting portal |
| Pair a new PC | `link` | `link` | keep |  |
| Device menu: Connect | `sign-in` | `sign-in` | keep |  |
| Device menu: Rename | `pencil-simple` | `pencil-simple` | keep | also Profile Rename and the rename dialog field |
| Device menu: Wake the PC | `power` | `alarm` | **replace** | an alarm clock; the power glyph is Quit and was the same glyph |
| Device menu: Unpair | `link-break` | `link-break` | keep |  |
| Device menu: Remove from Delos | `trash` | `trash` | keep | same meaning as Delete profile and the undo toast |
| Device menu: Start Delos with this PC | `star` | `star` | keep |  |
| Device card expand chevron | `caret-down` | `caret-down` | keep |  |
| Pair: Pair with a PIN | `numpad` | `numpad` | keep | also the PIN dialog field |
| Pair: Pair with a one-time code | `password` | `password` | keep | also the passphrase field |
| Pair: Enter address | `plus` | `map-pin-plus` | **replace** | map pin with a plus |
| Pair: Search again | `arrows-clockwise` | `arrows-clockwise` | keep |  |
| Pair: Cancel | `x` | `x` | keep |  |
| Address dialog field | `network` | `map-pin` | **replace** | map pin |
| Profile: Change avatar | `smiley` | `smiley` | keep |  |
| Profile: Rename | `pencil-simple` | `pencil-simple` | keep |  |
| Profile: Switch profile | `users` | `users` | keep |  |
| Profile: Add profile | `user-plus` | `user-plus` | keep |  |
| Profile: Delete profile | `trash` | `trash` | keep |  |
| Profile menu entries / Playing as | `user-circle` | `user-circle` | keep |  |
| Profile name field | `identification-badge` | `identification-badge` | keep | the logo moved to a trademark so the badge is the name only |
| First run: Get started | `arrow-right` | `arrow-right` | keep |  |
| First run: Skip to home | `fast-forward` | `fast-forward` | keep | same meaning as Skip the start screen |
| First run: Save / Create profile | `check` | `check` | keep |  |
| Welcome toast | `hand-waving` | `hand-waving` | keep |  |
| Home: no PC paired | `devices` | `devices` | keep |  |
| Home: PC offline | `power` | `wifi-slash` | **replace** | unreachable (same as the "cannot be reached" toast) |
| Home: loading | `arrows-clockwise` | `spinner-gap` | **replace** | spinner (also the SteamGridDB loading message) |
| Home: no games | `game-controller` | `game-controller` | keep | a game |

# Home: tiles, context menu and game info

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| Bundled Desktop tile | `desktop-tower` | `desktop` | **replace** | the desktop (was the PC glyph) |
| Bundled Remote input tile | `game-controller` | `mouse-simple` | **replace** | mouse |
| Bundled Remote monitor tile | `game-controller` | `television-simple` | **replace** | television |
| Bundled Steam Big Picture tile | `game-controller` | `steam-logo` | **replace** | brand glyph |
| Game placeholder tile | `game-controller` | `game-controller` | keep (repeat) | a game; repeat of the controller concept |
| Running section | `play` | `pulse` | **replace** | pulse; the play triangle is Start game |
| Last played section | `clock-counter-clockwise` | `clock-counter-clockwise` | keep |  |
| Context: Resume | `play-pause` | `play-pause` | keep | play/pause; same as the bar tile |
| Context: Quit game | `power` | `power` | keep | same as the bar tile and portal |
| Context: Quit and start | `arrows-left-right` | `swap` | **replace** | swap |
| Context: Start game | `play` | `play` | keep |  |
| Context: Artwork | `images` | `images` | keep |  |
| Context: Metadata | `magnifying-glass` | `file-magnifying-glass` | **replace** | file with a magnifier |
| Context: Game settings | `gear-six` | `gear-six` | keep |  |
| Context: Hide game / Show game | `eye-slash` / `eye` | `eye-slash` / `eye` | keep |  |
| Custom settings badge / chip | `sliders-horizontal` | `sliders-horizontal` | keep | custom = adjusted; also the per-game "Custom for this game" box |
| Info chip: Running on PC | `play` | `pulse` | **replace** | see Running section |
| Info chip: HDR | `sparkle` | `sparkle` | keep | sparkle = HDR everywhere |
| Info chip: SDR | `sun` | `sun-dim` | **replace** | dim sun |
| Info chip: unverified HDR | `question` | `seal-question` | **replace** | sealed question mark (help is a question mark) |
| Info chip: HLTB | `clock-counter-clockwise` | `clock-countdown` | **replace** | countdown clock; history is Last played |
| Info chip: Virtual display | `monitor-play` | `monitor-play` | keep |  |
| Genre chips | 23 glyphs | see Genres | **replace** | see Genres below |
| Theme chips | 21 glyphs | see Themes | **replace** | see Themes below |

# Metadata and artwork

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| Metadata: Search IGDB | `magnifying-glass` | `magnifying-glass` | keep |  |
| Metadata: Refresh | `arrows-clockwise` | `arrows-clockwise` | keep |  |
| Metadata: Reset match | `arrows-clockwise` | `arrow-u-up-left` | **replace** | it is a reset |
| Metadata: Reset library filters | `arrows-clockwise` | `arrow-u-up-left` | **replace** | it is a reset |
| Metadata: Previous matches | `magnifying-glass` | `arrow-left` | **replace** | paging |
| Metadata: More matches | `magnifying-glass` | `arrow-right` | **replace** | paging |
| Metadata: Search for another name | `pencil-simple` | `list-magnifying-glass` | **replace** | list with a magnifier (also the Artwork button) |
| Metadata: candidate game | `game-controller` | `game-controller` | keep | a game |
| Artwork tab Cover | `image` | `image` | keep |  |
| Artwork tab Wide cover | `rectangle` | `rectangle` | keep |  |
| Artwork tab Background | `panorama` | `panorama` | keep |  |
| Artwork tab Logo | `identification-badge` | `trademark` | **replace** | trademark |
| Artwork: Search for another name | `magnifying-glass` | `list-magnifying-glass` | **replace** | see above |
| Artwork: Filter / Change filters | `sliders-horizontal` | `funnel` | **replace** | funnel |
| Artwork: Reset filters / Reset chip | `arrows-clockwise` | `arrow-u-up-left` | **replace** | it is a reset |
| Artwork: Clear filter chip | `x` | `x` | keep |  |
| Artwork sheet: Type | `film-slate` | `film-slate` | keep |  |
| Artwork sheet: Style | `palette` | `paint-roller` | **replace** | paint roller |
| Artwork sheet: Dimensions | `rectangle` | `bounding-box` | **replace** | bounding box |
| Artwork sheet: Sort | `star` | `sort-ascending` | **replace** | sort arrows |
| Artwork: Animated badge | `play` | `play-circle` | **replace** | play circle |
| Artwork: In use | `check` | `check` | keep |  |
| Artwork: empty search | `magnifying-glass` | `magnifying-glass` | keep |  |
| Artwork: loading | `arrows-clockwise` | `spinner-gap` | **replace** | spinner |
| Artwork: no key | `key` | `key` | keep |  |
| Artwork: error | `warning-circle` | `warning-circle` | keep |  |
| Artwork: download toast | `download-simple` | `download-simple` | keep |  |
| Artwork: Back | `arrow-left` | `arrow-left` | keep |  |
| Artwork: candidate game | `game-controller` | `game-controller` | keep | a game |

# Filter modal

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| Section Genre | `game-controller` | `tag` | **replace** | the genre tag |
| Section Theme | `mask-happy` | `shapes` | **replace** | shapes (the drama mask is a chip) |
| Section HDR support | `sun` | `sparkle` | **replace** |  |
| Section Duration | `clock-counter-clockwise` | `clock-countdown` | **replace** | same as the HLTB chip |
| Chip HDR / SDR / Unknown | `sparkle` / `sun` / `question` | `sparkle` / `sun-dim` / `seal-question` | **replace** | same as the info chips |
| Reset | `arrows-clockwise` | `arrow-u-up-left` | **replace** | it is a reset |
| Done | `check` | `check` | keep |  |

# Dialogs, search keyboard, stream result, portals, toasts

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| Dialog confirm | `check` | `check` | keep |  |
| Search keyboard: Delete | `arrow-left` | `backspace` | **replace** | backspace key |
| Search keyboard: Clear | `x` | `x-circle` | **replace** | circled x |
| Search keyboard: Search field | `magnifying-glass` | `magnifying-glass` | keep |  |
| Settings Search button | `magnifying-glass` | `magnifying-glass` | keep |  |
| Stream result: failure face | `smiley-sad` | `smiley-sad` | keep |  |
| Stream result: Try again | `arrow-clockwise` | `arrows-clockwise` | **replace** | same retry/refresh arrows (RESTART_ALT removed) |
| Stream result: Back to library | `images` | `books` | **replace** | books |
| Stream result: expand/collapse | `caret-down` / `caret-up` | `caret-down` / `caret-up` | keep |  |
| Portal: connecting | `desktop-tower` | `desktop-tower` | keep |  |
| Portal: wake | `power` | `alarm` | **replace** | see Wake the PC |
| Portal: link test | `gauge` | `network` | **replace** | same as the Test link row |
| Portal: exit to Home | `house` | `house` | keep |  |
| Portal: quitting | `power` | `power` | keep |  |
| Toast: info | `info` | `info` | keep |  |
| Toast: success | `check-circle` | `check-circle` | keep |  |
| Toast: warning | `warning` | `warning` | keep |  |
| Toast: error | `warning-circle` | `warning-circle` | keep |  |
| Toast: action / undo | `trash` | `trash` | keep |  |
| Toast: unreachable | `wifi-slash` | `wifi-slash` | keep |  |
| Toast: unpaired | `link-break` | `link-break` | keep |  |
| Toast: reset | `arrow-clockwise` / `arrows-clockwise` | `arrow-u-up-left` | **replace** | it is a reset |
| Toast: default device | `star` | `star` | keep |  |
| Toast: download | `download-simple` | `download-simple` | keep |  |
| Toast: searching | `magnifying-glass` | `magnifying-glass` | keep |  |
| Toast: virtual display not ready | `monitor` | `monitor` | keep | the display |
| Toast: wake sent | `power` | `alarm` | **replace** | see Wake the PC |
| Toast: profile name needed | `identification-badge` | `identification-badge` | keep |  |
| Impact chip GPU | `cpu` | `graphics-card` | **replace** | graphics card (the CPU chip is the Debug kernel) |
| Impact chip Latency | `timer` | `timer` | keep | same as Ultra low latency |
| Impact chip Network | `wifi-high` | `wifi-high` | keep |  |
| Button capture dialog | `game-controller` | `game-controller` | keep | a controller |
| Controller hint badge in menus | `game-controller` | `game-controller` | keep | a controller |
| Context menu back/chevrons | `caret-left` / `caret-right` | `caret-left` / `caret-right` | keep |  |
| Full keyboard close | `x` | `x` | keep |  |

# Stream menu bar and pages

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| Bar: Resume | `play-pause` | `play-pause` | keep |  |
| Bar: Keyboard | `keyboard` | `keyboard` | keep |  |
| Bar: Overlay | `chart-bar` | `chart-bar` | keep |  |
| Bar: Brightness | `sun-dim` | `sun` | **replace** | sun; the dim sun is SDR |
| Bar: Bitrate | `gauge` | `gauge` | keep |  |
| Bar: Upscaler | `frame-corners` | `frame-corners` | keep |  |
| Bar: More | `dots-three` | `dots-three` | keep |  |
| Bar: Disconnect | `sign-out` | `sign-out` | keep |  |
| Bar: Quit game | `power` | `power` | keep |  |
| More: Send keys | `command` | `command` | keep | key combinations |
| More: Send clipboard | `upload-simple` | `paper-plane-tilt` | **replace** | paper plane |
| More: Get clipboard | `download-simple` | `tray-arrow-down` | **replace** | tray with arrow (download is the artwork download) |
| More: Commands | `terminal-window` | `terminal-window` | keep |  |
| More: Task manager | `list-checks` | `list-checks` | keep |  |
| More: Zoom | `magnifying-glass-plus` | `magnifying-glass-plus` | keep |  |
| More: Mouse | `mouse` | `mouse` | keep |  |
| More: On-screen controls | `joystick` | `joystick` | keep |  |
| More: Android keyboard | `keyboard` | `cursor-text` | **replace** | text cursor; the keyboard is the Delos keyboard tile on the bar |
| More: Touch sensitivity | `hand-tap` | `fingerprint-simple` | **replace** | same as the setting |
| More: Rumble | `vibrate` | `vibrate` | keep |  |
| More: Gyro smoothing | `sliders-horizontal` | `bezier-curve` | **replace** | same as the setting |
| Page: Screen brightness | `sun-dim` | `sun` | **replace** |  |
| Page: Bitrate | `gauge` | `gauge` | keep |  |
| Page: Fill recommended | `speedometer` | `thumbs-up` | **replace** | same as Use recommended bitrate |
| Page: Apply | `check` | `check` | keep |  |
| Page: Upscaler Enabled | `frame-corners` | `frame-corners` | keep |  |
| Page: Supersampling quality | `high-definition` | `high-definition` | keep |  |
| Page: Upscaler mode | `stack` | `stack` | keep |  |
| Page: Sharpness | `magic-wand` | `aperture` | **replace** | same as the setting |
| Page: Touchscreen mode | `hand-tap` | `hand-tap` | keep |  |
| Page: Local mouse cursor | `cursor` | `cursor` | keep |  |
| Page: rumble presets | `vibrate` | PRESET_* | **replace** | each preset has its own glyph, same as Debug |
| Popover back / choice chevrons / check | CHEVRON_* / `check` | CHEVRON_* / `check` | keep |  |

# Profile avatars (fallback glyph under the avatar image)

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| Sailor | `sailboat` | `sailboat` | keep |  |
| Voyager | `rocket-launch` | `rocket-launch` | keep |  |
| Player | `game-controller` | `game-controller` | keep |  |
| Fox | `paw-print` | `paw-print` | keep |  |
| Spark | `lightning` | `lightbulb-filament` | **replace** | filament bulb; the bolt is charging |
| Calm | `flower-lotus` | `flower-lotus` | keep |  |
| Knight | `sword` | `sword` | keep |  |
| Robot | `cpu` | `robot` | **replace** | robot head (the chip is the kernel) |
| Cat | `paw-print` | `cat` | **replace** | was the same paw as Fox |
| Wizard | `star` | `magic-wand` | **replace** | star is the default device |
| Racer | `speedometer` | `speedometer` | keep | the gauge no longer stands for anything else |
| Ghost | `smiley` | `ghost` | **replace** | the face is Change avatar |

# Genres (IGDB)

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| Point-and-click | `cursor-click` | `cursor-click` | keep |  |
| Fighting | `boxing-glove` | `boxing-glove` | keep |  |
| Shooter | `crosshair` | `crosshair` | keep |  |
| Music | `music-notes` | `piano-keys` | **replace** | piano keys (the note is Interface sounds) |
| Platform | `person-simple-run` | `person-simple-run` | keep |  |
| Puzzle | `puzzle-piece` | `puzzle-piece` | keep |  |
| Racing | `flag-checkered` | `flag-checkered` | keep |  |
| Real-time strategy | `timer` | `clock-clockwise` | **replace** | clock; the timer is latency |
| Role-playing | `shield-star` | `shield-star` | keep |  |
| Simulator | `airplane-tilt` | `airplane-tilt` | keep |  |
| Sport | `soccer-ball` | `soccer-ball` | keep |  |
| Strategy | `castle-turret` | `castle-turret` | keep |  |
| Turn-based strategy | `hourglass` | `calendar-check` | **replace** | calendar; the hourglass is Hold duration |
| Tactical | `strategy` | `strategy` | keep |  |
| Hack and slash | `sword` | `axe` | **replace** | axe (the sword is the Knight avatar) |
| Quiz | `question` | `exam` | **replace** | exam (the question mark is Help) |
| Pinball | `circles-three` | `circles-three` | keep |  |
| Adventure | `compass` | `signpost` | **replace** | signpost (the compass is Motion sensors) |
| Indie | `lightbulb` | `guitar` | **replace** | guitar (the bulb is the Spark avatar) |
| Arcade | `ghost` | `coin` | **replace** | coin (the ghost is the Ghost avatar) |
| Visual novel | `book-open-text` | `book-open-text` | keep |  |
| Card and board | `cards` | `cards` | keep |  |
| MOBA | `users-three` | `users-three` | keep |  |
| Unmapped genre or theme | `tag` | `tag` | keep | also the Genre filter section |

# Themes (IGDB)

| Item | Was | Now | Verdict | Why |
|---|---|---|---|---|
| Action | `fire` | `fire` | keep |  |
| Fantasy | `magic-wand` | `crown` | **replace** | crown (the wand is Sharpness and the Wizard avatar) |
| Science fiction | `planet` | `planet` | keep |  |
| Horror | `skull` | `skull` | keep |  |
| Thriller | `lightning` | `siren` | **replace** | siren (the bolt is charging) |
| Survival | `campfire` | `campfire` | keep |  |
| Historical | `scroll` | `scroll` | keep |  |
| Stealth | `eye-slash` | `detective` | **replace** | detective (the crossed eye is Hide) |
| Comedy | `smiley` | `smiley-wink` | **replace** | winking face (the face is Change avatar) |
| Business | `briefcase` | `briefcase` | keep |  |
| Drama | `mask-happy` | `mask-sad` | **replace** | sad mask (the happy mask was also this theme's section glyph) |
| Non-fiction | `newspaper` | `newspaper` | keep |  |
| Sandbox | `cube` | `cube` | keep | cube (Version moved to package) |
| Educational | `graduation-cap` | `graduation-cap` | keep |  |
| Kids | `balloon` | `balloon` | keep |  |
| Open world | `globe-hemisphere-west` | `globe-hemisphere-west` | keep |  |
| Warfare | `bomb` | `medal-military` | **replace** | medal (the bomb is the Explosion pattern) |
| Party | `confetti` | `confetti` | keep |  |
| 4X | `map-trifold` | `map-trifold` | keep |  |
| Mystery | `magnifying-glass` | `binoculars` | **replace** | binoculars (the magnifier is Search) |
| Romance | `heart` | `heart` | keep |  |
