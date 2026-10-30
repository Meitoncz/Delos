# Delos

An Android game streaming client for [Vibepollo](https://github.com/Nonary/Vibepollo) (also works with [Apollo](https://github.com/ClassicOldSong/Apollo) and [Sunshine](https://github.com/LizardByte/Sunshine)).

Delos is a fork of [Artemis Android](https://github.com/ClassicOldSong/moonlight-android) by ClassicOldSong, which is itself a fork of [Moonlight Android](https://github.com/moonlight-stream/moonlight-android). The goal is to bring in the latest Moonlight improvements and build a modern, console-like UI that is fully usable with a gamepad on Android handhelds.

> đźš§ Work in progress. Version 0.1.0 is based on Artemis 20.2.6 with Delos branding.

- Application ID: `io.github.meitoncz.delos` (debug builds: `io.github.meitoncz.delos.debug`), so Delos installs alongside Artemis and Moonlight.
- Debug APKs are built by the GitHub Actions *Build* workflow, which runs only when started manually (see its artifacts).

All unfinished work, ideas, investigations and maintenance are tracked in [GitHub Issues](https://github.com/Meitoncz/Delos/issues) and the [private Delos Project](https://github.com/users/Meitoncz/projects/1). Do not maintain separate Markdown task lists. See the [workflow](docs/GITHUB_WORKFLOW.md).

Technical docs: [analysis](docs/ANALYSIS.md), [UI specification](docs/GUI.md), [Windows setup](docs/WINDOWS_SETUP.md).

## License and credits

Delos is licensed under the GPLv3, same as Moonlight and Artemis. All credit for the streaming core goes to the Moonlight authors (listed below) and to ClassicOldSong for Artemis.

### Feature provenance

Feature ideas were compared with [Starwire](https://github.com/SicarioGaming/Starwire)
(SicarioGaming, GPL-3.0), including its "Where every feature comes from" table read on 2026-10-25.
The original projects/authors retain credit. This records inspiration and provenance, not a claim
that every idea is implemented or that its source code was copied.

| Area / idea | Original source credited by that comparison |
| --- | --- |
| Live bitrate and 1% low FPS | Moonlight V+ (qiin2333); Moonlight X Turnip also contributed the 1% low idea |
| Fast V-Sync / Nano Pacer and asynchronous decoding | Artemide (derflacco) |
| Per-game settings and brightness control | Moonlight X (MoreOrLessSoftware) |
| Audio-driven haptics | Moonlight V+; Starwire's own implementation |
| Capture-monitor switching | Moonlit Artemis (4had45) |
| Turnip driver-loading idea | Moonlight X Turnip (NoCrypt), libadrenotools (bylaws) |
| Wi-Fi keep-alive | Artemis (ClassicOldSong) |
| Automatic clipboard synchronization | Artemis via Artemide |
| JSON shortcut format | Axixi2233 / Artemis |

Game information uses [IGDB](https://www.igdb.com/) and technical evidence from
[PCGamingWiki](https://www.pcgamingwiki.com/) (data under
[CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/) unless otherwise noted).
Completion times come from [HowLongToBeat](https://howlongtobeat.com/) through an unofficial client.
The search flow is adapted from
[howlongtobeatpy 1.0.24](https://github.com/ScrappyCocco/HowLongToBeat-PythonAPI/tree/4275a452b9ccd230b7c2a82b8d1a5d851285eb14),
MIT, Copyright (c) 2018 Michele. The full notice is bundled in
`app/src/main/assets/licenses/howlongtobeatpy.txt`. Provider access/data terms are separate from the
client's code license. See [GUI.md](docs/GUI.md#game-information-and-library-filters) for behavior and
local credential provisioning; never commit real credentials.

---

*Original Artemis README:*

# Artemis Android

Previously named Moonlight Noir

An open source client for [Apollo](https://github.com/ClassicOldSong/Apollo)/[Sunshine](https://github.com/LizardByte/Sunshine).

Artemis Android will allow you to stream your collection of games from your Windows PC to your Android device,
whether in your own home or over the internet.

Artemis is currently the best fork of Moonlight with loads of optimizations for office usage.

A more seamless experience with virtual display will be Artemis paired with [Apollo](https://github.com/ClassicOldSong/Apollo).

# Features

If you switch back to the main stream version, you'll be missing the following awesome features which are very unlikely to be added there:

1. Custom virtual buttons with import and export support.
2. [Custom resolutions](https://github.com/moonlight-stream/moonlight-android/pull/1349).
3. Custom bitrates.
4. [Multiple mouse mode switching](https://github.com/moonlight-stream/moonlight-android/pull/1304) (normal mouse, [multi-touch](https://github.com/moonlight-stream/moonlight-android/pull/1364), touchpad, disabled, local cursor mode).
5. Optimized virtual gamepad skins and free joystick.
6. External monitor mode.
7. Joycon D-pad support.
8. Simplified performance information display.
9. [Game back menu](https://github.com/moonlight-stream/moonlight-android/pull/1171).
10. Custom shortcut commands.
11. Easy soft keyboard switching.
12. Portrait mode.
13. Display on top mode, useful for foldable phones.
14. [Virtual touchpad space and sensitivity adjustment](https://github.com/moonlight-stream/moonlight-android/issues/1348#issuecomment-2236344729) for playing right-click view games, such as Warcraft.
15. Force use device's own vibration motor (in case your gamepad's vibration is not effective).
16. Gamepad debugging page to view gamepad vibration and gyroscope information, as well as Android kernel version information.
17. Trackpad tap/scrolling support
18. Natural track pad mode with touch screen
19. Non-QWERTY keyboard layout support
20. Quick Meta key with physical BACK button
21. Frame rate lock fix for some devices
22. Video scale mode: Fit/Fill/Stretch
23. View pan/zoom support
24. Rotate screen in-game
25. Add option to quit app directly
26. Samsung DeX scrolling support
27. Proper click/scroll/right-click for trackpad on generic Android tablet when using local cursor
28. Virtual Display integration with [Apollo](https://github.com/ClassicOldSong/Apollo)
29. Server Command integration with [Apollo](https://github.com/ClassicOldSong/Apollo)
30. Clipboard sync (requires Apollo)
31. SBS 3D for external Displays (Using AI MiDaS v2 Lite)

# Disclaimer

This is the `go away` version of Moonlight Android.

I got kicked from Moonlight and Sunshine's Discord server literally for helping people out.

This is what I got for finding a bug, opened an issue, getting no response, troubleshoot myself, fixed the issue myself, shared it by PR to the main repo hoping my efforts can help someone else during the maintainance gap.

Yes, I'm going away. Fixes and improvements on this fork are not necessarily be merged to the main repo either. I have also started [a fork of Sunshine called Apollo](https://github.com/ClassicOldSong/Apollo) and will add useful features that will never get merged by the main repo shortly. [Apollo](https://github.com/ClassicOldSong/Apollo) and [Moonlight Noir](https://github.com/ClassicOldSong/moonlight-android) will no longer be compatible with OG Sunshine and OG Moonlight eventually, but they'll work even better with much more carefully designed features.

The main repo had stayed silent for 5 months, with nobody actually responding to issues, and people are getting totally no help besides the limited FAQ in their Discord server. I tried to answer issues and questions, solve problems within my ablilty but I got kicked out just for helping others.

**PRs for feature improvements are welcomed here unlike the main repo, your ideas are more likely to be appreciated and your efforts are actually being respected. We welcome people who can and willing to share their efforts, helping yourselves and other people in need.**

**Update**: They have contacted me and apologized for this incident, but the fact it **happened** still motivated me to start my own fork.

## Downloads
* [Download APK directly](https://github.com/ClassicOldSong/moonlight-android/releases)
* [Use Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.limelight.noir%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FClassicOldSong%2Fmoonlight-android%22%2C%22author%22%3A%22ClassicOldSong%22%2C%22name%22%3A%22Artemis%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22nonRoot%5C%22%2C%5C%22matchGroutToUse%5C%22%3A%5C%22%241%5C%22%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22v(.%2B)%5C%22%7D%22%7D) (recommended)

## Building
* Install Android Studio and the Android NDK
* Run â€git submodule update --init --recursiveâ€™ from within moonlight-android/
* In moonlight-android/, create a file called â€local.propertiesâ€™. Add an â€ndk.dir=â€™ property to the local.properties file and set it equal to your NDK directory.
* Build the APK using Android Studio or gradle

## Authors

* [Cameron Gutman](https://github.com/cgutman)  
* [Diego Waxemberg](https://github.com/dwaxemberg)  
* [Aaron Neyer](https://github.com/Aaronneyer)  
* [Andrew Hennessy](https://github.com/yetanothername)

Moonlight is the work of students at [Case Western](http://case.edu) and was
started as a project at [MHacks](http://mhacks.org).
