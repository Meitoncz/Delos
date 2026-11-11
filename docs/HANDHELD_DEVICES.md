# Handheld display reference

Purpose: sourced input for the screen-shape planner (see `docs/GUI.md`, "Screen shapes"). Research date: 2026-11-11.

Conventions:

- Aspect is `w:h` reduced, with the decimal `w/h` in brackets. It is always computed from the stated resolution, in landscape orientation.
- ppi is "stated" when a source prints it (the source is named). Otherwise it is "computed" as `sqrt(w² + h²) / diagonal`.
- "HA" means handheldsarena.com. HA is a spec aggregator that cites the maker page in its own "sources" box. Where a maker page was reachable, the maker page is linked too. Maker pages that are image-only, or that rate-limited or returned 404 during research, are noted per row.
- "HW" means handheldwiki.com. It is a secondary aggregator, used only for refresh rates where no maker or press source was found. Refresh values taken from HW alone are marked `(HW only)`.
- `[unverified]` means no source was found for that figure. "not stated" means the maker or source was read and does not say.
- Panel sizes are marketing diagonals, so computed ppi can differ from stated ppi by a fraction.

## Single-screen and dual-screen devices

### AYN

| Device | Display | Size | Resolution | Aspect | ppi | Refresh | Source | Notes |
|---|---|---|---|---|---|---|---|---|
| Odin 2 | 1 | 6.0" IPS | 1920x1080 | 16:9 (1.778) | 367.15 stated (HA) | 60 Hz | size/res/ppi/panel: [HA](https://handheldsarena.com/devices/ayn-technologies/odin-2/); refresh: [Android Central](https://www.androidcentral.com/gaming/ayn-odin-2-portal-announced) ("6-inch LCD ... 60Hz") | Released Oct 2023 (HA). Touch: yes (HA). |
| Odin 2 Mini | 1 | 5.0" Mini LED | 1920x1080 | 16:9 (1.778) | 440.58 stated (HA) | 60 Hz (HW only) | size/res/ppi/panel: [HA](https://handheldsarena.com/devices/ayn-technologies/odin-2-mini/); refresh: [HW](https://handheldwiki.com/odin-2-mini) | Released Jun 2024 (HA). **Touch: no** (HA). Maker page [ayntec.com/products/odin2-mini](https://www.ayntec.com/products/odin2-mini) has no text specs. |
| Odin 2 Portal | 1 | 7.0" AMOLED | 1920x1080 | 16:9 (1.778) | 314.7 stated (HA) | up to 120 Hz | size/res/ppi/panel: [HA](https://handheldsarena.com/devices/ayn-technologies/odin-2-portal/); size/res/refresh: [Android Central spec table](https://www.androidcentral.com/gaming/ayn-odin-2-portal-announced) | Released Jan 2025 (HA). Maker page [ayntec.com/products/odin2-portal](https://www.ayntec.com/products/odin2-portal) has no text specs. |
| Odin 3 | 1 | 6.0" AMOLED | 1920x1080 | 16:9 (1.778) | 367.15 stated (HA) | 120 Hz (60 Hz switchable) | size/res/ppi/panel: [HA](https://handheldsarena.com/devices/ayn-technologies/odin-3/); refresh: [Pocket Retro Gamer](https://pocketretrogamer.com/blog/ayn-odin-3) | Released Dec 2025 (HA). 650 nits peak (Pocket Retro Gamer). |
| Thor | top | 6.0" AMOLED | 1920x1080 | 16:9 (1.778) | 367.15 stated (HA) | 120 Hz | size/res/ppi/panel: [HA](https://handheldsarena.com/devices/ayn-technologies/thor/) (cites [ayntec.com/products/ayn-thor](https://www.ayntec.com/products/ayn-thor), which states "6\" AMOLED" and "3.92\" AMOLED Touch Display (Bottom Screen)"); refresh: [IGN](https://www.ign.com/articles/ayn-thor-review) | Released Oct 2025 (HA). Primary display: **top** (see Dual-screen section). |
| Thor | bottom | 3.92" AMOLED | 1240x1080 | 31:27 (1.148) | 419.49 stated (HA) | 60 Hz | as above; refresh: [IGN](https://www.ign.com/articles/ayn-thor-review), [DROIX](https://droix.net/blogs/en-au/ayn-thor-review/) | Same panel size and resolution as Retroid Pocket Mini V2 and AYANEO Pocket DMG. |

### Retroid

| Device | Display | Size | Resolution | Aspect | ppi | Refresh | Source | Notes |
|---|---|---|---|---|---|---|---|---|
| Retroid Pocket 4 Pro | 1 | 4.7" IPS | 1334x750 | 667:375 (1.779), marketed 16:9 | 325.61 stated (HA) | 60 Hz (HW only) | size/res/ppi/panel: [HA](https://handheldsarena.com/devices/retroid/pocket-4-pro/); refresh: [HW](https://handheldwiki.com/retroid-pocket-4-pro) | Released Jan 2024 (HA). The only 1334x750 panel in this list. |
| Retroid Pocket 5 | 1 | 5.5" AMOLED | 1920x1080 | 16:9 (1.778) | 400.53 stated (HA) | 60 Hz | size/res/ppi/panel: [HA](https://handheldsarena.com/devices/retroid/pocket-5/); refresh: [Retro Catalog](https://retrocatalog.com/retro-handhelds/retroid-pocket-5), [HW](https://handheldwiki.com/retroid-pocket-5) | Released Nov 2024 (HA). goretroid.com RP5 page was rate-limited during research. |
| Retroid Pocket Mini | 1 | 3.7" AMOLED | 1280x960 | 4:3 (1.333) | 432.43 stated (HA) | 60 fps | size/res/panel/refresh: [retroid.net](https://retroid.net/product/retroid-pocket-mini) ("1280×960@60fps"); ppi: [HA](https://handheldsarena.com/devices/retroid/pocket-mini/) | Released Sep 2024 (HA). retroid.net is a Retroid distributor, not goretroid.com. |
| Retroid Pocket Mini V2 | 1 | 3.92" AMOLED | 1240x1080 | 31:27 (1.148) | 419.49 stated (HA) | 60 Hz (HW only) | size/res/ppi/panel: [HA](https://handheldsarena.com/devices/retroid/pocket-mini-v2/); refresh: [HW](https://handheldwiki.com/retroid-pocket-mini-v2) | Released May 2025 (HA). |
| Retroid Pocket Flip 2 | 1 (clamshell, single panel) | 5.5" AMOLED | 1920x1080 ("1080p") | 16:9 (1.778) | 400.53 stated (HA) | 60 fps | size/panel/res/refresh: [goretroid.com](https://www.goretroid.com/products/retroid-pocket-flip-2) ("5.5 inch, AMOLED, 1080p@60fps"); ppi: [HA](https://handheldsarena.com/devices/retroid/pocket-flip-2/) | Released Apr 2025 (HA). Maker states "1080p" only; 1920x1080 is from HA. |
| Retroid Pocket 6 | 1 | 5.5" AMOLED | 1920x1080 ("1080p") | 16:9 (1.778) | 400.53 stated (HA) | 120 Hz | size/panel/res/refresh: [goretroid.com](https://www.goretroid.com/products/retroid-pocket-6-handheld) ("5.5 inch, AMOLED, 1080p@120Hz"); ppi: [HA](https://handheldsarena.com/devices/retroid/pocket-6/) | Released Jan 2026 (HA). |
| Retroid Dual Screen Add-on | clip-on second display | 5.5" AMOLED | 1920x1080 | 16:9 (1.778) | 400.5 computed | not stated | size/panel: [goretroid.com](https://www.goretroid.com/products/retroid-dual-screen-add-on); resolution: [Notebookcheck](https://www.notebookcheck.net/Retroid-s-Dual-Screen-Add-On-costs-69-and-works-with-third-party-devices.1032905.0.html) | Accessory, $69, 500 nits peak. Supports RP6/RP5/RP4 Pro/RP Mini/RP Mini V2 officially, plus 6-7" devices with DisplayPort over USB-C and height up to 115 mm (Notebookcheck). Details in Dual-screen section. |

### AYANEO

| Device | Display | Size | Resolution | Aspect | ppi | Refresh | Source | Notes |
|---|---|---|---|---|---|---|---|---|
| Pocket S (1080P edition) | 1 | 6.0" IPS | 1920x1080 | 16:9 (1.778) | 367.15 stated (HA) | 60 Hz (HW only) | size/panel: [AYANEO](https://www.ayaneo.com/product/AYANEO-Pocket-S) ("6\" 1080P IPS"); res/ppi: [HA](https://handheldsarena.com/devices/ayaneo/pocket-s-1080p/); refresh: [HW](https://handheldwiki.com/ayaneo-pocket-s) | Released May 2024 (HA). |
| Pocket S (1440P edition) | 1 | 6.0" IPS | 2560x1440 | 16:9 (1.778) | 490 stated (AYANEO); 489.53 (HA) | 60 Hz (HW only) | size/panel/ppi: [AYANEO launch post](https://next.ayaneo.com/article/828) ("6\" 1440P IPS", "490PPI"); res: [HA](https://handheldsarena.com/devices/ayaneo/pocket-s-1440p/); refresh: [HW](https://handheldwiki.com/ayaneo-pocket-s) | Two SKUs share one device name. |
| Pocket EVO | 1 | 7.0" OLED | 1920x1080 ("1080P") | 16:9 (1.778) | 314.7 stated (HA) | up to 165 Hz (adaptive across five rates) | size/panel/refresh: [AYANEO](https://store.ayaneo.com/article/854) ("7\" 1080P 165Hz OLED"); res/ppi: [HA](https://handheldsarena.com/devices/ayaneo/pocket-evo/) | Released Nov 2024 (HA). |
| Pocket DMG | 1 | 3.92" OLED | 1240x1080 | 31:27 (1.148); maker says "near 8:7" | 419 stated (AYANEO) | 60 Hz (HW only) | size/res/ppi/panel: [AYANEO](https://store.ayaneo.com/article/851); refresh: [HW](https://handheldwiki.com/ayaneo-pocket-dmg) | Released Nov 2024 (HA). Vertical retro form factor; maker does not state refresh. |
| Pocket DS | main (top) | 7.0" OLED | 1920x1080 | 16:9 (1.778) | 314.7 stated (HA) | 165/144/120/90/60 Hz | [AYANEO](https://store.ayaneo.com/article/892); ppi: [HA](https://handheldsarena.com/devices/ayaneo/pocket-ds/) | Released Aug 2025. Primary display: **bottom** (see Dual-screen section). |
| Pocket DS | secondary (bottom) | 5.0" LCD (maker: "4:3 LCD"; IPS per [ayaneo.net](https://ayaneo.net/product/ayaneo-pocket-ds)) | 1024x768 | 4:3 (1.333) | 256 stated (HA) | not stated [unverified] | [AYANEO](https://store.ayaneo.com/article/892) (550 nits, 100% sRGB); ppi: [HA](https://handheldsarena.com/devices/ayaneo/pocket-ds/) | HA lists the bottom panel as OLED; the maker says LCD. The maker wording is used. |
| Pocket Micro | 1 | 3.5" IPS | 960x640 | 3:2 (1.500) | 329.65 stated (HA) | [unverified] | size/panel: [AYANEO](https://store.ayaneo.com/article/851) ("3.5\" 640P IPS"); res/ppi: [HA](https://handheldsarena.com/devices/ayaneo/pocket-micro/) | Released Sep 2024 (HA). Maker says "640P"; the 960 width is from HA. |
| Pocket ACE | 1 | 4.5" IPS | 1620x1080 | 3:2 (1.500) | 432.67 stated (HA) | [unverified] | size/res/panel: [AYANEO](https://store.ayaneo.com/article/881) ("4.5-inch 1620×1080 ... 3:2 IPS", 550 nits); ppi: [HA](https://handheldsarena.com/devices/ayaneo/pocket-ace/) | Released 2025-04-28 per the maker post. The maker post does not state refresh. |

### Anbernic

| Device | Display | Size | Resolution | Aspect | ppi | Refresh | Source | Notes |
|---|---|---|---|---|---|---|---|---|
| RG556 | 1 | 5.48" AMOLED | 1080x1920 as printed by the maker (landscape 1920x1080) | 16:9 (1.778) | 401.99 stated (HA) | [unverified] | size/res/panel: [anbernic.com](https://anbernic.com/products/rg556); ppi: [HA](https://handheldsarena.com/devices/anbernic/rg-556/) | Released Mar 2024 (HA). The maker does not state refresh. |
| RG 406V | 1 (portrait body, 4:3 panel) | 4.0" IPS | 960x720 | 4:3 (1.333) | 300 stated (HA); 300.0 computed | [unverified] | size/res/panel: [anbernic.com](https://anbernic.com/products/rg-406v); ppi: [HA](https://handheldsarena.com/devices/anbernic/rg-406v/) | Released Sep 2024 (HA). Vertical body (14.5 x 10.5 cm per maker). |
| RG 406H | 1 | 4.0" IPS | 960x720 | 4:3 (1.333) | 300 stated (HA) | [unverified] | [HA](https://handheldsarena.com/devices/anbernic/rg-406h/) | Released Nov 2024 (HA). The maker page `anbernic.com/products/rg-406h` is cited by HA but returned 404 or was empty during research. Retailer [RetroGear](https://retrogear.nl/nl/products/anbernic-rg406h) confirms "4 inch 4:3 IPS". |
| RG Cube | 1 | 3.95" IPS | 720x720 | 1:1 (1.000) | 257.77 stated (HA) | [unverified] | size/res/panel: [anbernic.com](https://anbernic.com/products/rg-cube); ppi: [HA](https://handheldsarena.com/devices/anbernic/rg-cube/) | Released Jun 2024 (HA). Square panel. |
| RG 476H | 1 | 4.7" LTPS In-Cell | 1280x960 | 4:3 (1.333) | 340.43 stated (HA) | max 120 Hz | [anbernic.com](https://anbernic.com/products/rg476h) ("supports a maximum refresh rate of 120Hz"); ppi: [HA](https://handheldsarena.com/devices/anbernic/rg-476h/) | Released Sep 2025 (HA). USB-C DP output supports dual-screen display for NDS/3DS (maker). |
| RG DDS | top | 4.7" IPS | 1200x720 | 5:3 (1.667) | 300 stated (maker); 297.8 computed | not stated [unverified] | [anbernic.com](https://anbernic.com/products/rg-dds), [maker news post](https://anbernic.com/a/shop/content/articles/news/anbernic-rg-dds-first-look) | **Announced, pre-order only.** Ships from 2026-12-10 (maker page). 500 nits. Android 16. Primary display: not stated. |
| RG DDS | bottom | 4.0" IPS | 960x720 | 4:3 (1.333) | 300 stated (maker); 300.0 computed | not stated [unverified] | as above | The news post says refresh rate is "matched" across both panels but gives no number. Stylus-capable lower screen (maker). |

### Logitech and Razer

| Device | Display | Size | Resolution | Aspect | ppi | Refresh | Source | Notes |
|---|---|---|---|---|---|---|---|---|
| Logitech G Cloud | 1 | 7.0" IPS | 1920x1080 | 16:9 (1.778) | 314.7 computed (HA also states 314.7) | 60 Hz | [Logitech support spec page](https://support.logi.com/hc/en-us/articles/7712834867991-Specification-Logitech-G-CLOUD-Gaming-Handheld); ppi cross-check: [HA](https://handheldsarena.com/devices/logitech/g-cloud/) | Released Oct 2022 (HA). 450 nits, 10-finger multitouch (Logitech). |
| Razer Edge (Wi-Fi / 5G) | 1 | 6.8" AMOLED | 2400x1080 | 20:9 (2.222) | 387.0 computed (HA states 387.03) | 144 Hz | [razer.com spec](https://www.razer.com/mobile-handhelds/Razer-Edge/RZ80-04610100-B3U1) ("6.8\" FHD+ (2400x1080) AMOLED 144Hz"); ppi cross-check: [HA](https://handheldsarena.com/devices/razer/edge-5g/) | Released Jan 2023 (TechRadar, [link](https://www.techradar.com/news/razer-edge-gaming-handheld-finally-launches-in-both-5g-and-wifi-flavors)). Tablet-style body with a Kishi V2 Pro grip. The only 20:9 dedicated handheld here. |

## Dual-screen devices

| Device | Top panel | Bottom panel | Android primary display | Source for primary |
|---|---|---|---|---|
| AYN Thor | 6.0" 1920x1080 16:9 AMOLED, 120 Hz | 3.92" 1240x1080 31:27 AMOLED, 60 Hz | **top** | [Retro Game Corps](https://retrogamecorps.com/2025/10/27/dual-screen-android-handheld-guide): "on the AYN Thor ... the software thinks of the top screen as its primary display" |
| AYANEO Pocket DS | 7.0" 1920x1080 16:9 OLED, up to 165 Hz | 5.0" 1024x768 4:3 LCD, refresh not stated | **bottom** | [Retro Game Corps](https://retrogamecorps.com/2025/10/27/dual-screen-android-handheld-guide): "the bottom screen is recognized as the primary display and the top screen is external" |
| Retroid Dual Screen Add-on (clipped onto a Retroid handheld) | add-on: 5.5" 1920x1080 16:9 AMOLED | host handheld's own panel (RP6/RP5: 5.5" 1920x1080; RP Mini: 3.7" 1280x960; RP Mini V2: 3.92" 1240x1080; RP 4 Pro: 4.7" 1334x750) | **host handheld (bottom)**; add-on is the external display | [Retro Game Corps](https://retrogamecorps.com/2025/10/27/dual-screen-android-handheld-guide) (Retroid attachment grouped with Pocket DS); [Held Games](https://heldgames.com/guides/retroid-dual-screen-add-on-ds-3ds): "Android detects it as a second display" |
| Anbernic RG DDS | 4.7" 1200x720 5:3 IPS | 4.0" 960x720 4:3 IPS | not stated | none found. [Maker page](https://anbernic.com/products/rg-dds) and [maker news post](https://anbernic.com/a/shop/content/articles/news/anbernic-rg-dds-first-look) do not say. Announced and pre-order only. |

Notes:

- Three different dual-screen layouts exist: a top panel of 16:9 with a 31:27 bottom (Thor), 16:9 over 4:3 (Pocket DS), and 5:3 over 4:3 (RG DDS). The Retroid add-on pairs a fixed 16:9 panel with whatever the host has.
- Single-panel clamshells (Retroid Pocket Flip 2) are not dual-screen. See the Retroid table above.
- Out of scope but seen: Retroid Pocket Duo and Duo Lite (announced Sep 2026; [Retro Handhelds](https://retrohandhelds.gg/retroid-pocket-duo-recieves-its-first-announcement/)). Not researched further.
- How Delos tells a built-in second panel from an external monitor, and how to override it: [DUAL_SCREEN.md](DUAL_SCREEN.md). The Retroid add-on is attached over USB-C, so Android should list it as an external display and Delos should classify it as external (not verified on hardware); "Part of the device" in Settings > Display > Second display changes that.

## Phone in a grip (class, not a device list)

A phone clamped into a controller grip (Razer Kishi, Backbone, GameSir X-series and similar). The screen shape is the phone's, not the grip's, so it varies per user. Delos cannot assume a fixed resolution.

Observed range, from the sources below:

| Example phone (landscape) | Resolution | Aspect | Source |
|---|---|---|---|
| Sony Xperia 1 VII (6.5") | 2340x1080 | 13:6 = 19.5:9 (2.167), ~396 ppi stated | [GSMArena](https://www.gsmarena.com/sony_xperia_1_vii_5g-13843.php) |
| Samsung Galaxy S25 Ultra (6.9") | 3120x1440 | 13:6 = 19.5:9 (2.167), ~498 ppi stated | [GSMArena](https://www.gsmarena.com/samsung_galaxy_s25_ultra-13322.php) |
| Razer Edge (6.8", tablet body, not a clamp-in phone) | 2400x1080 | 20:9 (2.222) | [razer.com](https://www.razer.com/mobile-handhelds/Razer-Edge/RZ80-04610100-B3U1) |
| Sony Xperia 1 V (6.5") | resolution [unverified] | marketed "21:9 Wide display" (2.333 nominal) | [Sony UK spec](https://www.sony.co.uk/electronics/support/mobile-phones-tablets-mobile-phones/xperia-1-v-256gb/specifications) |

Class traits:

- Aspect is roughly 19.5:9 (2.167) to 21:9 (2.333). 20:9 (2.222) sits in between. Xperia 1 V is the only 21:9 example found; the 2025 Xperia 1 VII moved to 19.5:9.
- Cutouts are expected on one long edge or a corner: front-camera hole punch or notch, plus rounded corners. Cutout position depends on how the phone is rotated in the grip, and one landscape side is usually covered by the grip.
- Grips cover the left and right short edges of the screen, which can hide the edge-most pixels. The visible area is not the full panel. Backbone publishes sized adapters for case-compatible fit ([Backbone FAQ](https://help.backbone.com/hc/en-us/articles/25842236494487-How-to-use-your-Backbone-controller-with-compatible-cases)).
- Refresh and ppi are phone-dependent (60 to 144 Hz range seen in this document across Razer Edge, Xperia and Galaxy examples).
- Safe-area inset values for the class were not researched. Android supplies them at runtime through `DisplayCutout`.

## Not found / unverified

- Refresh rate, no source found: AYANEO Pocket Micro, AYANEO Pocket ACE, AYANEO Pocket DS bottom panel, Anbernic RG556, RG 406V, RG 406H, RG Cube, RG DDS (both panels), Retroid Dual Screen Add-on. Tried: maker product pages, maker launch posts, HA and HW (HA has no refresh field; HW has no pages for these devices).
- Refresh rate, secondary aggregator only (HW only): AYN Odin 2 Mini, Retroid Pocket 4 Pro, Retroid Pocket Mini V2, AYANEO Pocket S (both editions), AYANEO Pocket DMG.
- Maker spec pages that exist but show specs only as images or were unreachable: ayntec.com Odin 2, Odin 2 Mini, Odin 2 Portal, Odin 3 (ayntec.com pages carry no text specs, so HA and press are used). Retroid RP5, Mini, Mini V2 and 4 Pro on goretroid.com (rate-limited, so HA, retroid.net and HW are used). anbernic.com RG 406H (404).
- Anbernic RG DDS is announced and on pre-order, shipping from 2026-12-10, so not yet released.
- Primary display for RG DDS: not stated by any source found.
- Pocket DS bottom panel type: the maker says LCD ([AYANEO](https://store.ayaneo.com/article/892)), [ayaneo.net](https://ayaneo.net/product/ayaneo-pocket-ds) says IPS, HA says OLED. This is a conflict between sources, not resolved.
- Xperia 1 V exact resolution: [unverified].

## Planned shape class per device

Computed with `StagePlanner` (`app/src/main/java/com/limelight/shell/ui/StagePlanner.kt`; rules in [GUI.md](GUI.md), "Screen shapes"): window in dp = pixels / (dpi / 160), one stage pixel is `max(min(w / 1920, h / 1080), 0.43)` dp, the stage is the window divided by that, and the class is Compact when the stage is under 1500 stage px wide or narrower than about 4:3, else Standard (16:9 within 3 %), Wide (wider) or Tall (narrower, down to 4:3).

**Assumed density.** No maker states the density Android reports, so it is assumed to be the display's ppi (the stated one above, else computed) divided by 160, rounded to the nearest 10 dpi. The one measured exception is the AYN Odin 2 Portal, the reference device: it reports **369 dpi** (the figure the project lead measured on the device) although its 7" panel is 314.7 ppi, which is why the floor sits at 0.43 dp per stage px (0.4336 on the Odin). A device that really reports a different density than assumed here moves between rows; the simulator (`--es preview_shape <W>x<H>@<dpi>`) answers that per device. Each display is taken alone, in landscape, as the whole window; split-screen halves and the Android window insets are not included.

Reading the table: the **dp per stage px** column is 0.4300 wherever the floor applied, and then the stage is smaller than 1920 x 1080 even for a 16:9 panel (a 5.5" 1080p panel at 400 dpi gets 1786 x 1005, still Standard). Only 16:9 panels at or below about 372 dpi keep the full 1920 x 1080 stage.

| Device (display) | Resolution | Assumed density | Window (dp) | Stage (stage px) | dp per stage px | Class |
|---|---|---|---|---|---|---|
| AYN Odin 2 | 1920x1080 | 370 (ppi 367.15, rounded to 10) | 830x467 | 1920x1080 | 0.4324 | **STANDARD** |
| AYN Odin 2 Mini | 1920x1080 | 440 (ppi 440.58, rounded to 10) | 698x393 | 1624x913 | 0.4300 | **STANDARD** |
| AYN Odin 2 Portal | 1920x1080 | 369 observed (reported by the Odin) | 833x468 | 1920x1080 | 0.4336 | **STANDARD** |
| AYN Odin 3 | 1920x1080 | 370 (ppi 367.15, rounded to 10) | 830x467 | 1920x1080 | 0.4324 | **STANDARD** |
| AYN Thor top | 1920x1080 | 370 (ppi 367.15, rounded to 10) | 830x467 | 1920x1080 | 0.4324 | **STANDARD** |
| AYN Thor bottom | 1240x1080 | 420 (ppi 419.49, rounded to 10) | 472x411 | 1099x957 | 0.4300 | **COMPACT** |
| Retroid Pocket 4 Pro | 1334x750 | 330 (ppi 325.61, rounded to 10) | 647x364 | 1504x846 | 0.4300 | **STANDARD** |
| Retroid Pocket 5 | 1920x1080 | 400 (ppi 400.53, rounded to 10) | 768x432 | 1786x1005 | 0.4300 | **STANDARD** |
| Retroid Pocket Mini | 1280x960 | 430 (ppi 432.43, rounded to 10) | 476x357 | 1108x831 | 0.4300 | **COMPACT** |
| Retroid Pocket Mini V2 | 1240x1080 | 420 (ppi 419.49, rounded to 10) | 472x411 | 1099x957 | 0.4300 | **COMPACT** |
| Retroid Pocket Flip 2 | 1920x1080 | 400 (ppi 400.53, rounded to 10) | 768x432 | 1786x1005 | 0.4300 | **STANDARD** |
| Retroid Pocket 6 | 1920x1080 | 400 (ppi 400.53, rounded to 10) | 768x432 | 1786x1005 | 0.4300 | **STANDARD** |
| Retroid Dual Screen Add-on | 1920x1080 | 400 (ppi 400.5, rounded to 10) | 768x432 | 1786x1005 | 0.4300 | **STANDARD** |
| AYANEO Pocket S (1080P) | 1920x1080 | 370 (ppi 367.15, rounded to 10) | 830x467 | 1920x1080 | 0.4324 | **STANDARD** |
| AYANEO Pocket S (1440P) | 2560x1440 | 490 (ppi 490, rounded to 10) | 836x470 | 1920x1080 | 0.4354 | **STANDARD** |
| AYANEO Pocket EVO | 1920x1080 | 310 (ppi 314.7, rounded to 10) | 991x557 | 1920x1080 | 0.5161 | **STANDARD** |
| AYANEO Pocket DMG | 1240x1080 | 420 (ppi 419, rounded to 10) | 472x411 | 1099x957 | 0.4300 | **COMPACT** |
| AYANEO Pocket DS main | 1920x1080 | 310 (ppi 314.7, rounded to 10) | 991x557 | 1920x1080 | 0.5161 | **STANDARD** |
| AYANEO Pocket DS secondary | 1024x768 | 260 (ppi 256, rounded to 10) | 630x473 | 1465x1099 | 0.4300 | **COMPACT** |
| AYANEO Pocket Micro | 960x640 | 330 (ppi 329.65, rounded to 10) | 465x310 | 1082x722 | 0.4300 | **COMPACT** |
| AYANEO Pocket ACE | 1620x1080 | 430 (ppi 432.67, rounded to 10) | 603x402 | 1402x935 | 0.4300 | **COMPACT** |
| Anbernic RG556 | 1920x1080 | 400 (ppi 401.99, rounded to 10) | 768x432 | 1786x1005 | 0.4300 | **STANDARD** |
| Anbernic RG 406V | 960x720 | 300 (ppi 300, rounded to 10) | 512x384 | 1191x893 | 0.4300 | **COMPACT** |
| Anbernic RG 406H | 960x720 | 300 (ppi 300, rounded to 10) | 512x384 | 1191x893 | 0.4300 | **COMPACT** |
| Anbernic RG Cube | 720x720 | 260 (ppi 257.77, rounded to 10) | 443x443 | 1030x1030 | 0.4300 | **COMPACT** |
| Anbernic RG 476H | 1280x960 | 340 (ppi 340.43, rounded to 10) | 602x452 | 1401x1051 | 0.4300 | **COMPACT** |
| Anbernic RG DDS top | 1200x720 | 300 (ppi 300, rounded to 10) | 640x384 | 1488x893 | 0.4300 | **COMPACT** |
| Anbernic RG DDS bottom | 960x720 | 300 (ppi 300, rounded to 10) | 512x384 | 1191x893 | 0.4300 | **COMPACT** |
| Logitech G Cloud | 1920x1080 | 310 (ppi 314.7, rounded to 10) | 991x557 | 1920x1080 | 0.5161 | **STANDARD** |
| Razer Edge | 2400x1080 | 390 (ppi 387.03, rounded to 10) | 985x443 | 2290x1030 | 0.4300 | **WIDE** |
| Phone in a grip: Xperia 1 VII | 2340x1080 | 400 (ppi 396, rounded to 10) | 936x432 | 2177x1005 | 0.4300 | **WIDE** |
| Phone in a grip: Galaxy S25 Ultra | 3120x1440 | 500 (ppi 498, rounded to 10) | 998x461 | 2322x1072 | 0.4300 | **WIDE** |

