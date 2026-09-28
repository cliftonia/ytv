#!/bin/bash
# Publish the pool of retro Australian ad reels the televisions play in Pluto ad breaks, as
# ads.json at the repository root (the app fetches raw.githubusercontent.com/cliftonia/ytv/main/ads.json).
#
# Runs ON the home server (the CachyOS box), from a systemd user timer - never from the Mac,
# never from CI (a runner has no business streaming hours of archive.org video every night).
#
# What it does, incrementally:
#   1. find_reels.py searches archive.org for Australian commercial compilations and tags each
#      with its era -> $STATE/candidates.json (the previous list is kept if the search fails).
#   2. Up to $YTV_ADS_PER_RUN (15) candidates not seen before go through cut_reel.py, which
#      streams the mp4 through ffmpeg once (nothing is saved) and finds the ad boundaries.
#      Each answer - published or rejected - is remembered in $STATE/reels/<id>.json and never
#      recomputed; a reel that fails outright (network, ffmpeg) is retried on later nights and
#      given up on after three failures.
#   3. ads.json is rebuilt from every "ok" record and committed + pushed only if the pool
#      changed - a new stamp alone is not news.
#   4. mirror_ads.py downloads every published reel not yet on this box to $STATE/mirror/<id>.mp4
#      (atomically, one at a time, politely) and deletes reels that left the pool; serve_ads.py
#      serves that directory on :4245 so a television at home joins a reel in ~1 s, not ~9.
#
# Contract (the app reads exactly this):
#   {"generated": <unix s>, "reels": [{"id", "title", "era": "70s"|"80s"|"90s", "url",
#     "duration": <s>, "cuts": [<ascending s>, ...], "loudness": <LUFS>}]}   - only reels with
#     >= 5 cuts; "loudness" (EBU R128 integrated) is left out until the reel has been measured
#
# SETUP on the server (done 27 Sep 2026), as hermanb:
#
#   - Commits go through ~/ytv-foreign, the clone tools/publish_foreign.sh already pushes from
#     (the box's GitHub key, identity ytv-server <ytv-server@users.noreply.github.com>, hooks
#     off). Only ads.json is ever added.
#   - The tools run from where this script lives. Until tools/ads-pool is on main they are a
#     copy at ~/ytv-ads-tools (scp'd from the branch); once merged, point the unit's ExecStart
#     at ~/ytv-foreign/tools/ads-pool/publish_ads.sh and delete the copy.
#   - State: ~/.cache/ytv-ads/ (candidates.json, reels/, failures/). Deleting reels/<id>.json
#     makes that reel be re-cut; deleting the directory rebuilds the pool from scratch.
#   - Schedule: systemd USER timer (linger is on): ~/.config/systemd/user/ytv-ads.{service,timer}
#     (copies in tools/ads-pool/systemd/), daily 02:15 local - clear of the 01:30 foreign-language
#     publish (same clone) and the 03:00 Brisbane nightly (which pushes without rebasing).
#     Logs: `journalctl --user -u ytv-ads`. Run now: `systemctl --user start ytv-ads`.
#   - Needs ffmpeg (/usr/bin/ffmpeg, already installed) and python3; no pip packages.
#   - The LAN mirror: ~/.cache/ytv-ads/mirror (~10 GB for ~80 reels), filled by step 4 and served
#     by the ytv-ads-mirror user service (tools/ads-pool/systemd/ytv-ads-mirror.service, :4245,
#     ufw 4245 from the LAN and on tailscale0 only). The first run downloads the whole pool.
#
# Environment overrides: YTV_ADS_REPO (default ~/ytv-foreign), YTV_ADS_STATE (~/.cache/ytv-ads),
# YTV_ADS_PER_RUN (15), YTV_ADS_PUSH (1; 0 writes ads.json into the state directory and commits nothing),
# YTV_ADS_MIRROR (1; 0 skips the LAN mirror).
set -euo pipefail

TOOLS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="${YTV_ADS_REPO:-$HOME/ytv-foreign}"
STATE="${YTV_ADS_STATE:-$HOME/.cache/ytv-ads}"
PER_RUN="${YTV_ADS_PER_RUN:-15}"
PUSH="${YTV_ADS_PUSH:-1}"
MIRROR="${YTV_ADS_MIRROR:-1}"
OUT="ads.json"

# Hooks off for every git call: the repo's opt-in pre-push hook builds and installs the Android
# app on any awake television, which is not something a timer should run.
git_() { git -C "$REPO" -c core.hooksPath=/dev/null "$@"; }

mkdir -p "$STATE/reels" "$STATE/failures"
exec 9>"$STATE/lock"
flock -n 9 || { echo "another publish_ads is running"; exit 0; }

echo "== $(date '+%F %T') publish_ads (up to $PER_RUN new reels)"
git_ pull --quiet --rebase origin main

if python3 "$TOOLS/find_reels.py" > "$STATE/candidates.json.tmp"; then
  mv "$STATE/candidates.json.tmp" "$STATE/candidates.json"
else
  rm -f "$STATE/candidates.json.tmp"
  echo "search failed; working from the previous candidate list"
fi
[ -s "$STATE/candidates.json" ] || { echo "no candidate list"; exit 1; }

# id<TAB>era<TAB>title for every candidate without a remembered answer, in list order.
mapfile -t TODO < <(python3 - "$STATE" <<'PY'
import json, os, sys
state = sys.argv[1]
for reel in json.load(open(os.path.join(state, "candidates.json"))):
    if not os.path.exists(os.path.join(state, "reels", reel["id"] + ".json")):
        print("%s\t%s\t%s" % (reel["id"], reel["era"], reel["title"].replace("\t", " ")))
PY
)
echo "${#TODO[@]} candidates not yet cut"

