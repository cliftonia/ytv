#!/usr/bin/env python3
"""Serve the mirrored ad reels (mirror_ads.py) to the televisions, with Range support.

    serve_ads.py --bind 0.0.0.0:4245 --dir ~/.cache/ytv-ads/mirror

  GET|HEAD /ads/<id>.mp4   the reel, 200 whole or 206 for one byte range (416 when unsatisfiable)
  GET      /health         {"ok": true, "reels": <count>}

WHY A SERVICE OF ITS OWN, on :4245, rather than a route on the resolve accelerator (:4243) or a
path on the media Caddy (:4244): the accelerator is FastAPI, already at the repo's 500-line
ceiling, and whether its Starlette answers Range depends on a version this repo does not pin -
and it restarts whenever yt-dlp needs a fix. Caddy runs as `http`, which cannot read
/home/hermanb (750, as it should be). This runs as the user who owns the mirror, stdlib only,
beside the job that fills it, like pluto_sessions.py on :4246.

Range matters: the players read progressive files as bounded byte ranges, and the join into a
reel is a seek to a cut mid-file. One range per request (what players send); a multi-range
request is answered with the whole file, which RFC 9110 allows.

Read-only, no directory listing, and only names matching an archive.org identifier: nothing on
disk but <dir>/<id>.mp4 is reachable. ufw limits the port to the LAN and tailscale0.
"""
import argparse
import http.server
import json
import os
import re
import socketserver
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mirror_ads import SUFFIX, path_for, valid_id  # noqa: E402

ROUTE = re.compile(r"^/ads/([^/]+)\.mp4$")
CHUNK = 256 * 1024


def parse_range(header, size):
    """(start, end inclusive) for a single `bytes=` range, None to send the whole file, or
    "unsatisfiable". A malformed or multi-range header is ignored, as RFC 9110 permits."""
    if not header or size <= 0:
        return None
    match = re.fullmatch(r"\s*bytes\s*=\s*(\d*)\s*-\s*(\d*)\s*", header)
    if not match:
        return None
    first, last = match.groups()
    if not first and not last:
        return None
    if not first:  # suffix: the last N bytes
        length = int(last)
        if length == 0:
            return "unsatisfiable"
        return max(0, size - length), size - 1
    start = int(first)
    if start >= size:
        return "unsatisfiable"
    end = min(int(last), size - 1) if last else size - 1
    if end < start:
        return None
    return start, end


class _Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def make_handler(directory):
    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass  # a request per range per reel would bury the journal

        def do_HEAD(self):
            self._answer(body=False)

        def do_GET(self):
            self._answer(body=True)

        def _answer(self, body):
            path = self.path.split("?", 1)[0]
            if path == "/health":
                return self._json(200, {"ok": True, "reels": _count(directory)})
            match = ROUTE.match(path)
            if not match or not valid_id(match.group(1)):
                return self._json(404, {"error": "not found"})
            try:
                handle = open(path_for(directory, match.group(1)), "rb")
            except OSError:
                return self._json(404, {"error": "not mirrored"})
            with handle:
                size = os.fstat(handle.fileno()).st_size
                wanted = parse_range(self.headers.get("Range"), size)
                if wanted == "unsatisfiable":
                    self.send_response(416)
                    self.send_header("Content-Range", "bytes */%d" % size)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                start, end = wanted if wanted else (0, size - 1)
                self.send_response(206 if wanted else 200)
                self.send_header("Content-Type", "video/mp4")
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Length", str(end - start + 1))
                if wanted:
                    self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
                self.end_headers()
                if body:
                    self._copy(handle, start, end - start + 1)

        def _copy(self, handle, start, remaining):
            handle.seek(start)
            try:
                while remaining > 0:
                    chunk = handle.read(min(CHUNK, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                # A player seeking closes the connection mid-range; that is not an error.
                self.close_connection = True

        def _json(self, status, payload):
            data = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(data)

    return Handler


def _count(directory):
    try:
        return sum(1 for n in os.listdir(directory) if n.endswith(SUFFIX))
    except OSError:
        return 0


def serve(bind, directory):
    host, port = bind.rsplit(":", 1)
    server = _Server((host, int(port)), make_handler(directory))
    print("serving %s on %s" % (directory, bind), flush=True)
    server.serve_forever()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--bind", default="0.0.0.0:4245")
    parser.add_argument("--dir", default=os.path.expanduser("~/.cache/ytv-ads/mirror"))
    args = parser.parse_args(argv)
    os.makedirs(args.dir, exist_ok=True)
    serve(args.bind, args.dir)


if __name__ == "__main__":
    main()
