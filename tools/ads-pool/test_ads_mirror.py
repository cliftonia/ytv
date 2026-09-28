#!/usr/bin/env python3
"""The LAN mirror of the ad reels: what gets fetched and pruned, that a download is atomic, and
that the server answers byte ranges the way a player asks for them.

No network beyond 127.0.0.1. Run: python3 -m unittest discover -s tools/ads-pool
"""
import io
import json
import os
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mirror_ads  # noqa: E402
import serve_ads  # noqa: E402


class FakeResponse(io.BytesIO):
    def __init__(self, body, length=None):
        super().__init__(body)
        self.headers = {"Content-Length": str(len(body) if length is None else length)}


def opener_for(bodies, seen=None):
    """urlopen stand-in: url -> bytes (or an exception to raise, or (bytes, claimed length))."""
    def opener(request, timeout):
        if seen is not None:
            seen.append((request.full_url, request.get_header("User-agent")))
        body = bodies[request.full_url]
        if isinstance(body, Exception):
            raise body
        if isinstance(body, tuple):
            return FakeResponse(*body)
        return FakeResponse(body)
    return opener


class MirrorTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.dir = os.path.join(self.root, "mirror")
        self.pool = os.path.join(self.root, "ads.json")
        self.addCleanup(shutil.rmtree, self.root)

    def write_pool(self, reels):
        with open(self.pool, "w") as handle:
            json.dump({"generated": 1, "reels": reels}, handle)

    def reel(self, ident):
        return {"id": ident, "url": "https://archive.org/download/%s/r.mp4" % ident, "cuts": [0]}

    def run_mirror(self, bodies, seen=None, **kwargs):
        logged = []
        result = mirror_ads.run(self.pool, self.dir, pause=0, opener=opener_for(bodies, seen),
                                sleep=lambda s: None, log=logged.append, **kwargs)
        return result, logged

    def test_ids_are_plain_file_names(self):
        for good in ("80_s_Australian_Commercials_10", "aus-ads.1987", "a"):
            self.assertTrue(mirror_ads.valid_id(good), good)
        for bad in ("", ".", "..", "../etc", "a/b", ".hidden", "a b", None, 7):
            self.assertFalse(mirror_ads.valid_id(bad), bad)
        with self.assertRaises(ValueError):
            mirror_ads.path_for(self.dir, "../x")

    def test_fetches_the_missing_skips_the_present_and_prunes_the_retired(self):
        os.makedirs(self.dir)
        for name in ("kept.mp4", "retired.mp4", "half.mp4.part"):
            with open(os.path.join(self.dir, name), "wb") as handle:
                handle.write(b"old")
        self.write_pool([self.reel("kept"), self.reel("new")])
        seen = []
        (fetched, failed, pruned), _ = self.run_mirror(
            {"https://archive.org/download/new/r.mp4": b"NEWBYTES"}, seen)
        self.assertEqual((fetched, failed, pruned), (1, 0, 2))
        self.assertEqual(sorted(os.listdir(self.dir)), ["kept.mp4", "new.mp4"])
        self.assertEqual(open(os.path.join(self.dir, "kept.mp4"), "rb").read(), b"old")
        self.assertEqual(open(os.path.join(self.dir, "new.mp4"), "rb").read(), b"NEWBYTES")
        self.assertEqual([url for url, _ in seen], ["https://archive.org/download/new/r.mp4"])
        self.assertTrue(seen[0][1].startswith("ytv-ads-pool/"))

    def test_a_failed_or_short_download_leaves_nothing_behind(self):
        self.write_pool([self.reel("dead"), self.reel("short"), self.reel("fine")])
        (fetched, failed, _), logged = self.run_mirror({
            "https://archive.org/download/dead/r.mp4": urllib.error.URLError("refused"),
            "https://archive.org/download/short/r.mp4": (b"half", 100),
            "https://archive.org/download/fine/r.mp4": b"whole",
        })
        self.assertEqual((fetched, failed), (1, 2))
        self.assertEqual(os.listdir(self.dir), ["fine.mp4"])
        self.assertTrue(any("short read" in line for line in logged))

    def test_an_unreadable_or_empty_pool_never_empties_the_mirror(self):
        os.makedirs(self.dir)
        open(os.path.join(self.dir, "kept.mp4"), "wb").close()
        for contents in ("not json", json.dumps({"reels": []}), json.dumps([1])):
            with open(self.pool, "w") as handle:
                handle.write(contents)
            self.assertEqual(self.run_mirror({})[0], (0, 0, 0))
            self.assertEqual(os.listdir(self.dir), ["kept.mp4"])

    def test_unsafe_or_cleartext_reels_are_not_mirrored(self):
        self.write_pool([{"id": "../x", "url": "https://archive.org/x.mp4"},
                         {"id": "plain", "url": "http://archive.org/x.mp4"}, self.reel("ok")])
        self.assertEqual(list(mirror_ads.load_pool(self.pool)), ["ok"])

    def test_limit_caps_one_run(self):
        self.write_pool([self.reel("a"), self.reel("b"), self.reel("c")])
        bodies = {"https://archive.org/download/%s/r.mp4" % i: b"x" for i in "abc"}
        self.assertEqual(self.run_mirror(bodies, limit=2)[0], (2, 0, 0))
        self.assertEqual(self.run_mirror(bodies)[0], (1, 0, 0))


