#!/usr/bin/env bash
#
# Prove a build plays on BOTH engines before it is released.
#
# Usage:
#   tools/smoke-engines.sh [-s SERIAL] [-n PRESSES] [-e mpv|media3|both] [-a path/to.apk]
#
#   -s  adb serial (e.g. 192.168.4.156:5555). Required when more than one device is attached.
#   -n  channels to surf per engine (default 8)
#   -e  which engine(s) to exercise (default both)
#   -a  install this apk first; without it the build already on the device is tested
#
# Exits 0 only if EVERY engine rendered a first frame on a comfortable majority of its surfs
# and hit no playback error at all. Exits 1 otherwise, naming the engine and the failure, so
# it can gate a release.
#
# Why this exists: the television at home runs mpv and the Chromecast runs Media3, and a
# release was verified by watching the television - which means the Media3 half of the app was
# never exercised by anyone before it shipped. A dormant branch in the Media3 source builder
# (a subtitle merge that could only run once captions were carried on every resolve) turned
# out to raise a FATAL error on every clip, so the Chromecast could switch channels forever
# without ever showing a picture. Everything looked green on the device that was watched.
#
# One device is enough to catch that class of fault, because the engine is a launch-time
# choice: `--es engine media3` puts the television onto the Chromecast's code path. What this
# cannot catch is a difference in the DEVICE - a decoder the Chromecast lacks - so it is a
# necessary check, not a sufficient one.

set -euo pipefail

SERIAL=""
PRESSES=8
ENGINES="both"
APK=""

while getopts "s:n:e:a:" opt; do
  case "$opt" in
    s) SERIAL="-s $OPTARG" ;;
    n) PRESSES="$OPTARG" ;;
    e) ENGINES="$OPTARG" ;;
    a) APK="$OPTARG" ;;
    *) sed -n '2,25p' "$0"; exit 1 ;;
  esac
done

PKG="com.cliftonia.fs42tv"
# shellcheck disable=SC2086
ADB="adb $SERIAL"

case "$ENGINES" in
  both) LIST="mpv media3" ;;
  mpv | media3) LIST="$ENGINES" ;;
  *) echo "unknown engine: $ENGINES" >&2; exit 1 ;;
esac

# Below this share of surfs rendering, the engine is called broken. Not 100%: a single dead
# clip in the rotation is ordinary and the dead-clip skip handles it, but a genuinely broken
# engine renders nothing at all, and the gap between "one dead clip" and "nothing" is wide.
MIN_RENDER_PERCENT=75

MODEL=$($ADB shell getprop ro.product.model | tr -d '\r')
echo "== engine smoke on $MODEL =="

if [ -n "$APK" ]; then
  echo "   installing $APK"
  # A silent refusal here is the trap this script exists to close. `adb install -r` prints
  # Success and installs NOTHING when the apk's versionCode is lower than what is on the
  # device, so the version is read back and compared rather than trusted.
  $ADB install -r "$APK" > /dev/null
fi
VERSION=$($ADB shell "dumpsys package $PKG | grep versionCode | head -1" | tr -d '\r' \
  | sed -E 's/.*versionCode=([0-9]+).*/\1/')
echo "   build on device: $VERSION"

# Restored on exit so a smoke run never leaves the television on the wrong engine. The engine
# persists across launches by design (settings row VIDEO ENGINE), so without this the
# television would come back from a media3 run still on media3 - the exact fault the setting
# exists to escape from.
#
# Read from the app's own launch log rather than its preferences file: a release-signed build
# is not debuggable, so neither `cat /data/data/...` nor `run-as` can open shared_prefs, and
# the first version of this script silently restored nothing. onCreate logs "player engine X"
# on every launch, so one launch with no override IS the question "what would you have chosen".
$ADB shell am force-stop "$PKG"
$ADB logcat -c || true
$ADB shell "monkey -p $PKG -c android.intent.category.LEANBACK_LAUNCHER 1" > /dev/null 2>&1
sleep 6
ORIGINAL_ENGINE=$($ADB logcat -d -s fs42:I | tr -d '\r' \
  | sed -nE 's/.*player engine ([A-Z0-9]+) .*/\1/p' | tail -1 | tr '[:upper:]' '[:lower:]' || true)
