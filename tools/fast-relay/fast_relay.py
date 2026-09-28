#!/usr/bin/env python3
"""US-only FAST channels for the televisions in Brisbane, through the pluto-us tunnel.

WHY. iptv-org lists free ad-supported channels - Samsung TV Plus US, Fire TV, Tubi, Plex - as
plain HLS urls with no session to build. From Australia they answer 403 or an empty playlist;
the same urls fetched inside the pluto-us namespace (a Mullvad Los Angeles exit, pluto-ns.sh)
play. Unlike Pluto, where only the boot must come from the US, these check every request, so
every playlist, key and segment has to travel through the tunnel.

HOW. This script runs inside netns pluto-us on its veth address (10.103.1.2:8481), reachable
from the host only. On the host, systemd-socket-proxyd listens on :4247 and copies bytes to it -
a TCP forward, so the television's Host header, its Range and a streamed segment arrive here
untouched, and there is no second HTTP implementation to keep right. ufw opens 4247 to the LAN
and tailscale0 only, as every other ytv port.

  GET /hls?u=<url-encoded upstream>          what the app asks for, the channel's master
  GET /hls/<name>?u=<url-encoded upstream>   what rewritten playlists point at; <name> is the
                                             upstream's file name, so ffmpeg's extension check
                                             on segments (hls extension_picky) finds ".ts"
  GET /health

Every playlist is rewritten so each uri in it - variants, segments, EXT-X-KEY, EXT-X-MAP,
EXT-X-MEDIA and any other URI="..." attribute, relative or absolute - comes back through here,
at the address the television used (its Host header), so the LAN and the tailnet both work.
Anything else streams through in 64KB pieces, never held whole.

NOT AN OPEN PROXY. Only http(s) upstreams, and only to globally routable addresses: every name
is resolved here and the connection made to the address that was checked (no DNS rebinding), and
each redirect hop is checked again - the namespace can reach the host on 10.103.1.1, and nothing
on the LAN or tailnet may be asked for through this. Upstream connections are capped. Logs name
the upstream host and file, never a query string: these urls carry tokens. Of the client's headers
only Range and a plain Accept-Language travel on (Tubi's guide answers no other language).

KEPT ALIVE. The tunnel's exit is ~160ms away, and a fresh TCP and TLS handshake per request was
most of a relayed channel's 7-9s start: its master, playlist and first segments each paid one.
So upstream connections are HTTP/1.1 keep-alive, pooled ([Pool]) per scheme, host, port AND the
address validated for that very request - a pooled connection is only ever reused for a request
whose own check chose the same address, so pooling changes nothing about what may be reached. At
most POOL_PER_HOST idle per host and POOL_TOTAL in all, each closed after POOL_IDLE_SECONDS; one
whose response was not read to its end, or that the server said it would close, is never pooled.
A pooled connection the server closed while idle is retried once on a fresh one.

Stdlib only.
  fast_relay.py --bind 10.103.1.2:8481           (inside netns pluto-us)
"""
import argparse
import http.client
import http.server
import ipaddress
import json
import logging
import re
import socket
import socketserver
import ssl
import threading
import time
import urllib.parse

USER_AGENT = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
TIMEOUT = 15
MAX_REDIRECTS = 5
MAX_PLAYLIST = 4 * 1024 * 1024
CHUNK = 64 * 1024
# Two televisions, each a playlist refresh and a segment or two at once, plus the odd audio
# rendition: 24 is several times that, and small enough that a runaway client cannot hold the
# tunnel.
MAX_UPSTREAM = 24
SEMAPHORE_WAIT = 10
# Idle upstream connections kept for reuse - see Pool. A CDN keeps an idle connection 60s or more;
# 30s covers a playlist refresh cycle and a surf back, and lets go of a channel left behind.
POOL_PER_HOST = 4
POOL_TOTAL = 16
POOL_IDLE_SECONDS = 30
# A redirect's body is read and dropped so its connection can be reused; past this it is closed.
MAX_REDIRECT_BODY = 64 * 1024
PASS_HEADERS = ("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges",
                "Last-Modified", "ETag", "Cache-Control", "Expires")
PLAYLIST_TYPE = "application/vnd.apple.mpegurl"
URI_ATTR = re.compile(r'URI="([^"]*)"')
HOST_HEADER = re.compile(r"^(?:[A-Za-z0-9.-]{1,253}|\[[0-9A-Fa-f:.]{2,45}\])(?::\d{1,5})?$")
NAME_UNSAFE = re.compile(r"[^A-Za-z0-9._-]")
LANGUAGE = re.compile(r"[A-Za-z0-9*,;=. -]{1,64}")

