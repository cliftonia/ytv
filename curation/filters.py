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


# Languages that turn up on this dial, split by whether the word can be trusted on its own.
# Matched only where the word names the language OF the content, never where it merely describes
# the subject - see english_speech().
#
# The UNAMBIGUOUS tier almost never labels anything but the language itself: an English title has
# no reason to contain "Hindi" or "Deutsch" unless it is telling the viewer what they will hear.
FOREIGN_UNAMBIGUOUS = (r"hindi|urdu|tamil|telugu|bangla|bengali|punjabi|marathi|malayalam|"
                       r"kannada|indonesia|bahasa|espa[nñ]ol|latino|portugu[eê]s|brasil|"
                       r"fran[cç]ais|deutsch|t[uü]rk[cç]e|italiano|nederlands|polski")

# The AMBIGUOUS tier doubles as demonyms, and in an English title that is usually what it is
# doing: "Korean War Documentary", "Thai Street Food" and "Russian history lecture" are English
# programmes ABOUT Korea, Thailand and Russia, and a documentary channel is full of exactly
# these. A word from this tier says nothing about the audio unless the title also names the
# audio - see AUDIO_NOUN below.
FOREIGN_AMBIGUOUS = (r"vietnam|thai|korean|japanese|russian|persian|farsi|arabic|mandarin|"
                     r"cantonese|filipino|tagalog")

# The language word standing next to something that says it IS the audio: a dub, a feed, a
# commentary. "Hindi Dubbed" and "National Geographic Hindi |" are the content's language;
# "Filipino street food" and "Japanese LoFi" are its subject, and both are perfectly watchable.
# Nouns that make the language word describe the AUDIO rather than the subject. "Tamil Christian
# Short Film" is a film in Tamil; "Filipino street food" is food from the Philippines, in English.
# `song` and `mix` are deliberately absent - a Japanese lofi mix has no speech to be in a language.
MEDIA_NOUN = (r"film|movie|serial|drama|episode|sermon|documentary|show|series|comedy|"
              r"dub|dubbed|sub|subs|subbed|subtitle[sd]?|audio|version|voice[- ]?over|"
              r"commentary|highlights|explained|summary|news|channel|tv")

# The narrower set an AMBIGUOUS language word needs before it convicts. "Documentary", "show"
# and "news" are precisely the words that follow a subject - "Korean War Documentary", "Japanese
# engineering documentary" - so within this tier they prove nothing; a movie, a drama, a dub or
# a named language track is speech, and speech has a language.
AUDIO_NOUN = (r"film|movie|drama|serial|natok|episode|dub|dubbed|sub|subs|subbed|"
              r"subtitle[sd]?|audio|language|version|voice[- ]?over")

CONTENT_LANGUAGE = re.compile(
    # An unambiguous language, then a media noun within a few words: "Hindi Dubbed", "Tamil
    # Christian Short Film", "Telugu Full Episode".
    r"(?:\b(?:%(unambiguous)s)\b[\w\s'&-]{0,24}?\b(?:%(media)s)\b"
    # An ambiguous one needs the narrower audio nouns: "Thai drama" is a drama in Thai, while
    # "Thai Street Food" and "Korean War Documentary" are subjects, not soundtracks.
    r"|\b(?:%(ambiguous)s)\b[\w\s'&-]{0,24}?\b(?:%(audio)s)\b"
    # Or the other way round: "Dubbed in Hindi", "Audio: Telugu".
    r"|\b(?:%(media)s)\b[\s|,:-]*(?:in\s+)?\b(?:%(unambiguous)s)\b"
    r"|\b(?:%(audio)s)\b[\s|,:-]*(?:in\s+)?\b(?:%(ambiguous)s)\b"
    # Or the language ENDING a pipe-delimited segment, which is how a feed names itself:
    # "| National Geographic Hindi |", "| ETV Telugu". The unambiguous tier only: a segment
    # ending in "...Korean" or "...Japanese" is far more often naming a subject than a feed.
    r"|(?:%(unambiguous)s)\s*(?:\||$))"
    % {"unambiguous": FOREIGN_UNAMBIGUOUS, "ambiguous": FOREIGN_AMBIGUOUS,
       "media": MEDIA_NOUN, "audio": AUDIO_NOUN}, re.I)

# An explicit statement that the AUDIO is English. Wins outright, before anything else is even
# consulted: a Japanese OVA labelled "[Eng Dub]" is exactly what the Classic Anime channel wants,
# original title and all.
ENGLISH_AUDIO = re.compile(
    r"(?:\beng(?:lish)?[\s.-]*(?:dub(?:bed)?|audio|version|voice[- ]?over|commentary)\b"
    r"|\bin\s+english\b|\benglish\s+language\b|[\[(]\s*eng(?:lish)?\s*[\])])", re.I)

