# Delos

An Android game streaming client built for gaming handhelds. It connects to [Vibepollo](https://github.com/Nonary/Vibepollo) and also works with [Apollo](https://github.com/ClassicOldSong/Apollo) and [Sunshine](https://github.com/LizardByte/Sunshine).

> **Work in progress.** Delos is under active development and has no releases yet. Expect rough edges and changes.

Delos is a fork of [Artemis Android](https://github.com/ClassicOldSong/moonlight-android) by ClassicOldSong, which is itself a fork of [Moonlight Android](https://github.com/moonlight-stream/moonlight-android). It has since separated from them and goes its own way: a console-like interface that is fully usable with a gamepad, designed for Android gaming handhelds.

## Building

Follow [Mac setup](docs/MAC_SETUP.md) or [Windows setup](docs/WINDOWS_SETUP.md) for the toolchain, submodules and local environment, then run:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The ARM64 debug APK is `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`. The application ID is `io.github.meitoncz.delos` (debug builds add `.debug`), so Delos installs alongside Artemis and Moonlight.

## Contributing and bug reports

Found a problem or have an idea? Open an issue with one of the [issue forms](https://github.com/Meitoncz/Delos/issues/new/choose). Code contributions are welcome too: see [CONTRIBUTING.md](CONTRIBUTING.md).

Delos keeps a small diagnostic log on your device (never uploaded). After a crash it offers to open a prefilled bug report for you to check and submit, and Settings → About → Debug info can share a cleaned log. Details and privacy: [docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md).

## License

Delos is licensed under the [GNU General Public License v3.0](LICENSE.txt), the same license as Moonlight and Artemis.

## Credits

Delos stands on the work of many people. All credit for the streaming core goes to the Moonlight authors and to ClassicOldSong.

- **[Moonlight Android](https://github.com/moonlight-stream/moonlight-android)** and [moonlight-common-c](https://github.com/moonlight-stream/moonlight-common-c): Cameron Gutman, Diego Waxemberg, Aaron Neyer, Andrew Hennessy and all contributors. Moonlight is the work of students at Case Western and was started as a project at MHacks.
- **[Artemis Android](https://github.com/ClassicOldSong/moonlight-android)** and [Apollo](https://github.com/ClassicOldSong/Apollo): ClassicOldSong and contributors.
- **[Sunshine](https://github.com/LizardByte/Sunshine)**: LizardByte and contributors. **[Vibepollo](https://github.com/Nonary/Vibepollo)**: Nonary and contributors.
- Features that other Moonlight forks pioneered are credited to their authors: Moonlight V+ (qiin2333), Artemide (derflacco), Moonlight X (MoreOrLessSoftware), Moonlit Artemis (4had45), Moonlight X Turnip (NoCrypt) with libadrenotools (bylaws), and Axixi2233. The feature list was compared with [Starwire](https://github.com/SicarioGaming/Starwire) (SicarioGaming, GPL-3.0); this records inspiration, not a claim that its code was copied.
- **Video and upscaling:** [PyroWave](https://github.com/Themaister/Granite) and Granite (Hans-Kristian Arntzen, MIT), [volk](https://github.com/zeux/volk) (MIT), and Qualcomm Snapdragon Game Super Resolution (see `app/src/main/assets/licenses/`).
- **Fonts and icons:** [Unbounded](https://github.com/googlefonts/unbounded) and [Figtree](https://github.com/erikdkennedy/figtree) (SIL Open Font License), [Phosphor Icons](https://phosphoricons.com/) (MIT).
- **Game information and artwork:** [IGDB](https://www.igdb.com/), [PCGamingWiki](https://www.pcgamingwiki.com/) (data under [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/) unless otherwise noted), [SteamGridDB](https://www.steamgriddb.com/) (artwork belongs to its authors), Steam store user tags, and [HowLongToBeat](https://howlongtobeat.com/) through an unofficial client adapted from [howlongtobeatpy](https://github.com/ScrappyCocco/HowLongToBeat-PythonAPI) (MIT, Copyright (c) 2018 Michele). Provider terms are separate from this project's license; provider credentials live only in the metadata service, never in the app or this repository.

The full text of bundled third-party licenses is in `app/src/main/assets/licenses/`.
