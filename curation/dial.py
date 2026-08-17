#!/usr/bin/env python3
"""The dial, declared: what is on every channel and what number it sits on.

This is the file to edit when the lineup changes. `apply_dial.py` reconciles the confs to it -
renaming, merging, creating and renumbering as needed - so the shape of the dial lives in one
readable place rather than being an emergent property of 130 separate files.

Two blocks, deliberately:

  1-90     YouTube. Clip channels, clock-rotated, refreshed nightly.
  101+     Live. Broadcast HLS feeds, playing whatever is actually on.

The gap at 91-100 is not an accident. It means "the YouTube dial ends here" is a place on the
remote rather than a number you have to remember, and it leaves room to add clip channels without
pushing the news block around - which matters, because the news channels are the ones anyone
reaches for directly.
"""

# (number, slug, name, search query)
#
# The slug is the conf filename and never changes once content is attached to it; the name is what
# appears on screen and can. Where a channel absorbed another, its query was widened to cover both
# - see ABSORBED below for what went where.
YOUTUBE = [
    (1,  "ufc",              "UFC",                      "UFC fight highlights"),
    (2,  "boxing",           "Boxing",                   "boxing fight highlights"),
    (3,  "wrestling",        "Wrestling",                "pro wrestling match highlights"),
    (4,  "soccer",           "Soccer",                   "football match highlights"),
    (5,  "rugby_union",      "Rugby Union",              "rugby union match highlights"),
    (6,  "basketball",       "Basketball",               "basketball game highlights"),
    (7,  "tennis",           "Tennis",                   "tennis match highlights"),
    (8,  "golf",             "Golf",                     "golf tournament highlights"),
    (9,  "motorsports",      "Motorsports",              "motorsport race highlights"),
    (10, "surfing",          "Surfing",                  "surfing competition heat"),
    (11, "darts_snooker",    "Darts & Snooker",          "darts snooker match highlights"),
    (12, "documentaries",    "Documentaries",            "full documentary"),
    (13, "aussie_tv",        "Aussie TV",                "australian television full episode"),
    (14, "film_animation",   "Film & Animation",         "science fiction short film full"),
    (15, "short_films",      "Short Films",              "award winning short film"),
    (16, "public_domain",    "Public Domain Movies",     "public domain full movie"),
    (17, "westerns",         "Westerns",                 "classic western full movie"),
    (18, "martial_arts",     "Martial Arts",             "martial arts full movie english"),
    (19, "comedy",           "Comedy",                   "stand up comedy special full show"),
    (20, "sketch",           "Sketch Comedy",            "sketch comedy full episode"),
    (21, "panel_shows",      "Panel Shows",              "QI full episode"),
    (22, "sitcoms",          "Classic Sitcoms",          "classic sitcom full episode 1950s"),
    (23, "talk_shows",       "Talk Shows",               "late night talk show full interview"),
    (24, "cartoons",         "Cartoons",                 "classic cartoon full episode"),
    (25, "anime",            "Anime",                    "anime full episode english dub"),
    (26, "anime_classic",    "Classic Anime",            "retro anime OVA english dub"),
    (27, "game_shows",       "Game Shows",               "game show full episode"),
    (28, "true_crime",       "True Crime",               "true crime documentary full"),
    (29, "scitech",          "Science & Exploration",    "science documentary full episode"),
    (30, "nature",           "Nature & Wildlife",        "wildlife documentary full episode"),
    (31, "pets",             "Pets & Animals",           "animal rescue documentary"),
    (32, "geography",        "Geography & Expeditions",  "expedition documentary full"),
    (33, "history",          "History",                  "history documentary full episode"),
    (34, "military_history", "Military History",         "military history documentary full"),
    (35, "abandoned",        "Abandoned Places",         "abandoned places urban exploration"),
    (36, "literature",       "Literature",               "literature lecture audiobook classic"),
    (37, "programming",      "Programming",              "software engineering conference talk"),
    (38, "economics",        "Economics & Finance",      "economics documentary explainer"),
    (39, "video_essay",      "Video Essay",              "video essay"),
    (40, "podcasts",         "Podcasts & Interviews",    "long form interview podcast"),
    (41, "food",             "Food",                     "street food tour"),
    (42, "travel",           "Travel & Events",          "travel documentary tour"),
    (43, "fashion",          "Fashion",                  "fashion documentary designer"),
    (44, "runway",           "Runway",                   "runway show full fashion week"),
    (45, "vintage_fashion",  "Vintage Fashion",          "vintage fashion history"),
    (46, "sewing",           "Sewing & Dressmaking",     "sewing dressmaking tutorial"),
    (47, "camping",          "Camping & Bushcraft",      "bushcraft camping wild"),
    (48, "architecture",     "Architecture & Interiors", "architecture documentary house tour"),
    (49, "arts_crafts",      "Arts & Crafts",            "pottery painting craft process"),
    (50, "restoration",      "Restoration",              "restoration blacksmithing workshop"),
    (51, "model_railways",   "Model Railways",           "model railway layout scenic railway"),
    (52, "health_fitness",   "Health & Fitness",         "fitness training documentary"),
    (53, "autos",            "Autos & Vehicles",         "car restoration road test"),
    (54, "gaming",           "Gaming",                   "retro gaming documentary"),
    (55, "concerts",         "Concerts",                 "tiny desk concert NPR Music"),
    (56, "rock",             "Rock",                     "rock live performance"),
    (57, "country",          "Country",                  "country music live performance"),
    (58, "blues",            "Blues",                    "blues music live performance"),
    (59, "soul_motown",      "Soul & Motown",            "soul motown classic performance"),
    (60, "reggae",           "Reggae",                   "reggae live performance"),
    (61, "hiphop",           "Hip Hop",                  "hip hop live performance"),
    (62, "electronic",       "Electronic",               "electronic music live set"),
    (63, "yacht_rock",       "Yacht Rock",               "yacht rock soft rock 70s 80s"),
    (64, "classical",        "Classical Music",          "classical orchestra opera performance"),
    (65, "music_1920s",      "1920s Music",              "1920s jazz music"),
    (66, "music_1930s",      "1930s Music",              "1930s swing music"),
    (67, "music_1940s",      "1940s Music",              "1940s big band music"),
    (68, "music_1950s",      "1950s Music",              "1950s rock and roll"),
    (69, "music_1960s",      "1960s Music",              "1960s music hits"),
    (70, "music_1970s",      "1970s Music",              "1970s music hits"),
    (71, "music_1980s",      "1980s Music",              "1980s music video"),
    (72, "music_1990s",      "1990s Music",              "1990s music video"),
    (73, "music_2000s",      "2000s Music",              "2000s music video"),
    (74, "music_2010s",      "2010s Music",              "2010s music video"),
    (75, "music_now",        "Music Now",                "new music video"),
    (76, "christianity",     "Christianity",             "christian teaching sermon"),
    (77, "christian_music",  "Christian Music",          "worship music live"),
    (78, "bossa_nova",       "Bossa Nova",               "bossa nova classics"),
    (79, "nrl",              "Rugby League",             "NRL match highlights"),
    (80, "afl",              "AFL",                      "AFL match highlights"),
    (81, "cricket",          "Cricket",                  "cricket match highlights"),
    (82, "fishing",          "Fishing & Boating",        "fishing charter boating"),
    (83, "jazz",             "Jazz",                     "jazz live performance"),
    (84, "ambient",          "Ambient",                  "ambient lofi long mix"),
    (85, "space",            "Space & Astronomy",        "space astronomy documentary"),
    (86, "aviation",         "Engineering & Aviation",   "aviation engineering documentary"),
    (87, "philosophy",       "Philosophy & Psychology",  "philosophy psychology lecture"),
    (88, "gardening",        "Gardening",                "gardening farming smallholding"),
    (89, "christian_docs",   "Christian Docos",          "christian documentary full"),
    (90, "audio_bible",      "Audio Bible",              "audio bible full book"),
]