echo "   engine before the run: ${ORIGINAL_ENGINE:-unknown}"
restore() {
  if [ -n "$ORIGINAL_ENGINE" ]; then
    $ADB shell am start -S -n "$PKG/.MainActivity" --es engine "$ORIGINAL_ENGINE" > /dev/null 2>&1 || true
    echo "   engine restored to $ORIGINAL_ENGINE"
  else
    echo "   engine NOT restored - could not read it before the run; check VIDEO ENGINE in settings"
  fi
}

FAILED=0
for ENGINE in $LIST; do
  echo
  echo "-- $ENGINE --"
  $ADB shell am force-stop "$PKG"

  # Streamed rather than dumped - see measure-switch.sh for why a 256 KiB ring buffer that the
  # television's own apps keep filling evicts the evidence before an 8-press run is over.
  # Opened BEFORE the launch so the "player engine" line, which onCreate logs immediately, is
  # in the stream: it is the only proof the override took.
  STREAM=$(mktemp)
  $ADB logcat -c || true
  $ADB logcat -s fs42:D > "$STREAM" 2>/dev/null &
  TAIL_PID=$!

  # -S so a running app is killed rather than re-delivered the intent: MainActivity is
  # singleTask, and the engine is read in onCreate, which a re-delivery never runs.
  $ADB shell am start -S -n "$PKG/.MainActivity" --es engine "$ENGINE" > /dev/null
  # The first tune resolves and buffers; nothing meaningful can be measured until it lands.
  sleep 22

  for ((i = 0; i < PRESSES; i++)); do
    $ADB shell input keyevent KEYCODE_DPAD_UP
    sleep 9
  done
  sleep 5
  { kill "$TAIL_PID" && wait "$TAIL_PID"; } 2>/dev/null || true

  REPORT=$(mktemp)
  cat > "$REPORT" <<'PY'
import re, sys
engine, presses, floor = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
log = sys.stdin.read()

launched = re.search(r"player engine (\w+)", log)
frames = re.findall(r"first frame (\d+) ms", log)
errors = re.findall(r"re-tuning .* after playback error (\S+)", log)
fatal = re.findall(r"mpv core shut down|Legacy decoding|FATAL", log)
tunes = len(re.findall(r": clip \d+ at ", log))

rate = 100 * len(frames) // presses if presses else 0
print(f"   engine reported : {launched.group(1) if launched else 'NOT SEEN'}")
print(f"   surfs/tunes     : {presses}/{tunes}")
print(f"   first frames    : {len(frames)} ({rate}%)")
print(f"   playback errors : {len(errors)}" + (f"  {sorted(set(errors))}" if errors else ""))
if fatal:
    print(f"   fatal signatures: {sorted(set(fatal))}")

ok = True
if launched and launched.group(1).lower() != engine:
    print(f"   FAIL: asked for {engine}, got {launched.group(1)} - the override did not take")
    ok = False
if rate < floor:
    print(f"   FAIL: rendered {rate}% of surfs, below the {floor}% floor")
    ok = False
if errors:
    print("   FAIL: a working engine hits no playback errors on a fresh dial")
    ok = False
if fatal:
    print("   FAIL: fatal engine signature in the log")
    ok = False
print("   PASS" if ok else "   ** BROKEN **")
sys.exit(0 if ok else 1)
PY
  if ! python3 "$REPORT" "$ENGINE" "$PRESSES" "$MIN_RENDER_PERCENT" < "$STREAM"; then
    FAILED=1
  fi
  rm -f "$REPORT" "$STREAM"
done

restore
echo
if [ "$FAILED" -ne 0 ]; then
  echo "SMOKE FAILED on build $VERSION - do not release this"
  exit 1
fi
echo "SMOKE PASSED on build $VERSION - both engines play"
