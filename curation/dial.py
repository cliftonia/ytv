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
    # Sport, 1-14. Every code together, rather than the three Australian ones stranded in the
    # eighties where the old numbering had left them.
    (1,  "ufc",              "UFC",                      "UFC fight highlights"),
    (2,  "boxing",           "Boxing",                   "boxing fight highlights"),
    (3,  "wrestling",        "Wrestling",                "pro wrestling match highlights"),
    (4,  "soccer",           "Soccer",                   "football match highlights"),
    (5,  "rugby_union",      "Rugby Union",              "rugby union match highlights"),
    (6,  "nrl",              "Rugby League",             "NRL match highlights"),
    (7,  "afl",              "AFL",                      "AFL match highlights"),
    (8,  "cricket",          "Cricket",                  "cricket match highlights"),
    (9,  "basketball",       "Basketball",               "basketball game highlights"),
    (10, "tennis",           "Tennis",                   "tennis match highlights"),
    (11, "golf",             "Golf",                     "golf tournament highlights"),
    (12, "motorsports",      "Motorsports",              "motorsport race highlights"),
    (13, "surfing",          "Surfing",                  "surfing competition heat"),
    (14, "darts_snooker",    "Darts & Snooker",          "darts snooker match highlights"),

    # Film, 15-21.
    (15, "documentaries",    "Documentaries",            "full documentary"),
    (16, "aussie_tv",        "Aussie TV",                "australian television full episode"),
    (17, "film_animation",   "Film & Animation",         "science fiction short film full"),
    (18, "short_films",      "Short Films",              "award winning short film"),
    (19, "public_domain",    "Public Domain Movies",     "public domain full movie"),
    (20, "westerns",         "Westerns",                 "classic western full movie"),
    (21, "martial_arts",     "Martial Arts",             "martial arts full movie english"),

    # Comedy and television, 22-31.
    (22, "comedy",           "Comedy",                   "stand up comedy special full show"),
    (23, "sketch",           "Sketch Comedy",            "sketch comedy full episode"),
    (24, "panel_shows",      "Panel Shows",              "QI full episode"),
    (25, "sitcoms",          "Classic Sitcoms",          "classic sitcom full episode 1950s"),
    (26, "talk_shows",       "Talk Shows",               "late night talk show full interview"),
    (27, "cartoons",         "Cartoons",                 "classic cartoon full episode"),
    (28, "anime",            "Anime",                    "anime full episode english dub"),
    (29, "anime_classic",    "Classic Anime",            "retro anime OVA english dub"),
    (30, "game_shows",       "Game Shows",               "game show full episode"),
    (31, "true_crime",       "True Crime",               "true crime documentary full"),

    # Factual, 32-46.
    (32, "scitech",          "Science & Exploration",    "science documentary full episode"),
    (33, "space",            "Space & Astronomy",        "space astronomy documentary"),
    (34, "aviation",         "Engineering & Aviation",   "aviation engineering documentary"),
    (35, "nature",           "Nature & Wildlife",        "wildlife documentary full episode"),
    (36, "pets",             "Pets & Animals",           "animal rescue documentary"),
    (37, "geography",        "Geography & Expeditions",  "expedition documentary full"),
    (38, "history",          "History",                  "history documentary full episode"),
    (39, "military_history", "Military History",         "military history documentary full"),
    (40, "abandoned",        "Abandoned Places",         "abandoned places urban exploration"),
    (41, "literature",       "Literature",               "literature lecture audiobook classic"),
    (42, "philosophy",       "Philosophy & Psychology",  "philosophy psychology lecture"),
    (43, "programming",      "Programming",              "software engineering conference talk"),
    (44, "economics",        "Economics & Finance",      "economics documentary explainer"),
    (45, "video_essay",      "Video Essay",              "video essay"),
    (46, "podcasts",         "Podcasts & Interviews",    "long form interview podcast"),

    # Making, growing, going places, 47-62.
    (47, "food",             "Food",                     "street food tour"),
    (48, "travel",           "Travel & Events",          "travel documentary tour"),
    (49, "fashion",          "Fashion",                  "fashion documentary designer"),
    (50, "runway",           "Runway",                   "runway show full fashion week"),
    (51, "vintage_fashion",  "Vintage Fashion",          "vintage fashion history"),
    (52, "sewing",           "Sewing & Dressmaking",     "sewing dressmaking tutorial"),
    (53, "camping",          "Camping & Bushcraft",      "bushcraft camping wild"),
    (54, "fishing",          "Fishing & Boating",        "fishing charter boating"),
    (55, "gardening",        "Gardening",                "gardening farming smallholding"),
    (56, "architecture",     "Architecture & Interiors", "architecture documentary house tour"),
    (57, "arts_crafts",      "Arts & Crafts",            "pottery painting craft process"),
    (58, "restoration",      "Restoration",              "restoration blacksmithing workshop"),
    (59, "model_railways",   "Model Railways",           "model railway layout scenic railway"),
    (60, "health_fitness",   "Health & Fitness",         "fitness training documentary"),
    (61, "autos",            "Autos & Vehicles",         "car restoration road test"),
    (62, "gaming",           "Gaming",                   "retro gaming documentary"),

    # Music, 63-86: genres first, then the decades in order, then what came out this week.
    (63, "concerts",         "Concerts",                 "tiny desk concert NPR Music"),
    (64, "rock",             "Rock",                     "rock live performance"),
    (65, "country",          "Country",                  "country music live performance"),
    (66, "blues",            "Blues",                    "blues music live performance"),
    (67, "soul_motown",      "Soul & Motown",            "soul motown classic performance"),
    (68, "reggae",           "Reggae",                   "reggae live performance"),
    (69, "hiphop",           "Hip Hop",                  "hip hop live performance"),
    (70, "electronic",       "Electronic",               "electronic music live set"),
    (71, "yacht_rock",       "Yacht Rock",               "yacht rock soft rock 70s 80s"),
    (72, "jazz",             "Jazz",                     "jazz live performance"),
    (73, "bossa_nova",       "Bossa Nova",               "bossa nova classics"),
    (74, "classical",        "Classical Music",          "classical orchestra opera performance"),
    (75, "ambient",          "Ambient",                  "ambient lofi long mix"),
    (76, "music_1920s",      "1920s Music",              "1920s jazz music"),
    (77, "music_1930s",      "1930s Music",              "1930s swing music"),
    (78, "music_1940s",      "1940s Music",              "1940s big band music"),
    (79, "music_1950s",      "1950s Music",              "1950s rock and roll"),
    (80, "music_1960s",      "1960s Music",              "1960s music hits"),
    (81, "music_1970s",      "1970s Music",              "1970s music hits"),
    (82, "music_1980s",      "1980s Music",              "1980s music video"),
    (83, "music_1990s",      "1990s Music",              "1990s music video"),
    (84, "music_2000s",      "2000s Music",              "2000s music video"),
    (85, "music_2010s",      "2010s Music",              "2010s music video"),
    (86, "music_now",        "Music Now",                "new music video"),

    # Christian, 87-90, together at the end of the clip block rather than split across it.
    (87, "christianity",     "Christianity",             "christian teaching sermon"),
    (88, "christian_docs",   "Christian Docos",          "christian documentary full"),
    (89, "christian_music",  "Christian Music",          "worship music live"),
    (90, "audio_bible",      "Audio Bible",              "audio bible full book"),
]

