#!/bin/bash
# Every preview screen in every shape on the EMULATOR, checking only for crashes (no screenshots): finds layouts that throw (negative padding, bad constraints...).
# Usage: crashcheck.sh [adb serial, default emulator-5554]. Refuses anything that is not an emulator serial.
S=${1:-emulator-5554}; case "$S" in emulator-*) ;; *) echo "emulator serials only"; exit 2;; esac
ADB="$HOME/Library/Android/sdk/platform-tools/adb -s $S"; P=io.github.meitoncz.delos.debug
D="$(cd "$(dirname "$0")" && pwd)"; bad=0
while IFS= read -r line; do
  line="${line%%#*}"; set -- $line; [ $# -ge 2 ] || continue; name=$1; shape=$2; cut=${3:-}
  while IFS='|' read -r id args wait; do
    id="$(echo "$id" | xargs)"; [ -n "$id" ] || continue; [[ "$id" == \#* ]] && continue
    $ADB logcat -c; extra=""; [ -n "$cut" ] && extra="--es preview_cutout $cut"
    $ADB shell am start -S -n $P/com.limelight.shell.ShellActivity --es preview_shape $shape $extra $args >/dev/null 2>&1
    sleep 3
    if $ADB logcat -d -b crash | grep -q "FATAL EXCEPTION"; then echo "CRASH  $name  $id"; $ADB logcat -d -b crash | grep -m1 -A1 "FATAL" | tail -1 | cut -c1-160; bad=$((bad+1)); fi
  done < <(sed 's/#.*//' "$D/screens.txt")
done < "$D/shapes.txt"
echo "crashcheck done: $bad crash(es)"; [ $bad -eq 0 ]