log = logging.getLogger("fast-relay")


class Refused(Exception):
    """An upstream this relay will not fetch; the message is safe to log and return."""


def _global(address):
    ip = ipaddress.ip_address(address.split("%", 1)[0])
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return ip.is_global and not ip.is_multicast


def validate(url, resolve=socket.getaddrinfo):
    """(parts, address to connect to) for an upstream the relay may fetch, or raise Refused.

    Every address the name resolves to must be global, not just the first: a name answering with
    one public and one private address is exactly how a rebinding attack would be dressed.
    """
    try:
        parts = urllib.parse.urlsplit(url)
        port = parts.port
    except ValueError:
        raise Refused("malformed url")
    if parts.scheme not in ("http", "https"):
        raise Refused("only http and https upstreams")
    if not parts.hostname:
        raise Refused("no host")
    if parts.username is not None or parts.password is not None:
        raise Refused("credentials in url")
    port = port or (443 if parts.scheme == "https" else 80)
    try:
        infos = resolve(parts.hostname, port, 0, socket.SOCK_STREAM)
    except (socket.gaierror, UnicodeError):
        raise Refused("host does not resolve")
    addresses = [info[4][0] for info in infos]
    if not addresses:
        raise Refused("host does not resolve")
    if not all(_global(a) for a in addresses):
        raise Refused("private, loopback or link-local target")
    # IPv4 first: the tunnel carries IPv4 (pluto-ns.sh gives it no IPv6 address). Then in a fixed
    # order, not the resolver's: CDNs (CloudFront) rotate their answer on every lookup, and a pick
    # that moved with it found no pooled connection half the time. Every address is checked above.
    addresses.sort(key=lambda a: (":" in a, a))
    return parts, addresses[0]


def file_name(url):
    """The upstream's last path segment, safe for a path - "seg" when it has none."""
    path = urllib.parse.urlsplit(url).path
    name = NAME_UNSAFE.sub("_", urllib.parse.unquote(path.rsplit("/", 1)[-1]))[-80:]
    return name.lstrip(".") or "seg"


def relay_link(relay_base, absolute):
    return "%s/hls/%s?u=%s" % (relay_base, file_name(absolute), urllib.parse.quote(absolute, safe=""))


def _rewrite_uri(uri, base_url, relay_base):
    stripped = uri.strip()
    if not stripped:
        return uri
    absolute = urllib.parse.urljoin(base_url, stripped)
    # skd://, data: and the like are not fetched over http; the player handles them itself.
    if urllib.parse.urlsplit(absolute).scheme not in ("http", "https"):
        return uri
    return relay_link(relay_base, absolute)


def rewrite_playlist(text, base_url, relay_base):
    """[text], a master or media playlist fetched from [base_url] (the url after redirects), with
    every uri in it pointing back through the relay at [relay_base]. Tags without a uri - the
    byte ranges, durations, discontinuities - are left exactly as they were."""
    out = []
    for line in text.splitlines(keepends=True):
        body = line.rstrip("\r\n")
        ending = line[len(body):]
        if body.startswith("#"):
            body = URI_ATTR.sub(
                lambda m: 'URI="%s"' % _rewrite_uri(m.group(1), base_url, relay_base), body)
        elif body.strip():
            body = _rewrite_uri(body, base_url, relay_base)
        out.append(body + ending)
    return "".join(out)


def looks_like_playlist(content_type, head):
    if "mpegurl" in (content_type or "").lower():
        return True
    return head.lstrip(b"\xef\xbb\xbf \t\r\n").startswith(b"#EXTM3U")


def relay_base_of(host_header):
    """http://<the address the television used>, or None for a Host header that is not one."""
    host = (host_header or "").strip()
    return "http://" + host if HOST_HEADER.match(host) else None


def safe_label(url):
    """The upstream as it may be logged: host and file name, never the query or the path."""
    parts = urllib.parse.urlsplit(url)
    return "%s/%s" % (parts.hostname or "?", file_name(url)[:40])


class _Pinned(http.client.HTTPConnection):
    """Connects to the address [validate] checked, whatever the name resolves to now."""

    def __init__(self, host, port, address, timeout):
        super().__init__(host, port, timeout=timeout)
        self._address = address

    def connect(self):
        self.sock = socket.create_connection((self._address, self.port), self.timeout)


