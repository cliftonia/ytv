#!/usr/bin/env python3
"""Pluto TV sessions from the US and the UK, for the televisions in Brisbane.

WHY. Pluto decides what a stream shows when its session is created: boot.pluto.tv/v4/start reads
the caller's address and fixes the session's region. Most of the dial plays on an Australian
session, but some channels show only Pluto's logo unless the session is from the channel's home
country (measured Sep 2026: Home.Made.Nation, Homeful). Once created, a session's playlists and
video are served to any address - measured: a US session built through the tunnel, then its
playlist, segments and key fetched directly from Brisbane, all 200 and all programme. So only the
one boot request needs to leave from the US or UK; nothing else touches the VPN.

HOW. Two Mullvad WireGuard tunnels, each in its own network namespace (pluto-us, pluto-uk;
set up by pluto-ns.sh), so nothing else on this box is routed through them. In each namespace
runs this script as `--boot`, answering one question - "a fresh session, please" - by calling
boot.pluto.tv from inside the tunnel. On the host runs this script as `--front`, the only part
the televisions reach: GET /pluto/session?region=us|uk forwards to the right namespace.

ONE SESSION PER TELEVISION. Pluto allows one stream per session (Channels DVR users found this
in 2025; FastChannels keeps a pool for it). So the front caches a session per (caller, region)
and never hands one television's session to another - two sets on one session would knock each
other off. Sessions are refreshed well inside their lifetime.

Anonymous: no Pluto account, as FastChannels runs by default. Stdlib only.

  pluto_sessions.py --boot  --bind 10.103.1.2:8480           (inside netns pluto-us)
  pluto_sessions.py --front --bind 0.0.0.0:4246 \\
      --upstream us=http://10.103.1.2:8480 --upstream uk=http://10.103.2.2:8480
"""
import argparse
import http.server
import json
import socketserver
import threading
import time
import urllib.parse
import urllib.request
import uuid

BOOT_URL = "https://boot.pluto.tv/v4/start"
# The web client's boot, as maintained tools send it (FastChannels app/scrapers/pluto.py):
# no DRM capability declared, server-side ads off.
BOOT_PARAMS = {
    "appName": "web",
    "appVersion": "9.1.0",
    "deviceVersion": "122.0.0",
    "deviceModel": "web",
    "deviceMake": "chrome",
    "deviceType": "web",
    "clientModelNumber": "1.0.0",
    "serverSideAds": "false",
    "drmCapabilities": "",
}
USER_AGENT = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
# Tools cache a boot for four hours; handing out one that old leaves a television minutes before
# its token lapses. Three hours, and the television refreshes on its own schedule too.
SESSION_TTL = 3 * 3600
REGIONS = ("us", "uk")


def boot():
    """A fresh anonymous Pluto session, as the television needs it to build stream urls."""
    params = dict(BOOT_PARAMS, clientID=str(uuid.uuid4()))
    request = urllib.request.Request(BOOT_URL + "?" + urllib.parse.urlencode(params),
                                     headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=20) as response:
        body = json.load(response)
    session = body.get("session") or {}
    return {
        "stitcher": body["servers"]["stitcher"],
        "stitcherParams": body["stitcherParams"],
        "jwt": body["sessionToken"],
        "region": session.get("activeRegion", ""),
        "issuedAt": int(time.time()),
        "expiresAt": int(time.time()) + SESSION_TTL,
    }


class _Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def _reply(handler, status, payload):
    body = json.dumps(payload).encode()
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json")
    handler.send_header("Content-Length", str(len(body)))
    handler.send_header("Cache-Control", "no-store")
    handler.end_headers()
    handler.wfile.write(body)


def serve_boot(bind):
    """Inside a namespace: every GET /boot is a fresh session from this tunnel's country."""
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            if self.path != "/boot":
                return _reply(self, 404, {"error": "not found"})
            try:
                _reply(self, 200, boot())
            except Exception as e:
                _reply(self, 502, {"error": "boot failed: %s" % type(e).__name__})

    host, port = bind.rsplit(":", 1)
    _Server((host, int(port)), Handler).serve_forever()


class SessionCache:
    """One session per (caller, region), reused until it is due for refresh. Pure, for tests."""

    def __init__(self, fetch, now=time.time, refresh_margin=1800):
        self._fetch = fetch
        self._now = now
        self._margin = refresh_margin
        self._held = {}
        self._lock = threading.Lock()

    def get(self, caller, region):
        key = (caller, region)
        with self._lock:
            held = self._held.get(key)
            if held and held["expiresAt"] - self._margin > self._now():
                return held
        fresh = self._fetch(region)
        with self._lock:
            self._held[key] = fresh
            # Televisions come and go; forget sessions long past their use.
            cutoff = self._now()
            for k in [k for k, v in self._held.items() if v["expiresAt"] < cutoff]:
                del self._held[k]
        return fresh


def serve_front(bind, upstreams):
    def fetch(region):
        with urllib.request.urlopen(upstreams[region] + "/boot", timeout=25) as response:
            return json.load(response)

    cache = SessionCache(fetch)

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            url = urllib.parse.urlparse(self.path)
            if url.path == "/health":
                return _reply(self, 200, {"ok": True, "regions": sorted(upstreams)})
            if url.path != "/pluto/session":
                return _reply(self, 404, {"error": "not found"})
            region = (urllib.parse.parse_qs(url.query).get("region") or [""])[0]
            if region not in upstreams:
                return _reply(self, 400, {"error": "region must be one of %s" % sorted(upstreams)})
            try:
                _reply(self, 200, cache.get(self.client_address[0], region))
            except Exception as e:
                # The television falls back to its own Australian session on anything but 200.
                _reply(self, 502, {"error": "%s session unavailable: %s" % (region, type(e).__name__)})

    host, port = bind.rsplit(":", 1)
    _Server((host, int(port)), Handler).serve_forever()


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--boot", action="store_true", help="run inside a tunnel namespace")
    mode.add_argument("--front", action="store_true", help="run on the host, for the televisions")
    parser.add_argument("--bind", required=True, help="host:port")
    parser.add_argument("--upstream", action="append", default=[], help="region=http://ip:port")
    args = parser.parse_args()
    if args.boot:
        serve_boot(args.bind)
    else:
        upstreams = dict(u.split("=", 1) for u in args.upstream)
        unknown = set(upstreams) - set(REGIONS)
        if not upstreams or unknown:
            parser.error("--upstream needs us=... and/or uk=... (unknown: %s)" % sorted(unknown))
        serve_front(args.bind, upstreams)


if __name__ == "__main__":
    main()
