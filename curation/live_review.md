# LIVE TV draft - quality pass

A pass over all 852 channels of `live_draft.json`: each channel's placement checked against its
name, known shows, its guide's titles (Pluto's with genres), its service's group and description,
and its stream. Every fix is a rule in `build_live.py`, so it holds on the nightly rebuild; nothing
in the JSON was edited by hand, and no channel was renamed. 852 channels became 849: three were
the same feed as another channel and merged into it.

## The dial now

| Block | Sub-block | Channels |
|---|---|---:|
| Movies | Action | 6 |
| Movies | Comedy | 6 |
| Movies | Romance | 7 |
| Movies | Horror | 5 |
| Movies | Thriller | 8 |
| Movies | Sci-Fi | 4 |
| Movies | Westerns | 7 |
| Movies | Family | 3 |
| Movies | Classic | 4 |
| Movies | Drama | 6 |
| Movies | Decades | 5 |
| Movies | Martial Arts | 3 |
| Movies | Black Cinema | 6 |
| Movies | Cult & B-Movies | 5 |
| Movies | Indie & World | 4 |
| Movies | Movies – Mixed | 19 |
| **Movies** | **all** | **98** |
| Series | Comedy | 26 |
| Series | Action | 4 |
| Series | Westerns | 7 |
| Series | Sci-Fi & Fantasy | 10 |
| Series | Drama | 15 |
| Series | Classic Drama | 7 |
| Series | Family & Teen Drama | 8 |
| Series | K-Drama | 6 |
| Series | Crime | 17 |
| Series | Reality | 20 |
| Series | Competition Reality | 18 |
| Series | Dating Reality | 6 |
| Series | Pawn & Deals | 8 |
| Series | Docu-Reality | 11 |
| Series | Judge & Talk | 4 |
| **Series** | **all** | **167** |
| Sitcoms (USA) | Sitcoms | 18 |
| **Sitcoms (USA)** | **all** | **18** |
| Game Shows | Game Shows | 13 |
| **Game Shows** | **all** | **13** |
| Cartoons & Kids | Cartoons | 21 |
| Cartoons & Kids | Kids | 14 |
| Cartoons & Kids | Preschool | 20 |
| **Cartoons & Kids** | **all** | **55** |
| Anime | Anime | 12 |
| **Anime** | **all** | **12** |
| Food, Home & Travel | Food | 22 |
| Food, Home & Travel | Food Reality | 8 |
| Food, Home & Travel | Home | 20 |
| Food, Home & Travel | Antiques | 4 |
| Food, Home & Travel | Crafts & Garden | 9 |
| Food, Home & Travel | Travel | 17 |
| **Food, Home & Travel** | **all** | **80** |
| Music | 60s & 70s | 5 |
| Music | 80s | 4 |
| Music | 90s & 2000s | 7 |
| Music | Rock | 5 |
| Music | Country | 7 |
| Music | Hip-Hop/R&B | 9 |
| Music | Pop | 15 |
| Music | Classical/Jazz | 4 |
| Music | Concerts & Live | 5 |
| Music | Other | 10 |
| **Music** | **all** | **71** |
| Documentaries | General | 12 |
| Documentaries | Nature | 19 |
| Documentaries | Pets & Vets | 8 |
| Documentaries | History | 20 |
| Documentaries | Science & Space | 16 |
| Documentaries | Paranormal | 18 |
| Documentaries | True Crime | 25 |
| Documentaries | Forensics & Cold Cases | 10 |
| Documentaries | Cops & Courts | 11 |
| Documentaries | Motoring | 20 |
| Documentaries | Outdoors | 22 |
| **Documentaries** | **all** | **181** |
| Sports | General | 21 |
| Sports | Combat | 15 |
| Sports | Soccer | 7 |
| Sports | US Leagues | 9 |
| Sports | Motorsport | 10 |
| Sports | Tennis & Golf | 6 |
| Sports | Cue, Darts & Poker | 8 |
| Sports | Action & Extreme | 11 |
| Sports | Other Sports | 6 |
| **Sports** | **all** | **93** |
| Relax (off) | Relax | 22 |
| **Relax** | **all** | **22** |
| Unsorted (off) | Unsorted | 37 |
| **Unsorted** | **all** | **37** |
| Flagged (off) | Flagged | 2 |
| **Flagged** | **all** | **2** |

## Rules that changed

