#!/usr/bin/env python3
"""What may go on a channel: the duration band, the commentary rejects and the language test.

This is the policy the whole dial is made of, and it is pure. It used to sit in the same file as
the subprocess call that runs yt-dlp, which meant the most heavily tested logic in the pipeline
could only be reached through the one part of it that needs a network.

Nothing here knows about confs, searching or CI. Given a slug and a title it answers whether the
clip belongs, which is the only question refresh_channels asks of it.
"""
import re


# Channels whose whole point is that the content is old. Asking these for this year's uploads
# returns reaction videos and retrospectives instead of the thing itself.
PERIOD = ("music_19", "music_20", "yacht", "westerns", "sitcoms", "anime_classic", "cartoons",
          "public_domain", "vintage", "retro", "classic", "silent", "noir")

# A song is three minutes; a documentary is fifty. One duration window cannot serve both, and
# the wrong one is what once left a music channel with seventeen clips on it.
SONGS = ("music_", "yacht", "rock", "jazz", "blues", "country", "reggae", "hiphop", "hip_hop",
         "soul", "motown", "electronic", "classical", "bossa", "christian_music", "opera")
LONG = ("documentaries", "podcasts", "concerts", "public_domain", "westerns", "samurai",
        "docos", "history", "philosophy", "lecture")

# Somebody talking about the thing, rather than the thing. Every one of these was found on a
# channel it had no business being on.
COMMENTARY = ("top ", "best ", "ranked", "tier list", "greatest", "need to watch", "must watch",
              "recommendation", "in a nutshell", "of all time", "i watched", "i binged",
              "reaction", "explained", "review", "ranking", "compilation of", "tiktok",
              "shorts compilation")


# Languages that turn up on this dial. Matched only where the word names the language OF the
# content, never where it merely describes the subject - see english_speech().
FOREIGN = (r"hindi|urdu|tamil|telugu|bangla|bengali|punjabi|marathi|malayalam|kannada|"
           r"indonesia|bahasa|espa[nñ]ol|latino|portugu[eê]s|brasil|fran[cç]ais|deutsch|"
           r"t[uü]rk[cç]e|russian|filipino|tagalog|vietnam|thai|arabic|farsi|persian|"
           r"mandarin|cantonese|korean|japanese|italiano|nederlands|polski")

# The language word standing next to something that says it IS the audio: a dub, a feed, a
# commentary. "Hindi Dubbed" and "National Geographic Hindi |" are the content's language;
# "Filipino street food" and "Japanese LoFi" are its subject, and both are perfectly watchable.
# Nouns that make the language word describe the AUDIO rather than the subject. "Tamil Christian
# Short Film" is a film in Tamil; "Filipino street food" is food from the Philippines, in English.
# `song` and `mix` are deliberately absent - a Japanese lofi mix has no speech to be in a language.
MEDIA_NOUN = (r"film|movie|serial|drama|episode|sermon|documentary|show|series|comedy|"
              r"dub|dubbed|sub|subs|subbed|subtitle[sd]?|audio|version|voice[- ]?over|"
              r"commentary|highlights|explained|summary|news|channel|tv")

CONTENT_LANGUAGE = re.compile(
    # The language, then a media noun within a few words: "Hindi Dubbed", "Tamil Christian Short
    # Film", "Telugu Full Episode".
    r"(?:\b(?:%s)\b[\w\s'&-]{0,24}?\b(?:%s)\b"
    # Or the other way round: "Dubbed in Hindi", "Audio: Telugu".
    r"|\b(?:%s)\b[\s|,:-]*(?:in\s+)?\b(?:%s)\b"
    # Or the language ENDING a pipe-delimited segment, which is how a feed names itself:
    # "| National Geographic Hindi |", "| ETV Telugu".
    r"|(?:%s)\s*(?:\||$))" % (FOREIGN, MEDIA_NOUN, MEDIA_NOUN, FOREIGN, FOREIGN), re.I)

# An explicit statement that this IS in English. Overrides everything below: a Japanese OVA
# labelled "[Eng Dub]" is exactly what the Classic Anime channel wants, original title and all.
ENGLISH = re.compile(r"\b(eng(?:lish)?[\s.-]*(?:dub|dubbed|sub|subbed|subtitle[sd]?|audio)"
                     r"|english|\[eng\]|\(eng\)|eng\s*dub|multi[- ]?sub)\b", re.I)