# Channels that were folded into another, and where they went. Their confs are deleted; the
# survivor's query above was widened to cover the ground both used to.
#
# All of these had roughly a dozen clips on them and covered a slice of a subject the survivor
# already had - a channel too narrow to fill is a channel that repeats itself, and a dial with
# eight of them is worse than a dial with none.
ABSORBED = {
    "aussie_docos": "documentaries",     "scifi": "film_animation",
    "samurai": "westerns",               "anime_movies": "anime",
    "ghibli": "anime",                   "game_shows_uk": "game_shows",
    "game_shows_us": "game_shows",       "how_its_made": "scitech",
    "shipwrecks": "scitech",             "mountaineering": "scitech",
    "polar": "scitech",                  "ocean": "nature",
    "storms": "nature",                  "ancient_history": "history",
    "modern_history": "history",         "archaeology": "history",
    "wwii": "military_history",          "cooking": "food",
    "survival": "camping",               "blacksmithing": "restoration",
    "workshop": "restoration",           "trains": "model_railways",
    "retro_gaming": "gaming",            "music": "concerts",
    "music_docs": "concerts",            "aussie_rock": "rock",
    "opera": "classical",                "hymns": "christian_music",
    "slow_tv": "ambient",                "ambient_fire": "ambient",
    "engineering": "aviation",           "psychology": "philosophy",
    "farming": "gardening",              "homesteading": "gardening",
}

