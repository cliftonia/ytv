#!/bin/bash
# Publish details.json - posters, overviews, IMDb and Rotten Tomatoes ratings, cast and director
# for what airs on the LIVE TV dial in the next thirty hours - at the repository root (the app
# fetches raw.githubusercontent.com/cliftonia/ytv/main/details.json for the channel picker's
# details pane).
#
# Runs ON the home server (the CachyOS box), from a systemd user timer - never from the Mac, never
# from CI: the TMDB and OMDb keys live only on the server, never in the repository or the app.
#
# What it does:
#   1. Pulls the clone, so live.json and fast_guide.json are what the televisions read now (the
#      fast guide workflow publishes every six hours on the hour, UTC).
#   2. build_details.py reads them, asks Pluto's per-channel guide for the Pluto channels itself,
#      looks every title it has not seen before up on TMDB (ratings from OMDb, within the free
#      tier's 1,000 a day), and writes details.json plus tools/details/details_cache.json - every
#      lookup cached for good, so a title is asked about once. Refuses (and writes nothing) when
#      the sources come back mostly empty, and leaves details.json alone when nothing changed.
#   3. Commits those two files only, if either changed, and pushes - rebasing first, since the
#      lineup and guide workflows push to the same branch.
#
# SETUP on the server, as hermanb:
#
#   - Keys: ~/.config/ytv/details.env, mode 600, two lines - TMDB_API_KEY=... (a v3 key or a v4
#     read-access token) and OMDB_API_KEY=... . Without them nothing new is looked up and the cache
#     is republished; the app shows the guides' own titles and descriptions for anything missing.
#   - Commits go through ~/ytv-foreign, the clone tools/publish_foreign.sh and publish_ads.sh
#     already push from (the box's GitHub key, identity ytv-server, hooks off).
#   - The tools run from where this script lives: ~/ytv-foreign/tools/details/. Until this is on
#     main they are not there; the unit then fails harmlessly until the merge lands.
#   - Schedule: systemd USER timer (linger is on): ~/.config/systemd/user/ytv-details.{service,timer}
#     (copies in tools/details/systemd/), at 00:40, 06:40, 12:40 and 18:40 UTC - forty minutes after
#     each fast guide run starts, clear of the 17:00 UTC (03:00 Brisbane) nightly lineup push.
#       cp ~/ytv-foreign/tools/details/systemd/ytv-details.* ~/.config/systemd/user/
#       systemctl --user daemon-reload && systemctl --user enable --now ytv-details.timer
#     Logs: `journalctl --user -u ytv-details`. Run now: `systemctl --user start ytv-details`.
#   - The first run looks up ~2,500 titles (about twenty minutes, TMDB spaced out); later runs
#     only the new ones. Needs python3; no pip packages.
#
# Environment overrides: YTV_DETAILS_REPO (default ~/ytv-foreign), YTV_DETAILS_STATE
# (~/.cache/ytv-details, for the lock), YTV_DETAILS_PUSH (1; 0 builds and commits nothing).
set -euo pipefail

TOOLS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="${YTV_DETAILS_REPO:-$HOME/ytv-foreign}"
STATE="${YTV_DETAILS_STATE:-$HOME/.cache/ytv-details}"
PUSH="${YTV_DETAILS_PUSH:-1}"
FILES=(details.json tools/details/details_cache.json)

# Hooks off for every git call: the repo's opt-in pre-push hook builds and installs the Android
# app on any awake television, which is not something a timer should run.
git_() { git -C "$REPO" -c core.hooksPath=/dev/null "$@"; }

mkdir -p "$STATE"
exec 9>"$STATE/lock"
flock -n 9 || { echo "another publish_details is running"; exit 0; }

echo "== $(date '+%F %T') publish_details"
git_ pull --quiet --rebase origin main

python3 "$TOOLS/build_details.py" --repo "$REPO"

if [ "$PUSH" != "1" ]; then
  echo "YTV_DETAILS_PUSH=0: built, committing nothing"
  exit 0
fi

for f in "${FILES[@]}"; do
  if [ -f "$REPO/$f" ]; then git_ add -- "$f"; fi
done
if git_ diff --cached --quiet; then
  echo "nothing to publish"
  exit 0
fi
git_ commit --quiet -m "chore: refresh the programme details

- Update \`details.json\` posters, overviews, ratings and credits for the next thirty hours
- Update \`tools/details/details_cache.json\` the lookups this run made"

# The guide and lineup workflows push to main too: rebase over whatever landed, a few times.
for attempt in 1 2 3; do
  if git_ pull --quiet --rebase origin main && git_ push --quiet origin HEAD:main; then
    echo "published"
    exit 0
  fi
  sleep $((attempt * 15))
done
echo "push failed; the commit stays local and goes out with the next run"
exit 1
