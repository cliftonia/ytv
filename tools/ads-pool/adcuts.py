"""Where one advertisement ends and the next begins, inside an archive.org compilation reel.

Pure functions only - no network, no ffmpeg - so the whole decision is testable from recorded
filter output. cut_reel.py runs ffmpeg and hands the text here.

Why not just black frames: the reels on archive.org ("80s Australian Commercials 93 (ATN-7,
1985)" and friends) are off-air VHS captures edited back to back, and most boundaries are hard
cuts with no black at all - blackdetect finds ten runs in an 18-minute reel of ~35 ads. What
every boundary does have is a splice: the picture changes completely AND the audio drops to
near silence for a frame or two. But an ad's own edits do that too (a quiet jingle cut to the
beat dips at every shot), so the evidence that decides is the rhythm: Australian spots run
15, 30 or 60 seconds (promos 10/20, the odd 45/90/120), so the real boundaries sit a legal
length apart and the in-ad edits do not.

So: every scene change with an audio dip (or a black run) is a candidate with a weight, and a
dynamic program picks the chain of candidates that scores best, where each hop between
consecutive cuts earns a bonus for a legal ad length and a penalty otherwise.
"""
import bisect
import re
import statistics

SCENE_MIN = 0.30        # lavfi scene score for a candidate picture change
CONTRAST_MIN = 14.0     # dB the audio must drop below its surroundings at a candidate
DIP_WINDOW = 0.20       # s either side of a scene change searched for the audio minimum
CONTEXT_WINDOW = 3.0    # s either side used for the surrounding (median) audio level
COLLAPSE = 0.5          # s: candidates closer than this are one splice
MIN_GAP = 8.0           # s: no two cuts closer than this - nothing on air is shorter
TOLERANCE = 0.6         # s of slack on a legal ad length
PRIMARY_LENGTHS = (15.0, 30.0, 60.0)
SECONDARY_LENGTHS = (10.0, 20.0, 45.0, 90.0, 120.0)
PRIMARY_BONUS = 3.0
SECONDARY_BONUS = 1.5
ILLEGAL_PENALTY = -2.0
END_MARGIN = 5.0        # s: a cut this close to the end of the reel starts nothing playable

MIN_CUTS = 5            # a reel with fewer cuts is not published
MIN_LEGAL_SHARE = 0.5   # of the hops between cuts, at least this share must be legal lengths
SEGMENT_RANGE = (10.0, 90.0)  # the median hop must look like an ad


def parse_loudness(text):
    """Integrated loudness (LUFS) from ffmpeg's ebur128 summary, or None.

    The summary block ends a run: `Integrated loudness:` then `I: -22.6 LUFS`. A reel that is
    silence throughout reports the gate's floor, -70 - no figure to level by.
    """
    found = re.findall(r"Integrated loudness:\s*\n\s*I:\s*(-?[\d.]+) LUFS", text)
    if not found:
        return None
    value = float(found[-1])
    return None if value <= -69.0 else value


def parse_metadata_print(text, key):
    """(pts_time, value) pairs from a `metadata=print` / `ametadata=print` dump.

    The dump alternates `frame:N pts:P pts_time:T` lines with `key=value` lines. Values of
    -inf (digital silence) come back as -120 dB so arithmetic stays finite.
    """
    out = []
    time_s = None
    for line in text.splitlines():
        match = re.search(r"pts_time:(-?[\d.]+)", line)
        if match:
            time_s = float(match.group(1))
            continue
        if time_s is not None and line.strip().startswith(key + "="):
            raw = line.split("=", 1)[1].strip()
            try:
                value = float(raw)
            except ValueError:
                continue
            if value != value or value in (float("inf"), float("-inf")):
                value = -120.0
            out.append((time_s, value))
    return out


def parse_blackdetect(text):
    """(start, end) of every black run blackdetect logged."""
    return [(float(a), float(b)) for a, b in
            re.findall(r"black_start:(-?[\d.]+)\s+black_end:(-?[\d.]+)", text)]