# Curated playlists pinned to a channel, tried before search and in this order.
#
# Only for channels search cannot fill. A search asks YouTube for whatever it feels like ranking
# today; a playlist is a hundred of the thing that somebody has already sat down and gathered, and
# for a decade nobody is still uploading that is the difference between a channel and a gap.
#
# Several are allowed per channel because the 1930s has no single deep list - the best one found
# holds 37 songs inside the duration window, so it takes three to make a channel.
PLAYLISTS = {
    "music_1920s": ["PL7D797EBD172C452D"],
    "music_1930s": ["PLXkLwx3USrbXz6Ogrwvox1XXnxJ7dmAMv",
                    "PLIvacmZCzEbCAfqbkgzLAPZYgLI_g2yEu",
                    "PLF4noIcOSXnvuluLjMOCElbMUXTIzXxqc"],
    "music_1960s": ["PLs5nLtKbGBVOZBCzgXxcN9fyCz6sQ1fab"],
    "music_1970s": ["PLyqXsO_d0hU1EqKKNY2MdtiXitI3zEVqg"],
    "bossa_nova":  ["PLUXl043M6v4PlGqvrXAjiVaG9NZCwCnP3"],
}

# Extra searches for a channel, tried after its main query.
#
# Naming the programme beats naming the genre, every time. "panel shows" returned a hundred
# Supernatural fan-convention panels because a convention panel is also a panel; "QI full episode"
# has no such twin. The same is true of people: a search for "christian teaching sermon" returns
# whatever is being pushed this week, while naming the preachers returns the preachers.
#
# This is also how a channel reaches a hundred without padding. One query exhausts long before
# YouTube runs out of the thing - it simply stops offering new results - so a second angle on the
# same subject is worth more than a deeper crawl of the first.
EXTRA_QUERIES = {
    "christianity": [
        "Joel Osteen full sermon", "Billy Graham classic crusade sermon",
        "T D Jakes full sermon", "Joyce Meyer full teaching",
        "Charles Stanley In Touch full sermon", "Rick Warren full message",
        "Andrew Wommack full teaching", "Steven Furtick full sermon",
        "Tony Evans full sermon", "Greg Laurie full message",
    ],
    "christian_docs": [
        "christian documentary feature film", "bible archaeology documentary",
        "missionary documentary full", "church history documentary full",
    ],
    "christian_music": [
        "Hillsong Worship live", "Elevation Worship full set",
        "Bethel Music live worship", "Chris Tomlin live",
        "gospel choir live performance",
    ],
    "panel_shows": [
        "Would I Lie To You full episode", "Taskmaster full episode",
        "8 Out of 10 Cats Does Countdown full episode",
        "Have I Got News For You full episode", "Mock the Week full episode",
    ],
    "comedy": [
        "Live at the Apollo full episode", "Dry Bar Comedy full special",
        "Melbourne Comedy Festival gala full", "Just for Laughs full special",
    ],
    "anime": [
        "isekai anime full episode english dub", "shonen anime full episode english sub",
        "anime full movie english dub",
    ],
    "game_shows": [
        "The Chase full episode", "Family Feud full episode",
        "Wheel of Fortune full episode", "Jeopardy full episode",
        "Hard Quiz full episode",
    ],
    "music_1930s": [
        "Glenn Miller Orchestra", "Duke Ellington 1930s", "Benny Goodman big band",
        "Billie Holiday 1930s", "Fats Waller", "Cab Calloway",
    ],
    "music_1920s": [
        "Louis Armstrong Hot Five", "Bessie Smith blues", "Jelly Roll Morton",
        "Paul Whiteman orchestra",
    ],
    "bossa_nova": [
        "Joao Gilberto", "Antonio Carlos Jobim", "Stan Getz Astrud Gilberto",
        "Sergio Mendes bossa",
    ],
    "electronic": [
        "Boiler Room set", "Cercle live set", "essential mix live",
        "techno live set", "house music live set", "drum and bass live set",
    ],
}

# Channels whose clips are episodes of something, and should therefore play in order.
#
# `sequence.py` sorts these after every refresh. The app needs no part in it: ClockRotation
# already walks the clip list in order, so ordering the list is the whole feature.
#
# It only pays off in proportion to how much of the channel came from a SEASON playlist. Search
# returns episode one of fifty shows - measured on the real dial, only 10 of 100 anime clips sat
# in a run longer than one - so a channel listed here without season playlists above will sort
# correctly and change almost nothing.
SEQUENCED = (
    "sitcoms", "anime", "anime_classic", "panel_shows", "game_shows", "aussie_tv", "cartoons",
)

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
