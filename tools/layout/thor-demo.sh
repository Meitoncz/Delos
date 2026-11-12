#!/bin/bash
# AYN Thor demo on the two-display emulator (AVD delos-layout2, emulator-5556): the shell on the main 1920x1080 panel, the companion on the
# second 1240x1080 panel (Second display = Part of the device). Emulator only: refuses any other serial.
# usage: thor-demo.sh setup | home | settings | game | debug | stream | typing | shell-fake [serial]
#   setup     installs the debug APK and sets the two display preferences (run once, or after a reinstall)
#   home      Home with the focused game's details on the second display (D-pad moves the focus: the second panel follows)
#   settings  Settings: the description of the focused row on the second display, the list on the whole main panel
#   game      per-game settings, debug  Debug info
#   stream    the stream state on the second display (fake stats and the stream menu tiles), typing the keyboard state
# Drive the main panel with D-pad key events: adb -s emulator-5556 shell input keyevent 20 (down) / 19 / 21 / 22.
S=${2:-emulator-5556}; case "$S" in emulator-*) ;; *) echo "emulator serials only"; exit 2;; esac
ADB="$HOME/Library/Android/sdk/platform-tools/adb -s $S"; P=io.github.meitoncz.delos.debug; R="$(cd "$(dirname "$0")/../.." && pwd)"
start() { $ADB shell am force-stop $P; $ADB shell cmd uimode night yes >/dev/null; $ADB shell am start -n $P/com.limelight.shell.ShellActivity "$@" >/dev/null; }
case "$1" in
  setup)
    $ADB install -r "$R/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk" | tail -1
    $ADB shell am force-stop $P
    $ADB shell "run-as $P sh -c 'mkdir -p shared_prefs; cat > shared_prefs/${P}_preferences.xml'" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="display_second_role">built_in</string>
    <string name="display_main_screen">automatic</string>
</map>
XML
    $ADB shell settings put secure immersive_mode_confirmations confirmed; echo "setup done";;
  home) start --es preview_screen home;;
  settings) start --es preview_screen settings;;
  game) start --es preview_screen game_options;;
  debug) start --es preview_screen debug_info;;
  stream) start --es companion_fake stream;;
  typing) start --es companion_fake typing;;
  shell-fake) start --es companion_fake shell;;
  *) sed -n 2,12p "$0"; exit 2;;
esac
