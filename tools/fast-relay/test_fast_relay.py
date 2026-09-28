#!/usr/bin/env python3
"""The relay's two rules that matter: every uri in a playlist comes back through it, and it never
fetches anything but a public http(s) address - the namespace can reach the host, and the host
the LAN."""
import http.server
import os
import socket
import sys
import threading
import unittest
import urllib.parse
import urllib.request
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fast_relay

RELAY = "http://192.168.4.58:4247"


def through(url):
    return fast_relay.relay_link(RELAY, url)


def resolver(*addresses):
    def resolve(host, port, family, kind):
        return [(socket.AF_INET6 if ":" in a else socket.AF_INET, kind, 6, "", (a, port))
                for a in addresses]
    return resolve


class TestRewrite(unittest.TestCase):

    def test_master_variants_and_media_renditions(self):
        master = (
            "#EXTM3U\n"
            '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="en",URI="audio/en.m3u8"\n'
            "#EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO=\"aud\"\n"
            "720p/index.m3u8\n"
            "#EXT-X-STREAM-INF:BANDWIDTH=800000\n"
            "https://other.cdn.example/360p.m3u8?token=abc&x=1\n"
        )
        out = fast_relay.rewrite_playlist(master, "https://cdn.example/live/master.m3u8", RELAY)
        lines = out.splitlines()
        self.assertEqual(lines[0], "#EXTM3U")
        self.assertIn('URI="%s"' % through("https://cdn.example/live/audio/en.m3u8"), lines[1])
        self.assertTrue(lines[1].startswith('#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="en",'))
        self.assertEqual(lines[2], '#EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO="aud"')
        self.assertEqual(lines[3], through("https://cdn.example/live/720p/index.m3u8"))
        self.assertEqual(lines[5], through("https://other.cdn.example/360p.m3u8?token=abc&x=1"))

    def test_media_relative_root_relative_and_absolute_segments(self):
        media = (
            "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:100\n"
            "#EXTINF:6.0,\nseg100.ts\n"
            "#EXTINF:6.0,\n/abs/seg101.ts?sig=q\n"
            "#EXTINF:6.0,\nhttps://seg.example/seg102.ts\n"
            "#EXT-X-DISCONTINUITY\n#EXTINF:6.0,\n../ad/ad1.ts\n"
        )
        out = fast_relay.rewrite_playlist(media, "https://cdn.example/a/b/index.m3u8", RELAY)
        lines = out.splitlines()
        self.assertEqual(lines[4], through("https://cdn.example/a/b/seg100.ts"))
        self.assertEqual(lines[6], through("https://cdn.example/abs/seg101.ts?sig=q"))
        self.assertEqual(lines[8], through("https://seg.example/seg102.ts"))
        self.assertEqual(lines[9], "#EXT-X-DISCONTINUITY")
        self.assertEqual(lines[11], through("https://cdn.example/a/ad/ad1.ts"))
        self.assertEqual(lines[1:4], ["#EXT-X-TARGETDURATION:6", "#EXT-X-MEDIA-SEQUENCE:100", "#EXTINF:6.0,"])

    def test_key_and_map_uris_and_untouched_byte_ranges(self):
        media = (
            "#EXTM3U\r\n"
            '#EXT-X-KEY:METHOD=AES-128,URI="keys/k1.key",IV=0x1234\r\n'
            '#EXT-X-MAP:URI="init.mp4",BYTERANGE="720@0"\r\n'
            "#EXTINF:4.0,\r\n#EXT-X-BYTERANGE:50000@720\r\nmain.mp4\r\n"
        )
        base = "https://cdn.example/v/index.m3u8"
        out = fast_relay.rewrite_playlist(media, base, RELAY)
        lines = out.split("\r\n")
        self.assertEqual(lines[1], '#EXT-X-KEY:METHOD=AES-128,URI="%s",IV=0x1234'
                         % through("https://cdn.example/v/keys/k1.key"))
        self.assertEqual(lines[2], '#EXT-X-MAP:URI="%s",BYTERANGE="720@0"'
                         % through("https://cdn.example/v/init.mp4"))
        self.assertEqual(lines[4], "#EXT-X-BYTERANGE:50000@720")
        self.assertEqual(lines[5], through("https://cdn.example/v/main.mp4"))
        self.assertTrue(out.endswith("\r\n"), "line endings are kept")

    def test_non_http_key_uris_are_left_to_the_player(self):
        text = '#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://key42"\n#EXT-X-KEY:METHOD=NONE\n'
        self.assertEqual(fast_relay.rewrite_playlist(text, "https://c.example/i.m3u8", RELAY), text)

    def test_relay_links_carry_the_file_name_and_the_whole_encoded_upstream(self):
        link = through("https://c.example/p/seg%2001.ts?a=1&b=2")
        parts = urllib.parse.urlsplit(link)
        self.assertEqual(parts.path, "/hls/seg_01.ts")
        self.assertEqual(urllib.parse.parse_qs(parts.query)["u"], ["https://c.example/p/seg%2001.ts?a=1&b=2"])
        self.assertEqual(fast_relay.file_name("https://c.example/"), "seg")
        self.assertEqual(fast_relay.file_name("https://c.example/..%2F..%2Fetc"), "_.._etc")

    def test_relay_base_is_the_address_the_television_used(self):
        self.assertEqual(fast_relay.relay_base_of("100.74.3.68:4247"), "http://100.74.3.68:4247")
        self.assertEqual(fast_relay.relay_base_of("[fd7a::1]:4247"), "http://[fd7a::1]:4247")
        for bad in (None, "", "evil.example/x", 'a"b:4247', "a b", "h:4247\r\nX: y"):
            self.assertIsNone(fast_relay.relay_base_of(bad), bad)

    def test_playlist_sniffing(self):
        self.assertTrue(fast_relay.looks_like_playlist("application/x-mpegURL", b""))
        self.assertTrue(fast_relay.looks_like_playlist("text/plain", b"\xef\xbb\xbf#EXTM3U\n"))
        self.assertFalse(fast_relay.looks_like_playlist("video/mp2t", b"G@\x11\x10"))

    def test_logged_labels_drop_query_and_path(self):
        label = fast_relay.safe_label("https://cdn.example/tok3n/secret/seg1.ts?token=abc")
        self.assertEqual(label, "cdn.example/seg1.ts")