class _Levels:
    """Audio RMS levels (dB per short window) with windowed min / median lookups."""

    def __init__(self, rms):
        self.times = [t for t, _ in rms]
        self.values = [v for _, v in rms]

    def _slice(self, centre, half):
        lo = bisect.bisect_left(self.times, centre - half)
        hi = bisect.bisect_right(self.times, centre + half)
        return self.values[lo:hi]

    def contrast(self, centre):
        """dB between the surrounding median level and the minimum at the splice (0 if no data)."""
        dip = self._slice(centre, DIP_WINDOW)
        context = self._slice(centre, CONTEXT_WINDOW)
        if not dip or not context:
            return 0.0
        return statistics.median(context) - min(dip)


def candidates(scenes, rms, blacks):
    """Weighted splice candidates, ascending by time.

    scenes: (t, score) from the scene-change pass; rms: (t, dB) short-window audio levels;
    blacks: (start, end) black runs. A scene change qualifies with a deep enough audio dip; a
    black run always qualifies (its midpoint), with a bonus.
    """
    levels = _Levels(rms)
    raw = []
    for time_s, score in scenes:
        if score < SCENE_MIN:
            continue
        contrast = levels.contrast(time_s)
        if contrast < CONTRAST_MIN:
            continue
        raw.append((time_s, _weight(contrast, black=False, strong_scene=score >= 0.8)))
    for start, end in blacks:
        mid = (start + end) / 2.0
        raw.append((mid, _weight(levels.contrast(mid), black=True, strong_scene=False)))
    raw.sort()
    collapsed = []
    for time_s, weight in raw:
        if collapsed and time_s - collapsed[-1][0] < COLLAPSE:
            if weight > collapsed[-1][1]:
                collapsed[-1] = (time_s, weight)
            continue
        collapsed.append((time_s, weight))
    return collapsed


def _weight(contrast, black, strong_scene):
    weight = max(-2.0, min(3.0, (contrast - 20.0) / 4.0))
    if black:
        weight += 1.0
    if strong_scene:
        weight += 0.5
    return weight


def hop_score(gap):
    """What a hop of `gap` seconds between consecutive cuts is worth."""
    if any(abs(gap - length) <= TOLERANCE for length in PRIMARY_LENGTHS):
        return PRIMARY_BONUS
    if any(abs(gap - length) <= TOLERANCE for length in SECONDARY_LENGTHS):
        return SECONDARY_BONUS
    return ILLEGAL_PENALTY


def is_legal(gap):
    return hop_score(gap) > 0


def choose_cuts(cands, duration=None):
    """The best-scoring chain of candidate times (see module doc). Ascending, >= MIN_GAP apart."""
    if duration is not None:
        cands = [(t, w) for t, w in cands if 0.0 <= t <= duration - END_MARGIN]
    if not cands:
        return []
    best = []
    back = []
    for i, (time_i, weight_i) in enumerate(cands):
        score, prev = 0.0, None
        for j in range(i):
            gap = time_i - cands[j][0]
            if gap < MIN_GAP:
                continue
            linked = best[j] + hop_score(gap)
            if linked > score:
                score, prev = linked, j
        best.append(weight_i + score)
        back.append(prev)
    i = max(range(len(cands)), key=lambda k: (best[k], -k))
    chain = []
    while i is not None:
        chain.append(round(cands[i][0], 2))
        i = back[i]
    return chain[::-1]


def verdict(cuts, duration):
    """None when the reel is fit to publish, else the reason it is not."""
    if len(cuts) < MIN_CUTS:
        return "only %d cuts" % len(cuts)
    gaps = [b - a for a, b in zip(cuts, cuts[1:])]
    legal = sum(1 for gap in gaps if is_legal(gap))
    if legal < MIN_LEGAL_SHARE * len(gaps):
        return "only %d of %d hops are ad lengths" % (legal, len(gaps))
    median = statistics.median(gaps)
    if not SEGMENT_RANGE[0] <= median <= SEGMENT_RANGE[1]:
        return "median hop %.1fs is not an ad" % median
    if duration and cuts[-1] > duration:
        return "cut past the end"
    return None