- **Single words that are no genre** no longer place a channel: `love`, `heart`, `wedo` (Romance),
  `alter` (Horror), `fury` (Martial Arts). `love & drama` stays a Romance phrase. Flicks of Fury is
  still Martial Arts, now by its Pluto guide (70% of airtime).
- **Weak words** - `classic(s)`, `retro`, `vault`, `gold`, `forever`, the decades, `adventure` - say
  when, not what. A guide of two or more known shows, half of one kind, now beats them (BET Classics
  airs The Jeffersons, Webster, Sister, Sister; Pluto TV Adventure airs Snowpiercer, The Librarians,
  The 100); one show's twelve-hour marathon does not (CW FOREVER's Beauty and the Beast).
- **Shows**: The Walking Dead, Arrow and The 100 are sci-fi or fantasy, not action; the western
  series have their own Series – Westerns.
- **Plex** lists a channel once per region (`<region>-<channel>`); those copies no longer count as a
  tie, so a same-named channel elsewhere can borrow the guide (RCM).
- **A guide of films with no channel group** is a film channel (RCM: Plex's guide is films). With a
  group, it is not (hour-long podcasts such as The Diary Of A CEO stay unsorted).
- **Finer sub-blocks** (`REFINE`): by name words, then a name that is a listed show, then half the
  guide's airtime in two or more listed shows, then the service group or FAST category. A finer
  sub-block with fewer than three channels folds back. Cartoons & Kids takes its sub from the guide
  when the guide is clear.
- **Music**: the 60s (1 channel) join the 70s, the 2000s (2) join the 90s; concerts split from Other.
- **Order within a sub-block is alphabetical** (case and a leading "The" ignored), not Pluto first.
  The owner picks from an alphabetical list by name, so the dial reads the same way. The source says
  nothing to someone surfing, and the old order sorted by data quality, not by what is on.
- **Flagged** is a new block, off by default, for channels that are not what a retro cable dial
  carries (`OFF_FORMAT`, with the reason in `why`).

## Misplacements fixed (23)

- 90's Kids: Cartoons & Kids – Kids → Cartoons & Kids – Cartoons (name: "kids"; guide: mostly Hey Arnold!, Aaahh!!! Real Monsters (cartoon))
- Animation+: Cartoons & Kids – Cartoons → Series – Comedy (adult cartoons (The Cyanide & Happiness Show, Skits From My Cell); Samsung files it under Comedy)
- BET Classics: Series – Drama → Sitcoms (USA) – Sitcoms (guide: mostly The Jeffersons, Webster (American sitcom))
- BizaarTV: Unsorted – Unsorted → Series – Comedy (cult and adult cartoons (The Goode Family, Speed Racer); Samsung: "mind-bending animation")
- CW Gold: Series – Drama → Series – Crime (guide: mostly NUMB3RS, Ransom (crime series))
- DraftKings Network: Sports – General → Flagged – Flagged (flagged off: sports betting: the sportsbook's own network, "built for today's passionate fans and bettors" (Samsung's listing))
- Dungeons & Dragons Adventures: Unsorted – Unsorted → Cartoons & Kids – Cartoons (eOne's Dungeons & Dragons channel (the 1983 cartoon), a sibling of its Transformers and Power Rangers feeds)
- El Rey Rebel: Movies – Movies – Mixed → Movies – Cult & B-Movies (name: "el rey" (guide: films))
- Evolution Earth: Documentaries – Nature → Documentaries – History (ancient-history and prophecy documentaries (Roman Engineering, Omens of Doom), not nature)
- Feva TV: Music – Other → Unsorted – Unsorted (Nigerian general entertainment (films, documentaries, gospel mixes), not a music channel)
- Fox Soul: Documentaries – General → Unsorted – Unsorted (Black culture talk and lifestyle (Samsung's listing), with Sunday worship: no one genre)
- Inside Outside: Food, Home & Travel – Travel → Food, Home & Travel – Home (name: "inside outside")
- MSG SportsZone: Sports – Cue, Darts & Poker → Sports – General (name: "sportszone")
- Nickelodeon Pluto TV: Cartoons & Kids – Kids → Cartoons & Kids – Cartoons (name: "nickelodeon"; guide: mostly SpongeBob SquarePants, The Loud House (cartoon))
- Omstars: Unsorted – Unsorted → Relax – Relax (name: "omstars")
- Overtime: Sports – Action & Extreme → Sports – General (name: "overtime")
- Pluto TV Adventure: Series – Action → Series – Sci-Fi & Fantasy (guide: mostly The Librarians, Snowpiercer (sci-fi or fantasy series))
- Pluto TV Kids: Cartoons & Kids – Kids → Cartoons & Kids – Cartoons (name: "kids"; guide: mostly Transformers: Armada, Camp Lakebottom (cartoon))
- RCM: Unsorted – Unsorted → Movies – Movies – Mixed (guide: films via Plex's RCM)
- SportsGrid: Sports – General → Flagged – Flagged (flagged off: sports betting: Samsung's listing calls it "the first 24-hour sports betting channel" (odds and wagering news))
- The Walking Dead Universe: Series – Action → Series – Sci-Fi & Fantasy (name is the show "the walking dead" (sci-fi or fantasy series))
- WBTV Love and Marriage: Series – Drama → Series – Dating Reality (name: "marriage")
- wedo movies: Movies – Romance → Movies – Movies – Mixed (name: films)

## Sub-block splits and merges

### Documentaries – True Crime (21)
- 60 Days in Jail: Documentaries – True Crime → Documentaries – Cops & Courts (name is the show "60 days in" (true-crime documentary); "jail")
- Bloodline Detectives: Documentaries – True Crime → Documentaries – Forensics & Cold Cases (name is the show "bloodline detectives" (true-crime documentary))
- BuzzFeed Unsolved: Documentaries – True Crime → Documentaries – Forensics & Cold Cases (name is the show "buzzfeed unsolved" (true-crime documentary))
- Cold Case Files: Documentaries – True Crime → Documentaries – Forensics & Cold Cases (name is the show "cold case files" (true-crime documentary))
- Cops: Documentaries – True Crime → Documentaries – Cops & Courts (name is the show "cops" (true-crime documentary))
- Cops & Docs: Documentaries – True Crime → Documentaries – Cops & Courts (name is the show "cops" (true-crime documentary))
- Court TV: Documentaries – True Crime → Documentaries – Cops & Courts (name: "court tv")
- Crime 24/7: Documentaries – True Crime → Documentaries – Cops & Courts (name: "crime 24 7"; guide: mostly cops & courts shows)
- Dog The Bounty Hunter: Documentaries – True Crime → Documentaries – Cops & Courts (name is the show "dog the bounty hunter" (true-crime documentary))
- Dr. G Medical Examiner: Documentaries – True Crime → Documentaries – Forensics & Cold Cases (name is the show "dr g medical examiner" (true-crime documentary))
- Forensic Files: Documentaries – True Crime → Documentaries – Forensics & Cold Cases (name is the show "forensic files" (true-crime documentary))
- Jail: Documentaries – True Crime → Documentaries – Cops & Courts (name is the show "jail" (true-crime documentary))
- Law & Crime: Documentaries – True Crime → Documentaries – Cops & Courts (name: "law & crime")
- Live PD Presents: Documentaries – True Crime → Documentaries – Cops & Courts (name is the show "live pd" (true-crime documentary))
- Medical Detectives: Documentaries – True Crime → Documentaries – Forensics & Cold Cases (name is the show "medical detectives" (true-crime documentary))
- Pluto TV Investigation: Documentaries – True Crime → Documentaries – Forensics & Cold Cases (name: "investigation")
- The First 48: Documentaries – True Crime → Documentaries – Forensics & Cold Cases (name is the show "the first 48" (true-crime documentary))
- Unsolved Mysteries: Documentaries – True Crime → Documentaries – Forensics & Cold Cases (name is the show "unsolved mysteries" (true-crime documentary))
- WBTV Chasing Criminals: Documentaries – True Crime → Documentaries – Cops & Courts (name: "criminals")
- WBTV Crime scenes: Documentaries – True Crime → Documentaries – Forensics & Cold Cases (name: "crime" (series, filed under Crime); "crime scenes")
- Witness to Justice: Documentaries – True Crime → Documentaries – Cops & Courts (guide: mostly Court Cam (true-crime documentary); guide: mostly cops & courts shows)

### Series – Reality (46)
- All Out Reality: Series – Reality → Series – Competition Reality (name: "reality"; guide: mostly competition reality shows)
- All Weddings We TV: Series – Reality → Series – Dating Reality (name: "weddings")
- Alone By History: Series – Reality → Series – Competition Reality (name is the show "alone" (reality show); filed under Reality Competition)
- America's Got Talent: Series – Reality → Series – Competition Reality (name is the show "america's got talent" (reality show))
- American Idol: Series – Reality → Series – Competition Reality (name is the show "american idol" (reality show))
- American Ninja Warrior: Series – Reality → Series – Competition Reality (name is the show "american ninja warrior" (reality show))
- American Pickers by History: Series – Reality → Series – Pawn & Deals (name is the show "american pickers" (reality show))
- Bondi Rescue: Series – Reality → Series – Docu-Reality (name is the show "bondi rescue" (reality show))
- Bondi Vet: Series – Reality → Series – Docu-Reality (name is the show "bondi vet" (reality show))
- Bring It!: Series – Reality → Series – Competition Reality (name is the show "bring it" (reality show))
- Challenge Accepted: Series – Reality → Series – Competition Reality (name: "challenge")
- Cheaters: Series – Reality → Series – Dating Reality (name is the show "cheaters" (reality show))
- Cutlers Court: Series – Reality → Series – Judge & Talk (name is the show "cutlers court" (reality show))
- Dating Disasters: Series – Reality → Series – Dating Reality (name: "dating")
- Deal Masters: Series – Reality → Series – Pawn & Deals (name: "deal masters")
- Deal Zone: Series – Reality → Series – Pawn & Deals (name: "deal zone")
- Divorce Court: Series – Reality → Series – Judge & Talk (name is the show "divorce court" (reality show))
- Don't Tell The Bride: Series – Reality → Series – Dating Reality (name is the show "don't tell the bride" (reality show))
- Duck Dynasty: Series – Reality → Series – Docu-Reality (name is the show "duck dynasty" (reality show))
- Fear Factor: Series – Reality → Series – Competition Reality (name is the show "fear factor" (reality show))
- Forged In Fire: Series – Reality → Series – Competition Reality (name is the show "forged in fire" (reality show))
- Hardcore Pawn: Series – Reality → Series – Pawn & Deals (name is the show "hardcore pawn" (reality show))
- Hoarders: Series – Reality → Series – Docu-Reality (name is the show "hoarders" (reality show))
- Intervention: Series – Reality → Series – Docu-Reality (name is the show "intervention" (reality show))
- Judge Nosey: Series – Reality → Series – Judge & Talk (name: "judge")
- Matched Married Meet: Series – Reality → Series – Dating Reality (name: "married")
- Mr. Beast: Series – Reality → Series – Competition Reality (name is the show "mr beast" (reality show))
- Ninja Warrior: Series – Reality → Series – Competition Reality (name is the show "ninja warrior" (reality show))
- Nosey: Series – Reality → Series – Judge & Talk (name: "nosey")
- Operation Repo: Series – Reality → Series – Pawn & Deals (name is the show "operation repo" (reality show))
- Pawn Stars: Series – Reality → Series – Pawn & Deals (name is the show "pawn stars" (reality show))
- Pluto TV Competition: Series – Reality → Series – Competition Reality (name: "competition")
- Pluto TV Lives: Series – Reality → Series – Docu-Reality (name: "lives")
- Project Runway: Series – Reality → Series – Competition Reality (name is the show "project runway" (reality show))
- Robot Wars by Mech+: Series – Reality → Series – Competition Reality (name is the show "robot wars" (reality show))
- So… Real: Series – Reality → Series – Docu-Reality (service group: Reality; guide: mostly docu-reality shows)
- Spike Pluto TV: Series – Reality → Series – Pawn & Deals (guide: mostly Auction Hunters, Shipping Wars (reality show); guide: mostly pawn & deals shows)
- Storage Wars LA: Series – Reality → Series – Pawn & Deals (name is the show "storage wars" (reality show))
- Survivor: Series – Reality → Series – Competition Reality (name is the show "survivor" (reality show))
- The Biggest Loser: Series – Reality → Series – Competition Reality (name is the show "the biggest loser" (reality show))
- The Masked Singer: Series – Reality → Series – Competition Reality (name is the show "the masked singer" (reality show))
- True Lives: Series – Reality → Series – Docu-Reality (name: "true lives")
- Undercover Boss: Series – Reality → Series – Docu-Reality (name is the show "undercover boss" (reality show))
- Untold Stories of the ER: Series – Reality → Series – Docu-Reality (name is the show "untold stories of the er" (reality show))
- WBTV Unique Lives: Series – Reality → Series – Docu-Reality (name: "lives")
- Wipeout Xtra: Series – Reality → Series – Competition Reality (name is the show "wipeout" (reality show))

### Food, Home & Travel – Home (13)
- Antiques Roadshow PBS: Food, Home & Travel – Home → Food, Home & Travel – Antiques (name is the show "antiques roadshow" (home show))
- Antiques Roadshow UK: Food, Home & Travel – Home → Food, Home & Travel – Antiques (name is the show "antiques roadshow" (home show))
- At Home with Family Handyman: Food, Home & Travel – Home → Food, Home & Travel – Crafts & Garden (name: "home"; "handyman")
- Cash in the Attic: Food, Home & Travel – Home → Food, Home & Travel – Antiques (name is the show "cash in the attic" (home show))
- Craftsy: Food, Home & Travel – Home → Food, Home & Travel – Crafts & Garden (name: "craftsy")
- DIY Art: Food, Home & Travel – Home → Food, Home & Travel – Crafts & Garden (name: "diy")
- Epic Gardening TV: Food, Home & Travel – Home → Food, Home & Travel – Crafts & Garden (name: "gardening")
- Gardening With Monty Don: Food, Home & Travel – Home → Food, Home & Travel – Crafts & Garden (name: "gardening")
- PBS Antiques Road Trip: Food, Home & Travel – Home → Food, Home & Travel – Antiques (name: "antiques")
- The Bob Ross Channel: Food, Home & Travel – Home → Food, Home & Travel – Crafts & Garden (name is the show "bob ross" (home show))
- TheSorryGirls TV: Food, Home & Travel – Home → Food, Home & Travel – Crafts & Garden (name: "thesorrygirls")
- This Old House Makers: Food, Home & Travel – Home → Food, Home & Travel – Crafts & Garden (name is the show "this old house makers" (home show))
- WBTV How To: Food, Home & Travel – Home → Food, Home & Travel – Crafts & Garden (name: "how to")

### Series – Drama (21)
- BYUtv: Series – Drama → Series – Family & Teen Drama (service group: Action & Drama (guide: series); "byutv")
- Classic TV Drama: Series – Drama → Series – Classic Drama (name: "drama" (guide: series); "classic")
- CW FOREVER: Series – Drama → Series – Classic Drama (name: "forever" (guide: series))
- Degrassi: Series – Drama → Series – Family & Teen Drama (name is the show "degrassi" (drama series))
- Dhar Mann TV: Series – Drama → Series – Family & Teen Drama (service group: Action & Drama (guide: series); "dhar mann")
- Feel Good Drama: Series – Drama → Series – Family & Teen Drama (guide: mostly Touched By An Angel, Dr. Quinn, Medicine Woman (drama series); "feel good")
- HeartFelt TV: Series – Drama → Series – Family & Teen Drama (name: "heartfelt" (series))
- Heartland: Series – Drama → Series – Family & Teen Drama (name is the show "heartland" (drama series))
- K Content by CJ ENM: Series – Drama → Series – K-Drama (name: "k content" (series))
- K Drama by CJ ENM: Series – Drama → Series – K-Drama (name: "drama" (series); "k drama")
- K Stories by CJ ENM: Series – Drama → Series – K-Drama (name: "stories" (series); "k stories")
- Lassie: Series – Drama → Series – Classic Drama (name is the show "lassie" (drama series))
- Little House on the Prairie: Series – Drama → Series – Classic Drama (name is the show "little house on the prairie" (drama series))
- Pluto TV Classic TV: Series – Drama → Series – Classic Drama (name: "classic" (guide: series))
- Pluto TV Hometown Drama: Series – Drama → Series – Family & Teen Drama (guide: mostly Everwood, Hart Of Dixie (drama series); "hometown")
- Series K Edge: Series – Drama → Series – K-Drama (name: "series" (guide: series); "series k")
- Series K Heart: Series – Drama → Series – K-Drama (name: "series" (guide: series); "series k")
- Series K Legacy: Series – Drama → Series – K-Drama (name: "series" (guide: series); "series k")
- Shout! TV: Series – Drama → Series – Classic Drama (service group: Western & Classic TV (guide: series); filed under Western & Classic TV)
- TV Land Drama: Series – Drama → Series – Classic Drama (name: "drama" (guide: series); "tv land")
- WBTV Generation Drama: Series – Drama → Series – Family & Teen Drama (name: "drama" (series); "generation")

### Food, Home & Travel – Food (8)
- Chef vs Chef by Food Network: Food, Home & Travel – Food → Food, Home & Travel – Food Reality (name: "chef")
- Come Dine With Me: Food, Home & Travel – Food → Food, Home & Travel – Food Reality (name is the show "come dine with me" (cooking show))
- Gordon Ramsay: Food, Home & Travel – Food → Food, Home & Travel – Food Reality (name is the show "gordon ramsay" (cooking show))
- Great British Menu: Food, Home & Travel – Food → Food, Home & Travel – Food Reality (name is the show "great british menu" (cooking show))
- Hell's Kitchen: Food, Home & Travel – Food → Food, Home & Travel – Food Reality (name is the show "hell's kitchen" (cooking show))
- Kitchen Nightmares: Food, Home & Travel – Food → Food, Home & Travel – Food Reality (name is the show "kitchen nightmares" (cooking show))
- Masterchef UK: Food, Home & Travel – Food → Food, Home & Travel – Food Reality (name is the show "masterchef" (cooking show))
- Top Chef Vault: Food, Home & Travel – Food → Food, Home & Travel – Food Reality (name is the show "top chef" (cooking show))

### Music – Other (5)
- Cirque du Soleil: Music – Other → Music – Concerts & Live (Cirque du Soleil shows)
- Live Music: Music – Other → Music – Concerts & Live (name: "music"; "live music")
- Our Vinyl: Music – Other → Music – Concerts & Live (name: "vinyl")
- Playing for Change: Music – Other → Music – Concerts & Live (name: "playing for change")
- Qello Concerts: Music – Other → Music – Concerts & Live (name: "qello")

### Series – Action (7)
- Death Valley Days: Series – Action → Series – Westerns (name is the show "death valley days" (western series))
- Pluto TV Westerns: Series – Action → Series – Westerns (guide: mostly Rawhide, Annie Oakley (western series))
- The Lone Ranger: Series – Action → Series – Westerns (name is the show "the lone ranger" (western series))
- Universal Westerns: Series – Action → Series – Westerns (guide: mostly Wagon Train, Tales of Wells Fargo (western series) via Roku's Universal Westerns)
- Wanted: Dead or Alive: Series – Action → Series – Westerns (name is the show "wanted dead or alive" (western series))
- Western TV: Series – Action → Series – Westerns (guide: mostly Have Gun Will Travel, Tombstone Territory (western series))
- Wild West TV: Series – Action → Series – Westerns (western series (Death Valley Days and the like))

### Documentaries – Nature (8)
- Dog Whisperer: Documentaries – Nature → Documentaries – Pets & Vets (name is the show "dog whisperer" (nature documentary))
- Love Pets: Documentaries – Nature → Documentaries – Pets & Vets (name: "pets")
- Lucky Dog: Documentaries – Nature → Documentaries – Pets & Vets (name is the show "lucky dog" (nature documentary))
- Rovr Pets: Documentaries – Nature → Documentaries – Pets & Vets (name: "pets")
- Samsung Wild Life: Documentaries – Nature → Documentaries – Pets & Vets (name: "wild"; guide: mostly pets & vets shows)
- The Pet Collective: Documentaries – Nature → Documentaries – Pets & Vets (name: "pet")
- Unleashed by DOGTV: Documentaries – Nature → Documentaries – Pets & Vets (name: "dogtv")
- WBTV Paws and Claws: Documentaries – Nature → Documentaries – Pets & Vets (name: "paws")

### Music – 70s (4)
- NOW 70's: Music – 70s → Music – 60s & 70s (name: "now 70's")
- Stingray Flashback 70s: Music – 70s → Music – 60s & 70s (name: "stingray"; "70s")
- Vevo '70s: Music – 70s → Music – 60s & 70s (name: "vevo"; "70s")
- Vevo '70s & '80s: Music – 70s → Music – 60s & 70s (name: "vevo"; "70s")

### Music – 90s (5)
- NOW 90s00s: Music – 90s → Music – 90s & 2000s (name: "now 90s00s")
- Stingray Nothin' But 90s: Music – 90s → Music – 90s & 2000s (name: "stingray"; "90s")
- Vevo '90s: Music – 90s → Music – 90s & 2000s (name: "vevo"; "90s")
- Vevo '90s & '00s: Music – 90s → Music – 90s & 2000s (name: "vevo"; "90s")
- XITE 90s Throwback: Music – 90s → Music – 90s & 2000s (name: "xite"; "90s")

### Music – 60s (1)
- Stingray Jukebox Oldies: Music – 60s → Music – 60s & 70s (name: "stingray"; "jukebox")

### Music – 2000s (2)
- Stingray Y2K: Music – 2000s → Music – 90s & 2000s (name: "stingray"; "y2k")
- Vevo 2K: Music – 2000s → Music – 90s & 2000s (name: "vevo"; "2k")

## Flagged off (2)

- SportsGrid: sports betting. Samsung's listing calls it "the first 24-hour sports betting channel"
  (odds, wagering news).
- DraftKings Network: sports betting. The sportsbook's own network, "built for today's passionate
  fans and bettors" (Samsung's listing).

No other news, shopping, religious, adult or non-English channel was found with evidence. Fox Soul
airs some Sunday worship, but it is mainly talk, so it went to Unsorted, which is off anyway.

## Duplicates merged (3, in `SAME_CHANNEL`)

- **BBC Impossible (Xumo) = Impossible Quiz Show (Roku).** They are one Wurl channel,
  `bbc-impossible-1-us`, at `bbc-impossible-1-us.xumo.wurl.tv` and `…roku.wurl.tv`.
- **Born to Kill (Samsung UK) = True Lives (Rakuten UK).** They are one Amagi channel, `amg00654c7`,
  and both play the playlist `amg00654-itvstudiosfast-truelives`.
- **WWE Superstar Central (Samsung US) = Wrestling Legends TV (Pluto).** They have an identical
  schedule: the same titles start at the same minutes (02:17, 04:00, 04:50, 05:41, 06:33 and 08:17
  UTC on 28 Sep, from Samsung's guide and a live api.pluto.tv fetch). Pluto's copy is kept.

What I checked and found not to be duplicates:
- Stream keys. Hosts, Wurl slugs, Amagi channel codes, Xumo ids, Stingray ids and A+E slugs showed
  only the pairs above. The two PBS channels share an Amagi host but play different paths.
- Guides. Samsung, Plex and Roku schedules for every guide channel, compared title-at-time: no
  other pair.
- Pluto against Samsung at the same times. UnXplained Zone, Pluto TV Lives, Tough Jobs, Pluto TV
  Cars and Spike each differ from the similar Samsung channel.

## Open doubts

- **Unsorted (37).** Twenty unknown Sofast, Stirr and Fire TV channels have no guide, category or
  description: Absinthe TV, Chrono, Colour Blind, Edgy TV, Encore+, English TV, Jupiter TV, Mama
  Benz TV, Mercury+, Nitro TV, Outer Vision, PeekFlick, Powertube TV, Tensions TV, TV Blossom,
  Wildest Wish TV, Envoy FAST, WeShort, MVMT of Culture and Unchained TV. They could hide adult
  or non-English channels.
  The rest are talk, podcasts, celebrity or business.
- **AWE Plus** is probably a travel and lifestyle channel (A Wealth of Entertainment), but nothing
  here proves it, so it stays Unsorted.
- **Stream name mismatches.** Cinevault Murder and Mayhem streams a URL named `cinevault-70s`, and
  Great! Romance one named `greatchristmas`. The candidate names may not be what plays.
- **K-dramas.** CJ ENM's K Drama, K Stories and K Content may be Korean with subtitles; Series K's
  guide says "English Dub".
- **China Travel and Discovering China** are made by CGTN, China's state broadcaster. They are travel
  and documentary channels, not news, so they were kept.
- **Evidence from one twelve-hour guide.** Some placements rest on that alone and may move as
  rotations change: Evolution Earth, CW Gold (Crime), Pluto TV Classic TV (Classic Drama), Pluto
  TV Adventure and the Cartoons/Kids re-sorts.
- **Still large.** Series – Comedy has 26 channels (clips, sketch, stand-up and late night) and
  Series – Reality keeps 20 general ones. Both could split further.
- **Unconfirmed names.** Love 2 Hate TV, American Stories and WBTV Sweet Escapes are placed only by
  their category or a name word.
