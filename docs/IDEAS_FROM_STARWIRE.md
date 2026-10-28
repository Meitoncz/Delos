# Ideas from Starwire

Starwire ([SicarioGaming/Starwire](https://github.com/SicarioGaming/Starwire), GPL-3.0) is another
Moonlight Android client for Vibepollo, built on Moonlight X with features from Artemis, Artemide,
Moonlight V+, Moonlit Artemis and Moonlight X Turnip. This list compares its feature table
("Where every feature comes from", read 2026-10-25) with Delos and keeps what Delos lacks, ranked by
usefulness on an Android handheld (built-in controller, 1080p screen, Wi-Fi, often HDR). Where an
idea is taken, credit its original author as Starwire's table does.

Chosen for Delos (2026-10-25, tracked in `docs/ROADMAP.md`): 1, 2, 3, 4, 5, 10 and 12.

Delos already has, so they are left out: PyroWave (built from source, with HDR10 through P010),
Warp pacing and LFR, virtual display, clipboard send/get, PC commands, Task Manager, shortcuts,
full keyboard, touch modes and sensitivity, zoom, rotate, on-screen controls, the Lite (compact)
statistics, custom resolution and FPS, Wi-Fi locks, cover colours and a game screen of its own.

## High

1. **Change the bitrate during a stream** (Vibepollo). Idea from Moonlight V+ (qiin2333). Lower
   PyroWave's 450-530 Mbps on the fly when Wi-Fi struggles, without restarting the stream. Needs
   the host's bitrate control message in moonlight-common-c.
2. **Fast V-Sync (Nano Pacer) and async decoding**. Ported in Starwire from Artemide (derflacco).
   Lower latency for HEVC and AV1 through MediaCodec; complements Warp and LFR, which Delos has.
3. **Per-game settings** (codec, bitrate, HDR, frame pacing, touch mode). Moonlight X's per-app
   settings (MoreOrLessSoftware). Desktop wants different settings from a game, e.g. PyroWave SDR
   for Desktop and HEVC HDR for a game over weaker Wi-Fi. Implemented on 2026-10-25 with a focused
   quality/display scope approved by the user; touch mode remains in the profile. See
   [PER_GAME_SETTINGS.md](PER_GAME_SETTINGS.md).
4. **Hide the PC cursor** (never / while touching / always; Vibepollo). New in Starwire. With a
   controller the PC's pointer often sits in the middle of the picture.
5. **1% low FPS in the statistics**. Idea from Moonlight V+ and Moonlight X Turnip. Shows the
   hitches the average hides (useful for the "a few hitches in the first minute" kind of report).

## Medium

6. **Audio haptics**: the game's bass as vibration on the device or the controller. Idea from
   Moonlight V+, written new in Starwire. The Odin has a vibration motor; a nice handheld feature.
7. **Microphone to the PC**. Either VBAN to a small Windows receiver and VB-Cable (works with
   Vibepollo unchanged; Starwire encrypts it and pairs devices), or over the stream with logabell's
   Apollo microphone build. For voice chat; needs a PC-side setup, so it is the most work here.
8. **Monitor switcher** (Monitor 1-6 through the host's Ctrl+Alt+Shift+F1... shortcuts). Idea from
   Moonlit Artemis (4had45). Could be a page in the stream menu's More.
9. **Custom Turnip driver for PyroWave** (experimental). From Moonlight X Turnip (NoCrypt), with
   libadrenotools (bylaws). Its purpose is Vulkan 1.3 on Adreno GPUs whose stock driver lacks it, so
   PyroWave would run on older handhelds (Snapdragon 865 / 8 Gen 1 class, e.g. Retroid Pocket 5 /
   Mini). The Odin's stock driver already has Vulkan 1.3, so there it would mostly be an experiment
   in decode speed. Caveats: Turnip rejects AHardwareBuffer imports (Moonlight X Turnip keeps the
   system driver for MediaCodec), so the Delos P010 HDR path would fall back to its CPU copy under
   it; Starwire has not tried it with a real Turnip driver yet; it means shipping prebuilt
   libadrenotools libraries (Starwire rebuilds them from source to verify).
10. **Wi-Fi keep-alive during a stream** (formerly "Prevent Packet Loss" in Delos). Already inherited
    from Artemis: Delos sends a four-byte control payload approximately every 20 ms when enabled and
    connected, alongside its Wi-Fi locks. Starwire uses the same interval and payload, with an extra
    check for a Sunshine-family host and an encrypted control stream. Intended to discourage Wi-Fi
    power saving; benefit on the Odin has not been measured. Verified against both implementations
    on 2026-10-28; this is not a missing Delos feature.

## Low

11. **Automatic clipboard sync** (send yours when the stream starts, copy the PC's when you leave;
    Apollo / Vibepollo). From Artemis via Artemide. Delos has the manual buttons.
12. **Brightness slider in the stream menu**. From Moonlight X. Handhelds have the system one.
13. **Custom shortcuts imported from a JSON file** (Axixi2233 / Artemis format).
14. **Pinch to zoom at any time** and **long-press to right-click** in trackpad mode. Touch-first.
15. **Floating menu button / three-finger tap** to open the menu. Delos opens it from the controller.
16. **A "Predicted impact" panel or "Impact:" line** in settings: which choices lean to lowest
    latency or smoothest motion. A UI idea for the settings screen.
17. **Foldables**: the stream follows a foldable's inner screen. Not relevant to handhelds.
