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

echo "==> generating caddy paths from the channel confs"
# The serve list follows the dial, not a hand-edit: every file_ conf's media_dir becomes one
# allowed path prefix on :4244. A channel folder nobody declared stays unreachable.
python3 - "$REPO/curation/confs" "$REPO/tools/media-server/ytv-media.caddy" <<'EOF' > /tmp/ytv-media.gen
import glob, json, os, sys
dirs = []
for p in sorted(glob.glob(os.path.join(sys.argv[1], "file_*.json"))):
    d = json.load(open(p)).get("station_conf", {}).get("media_dir", "").strip()
    if d and d not in dirs:
        dirs.append(d)
if not dirs:
    dirs = ["Movies", "Series"]  # the service must adapt even when no confs are in hand
if any('"' in d for d in dirs):
    sys.exit("a media dir contains a quote; refusing to generate an invalid Caddyfile")
paths = " ".join('"/%s/*"' % d for d in dirs)
tmpl = open(sys.argv[2]).read().split("\n")
block = '\t@media path %s\n\thandle @media {\n\t\tfile_server\n\t}' % paths
# Substitute the placeholder LINE only - never str.replace over the whole file. 2026-09-09 the
# token was also in the header comment and str.replace put a second matcher block outside
# the site block; Caddy refused the file and the unit sat in start-limit-hit for a week.
slots = [i for i, line in enumerate(tmpl) if line.strip() == "@@PATHS@@"]
if len(slots) != 1:
    sys.exit("template must contain exactly one @@PATHS@@ line, found %d" % len(slots))
tmpl[slots[0]] = block
out = "\n".join(tmpl)
if "@@PATHS@@" in out:
    sys.exit("template carries @@PATHS@@ outside the placeholder line; refusing to generate")
print(out, end="")
EOF
cp /tmp/ytv-media.gen /tmp/ytv-media.caddy
# Prove the generated file parses before anything leaves this machine (caddy is on the Mac
# via brew, and on the box); the remote step validates again before overwriting the live one.
if command -v caddy >/dev/null; then
  caddy validate --config /tmp/ytv-media.caddy --adapter caddyfile >/dev/null 2>&1 \
    || { echo "generated caddyfile is invalid:" >&2; caddy validate --config /tmp/ytv-media.caddy --adapter caddyfile 2>&1 | tail -3 >&2; exit 1; }
fi

echo "==> caddy config, unit, ufw, rescan, Nextcloud reindex (see remote-steps.sh)"
# The remote default shell is fish, which mangles multi-line quoted commands - so the steps
# live in a file and are run through bash -s instead of inlined here.
rsync -aq --delete --exclude __pycache__ "$REPO/curation/" "$HOST:ytv-curation/"
scp -q /tmp/ytv-media.caddy "$REPO/tools/media-server/ytv-media.service" \
    "$REPO/tools/media-server/remote-steps.sh" "$HOST:/tmp/"
ssh "$HOST" 'bash -s' < "$REPO/tools/media-server/remote-steps.sh"

# Every file_ conf, not a hand-kept list: the scan rewrites all of them (Cinema Stream's remote
# urls and any channel ingest.py created), and naming only movies/series left the others'
# rescans stranded on the server - the checkout kept publishing stale streams for them. The
# glob is quoted so the REMOTE side expands it (scp's sftp mode globs server-side itself).
scp -q "$HOST:ytv-curation/confs/file_*.json" "$REPO/curation/confs/"

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
echo "next: cd $REPO/curation && python3 build_lineup.py && python3 check_lineup.py \\"
echo "      && git add -- ../channels.json confs && git commit"
