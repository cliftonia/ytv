#!/bin/bash
# Build YTV once and put it on every device that answers.
#
# Both televisions run the same apk and choose their own player engine from the display they are
# attached to, so there is nothing per-device to build - only the tedium of running the same four
# commands twice and forgetting which device already has which build. That is what this is for.
#
# The apk is also copied to the publisher, so a device can fetch it later without this Mac being
# involved at all - see the update check in the app.
#
#   tools/deploy.sh              build, install everywhere, publish the apk
#   tools/deploy.sh --no-build   install the apk that is already built
#   tools/deploy.sh --no-launch  install without restarting the app
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$REPO/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.cliftonia.fs42tv"
PUBLISHER="hermanb@192.168.4.203"
PUBLISH_DIR="~/FieldStation42/runtime/publish"

# Devices that are not always awake. A television that is off is not an error - it will pick the
# update up from the publisher next time it starts.
KNOWN_TCP=("192.168.4.174:5555")

BUILD=1
LAUNCH=1
for arg in "$@"; do
  case "$arg" in
    --no-build)  BUILD=0 ;;
    --no-launch) LAUNCH=0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

# yyDDDHHmm: ascending, and small enough for the Int that Android requires. See build.gradle.kts.
export YTV_VERSION="$(date '+%y%j%H%M')"

if [ "$BUILD" = 1 ]; then
  echo "==> building version $YTV_VERSION"
  # Deleted first because "BUILD SUCCESSFUL" is not evidence a change reached the apk: gradle
  # will happily consider an unchanged task up to date and leave yesterday's file in place,
  # which has cost this project a whole debugging session more than once.
  rm -f "$APK"
  ( cd "$REPO" && ./gradlew :app:testDebugUnitTest :app:assembleDebug ) || {
    echo "build FAILED - nothing installed" >&2; exit 1; }
  [ -f "$APK" ] || { echo "no apk at $APK" >&2; exit 1; }
fi

echo "==> apk: $(du -h "$APK" | cut -f1), built $(date -r "$APK" '+%H:%M')"

# Wake anything that is only reachable over TCP; mDNS devices reconnect on their own.
for target in "${KNOWN_TCP[@]}"; do
  adb connect "$target" >/dev/null 2>&1
done
sleep 1

# Read into an array the portable way. macOS ships bash 3.2, which has no `mapfile`, and this
# script is only ever run from the Mac that holds the Android SDK.
DEVICES=""
while read -r serial state _; do
  [ "$state" = "device" ] && DEVICES="$DEVICES $serial"
done < <(adb devices)
[ -n "$DEVICES" ] || echo "no devices reachable - apk still published below" >&2

for serial in $DEVICES; do
  name=$(adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
  printf '==> %s (%s)\n' "${name:-unknown}" "$serial"
  if ! adb -s "$serial" install -r "$APK" 2>&1 | tail -1 | sed 's/^/    /'; then
    echo "    install FAILED" >&2
    continue
  fi
  if [ "$LAUNCH" = 1 ]; then
    adb -s "$serial" shell am force-stop "$PKG" >/dev/null 2>&1
    # `am start` does NOT bring an app to the front on Google TV; monkey with the leanback
    # category does. Learned the hard way, more than once.
    adb -s "$serial" shell "monkey -p $PKG -c android.intent.category.LEANBACK_LAUNCHER 1" \
      >/dev/null 2>&1
    printf '    relaunched\n'
  fi
done

echo "==> publishing the apk for devices that were not awake"
VERSION="$YTV_VERSION"
if scp -q "$APK" "$PUBLISHER:$PUBLISH_DIR/ytv.apk" 2>/dev/null; then
  ssh "$PUBLISHER" "printf '{\"version\": %s, \"apk\": \"/ytv.apk\"}\n' '$VERSION' > $PUBLISH_DIR/app.json"
  echo "    published as version $VERSION"
else
  echo "    could not reach the publisher - skipped" >&2
fi
