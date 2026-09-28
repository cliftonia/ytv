#!/bin/bash
# Publish fast_guide.json - what is on the LIVE TV dial's FAST channels for the next thirty hours -
# at the repository root (the app fetches raw.githubusercontent.com/cliftonia/ytv/main/fast_guide.json
# for the banner, the guide rows and the picker's details pane).
#
# Runs ON the home server (the CachyOS box), from a systemd user timer - never from the Mac. It
# replaces .github/workflows/fast_guide.yml's schedule, which never ran; that workflow is kept for
# manual runs only, so two publishers never fight over the file. The server can also do what a
# runner could not be counted on for: ask Tubi's guide, which answers only inside the US, through
# the home server's US relay (tools/fast-relay, :4247) - every Tubi channel was titleless before.
#
# What it does:
#   1. Pulls the clone, so live.json is the lineup the televisions read now.
#   2. curation/build_fast_guide.py fetches every service's guide, Tubi through the relay, and cuts
#      them to the committed live.json's channels. It refuses (exit 1, writing nothing) when most
#      guide channels come back empty - the guides changed shape, not the dial - so the committed
#      file, at worst hours stale, stands. Built into the state directory, then the builder's unit
#      tests run, and only then does the file move into the clone.
#   3. Commits fast_guide.json only, if it changed, and pushes - rebasing first and retrying, since
#      the lineup workflow and the other server jobs push to the same branch.
#   Then systemd starts ytv-details.service (OnSuccess= in ytv-guide.service), so the programme
#   details are always looked up against the guide just published.
#
# SETUP on the server, as hermanb:
#
#   - Commits go through ~/ytv-foreign, the clone tools/publish_foreign.sh, publish_ads.sh and
#     publish_details.sh already push from (the box's GitHub key, identity ytv-server
#     <ytv-server@users.noreply.github.com>, hooks off). Only fast_guide.json is ever added.
#   - This script and the builder run from the clone: ~/ytv-foreign/tools/guide/ and
#     ~/ytv-foreign/curation/. Until this is on main they are not there; the unit then fails
#     harmlessly until the merge lands.
#   - Tubi goes through http://127.0.0.1:4247/hls?u= (YTV_TUBI_VIA in ytv-guide.service): the
#     fast-relay front, which must be up (`systemctl status fast-relay-front.socket`). With the
#     relay down only the Tubi channels lose their titles; the rest publish.
#   - Schedule: systemd USER timer (linger is on): ~/.config/systemd/user/ytv-guide.{service,timer}
#     (copies in tools/guide/systemd/), every three hours at five past, UTC - 00:05, 03:05 ... 21:05,
#     clear of the 17:00 UTC (03:00 Brisbane) nightly lineup push. The details job no longer has a
#     timer of its own: it follows each successful guide run.
#       cp ~/ytv-foreign/tools/guide/systemd/ytv-guide.* ~/.config/systemd/user/
#       cp ~/ytv-foreign/tools/details/systemd/ytv-details.service ~/.config/systemd/user/
#       systemctl --user disable --now ytv-details.timer; rm -f ~/.config/systemd/user/ytv-details.timer
#       systemctl --user daemon-reload && systemctl --user enable --now ytv-guide.timer
#     Logs: `journalctl --user -u ytv-guide -u ytv-details`. Run now: `systemctl --user start ytv-guide`.
#   - Downloads ~100 MB of XMLTV a run (i.mjh.nz) and takes a few minutes. Needs python3; no pip
#     packages.
#
# Environment overrides: YTV_GUIDE_REPO (default ~/ytv-foreign), YTV_GUIDE_STATE
# (~/.cache/ytv-guide, for the lock and the build), YTV_GUIDE_PUSH (1; 0 builds and commits
# nothing), YTV_TUBI_VIA (unset: Tubi asked directly, which answers only inside the US).
set -euo pipefail

REPO="${YTV_GUIDE_REPO:-$HOME/ytv-foreign}"
STATE="${YTV_GUIDE_STATE:-$HOME/.cache/ytv-guide}"
PUSH="${YTV_GUIDE_PUSH:-1}"
CURATION="$REPO/curation"
OUT="fast_guide.json"

# Hooks off for every git call: the repo's opt-in pre-push hook builds and installs the Android
# app on any awake television, which is not something a timer should run.
git_() { git -C "$REPO" -c core.hooksPath=/dev/null "$@"; }

mkdir -p "$STATE"
exec 9>"$STATE/lock"
flock -n 9 || { echo "another publish_guide is running"; exit 0; }

echo "== $(date '+%F %T') publish_guide (tubi via ${YTV_TUBI_VIA:-direct})"
git_ pull --quiet --rebase origin main

rm -f "$STATE/$OUT"
python3 "$CURATION/build_fast_guide.py" --out "$STATE/$OUT"
(cd "$CURATION" && python3 -m unittest -q tests.test_build_fast_guide)

if [ "$PUSH" != "1" ]; then
  echo "YTV_GUIDE_PUSH=0: built $STATE/$OUT, committing nothing"
  exit 0
fi

mv "$STATE/$OUT" "$REPO/$OUT"
git_ add -- "$OUT"
if git_ diff --cached --quiet; then
  echo "nothing to publish"
  exit 0
fi
git_ commit --quiet -m "chore: refresh the FAST guide

- Update \`fast_guide.json\` the next thirty hours on the LIVE TV dial's FAST channels"

# The lineup workflow and the other server jobs push to main too: rebase over whatever landed.
for attempt in 1 2 3; do
  if git_ pull --quiet --rebase origin main && git_ push --quiet origin HEAD:main; then
    echo "published"
    exit 0
  fi
  sleep $((attempt * 15))
done
echo "push failed; the commit stays local and goes out with the next run"
exit 1
