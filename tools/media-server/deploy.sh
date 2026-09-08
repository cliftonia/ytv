#!/bin/bash
# Install or refresh the YTV media server on the homelab box, then rescan the file channels.
#
# Run from a checkout of this repo on the Mac (tailnet reaches the box; the LAN does not have
# to be the one this Mac is on):
#
#   tools/media-server/deploy.sh
#
# What it does on cachyos-x8664, as hermanb with passwordless sudo:
#
#   1. installs /etc/caddy/ytv-media.caddy + the ytv-media systemd unit (port 4244)
#   2. opens ufw 4244 from the LAN and over tailscale0, matching the two addresses the app
#      already whitelists for cleartext
#   3. rsyncs curation/ to ~/ytv-curation and runs scan_media.py there as http - ffprobe must
#      read the media files, and only http can
#   4. runs Nextcloud's occ files:scan, so films dropped onto disk by hand or rsync show up in
#      the Nextcloud UI instead of living in two worlds
#   5. copies the rewritten file_*.json confs back to this checkout
#
# Afterwards, HERE:  cd curation && python3 build_lineup.py && commit and push - the
# televisions pick channels.json up from GitHub on launch, like every other lineup change.
set -euo pipefail

HOST=hermanb@100.74.3.68
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

echo "==> caddy config, unit, ufw, rescan, Nextcloud reindex (see remote-steps.sh)"
# The remote default shell is fish, which mangles multi-line quoted commands - so the steps
# live in a file and are run through bash -s instead of inlined here.
rsync -aq --delete --exclude __pycache__ "$REPO/curation/" "$HOST:ytv-curation/"
scp -q "$REPO/tools/media-server/ytv-media.caddy" "$REPO/tools/media-server/ytv-media.service" \
    "$REPO/tools/media-server/remote-steps.sh" "$HOST:/tmp/"
ssh "$HOST" 'bash -s' < "$REPO/tools/media-server/remote-steps.sh"

scp -q "$HOST:ytv-curation/confs/file_movies.json" "$HOST:ytv-curation/confs/file_series.json" \
    "$REPO/curation/confs/"

echo "==> health check over tailnet"
# A directory URL is a deliberate 404 (browse is off), so the check has to ask for a real
# file - the first scanned stream - and a 206 (Range honoured) is the answer that matters:
# both players read media as bounded byte ranges.
PROBE="$(python3 - "$REPO/curation/confs/file_movies.json" <<'EOF'
import json, sys
streams = json.load(open(sys.argv[1]))["station_conf"]["streams"]
print(streams[0]["url"].replace("192.168.4.58", "100.74.3.68") if streams else "")
EOF
)"
if [ -n "$PROBE" ] && curl -sf -m 10 -r 0-1023 -o /dev/null "$PROBE"; then
  echo "ok: 206 partial content from $PROBE"
else
  echo "WARN: no range answer from :4244 - check systemctl status ytv-media on the box" >&2
fi

echo
echo "next: cd $REPO/curation && python3 build_lineup.py && git add -A && git commit"
