#!/usr/bin/env bash
# Layout sheets for issue #9 (screen shapes): drives the debug previews through a list of shapes on the device, screenshots them and
# builds contact sheets (docs/GUI.md, "Screen shapes").
#
#   tools/layout/sheets.sh --adb <adb wrapper> --gate <stream gate> shapes   <label>   # every screen in every shape of shapes.txt
#   tools/layout/sheets.sh --adb <adb wrapper> --gate <stream gate> baseline [label]   # every screen natively (today's 16:9), label default "baseline"
#   tools/layout/sheets.sh compare <labelA> <labelB>                                   # pixel diff of two runs, no device needed
#
# <adb wrapper>: a script taking adb arguments that FAILS CLOSED (exits non-zero when the device is unreachable).
# <stream gate>: a script that exits 0 only when no Delos stream activity exists (anything else, including "device unreachable", is a refusal).
# Output: build/layout-sheets/<label>/<shape>/<screen>.png (baseline: native/<screen>.png), contact sheets in
# build/layout-sheets/<label>/sheets/by-screen/<screen>.png and by-shape/<shape>.png, and _before.png (the screen as it was found).
#
# Safety: the first thing a capture run does is take a screenshot, then ask the gate; it refuses to go on when a stream exists and asks
# again before every launch. Previews are started by intent only (`am start`), nothing is ever pressed or tapped. At the end (also after
# an error) the normal shell is started again. A run that was refused because of a stream touches nothing.
#
# Options: --screens "id,id"  only these screens (ids from screens.txt)      --only-shapes "name,name"  only these shapes (shapes mode)
#          --shapes-file F    --screens-file F                               --wait-extra SECONDS  added to every wait (slow device)
#          --tolerance N      (compare) per-channel difference that still counts as equal, default 0
#          --mask x0,y0,x1,y1 (compare) ignore this rectangle, fractions of the image (default 0.78,0,1,0.1 = the status bar's Wi-Fi, battery and clock;
#                              "none" compares everything)
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT_ROOT="$ROOT/build/layout-sheets"
COMPONENT="io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity"
PACKAGE="io.github.meitoncz.delos.debug"

ADB=""
GATE=""
SHAPES_FILE="$HERE/shapes.txt"
SCREENS_FILE="$HERE/screens.txt"
ONLY_SCREENS=""
ONLY_SHAPES=""
WAIT_EXTRA=0
TOLERANCE=0
MASK="0.78,0,1,0.1"
MODE=""
ARGS=()

die() { echo "sheets.sh: $*" >&2; exit 2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --adb) ADB="${2:-}"; shift 2 ;;
    --gate) GATE="${2:-}"; shift 2 ;;
    --shapes-file) SHAPES_FILE="${2:-}"; shift 2 ;;
    --screens-file) SCREENS_FILE="${2:-}"; shift 2 ;;
    --screens) ONLY_SCREENS="${2:-}"; shift 2 ;;
    --only-shapes) ONLY_SHAPES="${2:-}"; shift 2 ;;
    --wait-extra) WAIT_EXTRA="${2:-0}"; shift 2 ;;
    --tolerance) TOLERANCE="${2:-0}"; shift 2 ;;
    --mask) MASK="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,/^set -u/p' "$0" | sed '$d'; exit 0 ;;
    -*) die "unknown option $1" ;;
    *) if [ -z "$MODE" ]; then MODE="$1"; else ARGS+=("$1"); fi; shift ;;
  esac
done

[ -n "$MODE" ] || die "mode missing (shapes | baseline | compare); see --help"

csv_has() { # csv_has "a,b,c" b
  case ",$1," in *",$2,"*) return 0 ;; *) return 1 ;; esac
}

trim() { local s="$1"; s="${s#"${s%%[![:space:]]*}"}"; s="${s%"${s##*[![:space:]]}"}"; printf '%s' "$s"; }

# ---------------------------------------------------------------------------------------------------------- compare
if [ "$MODE" = "compare" ]; then
  [ "${#ARGS[@]}" -eq 2 ] || die "usage: sheets.sh compare <labelA> <labelB>"
  exec python3 "$HERE/sheets.py" compare "$OUT_ROOT/${ARGS[0]}" "$OUT_ROOT/${ARGS[1]}" "$OUT_ROOT/compare-${ARGS[0]}-vs-${ARGS[1]}" "$TOLERANCE" "$MASK"
fi

case "$MODE" in
  shapes) [ "${#ARGS[@]}" -eq 1 ] || die "usage: sheets.sh --adb A --gate G shapes <label>"; LABEL="${ARGS[0]}" ;;
  baseline) LABEL="${ARGS[0]:-baseline}" ;;
  *) die "unknown mode $MODE" ;;
