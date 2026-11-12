# Controller state, gyro and the stuck release

What was reported, what the client does with a button release, what is proven and what is not, and the diagnostics that are
always on. Related: [RUMBLE.md](RUMBLE.md) (the vibration side), [DIAGNOSTICS.md](DIAGNOSTICS.md) (the rolling log the lines
also go to).

## The report (AYN Odin 2 Portal Max, Android 13, streaming to a PC)

With the gamepad motion sensors on, the client announces the controller to the host as `LI_CTYPE_UNKNOWN` with accelerometer and
gyro capabilities (`ControllerHandler.InputDeviceContext.sendControllerArrival`, the `type != LI_CTYPE_PS && sensorManager != null`
branch), and the PC then emulates a PlayStation (DualShock 4 / DualSense) pad; with the sensors off the pad is an Xbox One pad.
With the sensors on, after a slingshot is fired in a game (hold RB or the trigger to draw, release to fire):

* (a) the vibration does not stop; the last host rumble event before the stuck vibration was non-zero (low 17, high 0) and no
  zero event followed (the client stops on every host zero, so the zero was never sent);
* (b) the game keeps treating the gyro as active after the button was released;
* (c) both end only when the camera stick is moved.

With the sensors off none of this happens.

## The cause of (a): a host bug, found 2026-11-12

The client was not at fault. The same slingshot shot gives these host rumble events (low/high, high byte) on the Odin:

| Pad the PC emulates | Events after the shot | Ends with |
|---|---|---|
| Xbox | `78/33 33/19 05/11 00/00 0c/00 17/00 0f/00 00/00` | `00/00` |
| DualSense | `79/33 33/1c 06/13 0f/00 17/00 19/00 18/00 11/00` | `11/00`, then nothing |
| DualShock 4 (Vibepollo Virtual Gamepad) | `79/33 33/1c 06/13 00/00 0f/00 17/00 19/00 18/00 11/00 00/00` | `00/00` |

SDL (and so Steam Input and most games) stops rumble on a DualSense with an output report that has the rumble valid bits
cleared; the host's virtual DualSense updated the motors only when a bit was set, kept the last amplitude and sent nothing.
Upstream reports: Nonary/Vibepollo#708 (reported fixed in the 2.2.0 nightly) and Nonary/libvirtualgamepad#10 (open at the
time of writing). Until the host has the fix, select a DualShock 4 profile on the host (globally, or as a per-game override).
A re-send of the unchanged controller state from the client was tried as a setting and removed: it has no effect.

(b) and (c) were not tied to the client: gyro smoothing settles within about 0.6 s at strength 100, and the client sends
gyro samples continuously like a real pad. Whether the game keeps aiming with the gyro after the release is the game's choice.

## What the client does with an RB release (read from the code, not yet measured)

1. Android delivers `KEYCODE_BUTTON_R1` up to `StreamKeyHandler` and then `ControllerHandler.handleButtonUp`
   (`ControllerHandler.java:2649`). It clears `RB_FLAG` in the context's `inputMap`, applies the emulation rules and calls
   `sendControllerInputPacket` (`:1481`) on every path that returns `true` (an R2 key up is swallowed on purpose while the analog
   trigger axis is in use; the axis event sends instead).
2. `sendControllerInputPacket` fuses the state of every context with the same controller number (OR of the buttons, per axis
   the larger magnitude) and ends in `sendControllerState` (`:394`), the one place a state packet is handed to the connection.
   The `ControllerSend` line is written there, just before `NvConnection.sendControllerInput` → JNI → `LiSendMultiControllerEvent`.
3. In moonlight-common-c (`src/InputStream.c`) the state packet is a reliable ENet packet on channel `0x10 + controller`
   (`sendControllerEventInternal`, `:998`, flags at `:1076`). A packet with the same button flags as one still waiting in the
   queue is merged into it (`:1045-1058`, the latest axes win); a changed button flag always starts a new packet, so a
   release is never merged into the press.