class TestValidate(unittest.TestCase):

    def check(self, url, *addresses):
        return fast_relay.validate(url, resolver(*addresses))

    def test_public_http_and_https_pass(self):
        parts, address = self.check("https://cdn.example/x.m3u8", "2606:4700::1", "104.16.1.1")
        self.assertEqual(address, "104.16.1.1", "IPv4 first: the tunnel has no IPv6")
        self.assertEqual(self.check("http://cdn.example:8080/x", "8.8.8.8")[1], "8.8.8.8")

    def test_only_http_and_https(self):
        for url in ("file:///etc/passwd", "ftp://cdn.example/x", "gopher://cdn.example/",
                    "//cdn.example/x", "cdn.example/x"):
            with self.assertRaises(fast_relay.Refused, msg=url):
                self.check(url, "8.8.8.8")

    def test_private_loopback_link_local_and_friends_are_refused(self):
        for address in ("127.0.0.1", "10.103.1.1", "10.64.0.1", "192.168.4.58", "172.16.0.9",
                        "169.254.169.254", "100.74.3.68", "0.0.0.0", "::1", "fe80::1",
                        "fd7a:115c:a1e0::1", "::ffff:192.168.4.1", "224.0.0.1"):
            with self.assertRaises(fast_relay.Refused, msg=address):
                self.check("http://sneaky.example/", address)

    def test_ip_literals_are_checked_too(self):
        with self.assertRaises(fast_relay.Refused):
            fast_relay.validate("http://192.168.4.1:8080/admin")
        with self.assertRaises(fast_relay.Refused):
            fast_relay.validate("http://[::1]/")

    def test_one_private_answer_among_public_ones_refuses(self):
        with self.assertRaises(fast_relay.Refused):
            self.check("https://rebind.example/", "8.8.8.8", "192.168.4.1")

    def test_credentials_and_unresolvable_names_are_refused(self):
        with self.assertRaises(fast_relay.Refused):
            self.check("https://user:pw@cdn.example/", "8.8.8.8")

        def fails(*args):
            raise socket.gaierror("no")
        with self.assertRaises(fast_relay.Refused):
            fast_relay.validate("https://nowhere.invalid/", fails)


