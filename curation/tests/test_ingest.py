#!/usr/bin/env python3
"""ingest.py: the pure seams of the torrent ingest.

The ssh/aria2 driving runs against the real server or not at all - faking it would test the
fake. What IS pinned here is everything the run depends on: reading the torrent, naming the
channel, finding the next number, and cutting dial.py without breaking the block.
"""
import io
import json
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import ingest


def single_file_torrent(name=b"a film.mp4", length=1234):
    return b'd8:announce10:tracker.co4:infod6:lengthi%de4:name%d:%see' % (
        length, len(name), name)


def multi_file_torrent():
    return (b'd4:infod5:filesld6:lengthi100e4:pathl3:dir7:one.mp4ee'
            b'd6:lengthi200e4:pathl7:two.mp4eee4:name11:A Show Pack'
            b'12:piece lengthi16384e6:pieces0:ee')


class TestMagnetValidation(unittest.TestCase):

    def test_a_v1_btih_magnet_is_accepted(self):
        self.assertTrue(ingest.MAGNET.match(
            "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a"
            "&dn=Pioneer%20One&tr=http%3A%2F%2Ftracker"))

    def test_a_base32_btih_is_accepted(self):
        self.assertTrue(ingest.MAGNET.match(
            "magnet:?xt=urn:btih:MFRGG2DFMZTWQ2LKMFRGG2DFMZTWQ2LK"))

    def test_a_magnet_without_a_hash_is_not_a_start(self):
        # aria2 would sit on it forever; refusing here keeps "dead link" a 1-second answer.
        self.assertFalse(ingest.MAGNET.match("magnet:?xt=urn:sha1:abc"))
        self.assertFalse(ingest.MAGNET.match("magnet:?xt=urn:btih:tooshort"))
        self.assertFalse(ingest.MAGNET.match("https://example.org/not-a-torrent.zip"))

    def test_a_torrent_url_is_accepted(self):
        self.assertTrue(ingest.TORRENT_URL.match(
            "https://archive.org/download/ElephantsDream/ElephantsDream_archive.torrent"))


class TestBdecodeAndSummary(unittest.TestCase):

    def test_single_file(self):
        name, total, files = ingest.torrent_summary(single_file_torrent())
        self.assertEqual("a film.mp4", name)
        self.assertEqual(1234, total)
        self.assertEqual([], files)

    def test_multi_file(self):
        name, total, files = ingest.torrent_summary(multi_file_torrent())
        self.assertEqual("A Show Pack", name)
        self.assertEqual(300, total)
        self.assertEqual([("dir/one.mp4", 100), ("two.mp4", 200)], files)

    def test_garbage_is_rejected_not_misread(self):
        # A corrupt torrent must fail here; a misread size would be used to confirm the
        # download into the wrong channel with a straight face.
        with self.assertRaises((ValueError, KeyError)):
            ingest.torrent_summary(b"not bencode at all")


class TestChannelNaming(unittest.TestCase):

    def test_slugify(self):
        self.assertEqual("cowboy-bebop", ingest.slugify("Cowboy Bebop"))
        self.assertEqual("mst3k", ingest.slugify("  MST3K  "))
        self.assertEqual("lois-clark", ingest.slugify("Lois & Clark!"))

    def test_quotes_in_names_are_unchanged_but_benign(self):
        # A name with a quote would make a conf and folder fine; the caddy generator is the
        # one that refuses, loudly, at deploy - by design.
        self.assertEqual("o-briens", ingest.slugify("O'Briens"))

    def test_next_number_follows_the_highest_file_channel(self):
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d, ignore_errors=True)
        for number, slug in ((91, "movies"), (92, "series")):
            with io.open(os.path.join(d, "file_%s.json" % slug), "w") as handle:
                json.dump({"station_conf": {"channel_number": number}}, handle)
        self.assertEqual(93, ingest.next_file_channel_number(d))

    def test_the_block_refuses_to_grow_into_live_numbers(self):
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d, ignore_errors=True)
        with io.open(os.path.join(d, "file_full.json"), "w") as handle:
            json.dump({"station_conf": {"channel_number": 99}}, handle)
        with self.assertRaises(ValueError):
            ingest.next_file_channel_number(d)


