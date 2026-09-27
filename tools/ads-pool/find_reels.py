#!/usr/bin/env python3
"""Candidate archive.org reels of retro Australian TV commercials, tagged by era.

Searches archive.org's advancedsearch API (a few sequential queries, a second apart, with a
User-Agent), keeps titles that name Australian commercials and read as a compilation, tags
each with the era its broadcast date implies, and prints a JSON list:

    [{"id": "<identifier>", "title": "...", "era": "80s", "year": 1985}, ...]

The order is deterministic and interleaves the eras (70s, 80s, 90s, 70s, ...; identifiers
ascending within an era), so a bounded nightly run of cut_reel.py covers every era early rather
than working through 197 reels of the 80s first. Capped at --cap (400).

What exists (27 Sep 2026): the "80s Australian Commercials 1..197" and "90s Australian
Commercials 1..98" series, "Australian TV Commercials N (GTV9, date)" and regional
"Australian Commercials (WIN Rockhampton, date)" reels. No 1970s compilation turns up under any
wording tried (1970s / 70s / 70's / 197x, aussie, ads, adverts): the 70s items are single
spots, which the cut stage drops as too short. The era is still searched so one appearing
later is picked up.

Nothing here reads the videos; that is cut_reel.py.
"""
import argparse
import json
import re
import sys
import time
import urllib.parse
import urllib.request

SEARCH_URL = "https://archive.org/advancedsearch.php"
USER_AGENT = "ytv-ads-pool/1.0 (+https://github.com/cliftonia/ytv)"
ERAS = ("70s", "80s", "90s")

QUERIES = (
    "title:(australian OR aussie) AND title:(commercials OR ads OR adverts OR advertisements)"
    " AND mediatype:movies",
    "title:(1970s OR 70s OR \"70's\" OR 1970 OR 1971 OR 1972 OR 1973 OR 1974 OR 1975 OR 1976"
    " OR 1977 OR 1978 OR 1979) AND title:(australian OR aussie OR australia)"
    " AND title:(commercials OR ads OR adverts OR advertisements) AND mediatype:movies",
)

# "ads" not followed by "-7"/"-10": ADS is also Adelaide's call sign ("ADS-7, 16/06/87").
_ADS_WORD = r"\b(commercials|ads(?!\s*-\s*\d)|adverts|advertisements)\b"
_ABOUT_ADS = re.compile(r"\b(australian|aussie|australia)\b.*" + _ADS_WORD
                        + "|" + _ADS_WORD + r".*\b(australian|aussie|australia)\b", re.I)
# Titles that name ads but are something else: a programme with its breaks left in, a brand's
# pair of spots, a documentary about ads, promos, a sports broadcast.
_NOT_A_REEL = re.compile(r"\b(during|idol|grand final|famous faces|vhs promo|sitcoms?|tv shows?|"
                         r"episode|full|no ads|ads included|promos?|news|programming|block|"
                         r"championships?)\b|-\s*\d+\s+australian tv commercials", re.I)
_YEAR4 = re.compile(r"(?<!\d)(19[5-9]\d|20\d\d)(?!\d)")
# d/m/yy with the year LAST: "20-21 / 11 / 94" must not read as 20-21/11 (year 11).
_DATE_YY = re.compile(r"(?<!\d)\d{1,2}\s*[/.\-⁄]\s*\d{1,2}\s*[/.\-⁄]\s*(\d{2})"
                      r"(?!\s*[/.\-⁄]|\d)")
_DECADE_LABEL = re.compile(r"(?<![\d])(?:19)?([789]0)\s*'?s\b", re.I)


def broadcast_year(title):
    """The year a reel was broadcast, as its title states it, else None.

    A four-digit year wins ("(ATN-7, 1985)", "21.12.2005"), then a dd/mm/yy date
    ("(TVQ-10, 20/12/96)", "20-21 / 11 / 94"). Episode numbers ("Commercials 93") are not years.
    """
    match = _YEAR4.search(title)
    if match:
        return int(match.group(1))
    match = _DATE_YY.search(title)
    if match:
        yy = int(match.group(1))
        return 1900 + yy if yy >= 50 else 2000 + yy
    return None


def era_of(title, metadata_year=None):
    """'70s' / '80s' / '90s' for a title, or None when it is outside those decades or unknown.

    The broadcast year in the title decides; failing that the decade the title names
    ("80s Australian Commercials 1"); failing that archive.org's `year` field, but only when it
    falls in the three decades (it is often the upload year).
    """
    year = broadcast_year(title)
    if year is None:
        label = _DECADE_LABEL.search(title)
        if label:
            return label.group(1) + "s"
        try:
            year = int(str(metadata_year)[:4]) if metadata_year else None
        except ValueError:
            year = None
    if year is None or not 1970 <= year <= 1999:
        return None
    return "%d0s" % ((year % 100) // 10)


def is_reel(title):
    """Does the title name a compilation of Australian commercials?"""
    return bool(_ABOUT_ADS.search(title)) and not _NOT_A_REEL.search(title)


def select(docs, cap=400):
    """Filter + tag + order search docs ({identifier, title, year}) into the candidate list."""
    seen = {}
    for doc in docs:
        ident = doc.get("identifier")
        title = doc.get("title")
        if isinstance(title, list):
            title = title[0] if title else None
        if not ident or not isinstance(title, str) or ident in seen or not is_reel(title):
            continue
        era = era_of(title, doc.get("year"))
        if era is None:
            continue
        seen[ident] = {"id": ident, "title": " ".join(title.split()), "era": era,
                       "year": broadcast_year(title)}
    by_era = {era: sorted((r for r in seen.values() if r["era"] == era), key=lambda r: r["id"])
              for era in ERAS}
    ordered = []
    for i in range(max((len(v) for v in by_era.values()), default=0)):
        for era in ERAS:
            if i < len(by_era[era]):
                ordered.append(by_era[era][i])
    return ordered[:cap]


def search(query, rows=2000):
    params = [("q", query), ("fl[]", "identifier"), ("fl[]", "title"), ("fl[]", "year"),
              ("rows", str(rows)), ("output", "json")]
    request = urllib.request.Request(SEARCH_URL + "?" + urllib.parse.urlencode(params),
                                     headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=90) as response:
        return json.load(response)["response"]["docs"]


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--cap", type=int, default=400)
    args = parser.parse_args()
    docs = []
    for i, query in enumerate(QUERIES):
        if i:
            time.sleep(1.5)
        docs.extend(search(query))
    reels = select(docs, args.cap)
    counts = {era: sum(1 for r in reels if r["era"] == era) for era in ERAS}
    print("find_reels: %d candidates %s" % (len(reels), counts), file=sys.stderr)
    json.dump(reels, sys.stdout, indent=1)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
