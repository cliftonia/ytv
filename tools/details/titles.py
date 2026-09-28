#!/usr/bin/env python3
"""Programme titles as the guides write them, turned into something TMDB can be asked about.

Two jobs, kept apart on purpose:

  key(raw)          the lookup key details.json is written under, and the app computes for the
                    title it has on screen. Deliberately dumb - case, accents, punctuation and
                    spacing only - because the app (details/TitleKey.kt) implements it too, and two
                    implementations of anything clever would drift. title_keys.json beside this holds the
                    shared cases both test suites run.
  parse(raw)        the clever part, server side only: what to search for. Strips labels
                    ("Movie: "), years ("(1984)"), episode markers ("S2 E5"), dub and format tags,
                    and says what kind of thing the title looks like.

Messy titles are the norm: "Movie: Fast Atlanta", "S2 E3 Apocalyptic Visions" (no series name at
all), "My Secret,Terrius Ep.16 (English Dub)", "The Jim Rome Podcast(series, 2024)". The rule
throughout is that a wrong poster is worse than none: when a title cannot be pinned down, parse
says so and nothing is looked up.
"""
import re
import unicodedata

MOVIE, TV = "movie", "tv"

_APOSTROPHES = re.compile(r"['‘’`´]")
_NOT_ALNUM = re.compile(r"[^0-9a-z]+")


def key(raw):
    """Lower case, accents off, apostrophes dropped, "&" as "and", everything else not a letter or
    digit a single space. "" for a title with nothing left (a title in another script)."""
    text = unicodedata.normalize("NFKD", raw or "")
    text = "".join(c for c in text if not unicodedata.combining(c)).lower()
    text = _APOSTROPHES.sub("", text).replace("&", " and ")
    return _NOT_ALNUM.sub(" ", text).strip()


def same(a, b):
    """Two keys naming the same title, a leading "the" aside: "karate kid" is "the karate kid"."""
    return bool(a) and (a == b or _article(a) == _article(b))


def _article(k):
    return k[4:] if k.startswith("the ") else k


# "Movie: X", "Film - X", "Holiday Movie: X". Only labels that say movie give the kind.
_LABEL = re.compile(r"^\s*((?:new |holiday |christmas |original |feature |classic )?(?:movie|film)"
                    r"|premiere|new|encore)\s*[:\-|–—]\s+", re.I)
_YEAR = re.compile(r"[(\[]\s*(?:(series|tv|movie|film)\s*,\s*)?((?:19|20)\d\d)\s*[)\]]", re.I)
_TRAILING_YEAR = re.compile(r"\s+[-–]\s+((?:19|20)\d\d)$")
_BRACKETS = re.compile(r"\s*[(\[][^()\[\]]*[)\]]")
# An episode marker, and everything after it - which is the episode, not the programme.
_EPISODE = re.compile(r"(?:^|[\s,:\-])(?:s\d{1,2}\s*[:.]?\s*e\d{1,4}\b|season\s+\d+|series\s+\d+\s+ep"
                      r"|episode\s+\d+|ep\.?\s*\d+\b|#\d+\b)", re.I)
# Not programmes anyone has a poster for, or live sport TMDB cannot know.
_SKIP = re.compile(r"^(paid programming|off air|sign off|to be announced|tba|tbd|infomercial|"
                   r"programming|station break)$|^live\s*[:\-]|\bvs\.? |\bfull game replay\b|"
                   r"\bcondensed game\b|\bhighlights\b", re.I)
_SEPARATORS = (" - ", ": ", " – ", " — ")


class Parsed:
    """What to search for: [title] (as written, cleaned), its [kind] if the title says, its [year]."""

    def __init__(self, title, kind=None, year=None):
        self.title, self.kind, self.year = title, kind, year

    def __repr__(self):
        return "Parsed(%r, %r, %r)" % (self.title, self.kind, self.year)

    def __eq__(self, other):
        return isinstance(other, Parsed) and (self.title, self.kind, self.year) == \
            (other.title, other.kind, other.year)


def parse(raw):
    """[raw] cleaned to a searchable title, or None when it cannot name a film or series: nothing
    before an episode marker ("S2 E3 Apocalyptic Visions"), live sport, paid programming."""
    text = " ".join((raw or "").split())
    if not key(text) or _SKIP.search(text):
        return None
    kind, year = None, None
    label = _LABEL.match(text)
    if label:
        if re.search(r"movie|film", label.group(1), re.I):
            kind = MOVIE
        text = text[label.end():]
    found = _YEAR.search(text)
    if found:
        year = int(found.group(2))
        if found.group(1):
            kind = TV if found.group(1).lower() in ("series", "tv") else MOVIE
        text = (text[:found.start()] + " " + text[found.end():]).strip()
    trailing = _TRAILING_YEAR.search(text)
    if trailing and year is None:
        year = int(trailing.group(1))
        text = text[:trailing.start()]
    episode = _EPISODE.search(text)
    if episode:
        text = text[:episode.start()]
        kind = TV
    text = _BRACKETS.sub("", text)
    text = " ".join(text.split()).strip(" -:,|–—")
    if not key(text):
        return None
    return Parsed(text, kind, year)


def prefix(raw):
    """The part before the first " - " or ": " in [raw], or None - the series half of
    "Cosmic Vistas: Night Fire". Only a candidate: see recurring_prefixes."""
    text = " ".join((raw or "").split())
    at = [text.find(s) for s in _SEPARATORS if s in text]
    if not at:
        return None
    head = text[:min(at)].strip()
    return head if key(head) else None


def recurring_prefixes(raws):
    """Prefix keys that front two or more DIFFERENT titles - "cosmic vistas" over "Night Fire" and
    "Fermi Paradox". Those are series with an episode after the separator; a prefix seen once is
    as likely half of a film's title ("Mission: Impossible") and is never searched alone."""
    seen = {}
    for raw in raws:
        head = prefix(raw)
        if head:
            seen.setdefault(key(head), set()).add(key(raw))
    return {k for k, fulls in seen.items() if len(fulls) >= 2}


def kind_from_minutes(minutes):
    """A slot of 80 minutes or more is a film's; 50 or less a series episode's; between, unsure."""
    if minutes is None:
        return None
    if minutes >= 80:
        return MOVIE
    if minutes <= 50:
        return TV
    return None


def year_of(date):
    """The year of a TMDB date ("1984-06-22"), or None."""
    return int(date[:4]) if date and len(date) >= 4 and date[:4].isdigit() else None


def trimmed(text, limit=240):
    """[text] on one line, cut to [limit] characters at a word with an ellipsis."""
    text = " ".join((text or "").split())
    if len(text) <= limit:
        return text
    return text[:limit - 1].rsplit(" ", 1)[0].rstrip(" ,;:-") + "…"