class TestVideoSelection(unittest.TestCase):

    def test_video_indices_pick_playables_in_torrent_order(self):
        # The Elephants Dream archive carries 24GB of frame tars beside a 64MB film; the
        # default selection must skip them or "not much storage" becomes prophecy.
        files = [("ED-1080-png.tar", 999), ("ed_hd.avi", 100), ("score.flac", 50),
                 ("ed_1024.mp4", 64)]
        self.assertEqual([2, 4], ingest.video_indices(files))

    def test_single_video_torrent_selects_it(self):
        self.assertEqual([1], ingest.video_indices([("film.mkv", 1000)]))

    def test_a_torrent_with_no_video_selects_nothing(self):
        self.assertEqual([], ingest.video_indices([("notes.txt", 1)]))


class TestStallFallback(unittest.TestCase):

    def test_summary_regex_reads_aria2_progress(self):
        line = "[#eaffaa 1.4GiB/2.0GiB(70%) CN:5 SD:0 DL:10MiB]"
        self.assertEqual("1.4GiB", ingest.SUMMARY.search(line).group(1))

    def test_ia_base_extracted_only_from_item_download_urls(self):
        self.assertEqual(
            "https://archive.org/download/ElephantsDream/",
            ingest.ia_download_base(
                "https://archive.org/download/ElephantsDream/ElephantsDream_archive.torrent"))
        # Anything else has no known http twin, and guessing one would fetch wrong bytes.
        self.assertIsNone(ingest.ia_download_base("magnet:?xt=urn:btih:abc"))
        self.assertIsNone(ingest.ia_download_base("https://othersite.org/x.torrent"))

    def test_tail_script_targets_sized_files_and_quotes_paths(self):
        script = ingest.build_tail_script(
            "https://archive.org/download/Item/", "Item Root", "/srv/media/Movies",
            [("dir/one film.mp4", 100)])
        self.assertIn("curl -fSL --retry 2 -sS -o", script)
        self.assertIn("one%20film.mp4", script)
        self.assertIn('if [ "$SZ" != 100 ]; then', script)
        # The root dir comes from the torrent name, matching aria2's layout.
        self.assertIn("Item Root", script)

    def test_tail_script_never_resumes_onto_holey_partials(self):
        # curl -C - onto an aria2-preallocated file appends after the preallocation and leaves
        # the holes as zeros - a file that passes ffprobe and dies at 41 minutes in.
        script = ingest.build_tail_script("https://x/", "", "/t", [("a.mp4", 5)])
        self.assertNotIn("curl -C", script)
        self.assertIn(".part", script)


class TestDialSurgery(unittest.TestCase):

    DIAL = 'LIVE = []\n\nFILES = [\n    (91, "movies", "Movies", "Movies", ()),\n]\n'

    def test_append_keeps_the_block_parseable_and_in_order(self):
        out = ingest.add_file_channel(self.DIAL, 92, "series", "Series", "Series")
        # The proof is structural, not textual: the edited dial must still import.
        scope = {}
        exec(out, scope)
        self.assertEqual([(91, "movies", "Movies", "Movies", ()),
                          (92, "series", "Series", "Series", ())], scope["FILES"])
        self.assertTrue(scope["LIVE"] is not None)

    def test_a_repeated_slug_is_refused(self):
        # Re-adding a channel must not produce two confs racing for one slug's files.
        with self.assertRaises(ValueError):
            ingest.add_file_channel(self.DIAL, 95, "movies", "More Movies", "Movies2")


if __name__ == "__main__":
    unittest.main()
