#!/bin/bash
# Smoothly changes the EMULATOR's logical screen size (wm size) so a running Delos screen re-lays out live, like dragging a browser window:
# 16:9 -> 21:9 -> 4:3 -> 1:1 -> tall, then resets. Watch the emulator window. Usage: sweep.sh [delay seconds, default 0.6]
E="$HOME/Library/Android/sdk/platform-tools/adb -s emulator-5554"; T=${1:-0.6}
step() { $E shell wm size "$1x$2" >/dev/null; sleep "$T"; }
for w in 1920 2100 2300 2520; do step $w 1080; done
for w in 2400 2200 1900 1700 1500 1440; do step $w 1080; done
for h in 1200 1320 1440; do step 1440 $h; done
for w in 1300 1200 1100 1080; do step $w 1080; done
for h in 1200 1320 1440 1600; do step 1080 $h; done
$E shell wm size reset
