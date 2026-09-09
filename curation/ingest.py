#!/usr/bin/env python3
"""Torrent/magnet ingest for the file channels: paste a link, watch it land on the dial.

    python3 curation/ingest.py

Flow: paste a magnet or a .torrent URL -> the server fetches the metadata and shows you the
name, size and file list -> you confirm -> pick Movies, Series, or a brand-new channel ->
download with live progress -> lineup rescan + rebuild + publish.

Lawful sources only: this is a downloader - whatever you paste lands on disk and plays from
disk, forever, like everything else on these channels. The transport is the point; the
curation is yours.

Server-side the heavy lifting is aria2 against the media folders (http owns them), then the
usual deploy pass re-serves, re-scans and re-indexes. Locally the publish steps are the same
ones you'd run by hand. A killed download leaves a .aria2 control file - pasting the same
magnet again RESUMES it, so a ctrl-C is not a lost hour.
"""
import base64
import os
import re
import subprocess
import sys

import confs

HOST = "hermanb@100.74.3.68"
FILES_ROOT = "/mnt/hdd/nextcloud-data/data/hermanb/files"
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Scratch for metadata fetches; nothing here survives the run.
STAGING = "/tmp/ytv-ingest"

# btih v1: 40 hex chars, or 32 from base32. The "magnet://?..." double-slash is a client
# dialect as common as the canonical "magnet:?..." - both start the same download. Params
# before xt= are fine; a missing xt=urn:btih is not a magnet aria2 can begin.
MAGNET = re.compile(
    r"^magnet:(?://)?\?\S*?xt=urn:btih:([0-9a-fA-F]{40}|[A-Za-z2-7]{32})\b", re.I)
TORRENT_URL = re.compile(r"^https?://\S+\.torrent(\?\S*)?$", re.I)


def canonical_magnet(uri):
    """The client's spelling to aria2's. aria2 1.37 refuses magnet:// outright
    ("Unrecognized URI or unsupported protocol"), so the tolerant regex accepts it and this
    is what actually reaches the daemon."""
    return re.sub(r"^magnet://", "magnet:", uri)


# --- the pure parts -----------------------------------------------------------------------

def bdecode(data, at=0):
    """Enough bencode to read a .torrent: ints, byte strings, lists, dicts.

    Hand-rolled rather than a dependency, like the JSON/VTT parsers in the app: three
    dozen lines, fully testable, no supply-chain to babysit.
    """
    token = data[at:at + 1]
    if token == b"i":
        end = data.index(b"e", at)
        return int(data[at + 1:end]), end + 1
    if token == b"l":
        at += 1
        items = []
        while data[at:at + 1] != b"e":
            value, at = bdecode(data, at)
            items.append(value)
        return items, at + 1
    if token == b"d":
        at += 1
        pairs = {}
        while data[at:at + 1] != b"e":
            key, at = bdecode(data, at)
            value, at = bdecode(data, at)
            pairs[key.decode()] = value
        return pairs, at + 1
    if token.isdigit():
        colon = data.index(b":", at)
        length = int(data[at:colon])
        start = colon + 1
        return data[start:start + length], start + length
    raise ValueError("not bencode at offset %d" % at)


def torrent_summary(raw):
    """(name, total_bytes, [(relpath, bytes), ...]) from a .torrent's info dict."""
    info = bdecode(raw)[0]["info"]
    name = info.get("name.utf-8") or info.get("name") or b""
    if isinstance(name, bytes):
        name = name.decode(errors="replace")
    files = []
    total = 0
    if "files" in info:
        for entry in info["files"]:
            size = int(entry.get("length", 0))
            parts = [p.decode(errors="replace") if isinstance(p, bytes) else str(p)
                     for p in entry.get("path", [])]
            files.append(("/".join(parts), size))
            total += size
    else:
        total = int(info.get("length", 0))
    return name, total, files


def slugify(name):
    """A conf slug from a channel name: 'Cowboy Bebop!' -> 'cowboy-bebop'."""
    slug = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    return re.sub(r"-{2,}", "-", slug)


