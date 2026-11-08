# Contributing to Delos

Thanks for your interest. Delos is in active development, has no releases yet, and is maintained by one person ([@Meitoncz](https://github.com/Meitoncz)), so things move at a human pace.

## Reporting bugs and ideas

Use the issue forms: pick "Bug report" or "Feature request" when opening a [new issue](https://github.com/Meitoncz/Delos/issues/new/choose). Search existing issues first. For bugs, the form asks for your device, host software and Delos build (shown on the About page), and explains how to collect logs.

Please remove personal data (IP addresses, PC and account names) from logs and screenshots before posting.

Problems on the PC side belong to [Vibepollo](https://github.com/Nonary/Vibepollo), [Apollo](https://github.com/ClassicOldSong/Apollo) or [Sunshine](https://github.com/LizardByte/Sunshine).

## Building

Follow [Mac setup](docs/MAC_SETUP.md) or [Windows setup](docs/WINDOWS_SETUP.md) for the toolchain and submodules, then run:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

## Principles

- Controller first. Every screen must be usable with the D-pad or stick, A/B/X/Y and the two menu buttons. Touch is supported but is not the design target.
- No confirmation dialogs in normal flows.
- Visual changes start as a prototype or a discussion in an issue, before code.
- Keep the Java package `com.limelight`.
- Delos is GPL-3.0. By contributing you agree your work is released under the same license.

## Pull requests

Welcome without prior discussion:

- Bug fixes
- Device compatibility reports and fixes
- Translations (later, once the strings settle)

Please discuss in an issue first:

- New features
- UI changes
- Anything touching moonlight-common-c, which lives in a fork submodule

Keep PRs small and focused, and fill in the PR template.

## Code style

Follow the patterns already in the code. The shell UI is Kotlin with Compose; the streaming core is Java. Do not reformat code you are not changing.

## AI tools

The maintainer works with AI coding agents and reviews all changes. If you use AI tools for a PR, you are still responsible for understanding and testing what you submit.
