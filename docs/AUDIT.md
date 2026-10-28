# Phase 4 Audit – Artemis-specific code

Date: October 22, 2026. Codebase: `main` at `a3381ff3` (Artemis 20.2.6 + Moonlight 12.2 merged).

This is a dated audit record, not the current bug list or execution plan. Findings/counts may have
been resolved or superseded. Current work is in [GitHub Issues](https://github.com/Meitoncz/Delos/issues);
the later poster-provider boundary finding is [#1](https://github.com/Meitoncz/Delos/issues/1).

## Scope and method

The Artemis vs. Moonlight analysis ([ANALYSIS.md](ANALYSIS.md)) only covered code both projects share. This audit looks at what Artemis added on its own. After the Phase 3 merge, the Artemis delta is exactly `git diff moonlight/master main`:

- **Java:** 152 files, +18.5k / −1.3k lines. 46 new files (virtual keyboard controller, profiles, external display, SBS 3D, debug page, utils) and heavy changes to shared files (`Game.java` 2,700 → 4,392 lines, `ControllerHandler.java`, `MediaCodecDecoderRenderer.java`, `StreamSettings.java`).

What was done:

1. **Dependencies** – usage in code, APK size contribution, latest versions (Google Maven / Maven Central), known vulnerabilities ([OSV.dev](https://osv.dev)).
2. **Automated checks** – Android Lint (`lintNonRoot_gameDebug`) and `javac -Xlint:deprecation`.
3. **Security review** – manifest exposure, deep links, accessibility service, FileProvider, clipboard, network/crypto changes vs. Moonlight.
4. **Targeted manual review** – risky patterns across the Artemis delta (static activity references, swallowed exceptions, threads, hardcoded endpoints) and the code touched during the merge.

Not done: a line-by-line read of all 18.5k lines. Findings below are prioritized; the manual review continues file by file during the refactoring batches.

Severity: **High** = crash or security issue users can hit, **Medium** = real bug/risk in less common situations, **Low** = quality, maintenance, size.

## Summary

| # | Finding | Severity | Effort | Batch |
|---|---|---|---|---|
| D1 | SBS 3D feature = ~29 MB of the 44 MB APK (66 %) | Medium (size) | M | 1 – **decided: remove** |
| D2 | Unused dependencies MPAndroidChart and SearchPreference | Low | S | 1 |
| C1 | Lint `StringFormatInvalid` in Vietnamese (`120% sẽ`) – false positive, the summary is never formatted | Low | S | 1 |
| S1 | `art://launch` deep link starts a game on the PC without confirmation, triggerable from any web page (needs the host's random UUID) | Low–Medium | S | 2 |
| S2 | Accessibility service forwards **system-wide** key events to the PC while a stream is connected, even when Delos is not focused; over-broad permissions | Medium | S | 2 |
| C2 | `doStart` needs Android 8 but minSdk is 21 → crash on Android 5–7 | Medium | S | 3 (minSdk decision) |
| S3 | FileProvider exposes the root of every storage area | Low | S | 1 |
| S4 | Performance-log sharing e-mails data to the Artemis community address | Low (privacy) | S | 1 (decision) |
| X1 | Dead code, incl. `DeviceUtils.getMacAddress()` which would toggle Wi-Fi off | Low | S | 1 |
| X2 | Debug builds are minified (slow builds, harder debugging) | Low | S | 1 |
| C3 | Settings back handler not called on Android 13+ (language reset logic skipped) | Low | S | 3 |
| C4 | `DebugInfoActivity` ignores window insets (under system bars on Android 15+) | Low | S | 3 |
| Q1 | Deprecated APIs in Artemis-only files (34 warnings) | Low | M | 3 |
| Q2 | Code quality: `Game.instance` static singleton (46 uses), 24 empty `catch` blocks, 85 lines of Chinese + some Italian comments, messy decoder pacing code | Low | L | 4 |
| Q3 | 439 lint warnings, almost all in Artemis files | Low | M | 4 |
| B1 | Branding leftovers (`ArtemisApplication`, log tags, "Moonlight" in Moonlight translations) | Low | S | 4 |
| C5 | Custom refresh rate is saved with the locale's decimal separator (e.g. "59,94" in Czech/German) and cannot be parsed back | Medium | S | 4a |
| T1 | Unit tests no longer compiled since compileSdk 37 (Robolectric 4.16); CI does not run them | Low | S | 4a |

No finding can crash the app on the Odin today. **No known vulnerabilities** in any dependency, the network/pairing/crypto code is unchanged from Moonlight, and smart clipboard sync is opt-in (default off).

## 1. Dependencies and APK size

APK (`arm64-v8a`, debug): **44.2 MB**.

| Entry | Compressed | Used by |
|---|---|---|
| `assets/midas-midas-v2-w8a8.tflite` | 16.9 MB | SBS 3D (MiDaS depth model) |
| `lib/arm64-v8a/libopencv_java4.so` | 9.5 MB | SBS 3D |
| `classes.dex` + `classes2.dex` | 4.1 MB | app |
| `lib/arm64-v8a/libmoonlight-core.so` | 2.4 MB | streaming core |
| `libtensorflowlite_jni.so` + `_gpu_jni.so` | 2.8 MB | SBS 3D |
| BouncyCastle `pqc/legacy/picnic/*.properties` | 1.2 MB | unused post-quantum resources (also in Moonlight) |

**D1 – SBS 3D (decided: remove).** LiteRT, LiteRT GPU, OpenCV and the MiDaS model are used only by `utils/Stereo3DRenderer.java` and `utils/ReflectivePaddingInt8Minimal.java`. Entry points: `ui/StreamContainer.java` (render modes `MODE_AI_3D*`), the performance overlay in `MediaCodecDecoderRenderer.java`, render-mode preferences/strings (`render_mode_3d_sbs*`, `parallax_depth`), ProGuard keeps. Removing it saves ~29 MB and two large native libraries. The external display mode itself stays.

**D2 – unused dependencies.**
- `com.github.PhilJay:MPAndroidChart` – the chart overlay was reverted by Artemis (`0c0c53a2`, "Revert Charts – implementation is not ideal"); no code references it. Leftover strings (`category_perf_charts`, …) and a ProGuard keep.
- `com.github.ByteHamster:SearchPreference` – the settings search bar was removed by Artemis (`15122dd1`); no code references it, only `searchpreference_*` translation strings.

**Versions** (all without known vulnerabilities on OSV.dev):

| Library | Current | Latest stable | Note |
|---|---|---|---|
| LiteRT / LiteRT GPU | 1.4.0 | 2.3.0 | removed with D1 |
| OpenCV | 4.12.0 | 5.0.0 | removed with D1 |
| Gson | 2.13.1 | 2.14.0 | Artemis-only (profiles, key config) |
| AppCompat | 1.7.1 | 1.8.0 | Artemis-only |
| Material Components | 1.13.0 | 1.14.0 | Artemis-only |
| AndroidX Annotation | 1.9.1 | 1.11.0 | Artemis-only |
| CardView, Preference, RecyclerView | latest | – | |
| OkHttp | 5.5.0 | 5.5.1 | shared – follow Moonlight |
| BouncyCastle | 1.85.2 | 1.86 | shared – follow Moonlight |
| jmDNS | 3.6.3 | 3.6.4 | shared – follow Moonlight |
| jcodec, desugar_jdk_libs | latest | – | |

Recommendation: update Artemis-only libraries in batch 1; leave shared ones to the next Moonlight merge to keep `build.gradle` diffs small.

## 2. Security

**S1 – `art://` deep link without confirmation (Medium).** `AddComputerManually` is exported with a `BROWSABLE` intent filter for the `art` scheme (Apollo/Vibepollo QR/link pairing):
- `art://<host>:<port>?pin=…&passphrase=…` adds the host and pairs – this path already asks for confirmation ("Do you really want to pair with …?"),
- `art://launch?host_uuid=…&app_uuid=…` launches an app on a paired PC via `ShortcutTrampoline` – **without confirmation**.

Any web page (or app) can open the launch link. It only works with the `host_uuid` of an already paired PC (a random UUID that only the host's web UI and paired clients know; unknown hosts are rejected by `ShortcutTrampoline`), which limits the risk. Fix (batch 2): a confirmation dialog with the app and host name, **only for links coming from outside Delos**. Launching games inside Delos and from frontends such as ES-DE (`.art` files, a different `ShortcutTrampoline` path) stays one-click.

**S2 – keyboard accessibility service (Medium).** `KeyboardAccessibilityService.onKeyEvent()` consumes and forwards every key event to the PC whenever `Game.instance.connected` is true – including while Delos is in the background or in picture-in-picture, so typing in another app would go to the PC. The XML config also requests `canRetrieveWindowContent`, `flagRetrieveInteractiveWindows` and many event types it never uses. Fix: forward only while the stream window has focus; trim the config to key-event filtering. On Android 16.1+ the native keyboard capture from Moonlight makes the service unnecessary.

**S3 – FileProvider paths (Low).** `res/xml/provider_file_paths.xml` exposes `.` of external storage, external files, cache, external cache and internal files. The provider is not exported, so only explicitly shared URIs are reachable, but the code only needs the cache (performance log) and external cache (`export_settings`). Fix: narrow to those two paths.

**S4 – performance log sharing (Low, privacy).** "Share performance logs" e-mails device/stream statistics to `artemistics.logs@gmail.com` (Artemis community database, `tinyurl.com/artemis-performance`). Opt-in, but it sends data to a third party unrelated to Delos. Decision needed: keep, or remove the e-mail target and keep only local export.

**Checked and OK:** network, pairing and certificate code (`NvHTTP`, `PairingManager`, crypto providers) has no trust/verification changes vs. Moonlight (Artemis only added app-list fields and server commands); `PosterContentProvider` is unchanged Moonlight code; smart clipboard sync is opt-in and marks its own clips to avoid loops.

## 3. Correctness (Lint errors)

Lint: **10 errors, 439 warnings** – all errors in Artemis code.

- **C1 (Low, false positive):** `values-vi/strings.xml:180` `summary_resolution_scale_factor` contains "120% sẽ", which lint reads as the format `% s`. Verified: the text is a plain `android:summary` and `SeekBarPreference` only formats the numeric value, so it cannot crash. Escape the `%` (or mark `formatted="false"`) to silence lint.
- **C2 (Medium):** `AppView.java` (7×) and `PcView.java` (1×) call `doStart`, which requires API 26, without a version check → crash on Android 5–7 (minSdk 21). Fix together with the minSdk decision (see below), or add version checks.
- **C3 (Low):** `StreamSettings.onBackPressed()` is never called on Android 13+ because `enableOnBackInvokedCallback="true"`; the "language reset → restart" path is skipped. Move it to an `OnBackPressedCallback`.
- **C4 (Low):** `DebugInfoActivity` does not call `UiHelper.notifyNewRootView` → content under the system bars on Android 15+ with targetSdk 36.

## 4. Deprecated APIs

`javac -Xlint:deprecation`: **195 warnings**. 161 are in files shared with Moonlight (mostly Moonlight's intentional compatibility code for minSdk 21 – e.g. `SYSTEM_UI_FLAG_*`, `getDefaultDisplay`, `Handler()`); changing those would make upstream merges harder, so they stay unless a minSdk raise turns them into dead code.

34 are in Artemis-only files – these should be fixed:

| File | Warnings | APIs |
|---|---|---|
| `DebugInfoActivity` | 9 | `VIBRATOR_SERVICE`, `Vibrator.vibrate`, `InputDevice.getVibrator` |
| `PerformanceDataTracker` | 6 | `android.preference.PreferenceManager` |
| `DeviceUtils` | 6 | `Build.CPU_ABI`/`CPU_ABI2`, `WifiManager.setWifiEnabled`, `getConnectionInfo` (all dead code, see X1) |
| `ExternalDisplayControlActivity` | 5 | `onBackPressed`, `InputMethodManager.toggleSoftInput`, `Intent.getParcelableExtra`, `KeyEvent.ACTION_MULTIPLE` |
| virtual keyboard controller files | 5 | `VIBRATOR_SERVICE` / `Vibrator.vibrate`, `Resources.getDrawable`, `HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING` |
| `Stereo3DRenderer` | 2 | removed with D1 |
| `GameMenu` | 1 | `Handler()` without a `Looper` |

Related: `Game.java` hides the system bars with deprecated `SYSTEM_UI_FLAG_*` flags – move to `WindowInsetsController` together with the Phase 6 fullscreen work.

## 5. Dead code and resources

**X1 – unused methods** (no callers anywhere):
- `utils/DeviceUtils`: `getMacAddress()` (would call `setWifiEnabled(true)` then `setWifiEnabled(false)` – **turning Wi-Fi off** on old Android versions), `isDeviceRooted()`, `isAdbEnabled()`, `isDevelopmentSettingsEnabled()`, `isEmulator()`, `isTablet()`, `getAndroidID()`, `getABIs()`, `getSDKVersionCode()`.
- `utils/TrafficStatsHelper`: six `getAll{Rx,Tx}Bytes*()` methods.
- `LayoutSnappingHelper.adjustSpacing()`, `FileUriUtils.openUriForWrite()`, `KeyMapper.setKeyMapping()`.
- Lint `UnusedResources`: 67 (61 in Artemis files), plus chart/search leftovers from D2.

**X2 – debug builds are minified** (`minifyEnabled true` in `debug`, Moonlight does not). Makes debug builds slower and stack traces harder to read. Disable for debug.

## 6. Code quality

- **`Game.instance`** – a static reference to the streaming activity, used 46× from 5 classes (`ExternalDisplayControlActivity` 29×, keyboard controller, accessibility service, external display receiver). Cleared in `onDestroy`, but it is fragile (stale/null races, leaks). Same pattern in `ExternalDisplayControlActivity.instance`.
- **Swallowed exceptions** – 24 empty `catch (Exception|Throwable)` blocks in Artemis code (Moonlight: 1). At least log them.
- **Non-English comments** – 85 lines with Chinese text (mostly `PreferenceConfiguration`, `Game`, `ControllerHandler`, virtual controller) and Italian comments in the decoder pacing code. Translate to English.
- **Decoder frame pacing** (`MediaCodecDecoderRenderer`) – duplicated release branches, dead nested `SDK_INT >= 21` checks, unused `__ts` variables. Clean up without changing behavior.
- **`Game.java` size** – 4,392 lines vs. 2,700 in Moonlight. Extracting Artemis-only features (game menu actions, clipboard sync, external display, touch sensitivity) into helper classes would shrink the merge surface for future Moonlight updates.
- **Lint warnings (Q3)** worth fixing: `DefaultLocale` (18 – `String.format` without a locale breaks in e.g. Turkish), `HardcodedText` (80 – untranslatable UI text), `RtlHardcoded` (28), `UseCompatLoadingForDrawables` (13), `WifiManagerPotentialLeak` (3).

**B1 – branding leftovers:** `ArtemisApplication` class, `ArtemisProfile` log tags, "Generated by Artemis" in exported shortcuts, `artemistics_logs.txt`; translations that came from Moonlight say "Moonlight" where they mean the app.

## 7. Decisions (October 22, 2026)

1. **minSdk 29 (Android 10).** Fixes C2, makes pre-Android-10 compatibility branches dead code and drops the `root` flavor (Android ≤ 7.1 only).
2. **Performance logs (S4):** removed completely (logging, "Share performance logs", link to the Artemis community statistics). The in-game performance overlay stays. A better, local-only implementation can be built from scratch later if needed.
3. **ABIs: ARM only** (`arm64-v8a`, `armeabi-v7a`). No `x86`/`x86_64` builds. The prebuilt x86 native libraries from Moonlight stay in the tree to keep upstream merges conflict-free.
4. **`art://` links (S1):** keep, with a confirmation dialog for launch links coming from outside the app (pairing links already ask). General rule: no confirmation dialogs in normal in-app flows; security prompts for external input are fine.

## 8. Historical remediation batches

The original batches below preserve historical evidence only. Current refactoring, cleanup and
confirmation work is tracked on GitHub, not selected or marked complete from this dated list.

- **Batch 1 – quick wins, low risk (done):** removed SBS 3D (D1) and the performance logging feature (S4), dropped unused dependencies (D2), silenced the Vietnamese lint false positive (C1), narrowed FileProvider to `export_settings/` (S3), removed dead code (X1), no R8 for debug builds (X2), ARM-only ABIs, Gson 2.14.0 and AndroidX Annotation 1.11.0. Debug APK: 44.2 MB → 19.4 MB.
- **Batch 2 – security:** confirmation dialog for external `art://launch` links (S1); accessibility service only forwards keys while the stream (or external display controller) has focus, config trimmed to key filtering (S2).
- **Batch 3 – platform (done):** minSdk 29 and the `root` flavor no longer built (C2; `src/root` and the evdev native code stay untouched for upstream merges), `OnBackPressedCallback` in `StreamSettings` and `ExternalDisplayControlActivity` (C3), insets in `DebugInfoActivity` (C4), no deprecated APIs left in Artemis-only files (Q1; new `utils/DeviceVibrator` helper; deliberate `@SuppressWarnings` only where no replacement exists), AppCompat 1.8.0 and Material 1.14.0. javac deprecation warnings: 195 → 160 (the rest is shared Moonlight code).
- **Batch 4 – code quality / refactoring**, split into steps:
  - **4a – mechanical cleanup (done):** all Chinese/Italian comments translated (Q2); empty `catch` blocks in Artemis code log a warning, per-frame ones are documented as best effort; branding (B1): `DelosApplication`, `Profiles:` log tag, `.art` header, clipboard marker, "Moonlight" → "Delos" in ~20 translations where it means the app (the "Moonlight Internet Hosting Tool" stays), the name "Delos" is never inflected; refresh rate formatting fixed (C5); unit tests fixed (T1: Robolectric 4.17, outdated tests updated – 51/51 pass) and now run in CI.
  - **4b – decoder pacing (done).** Findings: (1) `Game` overwrote the user's frame pacing with Balanced on every stream start, so Latency / Cap FPS / Smoothness never applied; (2) a "latest only" block at the top of the render loop rendered the newest frame immediately whenever LFR was off, so the effective default was Latency; (3) the Artemis drop logic for non-Balanced modes therefore never ran; (4) "Tight Vsync" was never read and the decoder ignored it; (5) the C2 "watchdog" sat after the render loop, so it only ran once at stream end and flushed the codec; (6) decode-time entries of dropped frames were never removed; (7) frames rendered by the fast path were not counted in the overlay stats; (8) `SurfaceView.setFrameRate` via reflection never resolves (the method only exists on `Surface`, which the decoder already uses). Changes (decided with the user): frame pacing is respected again with **Latency as the default** (same behavior as before); Latency keeps the immediate "newest frame" path; Balanced unchanged, LFR = Balanced variant; Cap FPS / Smoothness use Moonlight's proven logic; Tight Vsync, the dead watchdog, unused adaptive state and the reflection removed; stats entries pruned and all rendered frames counted. Render loop −223 lines.
  - **Historical 4c topic:** current responsibility/ownership work is recorded in [#29](https://github.com/Meitoncz/Delos/issues/29) and [#30](https://github.com/Meitoncz/Delos/issues/30).
  - **Historical 4d topic:** the code-audited remaining confirmations are recorded in [#8](https://github.com/Meitoncz/Delos/issues/8).