def next_file_channel_number(confs_dir):
    """The next free file-block number, or a refusal to steal from the live block."""
    used = []
    for path in confs.file_paths(confs_dir):
        number = confs.load(path).get("station_conf", {}).get("channel_number")
        if number is not None:
            used.append(int(number))
    candidate = max(used, default=90) + 1
    if candidate > 99:
        raise ValueError("the file block is 91-99 and it is full")
    return candidate


FILES_BLOCK = re.compile(r"\nFILES = \[.*?\n\]\n", re.S)


def add_file_channel(dial_source, number, slug, name, folder):
    """Insert one FILES row into dial.py's source, keeping the block's own style."""
    row = '    (%d, "%s", "%s", "%s", ()),\n' % (number, slug, name, folder)
    match = FILES_BLOCK.search(dial_source)
    if not match:
        raise ValueError("no FILES block found in dial.py")
    block = match.group(0)
    if '"%s"' % slug in block:
        raise ValueError("%s is already on the dial" % slug)
    return dial_source[:match.end() - 2] + row + dial_source[match.end() - 2:]


import scan_media

# Reuse the scanner's extension list: what lands is what airs.
VIDEO_EXT = scan_media.VIDEO_EXT


def video_indices(files):
    """1-based aria2 --select-file indices of the playable entries, in torrent order."""
    return [i + 1 for i, (rel, _) in enumerate(files)
            if os.path.splitext(rel)[1].lower() in VIDEO_EXT]

def ssh(script, *, stream=False):
    """Run a bash script on the server. stream=True pipes output straight to the terminal."""
    if stream:
        return subprocess.run(["ssh", HOST, "bash", "-s"], input=script, text=True).returncode
    out = subprocess.run(["ssh", HOST, "bash", "-s"], input=script, text=True,
                         capture_output=True)
    return out.returncode, out.stdout, out.stderr


# aria2's periodic summary, e.g. [#eaffaa 1.4GiB/1.4GiB(98%) CN:0 SD:0 DL:0B] - and the
# metadata phase's [#d9f05a 0B/0B(0%) ...], which must parse too or the watchdog sleeps
# through a dead swarm forever.
SUMMARY = re.compile(r"\[#\w+ ([\d.]+(?:[GMKTP]i)?B)/([\d.]+(?:[GMKTP]i)?B)\((\d+)%\)")
STALL_SECONDS = 90