done_count=0
for line in "${TODO[@]}"; do
  [ "$done_count" -ge "$PER_RUN" ] && break
  IFS=$'\t' read -r id era title <<<"$line"
  done_count=$((done_count + 1))
  if python3 "$TOOLS/cut_reel.py" "$id" --era "$era" --title "$title" > "$STATE/reels/$id.json.tmp"; then
    mv "$STATE/reels/$id.json.tmp" "$STATE/reels/$id.json"
    rm -f "$STATE/failures/$id"
    python3 -c 'import json,sys; r=json.load(open(sys.argv[1])); print("  %-60s %-8s %3d cuts %5.1fs %s" % (r["id"][:60], r["status"], len(r["cuts"]), r["seconds"], r["reason"] or ""))' "$STATE/reels/$id.json"
  else
    rm -f "$STATE/reels/$id.json.tmp"
    fails=$(( $(cat "$STATE/failures/$id" 2>/dev/null || echo 0) + 1 ))
    echo "$fails" > "$STATE/failures/$id"
    echo "  $id failed ($fails)"
    if [ "$fails" -ge 3 ]; then
      printf '{"id": "%s", "status": "rejected", "reason": "failed %d times", "cuts": []}\n' "$id" "$fails" \
        > "$STATE/reels/$id.json"
    fi
  fi
  sleep 3   # be polite to archive.org
done

# Reels cut before loudness was kept: measure them, audio only, within the same nightly budget.
# A failure leaves the record as it was, to be tried again another night.
mapfile -t UNMEASURED < <(python3 - "$STATE" <<'PY'
import glob, json, os, sys
for path in sorted(glob.glob(os.path.join(sys.argv[1], "reels", "*.json"))):
    try:
        record = json.load(open(path))
    except ValueError:
        continue
    if record.get("status") == "ok" and record.get("url") and "loudness" not in record:
        print("%s\t%s" % (path, record["url"]))
PY
)
[ "${#UNMEASURED[@]}" -gt 0 ] && echo "${#UNMEASURED[@]} reels to measure for loudness"
for line in "${UNMEASURED[@]}"; do
  [ "$done_count" -ge "$PER_RUN" ] && break
  IFS=$'\t' read -r path url <<<"$line"
  done_count=$((done_count + 1))
  if lufs=$(python3 "$TOOLS/cut_reel.py" --loudness "$url"); then
    python3 - "$path" "$lufs" <<'PY'
import json, os, sys
path, lufs = sys.argv[1], json.loads(sys.argv[2])
record = json.load(open(path))
record["loudness"] = lufs
with open(path + ".tmp", "w") as handle:
    json.dump(record, handle)
os.replace(path + ".tmp", path)
print("  %-60s loudness %s" % (record["id"][:60], lufs))
PY
  else
    echo "  $(basename "$path" .json) loudness failed"
  fi
  sleep 3
done

# Rebuild the pool; an unchanged pool (ignoring the stamp) writes nothing.
if [ "$PUSH" = "1" ]; then TARGET="$REPO/$OUT"; else TARGET="$STATE/$OUT"; fi
python3 - "$STATE" "$TARGET" <<'PY'
import glob, json, os, sys, time

state, out = sys.argv[1], sys.argv[2]
reels = []
for path in sorted(glob.glob(os.path.join(state, "reels", "*.json"))):
    try:
        record = json.load(open(path))
    except ValueError:
        continue
    if record.get("status") != "ok" or len(record.get("cuts") or []) < 5:
        continue
    if record.get("era") not in ("70s", "80s", "90s") or not record.get("url"):
        continue
    reel = {key: record[key] for key in ("id", "title", "era", "url", "duration", "cuts")}
    if record.get("loudness") is not None:
        reel["loudness"] = round(record["loudness"], 1)
    reels.append(reel)
reels.sort(key=lambda r: (r["era"], r["id"]))

try:
    previous = json.load(open(out)).get("reels")
except (OSError, ValueError):
    previous = None
if previous == reels:
    print("pool unchanged: %d reels" % len(reels))
    sys.exit(0)
with open(out + ".tmp", "w") as handle:
    json.dump({"generated": int(time.time()), "reels": reels}, handle, indent=1)
    handle.write("\n")
os.replace(out + ".tmp", out)
eras = {e: sum(1 for r in reels if r["era"] == e) for e in ("70s", "80s", "90s")}
print("pool: %d reels %s, %d cuts (was %s)" % (len(reels), eras, sum(len(r["cuts"]) for r in reels),
                                                "none" if previous is None else len(previous)))
PY

publish_pool() {
  git_ add -- "$OUT"
  if git_ diff --cached --quiet; then
    echo "nothing to publish"
    return 0
  fi
  git_ commit --quiet -m "chore: refresh retro ad reel pool

- Update \`ads.json\` reels cut into ads from archive.org compilations"
  git_ push --quiet origin HEAD:main
  echo "published"
}
if [ "$PUSH" = "1" ]; then publish_pool; else echo "YTV_ADS_PUSH=0: wrote $TARGET, committing nothing"; fi

# Last, after the pool is out: keep a copy of every published reel on this box for the LAN
# (serve_ads.py on :4245). Fetches only what is missing, deletes what left the pool; a failure
# here never fails the publish - the televisions fall back to archive.org reel by reel.
if [ "$MIRROR" = "1" ]; then
  python3 "$TOOLS/mirror_ads.py" "$TARGET" "$STATE/mirror" --pause 5 || echo "mirror failed"
fi