class _Conn:
    """Enough of an HTTPConnection for the pool: a socket, and whether it was closed."""

    def __init__(self):
        self.sock = object()
        self.closed = False

    def close(self):
        self.closed = True
        self.sock = None


class TestPool(unittest.TestCase):

    def setUp(self):
        self.now = 0.0
        self.pool = fast_relay.Pool(per_host=2, total=3, idle_seconds=30, clock=lambda: self.now)

    def test_a_connection_is_reused_only_for_the_same_host_and_validated_address(self):
        key = ("https", "cdn.example", 443, "104.16.1.1")
        conn = _Conn()
        self.assertTrue(self.pool.give(key, conn))
        self.assertIsNone(self.pool.take(("https", "cdn.example", 443, "104.16.9.9")),
                          "another address - another validation's answer - never gets it")
        self.assertIsNone(self.pool.take(("http", "cdn.example", 443, "104.16.1.1")))
        self.assertIs(self.pool.take(key), conn)
        self.assertIsNone(self.pool.take(key), "handed out once")

    def test_caps_per_host_and_in_all(self):
        a, b = ("https", "a.example", 443, "1.1.1.1"), ("https", "b.example", 443, "2.2.2.2")
        conns = [_Conn() for _ in range(5)]
        self.assertTrue(self.pool.give(a, conns[0]))
        self.assertTrue(self.pool.give(a, conns[1]))
        self.assertFalse(self.pool.give(a, conns[2]))
        self.assertTrue(conns[2].closed)
        self.assertTrue(self.pool.give(b, conns[3]))
        self.assertFalse(self.pool.give(b, conns[4]), "three idle in all at most")
        self.assertEqual(len(self.pool), 3)

    def test_idle_connections_are_closed_after_the_timeout(self):
        key = ("https", "a.example", 443, "1.1.1.1")
        conn = _Conn()
        self.pool.give(key, conn)
        self.now += 31
        self.pool.sweep()
        self.assertTrue(conn.closed)
        self.assertIsNone(self.pool.take(key))

    def test_a_closed_connection_is_never_handed_out(self):
        key = ("https", "a.example", 443, "1.1.1.1")
        conn = _Conn()
        self.pool.give(key, conn)
        conn.close()
        self.assertIsNone(self.pool.take(key))


class _Upstream(http.server.BaseHTTPRequestHandler):
    """A stand-in CDN: a redirecting master, a media playlist and a segment that honours Range.
    HTTP/1.1 keep-alive, like a real one, counting the connections it is asked on."""
    segment = bytes(range(256)) * 400
    protocol_version = "HTTP/1.1"
    connections = 0

    def setup(self):
        super().setup()
        type(self).connections += 1

    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path == "/abrupt.m3u8":
            # Answers, then drops the connection without saying so - an idle timeout on the CDN.
            body = b"#EXTM3U\n#EXTINF:6.0,\nseg1.ts\n"
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.apple.mpegurl")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            self.close_connection = True
        elif self.path == "/start.m3u8":
            self.send_response(302)
            self.send_header("Location", "/live/index.m3u8")
            self.send_header("Content-Length", "0")
            self.end_headers()
        elif self.path == "/live/index.m3u8":
            body = b"#EXTM3U\n#EXTINF:6.0,\nseg1.ts\n"
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.apple.mpegurl")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path == "/live/seg1.ts":
            data, status = self.segment, 200
            wanted = self.headers.get("Range")
            if wanted:
                start, end = (int(n) for n in wanted.split("=")[1].split("-"))
                data, status = self.segment[start:end + 1], 206
            self.send_response(status)
            self.send_header("Content-Type", "video/mp2t")
            self.send_header("Content-Length", str(len(data)))
            if wanted:
                self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, len(self.segment)))
            self.end_headers()
            self.wfile.write(data)
        else:
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()


