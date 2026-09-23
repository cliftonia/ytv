#!/bin/bash
# Publish the accelerator's foreign-language verdicts as curation/foreign.json.
#
# Runs ON the home server (the CachyOS box that runs the accelerator), from cron - never from
# the Mac, never from CI. Why it exists: the nightly lineup workflow runs on a GitHub runner, the
# accelerator is on the tailnet, and a runner cannot reach the tailnet - so the language sweep
# printed "could not reach" every night and removed nothing. Here the server pushes its answer
# into the repository instead, and curation/language_sweep.py reads the committed file.
#
# What it does: fetch GET /languages from the accelerator on this machine (the same endpoint
# language_sweep.py --from-server asks, over loopback rather than the tailnet), reduce it to
# {"generated": <unix>, "foreign": [sorted video ids]}, and commit + push it if the id list
# changed. An unchanged list writes nothing and pushes nothing - the stamp alone is not news.
# Any failure (accelerator down, bad json, push refused) exits non-zero having published
# nothing, and the committed file carries on as it was: a video's language does not change, so
# yesterday's verdicts stay true.
#
# SETUP on the server (done 23 Sep 2026), as hermanb:
#
#   - Clone: ~/ytv-foreign, separate from ~/ytv-curation (deploy.sh rsyncs over that one). It
#     pushes with the box's existing GitHub key (~/.ssh/config: github.com -> id_ed25519_github),
#     git identity ytv-server <ytv-server@users.noreply.github.com>. A repo-scoped deploy key
#     would be narrower; swap it in via `git config core.sshCommand` if that matters.
#   - Schedule: the box has no cron, so a systemd USER timer (linger is on, so it runs with
#     nobody logged in): ~/.config/systemd/user/ytv-foreign.{service,timer}, daily 01:30 local -
#     clear of the 03:00 Brisbane nightly, which pushes without rebasing and would lose a race
#     with this push. Logs: `journalctl --user -u ytv-foreign`. Run now:
#     `systemctl --user start ytv-foreign`.
#
# The script runs from the clone it lives in, pulls first, and so updates itself with the repo.
#
# Environment overrides: YTV_ACCELERATOR (default http://127.0.0.1:4243).
set -euo pipefail

ACCELERATOR="${YTV_ACCELERATOR:-http://127.0.0.1:4243}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="curation/foreign.json"

# Hooks off for every git call here. This repo's opt-in pre-push hook (tools/install-hooks.sh)
# builds the Android app and installs it on any awake television; on this box that is at best a
# ten-minute failure every night, and a checkout's hooks are not something cron should run.
git_() { git -C "$REPO" -c core.hooksPath=/dev/null "$@"; }

echo "== $(date '+%F %T') publish_foreign"
git_ pull --quiet --rebase origin main

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
curl -fsS -m 60 "$ACCELERATOR/languages" -o "$TMP"

# The accelerator answers {"foreign": {id: language, ...}}; the committed file keeps the ids only
# (all the sweep needs), sorted so the diff is a readable list of additions. A body without a
# "foreign" key is refused rather than published as "nothing is foreign".
python3 - "$TMP" "$REPO/$OUT" <<'PY'
import json, os, re, sys, time

body = json.load(open(sys.argv[1]))
foreign = body.get("foreign")
if not isinstance(foreign, (dict, list)):
    sys.exit("accelerator answered without a 'foreign' map; publishing nothing")
ids = sorted(i for i in foreign if isinstance(i, str) and re.fullmatch(r"[A-Za-z0-9_-]{11}", i))

try:
    previous = json.load(open(sys.argv[2])).get("foreign")
except (OSError, ValueError):
    previous = None
if previous == ids:
    print("unchanged: %d ids" % len(ids))
    sys.exit(0)

with open(sys.argv[2] + ".tmp", "w") as handle:
    json.dump({"generated": int(time.time()), "foreign": ids}, handle, indent=1)
    handle.write("\n")
os.replace(sys.argv[2] + ".tmp", sys.argv[2])
print("%d ids (was %s)" % (len(ids), "none" if previous is None else len(previous)))
PY

git_ add -- "$OUT"
if git_ diff --cached --quiet; then
  echo "nothing to publish"
  exit 0
fi
git_ commit --quiet -m "chore: refresh foreign-language verdicts

- Update \`foreign.json\` ids the accelerator has seen declare a language other than English"
git_ push --quiet origin HEAD:main
echo "published"