class _PinnedTLS(http.client.HTTPSConnection):
    def __init__(self, host, port, address, timeout, context):
        super().__init__(host, port, timeout=timeout, context=context)
        self._address = address
        self._tls = context

    def connect(self):
        sock = socket.create_connection((self._address, self.port), self.timeout)
        self.sock = self._tls.wrap_socket(sock, server_hostname=self.host)


_TLS = ssl.create_default_context()


class Pool:
    """Idle keep-alive upstream connections, by (scheme, host, port, validated address).

    Thread-safe. [take] hands back the most recently idle connection for a key, or None; [give]
    keeps one for later, or closes it when the caps are reached. Anything idle past [idle_seconds]
    is closed on the next take, give or sweep.
    """

    def __init__(self, per_host=POOL_PER_HOST, total=POOL_TOTAL, idle_seconds=POOL_IDLE_SECONDS,
                 clock=time.monotonic):
        self._per_host = per_host
        self._total = total
        self._idle_seconds = idle_seconds
        self._clock = clock
        self._lock = threading.Lock()
        self._idle = {}

    def take(self, key):
        with self._lock:
            self._sweep_locked()
            idle = self._idle.get(key)
            while idle:
                conn, _ = idle.pop()
                if not idle:
                    del self._idle[key]
                if conn.sock is not None:
                    return conn
                conn.close()
        return None

    def give(self, key, conn):
        """Keep [conn] for [key]; False when it was closed instead."""
        with self._lock:
            self._sweep_locked()
            count = sum(len(v) for v in self._idle.values())
            idle = self._idle.get(key, [])
            if conn.sock is None or len(idle) >= self._per_host or count >= self._total:
                conn.close()
                return False
            idle.append((conn, self._clock()))
            self._idle[key] = idle
            return True

    def sweep(self):
        with self._lock:
            self._sweep_locked()

    def clear(self):
        with self._lock:
            for idle in self._idle.values():
                for conn, _ in idle:
                    conn.close()
            self._idle.clear()

    def __len__(self):
        with self._lock:
            return sum(len(v) for v in self._idle.values())

    def _sweep_locked(self):
        now = self._clock()
        for key in list(self._idle):
            kept = []
            for conn, since in self._idle[key]:
                if now - since >= self._idle_seconds:
                    conn.close()
                else:
                    kept.append((conn, since))
            if kept:
                self._idle[key] = kept
            else:
                del self._idle[key]


POOL = Pool()


def _key(parts, address):
    port = parts.port or (443 if parts.scheme == "https" else 80)
    return (parts.scheme, parts.hostname.lower(), port, address)


def _connect(parts, address):
    port = parts.port or (443 if parts.scheme == "https" else 80)
    if parts.scheme == "https":
        return _PinnedTLS(parts.hostname, port, address, TIMEOUT, _TLS)
    return _Pinned(parts.hostname, port, address, TIMEOUT)


def _send(parts, address, path, headers, pool):
    """(connection, response): on a pooled connection when one is idle for this very address -
    retried once on a fresh one if the server had closed it - else on a new connection."""
    conn = pool.take(_key(parts, address)) if pool is not None else None
    if conn is not None:
        try:
            conn.request("GET", path, headers=headers)
            return conn, conn.getresponse()
        except (http.client.HTTPException, OSError):
            # Closed by the server while idle: a GET is safe to send again, once, fresh.
            conn.close()
    conn = _connect(parts, address)
    try:
        conn.request("GET", path, headers=headers)
        return conn, conn.getresponse()
    except BaseException:
        conn.close()
        raise


def finish(conn, response, key, pool=None):
    """Done with [response]: its connection back to the pool when the body was read to its end
    and the server will keep it open - else closed."""
    pool = POOL if pool is None else pool
    if key is not None and response is not None and response.isclosed() and not response.will_close:
        pool.give(key, conn)
    else:
        conn.close()


def open_upstream(url, headers, resolve=socket.getaddrinfo, pool=None):
    """(connection, response, final url, pool key) for [url], redirects followed and each hop
    validated. Hand the connection back with [finish] once the response is done with."""
    pool = POOL if pool is None else pool
    for _ in range(MAX_REDIRECTS + 1):
        parts, address = validate(url, resolve)
        path = (parts.path or "/") + ("?" + parts.query if parts.query else "")
        conn, response = _send(parts, address, path, headers, pool)
        key = _key(parts, address)
        location = response.getheader("Location")
        if response.status in (301, 302, 303, 307, 308) and location:
            try:
                response.read(MAX_REDIRECT_BODY)
            except (http.client.HTTPException, OSError):
                pass
            finish(conn, response, key, pool)
            url = urllib.parse.urljoin(url, location)
            continue
        return conn, response, url, key
    raise Refused("too many redirects")


