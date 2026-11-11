# Diagnostics: logging, crash capture and problem reports

Delos keeps a small log of what it did, on the device, so that a problem can be reported with facts instead of memories.
**Nothing leaves the device unless the user starts it**: there is no analytics, no automatic upload, no account and no token
anywhere in this feature. The issue is [#71](https://github.com/Meitoncz/Delos/issues/71).

## What is logged

A rolling log in the app's private storage (`files/diagnostics/diag-0.log` and `diag-1.log`):

- **Two files of at most 1 MiB each**. When the current file would grow past that, the older one is deleted and the current one becomes the
  older one, so the log holds between 1 and 2 MiB of the newest lines (a few thousand lines).
- **INFO and above in a release build, DEBUG as well in a debug build.** A line is `2026-11-10 12:34:56.789 I/Stream [main] message`, in the
  device's time zone.
- **A writer thread** (`DiagLogWriter`, minimum priority) does all disk work. `DiagLog.log` formats one line and offers it to a queue of 1024;
  when the queue is full the line is dropped (a later line says how many) instead of blocking the stream, render or input thread. Before
  `DiagLog.init`, or with the switch off, a call does nothing.
- **Switch:** Settings → About → **Keep diagnostic logs** (default on, device-wide, key `diag_logs_enabled` in the default preferences). Off stops
  new lines, including the crash file; what is on the disk stays until it is cleared (Debug info → Clear diagnostic logs).

Sources, all into the same sink:

| Source | What |
|---|---|
| `LimeLog` (`info` / `warning` / `severe`) | the existing Java log: connection, display mode, decoder recovery and failure, the client id line, pairing, ... (tag `Lime`) |
| native log callback | `BridgeClLogMessage` (moonlight-common-c) and the PyroWave renderer / YUV copy / thread tuning lines, through `DelosDiagLog` in `callbacks.c` and `MoonBridge.bridgeNativeLog`; limited to 60 lines per second, the text crosses JNI as bytes (never `NewStringUTF` on foreign text) |
| stream events | stage starting, stream started and stopped, **negotiated codec / chroma / HDR, size, rate and bitrate** (`StreamEvents`), connection quality changes, the stream result (`Stream ended: ...`) |
| `DelosPyroDiag` | the PyroWave start line and **one periodic line every 30 s** (logcat of a debug build still gets every one), UDP drop counters, SGSR overload and optical-flow governor events, the native swapchain and present-mode lines |
| toasts | every **warning and error** toast of the shell and of the stream window (tag `Toast`) |
| displays | one line per Android display at process start and one per display added, changed or removed (tag `Display`; `DisplayInventory`): id, name, size, density, refresh rate, state, flags and Delos's classification with its reason. The name is the system's device name ("Built-in Screen", "HDMI Screen", "Overlay #1"); a wireless display's name is replaced by "wireless display". See docs/DUAL_SCREEN.md |
| crashes | the uncaught exception with its stack trace (tag `Crash`), a decoder crash that takes the app down, the `Problem` lines of the offer |

## What the scrubber removes

`LogScrubber` (pure, `LogScrubberTest`) runs over the **whole text** of every report, summary and prefilled issue address after it is built
(the file on the disk stays raw: it is private to the app). It errs on the side of removing: a harmless version number with four dotted
numbers is replaced too. Names and addresses get a number, the same for the same value within one report (`<ip-1>`, `<pc-2>`), so the log
stays readable.

| Rule | Replaced by |
|---|---|
| Names of the PCs: their own and the ones given in Delos, read from the stored PC list (and each profile's `ui_pc_name_*`) at export time; whole words, case-insensitive, at least 3 characters | `<pc-N>` |
| Addresses and host names stored for the PCs (`ComputerDetails`) | `<host>` |
| Profile names and the device's own name (`Settings.Global.DEVICE_NAME`) | `<name>` |
| The client id (`uniqueid` file), the Android id, profile ids, PC uuids | `<id>` |
| The SteamGridDB API key (known value) | `<secret>` |
| IPv4 addresses (every octet 0-255) and IPv6 addresses (full, compressed, zone, IPv4-mapped, bracketed) | `<ip-N>` |
| MAC addresses (`:` or `-`) | `<mac-N>` |
| URLs: user info removed, host replaced (public services such as steamgriddb.com and github.com are kept), **every query value replaced** except small numbers, resolutions and booleans (`?devicename=<redacted>&uniqueid=<redacted>`), fragment dropped | the same URL with `<ip-N>` / `<pc-N>` / `<host>` |
| `key=value` or `key: value` for ids, tokens, secrets, `password`, `pin`, `authorization`, `serial`, `imei`, `hostname`, `user name`, `rikey`, ... and `Bearer` / `Basic` credentials | the value becomes `<redacted>` |
| Wi-Fi names (`SSID`, `BSSID` up to the end of the line or comma) | `<redacted>` |
| `UnknownHostException: x`, `Unable to resolve host "x"` | `<host>` |
| UUIDs, hexadecimal runs of 16 characters or more (client ids, fingerprints, keys), tokens of 32 or more letters, digits, `-` and `_` | `<uuid>`, `<hex>`, `<token>` |
| File paths with a user name (`/Users/x`, `/home/x`, `C:\Users\x`) and external volume ids (`/storage/ABCD-1234`) | `<user>`, `<volume>` |
| E-mail addresses; host names ending in `.local`, `.lan`, `.home`, `.internal`, `.ts.net`, `.duckdns.org`, ... | `<email>`, `<host>` |

`LogScrubberTest` covers each rule with lines as the app logs them (the parallel poll line, the `applist?devicename=...&uniqueid=...` request, the
`UID is now:` line, IPv6 with a zone, WoL MAC addresses, SSIDs) and that times, ports and ordinary lines stay unchanged and that scrubbing twice
changes nothing more. The device block (`DeviceInfo`) is built from a fixed list of facts and a fixed list of settings
(`StreamSettingsSnapshot.KEYS`): never the whole preference file.

## The device block

Model, manufacturer and device name, Android release / API / security patch / build id, SoC (`Build.SOC_MODEL` on Android 12+, else the hardware
name), ABI, GPU (the GL renderer the stream window last stored; the Vulkan device name is in the PyroWave lines of the log), the default
display's size, refresh rate and density, the kind of network (Wi-Fi, Ethernet, ...; never its name), locale, the UTC offset (not the region),
the Delos version and build type, and the stream settings: codec, resolution, frame rate, bitrate, PyroWave chroma, HDR, frame pacing and the
upscaler (mode, sharpness, supersampling). No PC names, addresses, tokens, API keys or game list.

## Sharing from Debug info

Settings → About → Debug info → **Diagnostics** (the first section after the device rows):

- **Diagnostic log**: an info row showing the size and line count of exactly what Share sends (for example `212 KB, 1,904 log lines`); the panel on
  the right previews the header, the device block and the newest 40 lines of the **scrubbed** text, built when the screen opens and after Clear.
- **Share diagnostic log**: writes that text to `cache/diagnostics/delos-diagnostic-<time>.txt` (older shared files are removed first), and opens
  the Android share sheet through the app's `FileProvider` (`provider_file_paths.xml`, `cache-path diagnostics`), with read access for that one file.
- **Copy diagnostic summary**: the device block and the last 100 lines, scrubbed, to the clipboard (a toast confirms it).
- **Clear diagnostic logs**: deletes both log files and the shared copy.
- **Trigger test crash**: only in debug builds (`BuildConfig.DEBUG`, the row is never added otherwise).

All of it is built on a worker thread from the PC list as it is at that moment.

## Crash capture and the report offer

**Markers** (all in `files/diagnostics`, nothing else is kept):

- `crash.txt` + `crash.marker`: the **uncaught-exception handler** (`CrashReporter`, chained to the previous handler, which still ends the process)
  logs the exception, flushes the log and writes `crash.txt` (exception, thread, process, the stack trace and the **last 200 log lines**, raw; format
  and parser in `CrashFile`), then `crash.marker`. A file without its marker is an interrupted write and is ignored. With the switch off nothing is
  written.
- `session.marker`: `foreground|background pid startedAt updatedAt`, written when the first activity starts and when the last one stops. A process
  that ended while the state was `foreground` ended abnormally. This is the only signal on **API 29**; from API 30 it is ignored.
- `ActivityManager.getHistoricalProcessExitReasons` (API 30+): `REASON_CRASH_NATIVE` (native crash), `REASON_ANR`, `REASON_CRASH` and
  `REASON_INITIALIZATION_FAILURE` of this app's main process, with the system's description (for example `signal 11 (SIGSEGV)`).

**Detection** (`ProblemDetector`, pure, `ProblemDetectorTest`), at the first start of the shell after the problem:

| Case | Result |
|---|---|
| nothing new | no offer |
| Java crash marker | `JAVA_CRASH` with the summary `IllegalStateException: message (at com.limelight.Foo.bar)` and the stack trace |
| native crash or ANR exit reason newer than the last offer | `NATIVE_CRASH` / `ANR` with the system's description |
| a `REASON_CRASH` exit of the same process as the crash file | the same crash (the file wins), never a second offer |
| a `REASON_CRASH` without a crash file | `JAVA_CRASH` with the system's description |
| API 29, previous process left `foreground` and no crash file | `UNCLEAN_EXIT` |
| other exit reasons (user, low memory, update), a process that ended in the background | no problem |
| several at once | one offer: Java crash, then native, ANR, unclean exit; the newest of a kind; every source looked at counts as offered |
| signals older than 7 days | dropped |
| a stream result `FAILED_TO_START` / `CONNECTION_LOST` (the secondary entry point; the result screen is unchanged) | `STREAM_FAILED`, at most one offer per 30 minutes |

**Once per problem:** the ledger (`diag_problems` preferences: the newest crash time, exit time and session start offered, and the last stream offer)
is stored at detection, so a problem is never offered again, whether it was reported, dismissed or ignored.

**The card** (`ProblemOfferCard`, docs/GUI.md): top right of the start screen and the home screen, below the toast's place, only when no layer,
portal, launch loader or stream result is up. It says what happened in one sentence, the one-line summary and: *Nothing is sent automatically.
Report opens GitHub in your browser with a draft for you to check, and saves a cleaned log file to Downloads for you to attach.* Focus starts on
**Report this problem** (A); left/right moves to **Dismiss**; **B dismisses** from anywhere while the card shows. Touch works too. There is no scrim
and no confirmation: the rest of the screen stays usable. `Problem` carries typed facts (kind, subject, detail, whether only the exit record told of it);
the card text is built in the user's language from `diag_problem_*` (`problemSentence`, `problemSummary` in `ProblemOfferCard.kt`), the subject (exception,
signal, system description) shown as it is. `Problem.summary`, the issue draft and the report stay English for the developers.

**Report this problem:**

1. builds the scrubbed report (header, device block, a `Problem` section with the full stack trace, the whole log) and the prefilled address;
2. saves the report to **Downloads** as `delos-diagnostic-<time>.txt` through `MediaStore.Downloads` (no permission needed); a toast says
   `Log saved to Downloads as ... Attach it to the issue.` (or that it could not be saved and that Debug info can share it);
3. after 0.9 s opens the **GitHub new-issue page in the browser** with the `bug_report.yml` form filled in through query parameters named after
   the field ids: `title`, `what-happened` (what happened, the file name to attach, a request to add what the user was doing), `area` (`Other`, or
   `Pairing/connection` for a failed stream), `device`, `build`, `stream-settings`, `logs` (the summary and the first 12 stack frames), plus
   `labels=from-app` (GitHub applies a label from the address only for people who may label issues; the **`from-app` label must exist** in the repository);
4. resolves the problem (the crash files are deleted).

The address stays below 6000 characters: `logs` is cut first (a tenth at a time, with a note that the file has the rest), then `what-happened`; every
free-text field passes the scrubber; a cut is never inside a percent escape (`IssueUrl`, `IssueUrlTest`, `ProblemIssueTest`). The file is attached by the
user in the browser: drag it into the **What happened?** box (a textarea with `render:` cannot take files, so the log box at the end cannot).

**Dismiss** resolves the problem and returns the focus to the screen.

## How to test

Automated: `./gradlew :app:testDebugUnitTest --tests 'com.limelight.diag.*'` (scrubber, writer, report, detector, crash file, issue address, settings snapshot).

On a device with a debug build (never while streaming):

1. **Log and share:** Settings → About → Debug info → Diagnostics: the size row shows a count; focus it for the preview; **Share diagnostic log**
   opens the share sheet; **Copy diagnostic summary** copies; **Clear diagnostic logs** empties (the row reads `Reading…` and then a small count).
2. **Switch:** Settings → About → Keep diagnostic logs off, use the app, the size stays; on again.
3. **Crash and offer:** Debug info → **Trigger test crash**. Delos closes; start it again: the card appears on the start or home screen, once. A reports
   (a file in Downloads, the browser opens the prefilled form), B dismisses. Starting again shows nothing.
4. **Failed stream:** with the PC off or a wrong port, start a game: the result screen is as before; after Back to library the card follows (not again
   within 30 minutes).
5. **Native crash:** with the app in the foreground, `adb shell run-as io.github.meitoncz.delos.debug kill -ABRT $(adb shell pidof io.github.meitoncz.delos.debug)`
   (expected to show as a native crash on Android 11+, and as an unclean exit on API 29). An ANR is offered the same way when the system reports one.
6. `adb shell run-as io.github.meitoncz.delos.debug ls files/diagnostics` lists the log files, `crash.txt`, `crash.marker` and `session.marker`.