class RangeTest(unittest.TestCase):
    def test_ranges(self):
        cases = [
            (None, None), ("", None), ("bytes=0-99", (0, 99)), ("bytes=100-", (100, 999)),
            ("bytes=990-5000", (990, 999)), ("bytes=-10", (990, 999)), ("bytes=-5000", (0, 999)),
            ("bytes=1000-", "unsatisfiable"), ("bytes=-0", "unsatisfiable"),
            ("bytes=5-2", None), ("bytes=0-1,5-6", None), ("items=0-1", None), ("bytes=-", None),
        ]
        for header, expected in cases:
            self.assertEqual(serve_ads.parse_range(header, 1000), expected, header)


class ServerTest(unittest.TestCase):
    BODY = bytes(range(256)) * 40  # 10240 bytes

    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.mkdtemp()
        with open(os.path.join(cls.dir, "reel-1.mp4"), "wb") as handle:
            handle.write(cls.BODY)
        with open(os.path.join(cls.dir, "secret.txt"), "wb") as handle:
            handle.write(b"no")
        cls.server = serve_ads._Server(("127.0.0.1", 0), serve_ads.make_handler(cls.dir))
        cls.base = "http://127.0.0.1:%d" % cls.server.server_address[1]
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        shutil.rmtree(cls.dir)

    def get(self, path, headers=None, method="GET"):
        request = urllib.request.Request(self.base + path, headers=headers or {}, method=method)
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, dict(response.headers), response.read()
        except urllib.error.HTTPError as error:
            return error.code, dict(error.headers), error.read()

    def test_whole_file(self):
        status, headers, body = self.get("/ads/reel-1.mp4")
        self.assertEqual((status, body), (200, self.BODY))
        self.assertEqual(headers["Accept-Ranges"], "bytes")
        self.assertEqual(headers["Content-Type"], "video/mp4")

    def test_a_range_mid_file(self):
        status, headers, body = self.get("/ads/reel-1.mp4", {"Range": "bytes=5000-5099"})
        self.assertEqual((status, body), (206, self.BODY[5000:5100]))
        self.assertEqual(headers["Content-Range"], "bytes 5000-5099/10240")

    def test_an_open_range_and_a_suffix(self):
        self.assertEqual(self.get("/ads/reel-1.mp4", {"Range": "bytes=10000-"})[2], self.BODY[10000:])
        self.assertEqual(self.get("/ads/reel-1.mp4", {"Range": "bytes=-40"})[2], self.BODY[-40:])

    def test_past_the_end_is_416(self):
        status, headers, _ = self.get("/ads/reel-1.mp4", {"Range": "bytes=20000-"})
        self.assertEqual(status, 416)
        self.assertEqual(headers["Content-Range"], "bytes */10240")

    def test_head_has_the_length_and_no_body(self):
        status, headers, body = self.get("/ads/reel-1.mp4", method="HEAD")
        self.assertEqual((status, headers["Content-Length"], body), (200, "10240", b""))

    def test_nothing_but_mirrored_reels_is_reachable(self):
        for path in ("/ads/missing.mp4", "/ads/..%2Fsecret.txt", "/ads/../secret.txt", "/secret.txt",
                     "/ads/", "/", "/ads/secret.txt", "/ads/.hidden.mp4"):
            self.assertEqual(self.get(path)[0], 404, path)

    def test_health_counts_the_reels(self):
        status, _, body = self.get("/health")
        self.assertEqual((status, json.loads(body)), (200, {"ok": True, "reels": 1}))


if __name__ == "__main__":
    unittest.main()