4. The single `InputSend` thread (`:323`) sends the queue in order. A state packet waits at most 10 ms in
   `sendMessageEnet` (`ControlStream.c:714`) for the datagram to leave; a lost datagram is retransmitted by ENet.
5. Motion events are unreliable packets on a separate channel, `0x20 + controller`
   (`LiSendControllerMotionEvent`, `:1527`; only a gyro `0,0,0` is reliable), merged per sensor while queued (`dirty` flag). They
   share the `InputSend` thread with the state packets but not the channel, so they cannot reorder, replace or hold back
   a state packet on the wire; a state packet waits behind at most the one motion packet being sent.
6. The sensor listeners run on the main thread at up to 200 Hz (`createSensorListener`, `:2442`; Android delivers to the
   registering thread's looper, here the main one, where the key events are handled as well). Each sample costs one JNI call;
   input events are queued, never dropped, by the looper.

What gyro changes in the client is therefore only: the announced type and capabilities, `needsClickpadEmulation`, and the sensor
traffic. `needsClickpadEmulation` turns Select+LB (inputMap exactly BACK|LB, or BACK pressed within 100 ms of an LB release)
into the touchpad flag (`:3087`, release at `:2878`); it cannot touch RB.

**No code path was found in the client that drops, delays or reorders a button release when the sensors are on.** The cause is
therefore not proven from the client code. The chain above is why the diagnostics below log what is handed to the connection:
they decide between the client and the host with one run.

## Facts read on the host side (upstream Sunshine, `LizardByte/Sunshine` master, 12 Nov 2026; the Vibepollo fork was not inspected)

These are the places a PlayStation emulation differs from an Xbox one. They are *not* proven to cause the report.

* `src/platform/windows/input.cpp`, `ds4_buttons`: the digital L2/R2 button of the emulated DS4 is set whenever the analog trigger
  value is greater than 0 (no threshold). An Xbox 360 pad has no digital trigger button; games read the analog value with their
  own threshold. A trigger value that stays at 1 or 2 after release would keep a PlayStation-aware game in "R2 held". The client
  zeroes trigger values below the device's dead zone (at least 13 %, `triggerDeadzone`), so this needs a value above it.
* `ds4_update_ts_and_send`: every state or motion packet re-sends the whole DS4 report, and the host repeats it every 100 ms.
  The report is one structure shared by the state, motion and repeat tasks.
* `src/input.cpp`, `passthrough_next_message`: queued packets are batched; a change of button flags ends a batch, so a
  release is not merged away on the host either.

## Diagnostics (always on, cheap)

Every line starts with one of four words; the pattern for `.gradle-agent/vibe-measure.py` and `adb logcat` is

```
ControllerSend|ClickpadEmu|InputDeviceContext|MotionSend
```

The lines go through `LimeLog.info`: logcat (use `logcat -v epoch`) and the rolling diagnostic file. Each line is built inside
a `try/catch`; none reads a platform setting or calls an API that can throw `SecurityException`.

| Line | When | Meaning |
|---|---|---|
| `ControllerSend #n buttons=0x0200 lt=0 rt=0 lx=0 ly=0 rx=0 ry=0 pad 0` | a state packet is handed to the connection and its buttons or triggers differ from the previous one of that pad | what the host is told; `lt`/`rt` are the unsigned bytes. `#n` counts every call (all pads), so a gap is the number of packets left out |
| the same, ending in `(+N stick-only packets not logged)` | a packet that differs only in the sticks, at most every 250 ms | a stick held in motion would fill the 1 MiB rolling file otherwise; a button or trigger change is never left out |
| the same, ending in `identical=50` | every 50th call identical to the previous packet | counts identical calls |
| `InputDeviceContext arrival pad N 'name' (device id): detected type X, reported to the host as Y, capabilities 0x…, supported buttons …, motion sensors from built-in device / input device / none, clickpad emulation true/false` | a controller is announced to the host | proves what type the PC is told and where the sensors come from |
| `InputDeviceContext migrated device id 'name' (context a -> b) controller N: held before the change buttons=0x… lt rt, buttons carried=0x… lt rt [(held state NOT carried over)], motion sensors old -> new` | Android reports the input device as changed | see "Latent defects" |
| `ClickpadEmu injected TOUCHPAD and swallowed BACK+LB on pad N (buttons now 0x…)` | the Select+LB combo became the touchpad flag | the only way the emulation alters button state |
| `ClickpadEmu released the emulated TOUCHPAD on pad N (BACK down …, LB down …, buttons now 0x…)` | the combo was let go | |
| `MotionSend gyro 198/s accel 199/s pad 0 (sensor callbacks gyro 200/s accel 199/s, refused gyro 0 accel 0)` | once per second while a motion listener is registered | motion events handed to the connection per second; callbacks are the sensor's own rate; `refused` are events the connection did not accept (host without motion support, queue full). The connection merges queued events, so the wire rate is at most this. A silent sensor shows as `0/s` |

Tests: `ControllerHandlerSendTest` (the real handler: the press and release packets and their `ControllerSend` lines),
`ControllerSendLogTest`, `MotionSendRateTest`.

### Reading a run

Press RB, hold, release, wait for the stuck vibration, move the stick. In the lines around the release:

| What the lines show | It means |
|---|---|
| a `ControllerSend` line with `0x0200` cleared (and `rt`/`lt` 0) exists at the release, then nothing is wrong on the wire | the client sent the release; the host or the game lost it |
| no line with the bit cleared between the press and the stick move | the client did not send it: look for `InputDeviceContext migrated` / `ClickpadEmu` lines just before |
| `lt`/`rt` do not return to 0 in the lines, or the first line after the stuck period shows a different `rt`/`lt`/d-pad bit | a stale trigger or d-pad value in the client: any stick `MotionEvent` rewrites the sticks, the triggers and the d-pad from the event (`handleAxisSet`, `:1969`) and sends the full state, which is also what "ends when I move the camera stick" looks like |
| `InputDeviceContext migrated … (held state NOT carried over)` between press and release | see "Latent defects" |
| `MotionSend` at 0/s or far below the callback rate while the sticks and buttons are fine | the sensor traffic is starved; it still cannot hold a state packet back (separate channel) |
| the native line `Control message took over 10 ms to send` around the release | the reliable packet was slow to leave (Wi-Fi); `peer->roundTripTime` and the loss are in the line |

## Latent defects seen while reading (not the cause of the report as far as the code shows; not changed)

* `InputDeviceContext.migrateContext` (`:3606`, called from `onInputDeviceChanged`, `:268`) copies the controller number, sensors,
  LED and `needsClickpadEmulation`, but **not** the held state (`inputMap`, triggers, sticks, the `…AxisUsed` flags,
  `emulatingButtonFlags`, the learned `hasSelect`/`hasMode`). A button held while Android reports a device change is forgotten by
  the client: the host keeps it pressed until the next packet, which then releases it early. The release itself is still sent
  (`handleButtonUp` always sends), so this is an early release, not a stuck one. The `InputDeviceContext migrated` line shows
  `held state NOT carried over` when it happens; carry the state over only if that line shows up in a bad run.
* `maxByMagnitude(byte, byte)` (`:1319`) takes the magnitude of a trigger as a *signed* byte, so a value from 128 to 255 looks small
  next to another context's value. It only matters when two input devices share a controller number.
* moonlight-common-c `sendControllerEventInternal` (`InputStream.c:1130-1155`) stores a new holder in
  `currentQueuedControllerPacket` before it is queued and does not clear the entry when the queue is full (150 packets) and the holder
  is freed. A later packet with the same buttons would then be merged into freed memory and never sent. Unreachable with the motion
  merging above (at most one queued motion packet per sensor), but a real defect if the queue ever fills.
