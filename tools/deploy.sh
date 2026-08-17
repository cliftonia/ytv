#!/bin/bash
# Build YTV once and put it on every device that answers.
#
# Both televisions run the same apk and choose their own player engine from the display they are
# attached to, so there is nothing per-device to build - only the tedium of running the same four
# commands twice and forgetting which device already has which build. That is what this is for.
#
# Builds a SIGNED RELEASE apk, not a debug one, and that is not a detail. Android refuses to
# install an update signed by a different key than the installed app, so a build from this Mac and
# a build from the release workflow have to come from the same keystore or the two paths diverge
# permanently: whichever got there first would be the only one that could ever update the device
# again. The keystore lives in ~/.ytv and is deliberately not in this repository, which is public.
#
# For devices that are switched off, or in the car and away from the house network, the update
# arrives instead through the release workflow - the app checks GitHub releases on launch. This
# script is the fast path for when you are standing in front of the television.
#
#   tools/deploy.sh              build, install everywhere
#   tools/deploy.sh --no-build   install the apk that is already built
#   tools/deploy.sh --no-launch  install without restarting the app
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$REPO/app/build/outputs/apk/release/app-release.apk"
PKG="com.cliftonia.fs42tv"
KEYSTORE_DIR="$HOME/.ytv"

# Devices that are not always awake. A television that is off is not an error - it will pick the
# update up from GitHub next time it starts.
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
  if [ ! -f "$KEYSTORE_DIR/ytv-release.jks" ]; then
    echo "no keystore at $KEYSTORE_DIR/ytv-release.jks" >&2
    echo "an unsigned build cannot be installed, and one signed with a different key cannot" >&2
    echo "update the televisions - so this stops rather than producing either." >&2
    exit 1
  fi
  export YTV_KEYSTORE="$KEYSTORE_DIR/ytv-release.jks"
  export YTV_KEYSTORE_PASSWORD="$(cat "$KEYSTORE_DIR/keystore-password.txt")"
  export YTV_KEY_ALIAS="ytv"

  echo "==> building version $YTV_VERSION"
  # Deleted first because "BUILD SUCCESSFUL" is not evidence a change reached the apk: gradle
  # will happily consider an unchanged task up to date and leave yesterday's file in place,
  # which has cost this project a whole debugging session more than once.
  rm -f "$APK"
  ( cd "$REPO" && ./gradlew :app:testDebugUnitTest :app:assembleRelease ) || {
    echo "build FAILED - nothing installed" >&2; exit 1; }
  [ -f "$APK" ] || { echo "no apk at $APK - did signing fall through to unsigned?" >&2; exit 1; }
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
[ -n "$DEVICES" ] || echo "no devices reachable" >&2

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