esac
case "$LABEL" in */*|""|.*) die "bad label '$LABEL'" ;; esac

[ -n "$ADB" ] && [ -x "$ADB" ] || die "--adb <fail-closed adb wrapper> is required and must be executable"
[ -n "$GATE" ] && [ -x "$GATE" ] || die "--gate <stream gate helper> is required and must be executable"
[ -f "$SHAPES_FILE" ] || die "no $SHAPES_FILE"
[ -f "$SCREENS_FILE" ] || die "no $SCREENS_FILE"

DIR="$OUT_ROOT/$LABEL"
mkdir -p "$DIR" || die "cannot create $DIR"

# --------------------------------------------------------------------------------------------- screenshot first, gate second
if ! "$ADB" exec-out screencap -p > "$DIR/_before.png" 2>/dev/null </dev/null || [ "$(wc -c < "$DIR/_before.png")" -lt 1000 ]; then
  rm -f "$DIR/_before.png"
  die "could not take the first screenshot (device unreachable?); nothing was started"
fi
echo "screen as found: $DIR/_before.png"

stream_gate() {
  "$GATE" >/dev/null 2>&1 </dev/null
}

if ! stream_gate; then
  echo "sheets.sh: REFUSED: the gate reports a stream (or the device is unreachable); nothing was started" >&2
  exit 3
fi

if ! "$ADB" shell pm list packages "$PACKAGE" 2>/dev/null </dev/null | grep -q "package:$PACKAGE\$"; then
  die "$PACKAGE is not installed on the device (install the debug build first); nothing was started"
fi

# --------------------------------------------------------------------------------------------------------------- run
TOUCHED=0
REFUSED=0

restore_shell() {
  # A stream that appeared meanwhile is never touched
  if [ "$TOUCHED" = 1 ] && [ "$REFUSED" = 0 ]; then
    if stream_gate; then
      "$ADB" shell am force-stop "$PACKAGE" >/dev/null 2>&1 </dev/null
      "$ADB" shell am start -n "$COMPONENT" >/dev/null 2>&1 </dev/null && echo "normal shell restored"
    else
      echo "sheets.sh: not restoring the shell: the gate reports a stream or no device" >&2
    fi
  fi
}
trap restore_shell EXIT

# capture <file> <seconds> <extras...>: a fresh start of the debug shell with the extras, wait, screenshot
capture() {
  local file="$1" wait="$2"; shift 2
  if ! stream_gate; then
    REFUSED=1
    echo "sheets.sh: ABORTED: a stream appeared (or the device is gone); leaving everything as it is" >&2
    exit 3
  fi
  TOUCHED=1
  # -S: stop a running instance first, so the extras are read by a fresh onCreate; -W: wait for the launch
  "$ADB" shell am start -S -W -n "$COMPONENT" "$@" >/dev/null 2>&1 </dev/null || { echo "  start failed: $*" >&2; return 1; }
  sleep "$(awk -v w="$wait" -v e="$WAIT_EXTRA" 'BEGIN { printf "%.1f", w + e }')"
  mkdir -p "$(dirname "$file")"
  if ! "$ADB" exec-out screencap -p > "$file" 2>/dev/null </dev/null || [ "$(wc -c < "$file")" -lt 1000 ]; then
    rm -f "$file"
    echo "  screenshot failed: $file" >&2
    return 1
  fi
  echo "  $file"
}

FAILED=0
read_screens() { # prints "id|extras|wait" lines that pass the --screens filter
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%%#*}"; [ -n "$(trim "$line")" ] || continue
    local id extras wait
    IFS='|' read -r id extras wait <<< "$line"
    id="$(trim "$id")"; extras="$(trim "$extras")"; wait="$(trim "$wait")"
    if [ -n "$ONLY_SCREENS" ] && ! csv_has "$ONLY_SCREENS" "$id"; then continue; fi
    echo "$id|$extras|${wait:-4}"
  done < "$SCREENS_FILE"
}

SCREENS="$(read_screens)"
[ -n "$SCREENS" ] || die "no screens selected"

if [ "$MODE" = "baseline" ]; then
  echo "baseline '$LABEL': every screen natively (no preview_shape)"
  while IFS='|' read -r id extras wait; do
    # shellcheck disable=SC2086
    capture "$DIR/native/$id.png" "$wait" $extras || FAILED=$((FAILED + 1))
  done <<< "$SCREENS"
else
  echo "shapes '$LABEL': every screen in every shape of $SHAPES_FILE"
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%%#*}"; [ -n "$(trim "$line")" ] || continue
    name="" spec="" cutout=""
    read -r name spec cutout <<< "$line"
    if [ -n "$ONLY_SHAPES" ] && ! csv_has "$ONLY_SHAPES" "$name"; then continue; fi
    echo "shape $name ($spec${cutout:+ cutout $cutout})"
    while IFS='|' read -r id extras wait; do
      # shellcheck disable=SC2086
      capture "$DIR/$name/$id.png" "$wait" $extras --es preview_shape "$spec" ${cutout:+--es preview_cutout "$cutout"} || FAILED=$((FAILED + 1))
    done <<< "$SCREENS"
  done < "$SHAPES_FILE"
fi

python3 "$HERE/sheets.py" sheets "$DIR" || FAILED=$((FAILED + 1))
echo "done: $DIR/sheets ($FAILED failures)"
[ "$FAILED" = 0 ]