# Gone entirely, with the reason, because "why did that channel disappear" is a question that
# comes up months later.
DROPPED = {
    "news_politics": "news from YouTube is hours old; the live block does it properly",
    "classic_tv": "overlapped Classic Sitcoms and Cartoons without adding anything",
    "retro_ads": "a novelty that wore off after one evening",
    "trailers": "advertising, and it played like advertising",
    "vlogs": "no coherent subject, so nothing to search for",
}

# Renamed from the slug they still use on disk, so existing content is not thrown away.
RENAMED_SLUGS = {"lofi": "ambient", "game_shows_au": "game_shows"}

# (number, slug, name, [(title, url)]) - broadcast feeds, played as they come.
LIVE = [
    (101, "iptv_abc_tv_qld", "ABC TV QLD", [
        ("ABC TV QLD", "https://c.mjh.nz/abc-qld.m3u8")]),
    (102, "iptv_abc_tv_plus", "ABC TV Plus", [
        ("ABC TV Plus", "https://c.mjh.nz/abc-plus.m3u8")]),
    (103, "iptv_abc_entertains", "ABC Entertains", [
        ("ABC Entertains", "https://c.mjh.nz/abc-entertains.m3u8")]),
    (104, "iptv_abc_kids", "ABC Kids", [
        ("ABC Kids", "https://c.mjh.nz/abc-kids.m3u8")]),
    (105, "iptv_abc_news", "ABC News", [
        ("ABC News", "https://abc-news-dmd-streams-1.akamaized.net/out/v1/"
                     "701126012d044971b3fa89406a440133/index.m3u8")]),
    (106, "news_local", "AUS News", None),
    (107, "news_aljazeera", "Al Jazeera", [
        ("Al Jazeera English", "https://live-hls-apps-aje-fa.getaj.net/AJE/index.m3u8")]),
    (108, "news_france24", "France 24", [
        ("France 24 English",
         "https://live.france24.com/hls/live/2037218-b/F24_EN_HI_HLS/master_5000.m3u8")]),
    (109, "news_dw", "DW English", [
        ("DW English", "https://dwamdstream102.akamaized.net/hls/live/2015525/"
                       "dwstream102/index.m3u8")]),
    (110, "news_euronews", "Euronews", [
        ("Euronews English", "https://jmp2.uk/plu-61de96114757070008d33cae.m3u8")]),
    (111, "news_nhk", "NHK World", [
        ("NHK World-Japan", "https://masterpl.hls.nhkworld.jp/hls/w/live/smarttv.m3u8")]),
    (112, "news_cna", "CNA", [
        ("CNA", "https://live1.mediadesk.al/cnatvlive.m3u8")]),
    (113, "news_cgtn", "CGTN", [
        ("CGTN", "https://news.cgtn.com/resource/live/english/cgtn-news.m3u8")]),
    (114, "news_nbc", "NBC News NOW", [
        ("NBC News NOW", "https://d1si3n1st4nkgb.cloudfront.net/10502/88896001/hls/master.m3u8")]),
    (115, "news_cbs", "CBS News", [
        ("CBS News 24/7", "https://jmp2.uk/plu-6350fdd266e9ea0007bedec5.m3u8")]),
    (116, "iptv_sky_racing_1", "Sky Racing 1", None),
    (117, "iptv_sky_racing_2", "Sky Racing 2", None),
    (118, "iptv_sky_thoroughbred_central", "Sky Thoroughbred Central", None),
]