class TestRelayEndToEnd(unittest.TestCase):
    """The real handler against a local upstream - loopback allowed here only, by patching."""

    @classmethod
    def setUpClass(cls):
        cls.servers = []
        for handler in (_Upstream, fast_relay.Handler):
            server = fast_relay._Server(("127.0.0.1", 0), handler)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            cls.servers.append(server)
        cls.upstream = "http://127.0.0.1:%d" % cls.servers[0].server_address[1]
        cls.relay = "http://127.0.0.1:%d" % cls.servers[1].server_address[1]

    @classmethod
    def tearDownClass(cls):
        for server in cls.servers:
            server.shutdown()
            server.server_close()

    def setUp(self):
        fast_relay.POOL.clear()

    def get(self, url, **headers):
        return urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=5)

    def fetch(self, path):
        u = urllib.parse.quote(self.upstream + path, safe="")
        with mock.patch.object(fast_relay, "_global", return_value=True):
            with self.get(self.relay + "/hls?u=" + u) as response:
                return response.read()

    def test_upstream_connections_are_reused_across_requests_and_redirects(self):
        before = _Upstream.connections
        for _ in range(3):
            self.fetch("/start.m3u8")
            self.fetch("/live/seg1.ts")
        self.assertEqual(_Upstream.connections - before, 1,
                         "one connection for three redirects, three playlists and three segments")
        self.assertEqual(len(fast_relay.POOL), 1)

    def test_a_connection_the_upstream_dropped_while_idle_is_retried_fresh(self):
        self.fetch("/abrupt.m3u8")
        self.assertEqual(len(fast_relay.POOL), 1, "it looked reusable")
        before = _Upstream.connections
        self.assertIn(b"#EXTM3U", self.fetch("/live/index.m3u8"))
        self.assertEqual(_Upstream.connections - before, 1)

    def test_only_a_response_read_to_its_end_on_a_kept_connection_goes_back_in_the_pool(self):
        class Response:
            def __init__(self, closed, will_close):
                self._closed, self.will_close = closed, will_close

            def isclosed(self):
                return self._closed

        key = ("https", "a.example", 443, "1.1.1.1")
        pool = fast_relay.Pool()
        for closed, will_close, kept in ((True, False, True), (False, False, False), (True, True, False)):
            conn = _Conn()
            fast_relay.finish(conn, Response(closed, will_close), key, pool)
            self.assertEqual(not conn.closed, kept, (closed, will_close))
            pool.clear()
        conn = _Conn()
        fast_relay.finish(conn, Response(True, False), None, pool)
        self.assertTrue(conn.closed, "no key - the relay raised mid-reply - is never pooled")

    def test_the_address_check_still_runs_on_every_request_with_a_pool(self):
        self.fetch("/live/index.m3u8")
        self.assertEqual(len(fast_relay.POOL), 1)
        u = urllib.parse.quote(self.upstream + "/live/index.m3u8", safe="")
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self.get(self.relay + "/hls?u=" + u)
        self.assertEqual(caught.exception.code, 403, "a pooled connection is no way around it")

    def test_redirects_are_followed_and_the_playlist_rewritten_against_the_final_url(self):
        with mock.patch.object(fast_relay, "_global", return_value=True):
            u = urllib.parse.quote(self.upstream + "/start.m3u8", safe="")
            with self.get(self.relay + "/hls?u=" + u) as response:
                body = response.read().decode()
                self.assertEqual(response.headers["Content-Type"], fast_relay.PLAYLIST_TYPE)
        segment = body.splitlines()[2]
        self.assertEqual(segment, fast_relay.relay_link(self.relay, self.upstream + "/live/seg1.ts"))
        with mock.patch.object(fast_relay, "_global", return_value=True):
            with self.get(segment) as response:
                self.assertEqual(response.read(), _Upstream.segment)
            with self.get(segment, Range="bytes=10-19") as response:
                self.assertEqual(response.status, 206)
                self.assertEqual(response.headers["Content-Range"], "bytes 10-19/102400")
                self.assertEqual(response.read(), _Upstream.segment[10:20])

    def test_a_loopback_upstream_is_refused_for_real(self):
        u = urllib.parse.quote(self.upstream + "/live/seg1.ts", safe="")
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self.get(self.relay + "/hls?u=" + u)
        self.assertEqual(caught.exception.code, 403)

    def test_unknown_paths_and_missing_upstream(self):
        for path, code in (("/proxy?u=x", 404), ("/hls", 400)):
            with self.assertRaises(urllib.error.HTTPError) as caught:
                self.get(self.relay + path)
            self.assertEqual(caught.exception.code, code)


if __name__ == "__main__":
    unittest.main()