# How much of a title may be in another script before it stops being an English title. A few
# characters are an original title in brackets or a stylistic flourish; a third of the line is
# the actual name of a programme in another language.
FOREIGN_SCRIPT_SHARE = 0.20


def _ranges(*bounds):
    out = set()
    for low, high in bounds:
        out.update(range(low, high + 1))
    return frozenset(out)


# Scripts that are never decoration. Unlike Japanese or Korean - which appear in the original
# title of plenty of English-dubbed material - a single character from any of these means the
# title was written for someone who reads it.
CONTENT_SCRIPTS = _ranges(
    (0x0400, 0x04FF),   # Cyrillic
    (0x0590, 0x05FF),   # Hebrew
    (0x0600, 0x06FF),   # Arabic
    (0x0900, 0x097F),   # Devanagari
    (0x0980, 0x09FF),   # Bengali
    (0x0A00, 0x0A7F),   # Gurmukhi
    (0x0B80, 0x0BFF),   # Tamil
    (0x0C00, 0x0C7F),   # Telugu
    (0x0C80, 0x0CFF),   # Kannada
    (0x0D00, 0x0D7F),   # Malayalam
    (0x0E00, 0x0E7F),   # Thai
)


def english_speech(title):
    """Whether this looks like something an English speaker can follow.

    Three real titles off the dial set the shape of this, and a blunter filter gets all three
    wrong: "Angel Cop [Eng Dub] (OVA - 1989) エンゼル コップ" is an English dub whose original title
    is Japanese; "Philippines Street Food!! 14 Hour FILIPINO STREET FOOD Tour" is English-language
    vlogging where the language word describes the food; "Japanese LoFi HipHop Mix 時間の流れ" has no
    speech in it at all. Meanwhile "National Geographic Hindi" and "KBC के मंच पर पहुंची" genuinely
    are in another language.

    So: an explicit English marker wins outright, then a language named as the audio loses, then
    a title mostly written in another script loses.
    """
    text = title or ""
    if not text:
        return False
    if ENGLISH.search(text):
        return True
    if CONTENT_LANGUAGE.search(text):
        return False
    # Two classes of script, because they mean different things in a title.
    #
    # Japanese and Korean characters routinely appear in the ORIGINAL title of something that is
    # nonetheless in English - "Angel Cop [Eng Dub] (OVA - 1989) エンゼル コップ" - so a few of them
    # are allowed. Devanagari, Tamil, Telugu, Bengali, Arabic, Hebrew, Thai and Cyrillic do not
    # turn up that way: a title carrying any of them at all is a title written for someone who
    # reads them, and "KBC S18 | Ep. 1 | Full Episode | Sunny के Dhai Kilo" is a Hindi programme
    # however much of its title happens to be in English.
    if any(ord(c) in CONTENT_SCRIPTS for c in text):
        return False
    letters = [c for c in text if not c.isspace()]
    if not letters:
        return False
    foreign = sum(1 for c in letters if ord(c) > 0x0400)
    return foreign <= len(letters) * FOREIGN_SCRIPT_SHARE


def is_period(slug):
    return any(token in slug for token in PERIOD)


def window(slug):
    """The duration band a clip must fall inside to belong on this channel."""
    if any(token in slug for token in SONGS):
        return 60, 420
    if any(token in slug for token in LONG):
        return 1200, 9000
    return 240, 5400


def usable(title):
    low = (title or "").lower()
    if not low:
        return False
    if any(word in low for word in COMMENTARY):
        return False
    # A programme nobody in the room can follow is not content, whatever else is right about it.
    return english_speech(title)


# Deliberately not shared with sequence.show_name, which normalises a title for a different
# purpose. That one strips episode numbers and words like "full" and "series" so two uploaders'
# titles for the same SHOW land in one bucket; this one keeps every digit so two EPISODES of one
# show stay apart. Merging them would silently give one of the two the other's answer.
def title_key(title):
    """Loose identity for a clip, so the same song from two uploaders lands once.

    Digits are KEPT. Dropping standalone numbers was meant to stop "Live 2019" and "Live 2024"
    counting as two, and instead it deleted every episode of every series: "Episode 1" and
    "Episode 2" collapsed to the same key, so a season playlist was reduced to one episode before
    search even began. That is why the episodic channels never had a run longer than two - not
    because uploaders only post pilots, which is what the code used to say.

    The duplicate-year case it was protecting against is rare and harmless; losing whole series
    was neither.
    """
    low = re.sub(r"[^a-z0-9 ]", " ", (title or "").lower())
    return " ".join(low.split())[:60]
