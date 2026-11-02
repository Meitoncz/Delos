# Delos – context for Claude Code

Android client for streaming games from **Vibepollo** (a fork of Apollo/Sunshine). A fork of **Artemis** (`ClassicOldSong/moonlight-android`, branch `moonlight-noir`), which is itself a fork of **Moonlight Android**. The project was formerly called **Vibetemis**.

- The user is **not a programmer**. Talk to them **in Czech**, explain clearly, propose steps and ask before big or irreversible actions (force push, deleting branches, releases).
- Code, comments, commit messages, PR descriptions and all repo documentation are **in English**.
- The app name **"Delos" is never translated or inflected** in any language (no case endings, no possessive `'s`); rephrase instead, e.g. "aplikace Delos", "the Delos USB driver".
- **Agreed work runs end to end without asking at each step** (agreed 2026-10-23): feature branch → local build, tests and lint → test on the Odin → commit → fast-forward `main` → push with `[skip ci]` → delete the feature branch, then report. Run GitHub Actions **only when the user explicitly requests CI**. Ask first for anything outside the plan, open design decisions, force pushes, deleting other branches, rewriting history and releases.
- Development happens on **Windows** (same machine as Vibepollo). Write commands for PowerShell, build with `gradlew.bat`.
- **Task tracking:** [GitHub Issues](https://github.com/Meitoncz/Delos/issues) and the
  [private Delos Project](https://github.com/users/Meitoncz/projects/1) are the only task tracker.
  All unfinished work belongs there, including ideas, research, deferred/conditional plans and
  recurring maintenance. Do not create or maintain Markdown TODOs, roadmaps or local task indexes.
  Technical specifications and dated evidence may remain in Markdown, but never duplicate live
  status or a task queue. Completed/historical work must not become new open issues.
- Read the latest issue description, comments, Project fields and dependencies before starting.
  Record decisions, exercised checks and the completion commit in English; close only when the
  actual criteria are met. An issue is not automatic approval to implement or redesign.
- Workflow: `docs/GITHUB_WORKFLOW.md`. UI specification and preserved architecture: `docs/GUI.md`.
  Approved visual reference: `docs/prototype/delos-gui.html`. Diff analysis: `docs/ANALYSIS.md`.
- Environment: `docs/WINDOWS_SETUP.md`.
- Priority: **Moonlight updates first** (common-c → toolchain → merge `moonlight/master` → OpenSSL/opus), **UI and custom features after that**.

## Multi-agent workflow

Claude is the lead agent and Codex a helper for smaller scoped tasks (agreed 2026-10-30). Delegate through
herdr with a task file; helpers use their own git worktree and branch, never run Gradle/ADB, push or merge,
and report back with `herdr agent prompt`. The lead reviews every helper commit, builds, tests, lints,
installs on the Odin and integrates. Agent commits have the agent as author (`Claude <noreply@anthropic.com>`,
`Codex <noreply@openai.com>`), the user as committer, messages end with `[skip ci]`; issues and comments
created with `gh` end with `Created by <agent> via gh as Meitoncz`. Full rules: `AGENTS.md`, section
"Multi-agent workflow".

## Remotes

- `origin` – `Meitoncz/Delos` (private)
- `artemis` – `ClassicOldSong/moonlight-android` (`moonlight-noir`)
- `moonlight` – `moonlight-stream/moonlight-android` (`master`)

When merging Moonlight, do each phase in its own branch (`update/common-c`, `update/toolchain`, `update/moonlight-12.2`) and merge into `main` only after a successful build.

## Structure

- `app/src/main/java/com/limelight/` – Java code; `Game.java` = streaming activity, `binding/input/` = input, `binding/video/` = decoder
- `app/src/main/jni/moonlight-core/` – native code; prebuilt OpenSSL and opus; submodule `moonlight-common-c` → `Meitoncz/Delos-common-c` (public fork of ClassicOldSong's Apollo fork, merged with upstream; inside the submodule `origin` = Delos-common-c, `artemis` = ClassicOldSong, `upstream` = moonlight-stream). Changes to it are pushed to the fork first, then the pin is updated here.
- Build: Gradle, `app/build.gradle`, NDK via `Android.mk`
- App identity: `applicationId` `io.github.meitoncz.delos` (debug `.debug`); the Java package stays `com.limelight` to keep upstream merges small

## Devices

- Test device: **AYN Odin 2 Portal Max** (Android 13, 1920×1080 landscape), wireless ADB – find it with `adb mdns services`, install the `arm64-v8a` APK
- Testing uses both the Odin and the emulator (AVD `Odin2_Portal_Max`, 1920×1080, start with `emulator -avd Odin2_Portal_Max -gpu host`).
