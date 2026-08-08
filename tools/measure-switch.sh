#!/usr/bin/env bash
#
# Measure channel-switch latency: keypress to first rendered frame.
#
# Usage:
#   tools/measure-switch.sh [-s SERIAL] [-n PRESSES] [-d DIRECTION] [-l LABEL]
#
#   -s  adb serial (e.g. 192.168.4.55:5555). Required when more than one device is attached.
#   -n  number of channel presses (default 18)
#   -d  up | down | alternating (default up)
#   -l  a label printed with the result, so runs can be told apart afterwards
#   -b  preload budget override (slots). Omit to use whatever the device's RAM implies.
#   -w  preload window override in ms. Omit to use the built-in default.
#   -t  pin the app's clock to this epoch-seconds value. Without it, every run watches whatever
#       the wall clock puts on air, so two runs minutes apart compare different clips at
#       different bitrates - the single largest source of variance in this project's
#       measurements, and the cause of three separate false results.
#
# Reports the median, the spread, AND the render rate. The render rate matters as much as the
# median: a change that makes tunes fail more often can LOWER the median, because the slow cases
# drop out of the sample entirely rather than being counted as the failures they are.
#
# Measurements taken minutes apart are NOT comparable. An emulator that measured 3892ms one hour
# measured 9971ms the next on identical code, which invalidated a whole afternoon of A/B results.
# Use `-d alternating`, or run the two configurations back to back and then re-run the first, so
# drift shows up instead of being attributed to the change under test.

set -euo pipefail

SERIAL=""
PRESSES=18
DIRECTION="up"
LABEL=""
EXTRAS=""

while getopts "s:n:d:l:b:w:t:" opt; do
  case "$opt" in
    s) SERIAL="-s $OPTARG" ;;
    n) PRESSES="$OPTARG" ;;
    d) DIRECTION="$OPTARG" ;;
    l) LABEL="$OPTARG" ;;
    b) EXTRAS="$EXTRAS --ei fs42.budget $OPTARG" ;;
    w) EXTRAS="$EXTRAS --el fs42.preload_ms $OPTARG" ;;
    t) EXTRAS="$EXTRAS --el fs42.now $OPTARG" ;;
    *) sed -n '2,20p' "$0"; exit 1 ;;
  esac
done

PKG="com.cliftonia.fs42tv"
# shellcheck disable=SC2086
ADB="adb $SERIAL"

case "$DIRECTION" in
  up) KEYS=("KEYCODE_DPAD_UP") ;;
  down) KEYS=("KEYCODE_DPAD_DOWN") ;;
  alternating) KEYS=("KEYCODE_DPAD_UP" "KEYCODE_DPAD_DOWN") ;;
  *) echo "unknown direction: $DIRECTION" >&2; exit 1 ;;
esac

echo "== ${LABEL:-measurement} on $($ADB shell getprop ro.product.model | tr -d '\r') =="
$ADB shell am force-stop "$PKG"
# Cleared so the run always starts from the same end of the dial and syncs a fresh channels.json;
# resuming on whatever was last watched would sample a different set of channels each time.
$ADB shell pm clear "$PKG" > /dev/null
# Launched the way the remote does it, via the LEANBACK_LAUNCHER category.
#
# `am start -n pkg/.MainActivity` starts the activity but does NOT reliably bring it to the
# front on Google TV: the launcher can stay on top while the app runs behind it. A measurement
# taken then is of a screen nobody is looking at, and the log fills with tunes that never
# reached a display.
#
# monkey fires the launcher intent itself, which carries the foreground semantics. Extras still
# go through am start, so the two are combined: start with extras, then bring forward.
# shellcheck disable=SC2086
if [ -n "$EXTRAS" ]; then
  $ADB shell am start -n "$PKG/.MainActivity" $EXTRAS > /dev/null
else
  $ADB shell "monkey -p $PKG -c android.intent.category.LEANBACK_LAUNCHER 1" > /dev/null 2>&1
fi
echo "   waiting for the first tune to settle..."
sleep 24
$ADB logcat -c

# Stream the log for the whole run rather than dumping it at the end. A real television has a
# 256 KiB ring buffer that its own system apps are already filling, so on an 18-press run our
# lines are evicted long before the run finishes: the dump comes back with two samples and a
# "beginning of main" marker where the rest used to be. That reads as "the presses did not land"
# when in fact only the evidence was lost - the emulator never showed this because nothing else
# on it logs.
STREAM=$(mktemp)
$ADB logcat -s fs42:D > "$STREAM" &
TAIL_PID=$!
trap 'kill "$TAIL_PID" 2>/dev/null || true; rm -f "$STREAM"' EXIT

for ((i = 0; i < PRESSES; i++)); do
  $ADB shell input keyevent "${KEYS[$((i % ${#KEYS[@]}))]}"
  # Long enough that each tune completes rather than superseding the one before it. This is
  # measuring one switch at a time, not a burst.
  sleep 8
done

# Let the last tune render before the stream is cut, or the final sample is lost to exactly the
# impatience this script exists to measure.
sleep 5
kill "$TAIL_PID" 2>/dev/null || true

# The report script goes in a file rather than a heredoc: piping the log in on stdin AND
# supplying the program on stdin cannot both work, and the failure mode is the log being
# executed as Python.
REPORT=$(mktemp)
cat > "$REPORT" <<'PY'
import re, sys
presses, label = int(sys.argv[1]), sys.argv[2]
log = sys.stdin.read()
frames = sorted(int(m) for m in re.findall(r"first frame (\d+) ms", log))
tunes = len(re.findall(r": clip ", log))
print()
print(f"{label}: presses={presses} tunes={tunes} rendered={len(frames)}")
if not frames:
    print("  no frames rendered - nothing to report, and that IS the result")
    raise SystemExit(0)
mid = frames[len(frames) // 2]
print(f"  n={len(frames)} min={frames[0]} MEDIAN={mid} max={frames[-1]}")
print(f"  render rate {len(frames)}/{presses} = {100 * len(frames) // presses}%")
print(f"  samples: {frames}")
PY

python3 "$REPORT" "$PRESSES" "${LABEL:-measurement}" < "$STREAM"
rm -f "$REPORT"
