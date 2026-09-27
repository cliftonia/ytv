#!/usr/bin/env python3
"""The ads pool: cut extraction from ffmpeg output, choosing / merging / filtering cuts, and
which archive.org titles become candidates in which era.

Nothing here touches the network or runs ffmpeg. Run: python3 -m unittest discover -s tools/ads-pool
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import adcuts  # noqa: E402
import cut_reel  # noqa: E402
import find_reels  # noqa: E402


def quiet_audio_at(splices, duration, loud=-18.0, quiet=-50.0, step=0.04):
    """RMS series at `loud` dB with a two-window dip to `quiet` at each splice time."""
    rms = []
    t = 0.0
    while t < duration:
        near = any(abs(t - s) <= 0.04 for s in splices)
        rms.append((round(t, 2), quiet if near else loud))
        t += step
    return rms


class ParsingTest(unittest.TestCase):
    def test_metadata_print_pairs_times_with_values(self):
        text = ("frame:0    pts:59904   pts_time:4.68\nlavfi.scene_score=0.551473\n"
                "frame:1    pts:60416   pts_time:4.72\nlavfi.scene_score=0.284983\n")
        self.assertEqual(adcuts.parse_metadata_print(text, "lavfi.scene_score"),
                         [(4.68, 0.551473), (4.72, 0.284983)])

    def test_digital_silence_is_finite(self):
        text = "frame:0 pts:0 pts_time:0\nlavfi.astats.Overall.RMS_level=-inf\n"
        self.assertEqual(adcuts.parse_metadata_print(text, "lavfi.astats.Overall.RMS_level"),
                         [(0.0, -120.0)])

    def test_blackdetect_runs(self):
        text = ("[blackdetect @ 0x1] black_start:149 black_end:149.12 black_duration:0.12\n"
                "noise\n[blackdetect @ 0x1] black_start:308.04 black_end:308.4 black_duration:0.36\n")
        self.assertEqual(adcuts.parse_blackdetect(text), [(149.0, 149.12), (308.04, 308.4)])


class CandidateTest(unittest.TestCase):
    def test_scene_change_needs_an_audio_dip(self):
        rms = quiet_audio_at([30.0], 60.0)
        scenes = [(30.0, 0.9), (45.0, 0.9)]          # 45 s: a picture change with the sound running
        times = [t for t, _ in adcuts.candidates(scenes, rms, [])]
        self.assertEqual(times, [30.0])

    def test_weak_scene_score_is_ignored(self):
        rms = quiet_audio_at([30.0], 60.0)
        self.assertEqual(adcuts.candidates([(30.0, 0.1)], rms, []), [])

    def test_black_run_counts_at_its_midpoint_even_with_sound(self):
        rms = quiet_audio_at([], 60.0)
        times = [t for t, _ in adcuts.candidates([], rms, [(20.0, 21.0)])]
        self.assertEqual(times, [20.5])

    def test_near_duplicates_collapse_to_the_strongest(self):
        rms = quiet_audio_at([30.0], 60.0)
        cands = adcuts.candidates([(30.0, 0.9), (30.04, 0.4)], rms, [(29.96, 30.08)])
        self.assertEqual(len(cands), 1)


class ChooseCutsTest(unittest.TestCase):
    def test_hop_scores(self):
        self.assertGreater(adcuts.hop_score(29.7), adcuts.hop_score(20.2))
        self.assertGreater(adcuts.hop_score(20.2), 0)
        self.assertLess(adcuts.hop_score(24.5), 0)
        self.assertTrue(adcuts.is_legal(15.4))
        self.assertFalse(adcuts.is_legal(17.4))

    def test_rhythm_beats_in_ad_edits(self):
        # Real splices every 30 s; a quiet ad's own edits at 37.3 and 52.9 dip just as deeply.
        real = [0.5, 30.4, 60.3, 90.2, 120.2, 150.1]
        cands = [(t, 1.5) for t in real] + [(37.3, 1.5), (52.9, 1.5)]
        self.assertEqual(adcuts.choose_cuts(sorted(cands), 200.0), real)

    def test_no_two_cuts_closer_than_the_minimum(self):
        cands = [(0.0, 2.0), (15.0, 2.0), (19.0, 2.0), (30.0, 2.0), (45.0, 2.0)]
        cuts = adcuts.choose_cuts(cands, 100.0)
        self.assertTrue(all(b - a >= adcuts.MIN_GAP for a, b in zip(cuts, cuts[1:])))
        self.assertEqual(cuts, [0.0, 15.0, 30.0, 45.0])

    def test_odd_length_segment_does_not_break_the_chain(self):
        cands = [(0.0, 2.0), (30.0, 2.0), (66.5, 2.0), (96.5, 2.0), (126.5, 2.0)]
        self.assertEqual(adcuts.choose_cuts(cands, 200.0), [0.0, 30.0, 66.5, 96.5, 126.5])

    def test_cut_at_the_very_end_is_dropped(self):
        cands = [(0.0, 2.0), (30.0, 2.0), (60.0, 2.0)]
        self.assertEqual(adcuts.choose_cuts(cands, 62.0), [0.0, 30.0])

    def test_end_to_end_from_detector_output(self):
        splices = [10.0 + 30.0 * k for k in range(8)]       # 10, 40, ..., 220
        rms = quiet_audio_at(splices, 260.0)
        scenes = [(t, 0.9) for t in splices] + [(t + 7.0, 0.6) for t in splices]
        cuts = adcuts.choose_cuts(adcuts.candidates(scenes, rms, []), 260.0)
        self.assertEqual(cuts, splices)
        self.assertIsNone(adcuts.verdict(cuts, 260.0))


class VerdictTest(unittest.TestCase):
    def test_too_few_cuts(self):
        self.assertIn("only 4 cuts", adcuts.verdict([0, 30, 60, 90], 200))

    def test_arrhythmic_reel_is_rejected(self):
        self.assertIn("ad lengths", adcuts.verdict([0, 23, 47, 71, 96, 118], 200))

    def test_long_segments_are_not_ads(self):
        self.assertIn("median", adcuts.verdict([0, 120, 240, 360, 480, 600], 700))

    def test_good_reel(self):
        self.assertIsNone(adcuts.verdict([0.5, 30.4, 60.3, 75.3, 105.2], 200))


class EraTest(unittest.TestCase):
    CASES = [
        ("80s Australian Commercials 93 (ATN-7, 1985)", "80s"),
        ("Australian 90s Commercials 14 (SAS-7, 1990)", "90s"),
        ("90s Australian Commercials 82 (TVQ-10, 20/12/96)", "90s"),
        ("90s Australian Commercials (BTQ-7, 20-21 ⁄ 11 ⁄ 94)", "90s"),
        ("80's Australian Commercials 10", "80s"),              # the label, not episode 10
        ("80s Australian Commercials 197 (TEN-10, 3/12/89)", "80s"),
        ("Australian TV Commercials 11 (ATV10, 30.9.1983)", "80s"),
        ("Australian TV Commercials 26 (GTV9, 21.12.2005)", None),
        ("Australian Commercials, 1968 - 16mm", None),
        ("Coppertone, Classic Australian TV Commercial (1970's)", "70s"),
        ("Aussie ads 1970s", "70s"),
        ("Australian Commercials (WIN Rockhampton, 02/08/1999)", "90s"),
    ]

    def test_eras(self):
        for title, era in self.CASES:
            with self.subTest(title=title):
                self.assertEqual(find_reels.era_of(title), era)

    def test_metadata_year_only_when_plausible(self):
        self.assertEqual(find_reels.era_of("Australian Cricket TV Ads Various", "1987"), "80s")
        self.assertIsNone(find_reels.era_of("Australian Cricket TV Ads Various", "2021"))

    def test_reel_titles(self):
        self.assertTrue(find_reels.is_reel("80s Australian Commercials 93 (ATN-7, 1985)"))
        self.assertTrue(find_reels.is_reel("Australian TV ads - Channel 10 - 1996"))
        for title in ("Telecom - 2 Australian TV Commercials (1991)",
                      "Seven’s Big League - 1987 Australian Football Championships: WA V SA (ADS-7, 16/06/87)",
                      "2008 Australian Idol Grand Final - Network Ten Melbourne (Mostly Complete With Commercials)",
                      "Perfect Match (29 May 1985, full episode including ads) - 1985 Australian TV Programme",
                      "Vintage Australian Synthpop (1983)"):
            with self.subTest(title=title):
                self.assertFalse(find_reels.is_reel(title))

    def test_select_dedupes_interleaves_and_caps(self):
        docs = [
            {"identifier": "b90", "title": "90s Australian Commercials 2 (ADS-10, 4/3/90)"},
            {"identifier": "a80", "title": "80s Australian Commercials 1 (GTV-9/ATV-10)"},
            {"identifier": "c80", "title": "80s Australian Commercials 3 (1986)"},
            {"identifier": "a80", "title": "80s Australian Commercials 1 (GTV-9/ATV-10)"},
            {"identifier": "z05", "title": "Australian TV Commercials 26 (GTV9, 21.12.2005)"},
            {"identifier": "s70", "title": ["Australian ads 1974"]},
        ]
        chosen = find_reels.select(docs)
        self.assertEqual([r["id"] for r in chosen], ["s70", "a80", "b90", "c80"])
        self.assertEqual(chosen[2], {"id": "b90", "title": "90s Australian Commercials 2 (ADS-10, 4/3/90)",
                                     "era": "90s", "year": 1990})
        self.assertEqual(len(find_reels.select(docs, cap=2)), 2)


class FilePickTest(unittest.TestCase):
    def test_prefers_derived_h264_and_encodes_the_url(self):
        files = [
            {"name": "Reel (ATN-7, 1985).mkv", "format": "Matroska", "length": "1110.78"},
            {"name": "Reel (ATN-7, 1985).mp4", "format": "h.264", "length": "1110.73"},
            {"name": "Reel (ATN-7, 1985).ogv", "format": "Ogg Video", "length": "1110.7"},
        ]
        self.assertEqual(cut_reel.pick_mp4(files), ("Reel (ATN-7, 1985).mp4", 1110.73))
        self.assertEqual(cut_reel.download_url("x_1985", "Reel (ATN-7, 1985).mp4"),
                         "https://archive.org/download/x_1985/Reel%20%28ATN-7%2C%201985%29.mp4")
        self.assertIsNone(cut_reel.pick_mp4(files[:1]))

    def test_length_forms(self):
        self.assertEqual(cut_reel.parse_length("1110.73"), 1110.73)
        self.assertEqual(cut_reel.parse_length("18:30"), 1110.0)
        self.assertEqual(cut_reel.parse_length("0:18:30"), 1110.0)
        self.assertIsNone(cut_reel.parse_length(None))
        self.assertIsNone(cut_reel.parse_length("n/a"))


if __name__ == "__main__":
    unittest.main()