class _Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    # An idle keep-alive connection gives its thread back after a minute.
    timeout = 60
    upstream_slots = threading.BoundedSemaphore(MAX_UPSTREAM)

    def log_message(self, *args):
        pass

    def _plain(self, status, message):
        body = (json.dumps({"error": message}) if status >= 400 else json.dumps(message)).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        if url.path == "/health":
            return self._plain(200, {"ok": True})
        if url.path != "/hls" and not url.path.startswith("/hls/"):
            return self._plain(404, "not found")
        upstream = (urllib.parse.parse_qs(url.query).get("u") or [""])[0]
        relay_base = relay_base_of(self.headers.get("Host"))
        if not upstream or relay_base is None:
            return self._plain(400, "needs ?u= and a Host header")
        if not self.upstream_slots.acquire(timeout=SEMAPHORE_WAIT):
            log.warning("busy: refused %s", safe_label(upstream))
            return self._plain(503, "relay busy")
        try:
            self._relay(upstream, relay_base)
        except Refused as e:
            log.warning("refused %s: %s", safe_label(upstream), e)
            self._plain(403, str(e))
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True
        except Exception as e:
            log.warning("upstream %s failed: %s", safe_label(upstream), type(e).__name__)
            self._error_if_unsent(502, "upstream failed: %s" % type(e).__name__)
        finally:
            self.upstream_slots.release()

    def _error_if_unsent(self, status, message):
        # Once a streamed reply has begun, the only honest signal left is a cut connection.
        if getattr(self, "_started", False):
            self.close_connection = True
        else:
            self._plain(status, message)

    def _relay(self, upstream, relay_base):
        self._started = False
        headers = {"User-Agent": USER_AGENT, "Accept": "*/*", "Accept-Encoding": "identity",
                   "Connection": "keep-alive"}
        range_header = self.headers.get("Range")
        is_m3u8 = urllib.parse.urlsplit(upstream).path.lower().endswith((".m3u8", ".m3u"))
        if range_header and not is_m3u8:
            headers["Range"] = range_header
        language = self.headers.get("Accept-Language") or ""
        if LANGUAGE.fullmatch(language):
            headers["Accept-Language"] = language
        started = time.monotonic()
        conn, response, final_url, key = open_upstream(upstream, headers)
        try:
            head = response.read(64) if response.status == 200 else b""
            if response.status == 200 and looks_like_playlist(response.getheader("Content-Type"), head):
                self._send_playlist(response, head, final_url, relay_base)
                log.info("playlist %s in %dms", safe_label(final_url),
                         (time.monotonic() - started) * 1000)
            else:
                self._stream(response, head)
                if response.status >= 400:
                    log.warning("upstream %s answered %d", safe_label(final_url), response.status)
        except BaseException:
            key = None
            raise
        finally:
            # Only a response read to its end, with nothing raised, gives its connection back.
            finish(conn, response, key)

    def _send_playlist(self, response, head, final_url, relay_base):
        body = head + response.read(MAX_PLAYLIST + 1 - len(head))
        if len(body) > MAX_PLAYLIST:
            raise ValueError("playlist too large")
        text = rewrite_playlist(body.decode("utf-8", errors="replace"), final_url, relay_base)
        payload = text.encode("utf-8")
        self._started = True
        self.send_response(200)
        self.send_header("Content-Type", PLAYLIST_TYPE)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(payload)

    def _stream(self, response, head):
        self._started = True
        self.send_response(response.status)
        for name in PASS_HEADERS:
            value = response.getheader(name)
            if value is not None:
                self.send_header(name, value)
        if response.getheader("Content-Length") is None:
            # No length to frame the body with: the end of the connection frames it.
            self.send_header("Connection", "close")
            self.close_connection = True
        self.end_headers()
        if head:
            self.wfile.write(head)
        while True:
            chunk = response.read1(CHUNK)
            if not chunk:
                break
            self.wfile.write(chunk)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--bind", required=True, help="host:port, the namespace's veth address")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    host, port = args.bind.rsplit(":", 1)
    log.info("relaying on %s", args.bind)

    def sweep():
        while True:
            time.sleep(POOL_IDLE_SECONDS)
            POOL.sweep()
    threading.Thread(target=sweep, name="pool-sweep", daemon=True).start()
    _Server((host, int(port)), Handler).serve_forever()


if __name__ == "__main__":
    main()