def run_download(script):
    """Drive the remote aria2, printing its summaries live. 'stalled' if the byte count
    stops moving - on archive.org that means the swarm's tail lives in a webseed, which
    aria2 cannot read (no BEP19), so waiting longer resolves nothing."""
    proc = subprocess.Popen(["ssh", HOST, "bash", "-s"], stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    proc.stdin.write(script)
    proc.stdin.close()
    import time
    last_size, last_move = "", time.monotonic()
    for line in iter(proc.stdout.readline, ""):
        print(line, end="")
        match = SUMMARY.search(line)
        if match:
            if match.group(1) != last_size:
                last_size, last_move = match.group(1), time.monotonic()
            elif time.monotonic() - last_move > STALL_SECONDS:
                print("\n-- no progress for %ds; taking the tail over http" % STALL_SECONDS)
                proc.terminate()
                proc.wait(timeout=15)
                return "stalled"
    return "ok" if proc.wait() == 0 else "failed"


def ia_download_base(torrent_url):
    """.../download/<id>/<item>_archive.torrent -> .../download/<id>/, else None."""
    match = re.match(r"(https?://\S+/download/[^/]+/)\S+\.torrent", torrent_url or "")
    return match.group(1) if match else None


def build_tail_script(base, root, target, chosen):
    """The remote script for the http fallback, built here so tests can read it.

    Whole-file replacement, never append-resume: aria2 preallocates with holes, so
    curl -C - would happily "finish" a file whose middle is zeros. A .part name keeps a
    half-fetched replacement from ever being mistaken for content by the scanner.
    """
    lines = ["set -u"]
    for rel, size in chosen:
        url = base + "/".join(quote_path_segment(seg) for seg in rel.split("/"))
        local = "/".join(p for p in (target, root, rel) if p)
        lines.append(
            "SZ=$(sudo -u http stat -c%%s %s 2>/dev/null || echo missing)\n"
            "if [ \"$SZ\" != %d ]; then\n"
            "  echo '-- http tail: %s'\n"
            "  sudo -u http curl -fSL --retry 2 -sS -o %s.part %s && sudo -u http mv %s.part %s\n"
            "else echo '-- already complete: %s'; fi"
            % (quote_shell(local), size, local, quote_shell(local), quote_shell(url),
               quote_shell(local), quote_shell(local), local))
    return "\n".join(lines)


def complete_over_http(base, root, target, chosen):
    """Finish selected files over plain https when the swarm's tail only lives in a webseed."""
    rc = ssh(build_tail_script(base, root, target, chosen), stream=True)
    if rc != 0:
        print("http tail failed part-way; rerun repeats it", file=sys.stderr)
        raise SystemExit(1)


def quote_path_segment(segment):
    """URL-quote one path segment from the torrent, spaces and all."""
    from urllib.parse import quote as _q
    return _q(segment)


def fetch_metadata(uri):
    """Verify the link and get the .torrent on the server. Prints the summary, returns name."""
    uri = canonical_magnet(uri)
    print("fetching metadata (the swarm answers for it, not the tracker)...")
    print("(dead air here = a dead or tracker-less swarm; live summaries appear as they come)")
    # Streamed: a silent 180s reads as a hang; periodic aria2 lines prove the box is
    # listening. verify runs as hermanb - http's whole job starts at the download.
    script = """
rm -rf %(s)s && mkdir -p %(s)s
if [[ "%(u)s" == magnet:* ]]; then
    timeout 180 aria2c --bt-metadata-only=true --bt-save-metadata=true --seed-time=0 \
        --dir=%(s)s --summary-interval=10 --dht-file-path=/tmp/ytv-dht-verify.dat \
        --console-log-level=notice "%(u)s" || true
else
    curl -fSL --retry 2 -o %(s)s/source.torrent "%(u)s"
fi
ls %(s)s/*.torrent
""" % {"s": STAGING, "u": uri}
    # The streamed pass prints progress; the metadata test itself needs the capture pass.
    ssh(script, stream=True)
    rc, stdout, stderr = ssh("ls %s/*.torrent" % STAGING)
    torrents = [line for line in stdout.splitlines() if line.endswith(".torrent")]
    if not torrents:
        print("""metadata never arrived (180s, zero peers ever answered)

The swarm for this exact infohash is dead. Measured against a control magnet that resolves
in seconds, so this is the content, not your network or this tool. Realistic remedies:
  1. wherever this link came from, take the magnet WITH its &tr= tracker params, or the
     .torrent file - tracker-less magnets rely on DHT alone, which is exactly this death
  2. pick a release that lists seeders > 0 - that number is whether a torrent is alive
  3. retry much later: if the lone seeder ever returns, this same paste resumes cleanly""",
              file=sys.stderr)
        raise SystemExit(1)
    raw_path = torrents[0]
    rc, stdout, _ = ssh("base64 -w0 %s" % raw_path)
    return torrent_summary(base64.b64decode(stdout)), raw_path


def describe(summary):
    name, total, files = summary
    print("\n  name:  %s" % name)
    print("  size:  %.1f GB" % (total / 1073741824))
    print("  files: %d" % (len(files) or 1))
    for rel, size in files[:8]:
        print("    %7.1f MB  %s" % (size / 1048576, rel))
    if len(files) > 8:
        print("    ... and %d more" % (len(files) - 8))
    return name


def main():
    print("ytv ingest - torrents to television. Paste NOTHING unlawful.")
    uri = input("magnet or .torrent url: ").strip()
    if not MAGNET.match(uri) and not TORRENT_URL.match(uri):
        print("that is neither a magnet link nor a .torrent url", file=sys.stderr)
        return 2

    summary, meta_path = fetch_metadata(uri)
    name, total, files = summary
    describe(summary)
    if input("\nlook right? [y/N] ").strip().lower() != "y":
        print("nothing downloaded, nothing changed")
        return 0

    # Archive-style torrents carry everything (sources, score, posters): the dial needs the
    # video and the disk thanks you for the rest staying out. Default-select the video files.
    select = ""
    if len(files) > 1:
        videos = video_indices(files)
        if videos and len(videos) < len(files):
            pick = input("video files only (%d of %d)? [Y/n] "
                         % (len(videos), len(files))).strip().lower()
            if pick != "n":
                select = " --select-file=" + ",".join(map(str, videos))

    print("\n  1) Movies   2) Series   3) a new channel")
    choice = input("where does it go: ").strip()
    new_channel = None
    if choice == "1":
        folder = "Movies"
    elif choice == "2":
        show = input("show name [%s]: " % name).strip() or name
        folder = "Series/" + show
    elif choice == "3":
        channel = input("channel name: ").strip()
        if not channel:
            print("a channel needs a name", file=sys.stderr)
            return 2
        slug = slugify(channel)
        number = next_file_channel_number(confs.default_dir())
        new_channel = (number, slug, channel, channel)
        folder = channel
        print("  -> will become channel %d, slug %s, folder %s/" % (number, slug, channel))
    else:
        return 2

    target = "%s/%s" % (FILES_ROOT, folder)
    print("\ndownloading into %s (ctrl-C is safe; the same link resumes it)" % target)
    # Both shapes download from the verified .torrent on disk: the memory-phase metadata
    # re-fetch for magnets was a dead wait against the same cold swarm. The two dht caches are
    # deliberately per-uid (/tmp, one owner each) because verify runs as hermanb and the
    # download as http - a shared file would belong to whichever ran first.
    src = meta_path
    script = """
sudo -u http mkdir -p {t}
sudo -u http -s /bin/bash -c "aria2c --seed-time=0 --summary-interval=5 \\
    --dht-file-path=/tmp/ytv-dht-http.dat \\
    --console-log-level=warn --show-console-readout=false --dir={t}{sel} {src}"
""".format(t=quote_shell(target), sel=select, src=quote_shell(src))
    outcome = run_download(script)

    if outcome == "stalled":
        base = ia_download_base(uri if TORRENT_URL.match(uri) else None)
        if files:
            wanted = set(video_indices(files)) if select else None
            chosen = [(r, s) for i, (r, s) in enumerate(files)
                      if wanted is None or i + 1 in wanted]
            # aria2 mirrors the torrent's root directory under the target.
            root = name
        else:
            chosen = [(name, total)]
            root = ""  # single-file torrents drop the file straight in the folder
        if base and chosen:
            complete_over_http(base, root, target, chosen)
        else:
            print("stalled and no http twin to finish from - partial kept for resume",
                  file=sys.stderr)
            return 1
    elif outcome != "ok":
        print("\ndownload did not complete (%s); partial kept for resume" % outcome,
              file=sys.stderr)
        return 1

    if new_channel:
        number, slug, channel, chan_folder = new_channel
        dial_path = os.path.join(REPO_ROOT, "curation", "dial.py")
        with open(dial_path, encoding="utf-8") as handle:
            text = handle.read()
        with open(dial_path, "w", encoding="utf-8") as handle:
            handle.write(add_file_channel(text, number, slug, channel, chan_folder))
        subprocess.run([sys.executable, os.path.join(REPO_ROOT, "curation", "apply_dial.py")],
                       check=False)

    print("\n== re-serving, rescanning, reindexing")
    subprocess.run([os.path.join(REPO_ROOT, "tools", "media-server", "deploy.sh")],
                   check=False, cwd=REPO_ROOT)
    print("\n== publish")
    subprocess.run([sys.executable, os.path.join(REPO_ROOT, "curation", "build_lineup.py")],
                   check=False)
    for cmd in (["git", "add", "-A"],
                ["git", "commit", "-m", "ingest: %s" % name],
                ["git", "pull", "--rebase", "origin", "main"],
                ["git", "push", "origin", "main"]):
        rc = subprocess.run(cmd, cwd=REPO_ROOT).returncode
        if rc != 0:
            print("publish step failed: %s - finish by hand (see HANDOVER.md)" % cmd,
                  file=sys.stderr)
            return 1
    print("\non the dial. The televisions pick it up next launch.")
    return 0


def quote_shell(path):
    return "'" + path.replace("'", "'\\''") + "'"


if __name__ == "__main__":
    sys.exit(main())