# English SUBTITLES are weaker evidence than English audio, and keeping them apart is the whole
# point: "Full Movie Hindi Dubbed | English Subtitles" is a Hindi soundtrack with English text
# under it, and a room listening to the television rather than reading it cannot follow that.
# Subtitles only save a title that named no other language - see the ordering in english_speech.
ENGLISH_SUBS = re.compile(
    r"(?:\beng(?:lish)?[\s.-]*(?:sub(?:bed|s)?|subtitle[sd]?)\b|\bmulti[- ]?sub\b)", re.I)

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


# Latin-script languages, which the script check above cannot see at all.
#
# Two tiers, because matching a short function word at a word boundary looks precise and is not:
# `kung` matches "Kung Fu", `ng` matches "Andrew Ng", `yang` matches "Jimmy Yang", `de la` matches
# "De La Soul". English titles are full of loanwords and proper nouns from every language on this
# list, and a single-tier filter deletes them all.
#
# STRONG markers convict alone - no English title contains them by accident.
STRONG_FOREIGN = re.compile(
    r"\b(pinoy|tagalog|taglish|pilipino|bagong|sub\s*indo|indo\s*sub|dublado|legendado|"
    r"pelicula\s+completa|filme\s+completo|película|sinhala|vietsub)\b", re.I)

# WEAK markers mean nothing alone and a great deal together. Two distinct ones is the threshold:
# one is a loanword or a surname, two is a sentence in another language.
WEAK_FOREIGN = (
    re.compile(r"\b(ang|mga|ako|ikaw|kayo|sa'yo|kung|wala|salamat|sigaw|gintong|payapang|"
               r"probinsya|araw|ulan|alon|hulaan|bilis)\b", re.I),          # filipino
    re.compile(r"\b(dan|yang|untuk|dengan|tidak|adalah|animasi|sekolah|lucu)\b", re.I),  # indonesian
    re.compile(r"\b(que|una|con|para|por|del|como|capitulo|completo|nino)\b", re.I),     # spanish
    re.compile(r"\b(nao|voce|episodio|dos|uma|pela)\b", re.I),                            # portuguese
    re.compile(r"\b(icin|bolum|izle|ile|olarak)\b", re.I),                                # turkish
    re.compile(r"\b(cua|nguoi|khong|duoc|nhung|phim)\b", re.I),                           # vietnamese
)

# Two or more distinct words from ONE language. Requiring them from the same language stops a
# title borrowing one word each from three languages - which is what a global music channel's
# titles look like - from being read as foreign.
WEAK_THRESHOLD = 2


def latin_script_foreign(title):
    """Whether a title written in the Latin alphabet is nonetheless in another language."""
    text = title or ""
    if STRONG_FOREIGN.search(text):
        return True
    return any(len({w.lower() for w in rx.findall(text)}) >= WEAK_THRESHOLD
               for rx in WEAK_FOREIGN)


def english_speech(title):
    """Whether this looks like something an English speaker can follow.

    Three real titles off the dial set the shape of this, and a blunter filter gets all three
    wrong: "Angel Cop [Eng Dub] (OVA - 1989) エンゼル コップ" is an English dub whose original title
    is Japanese; "Philippines Street Food!! 14 Hour FILIPINO STREET FOOD Tour" is English-language
    vlogging where the language word describes the food; "Japanese LoFi HipHop Mix 時間の流れ" has no
    speech in it at all. Meanwhile "National Geographic Hindi" and "KBC के मंच पर पहुंची" genuinely
    are in another language.

    So: a marker that the AUDIO is English wins outright, then a language named as the audio
    loses, then English subtitles save whatever named no other language, then a title mostly
    written in another script loses.
    """
    text = title or ""
    if not text:
        return False
    if ENGLISH_AUDIO.search(text):
        return True
    if CONTENT_LANGUAGE.search(text):
        return False
    # Consulted AFTER the content language on purpose. "Hindi Dubbed | English Subtitles" has
    # named its soundtrack, and the subtitles do not change what the room hears; but on a title
    # that names no other language, "[English Subbed]" is the uploader saying an English speaker
    # can follow it, which is the question being asked.
    if ENGLISH_SUBS.search(text):
        return True
    # Another language written in our alphabet, which no script check can catch.
    if latin_script_foreign(text):
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
