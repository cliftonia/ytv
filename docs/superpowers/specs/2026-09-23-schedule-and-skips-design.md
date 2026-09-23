# Half-hour schedule, time-of-day mixes, and sponsor skips

Two features that change how "what is on now" is computed, specified together because the second
packs clips by the length the first leaves behind. Both apply to clock-rotating channels only
(`rotation: "clock"`: YouTube and file channels). Live channels and the Pluto dial are untouched.

Each ships behind a Settings row, default ON, so either can be switched off from the remote:
`SKIP SPONSORS: ON/OFF` and `SCHEDULE: HALF-HOUR/CONTINUOUS`. OFF is exactly today's behaviour.

## Part 1: Sponsor skips

### Data

The nightly job looks each YouTube clip up in SponsorBlock and stores what to skip on the stream:

```json
{"id": "abc123def45", "url": "...", "duration": 812, "title": "...", "skip": [[31.2, 74.9], [790.0, 812.0]]}
```

- Categories: `sponsor`, `selfpromo`, `interaction`. Intros and outros are NOT skipped.
- Only segments with `votes >= 0` and not `hidden`/`shadowHidden`; locked segments always count.
- Ranges merged, clamped to `[0, duration]`, sorted; ranges under 1s dropped.
- `duration` stays the raw length. The app derives `watchDuration = duration - sum(skip)`.
- Lookup uses the privacy-preserving hash-prefix endpoint
  `GET https://sponsor.ajay.app/api/skipSegments/<first 4 hex of sha256(id)>?categories=[...]`,
  one request per distinct prefix, results cached in the confs so each clip is looked up once
  (re-checked after 30 days, since segments get submitted after upload).
- A failed lookup is "no information": the clip plays in full. SponsorBlock being down must never
  fail the nightly or the gate.
- Attribution: README credits SponsorBlock (data CC BY-NC-SA 4.0; this is non-commercial).

### App

- `Stream.skip: List<List<Double>> = emptyList()` (unknown-field tolerant, as the contract requires).
- **Clock → media time.** The rotation walks `watchDuration`. A position `t` of watch time maps to
  media time by stepping over each skip range that starts at or before it. Pure function, tested.
- **During playback**, a position watcher (every 250ms, on the UI thread, both engines) seeks to
  the end of a range when playback enters one. A range reaching the end of the clip ends the clip.
- With `SKIP SPONSORS: OFF`, `skip` is ignored everywhere and `watchDuration == duration`.

## Part 2: Half-hour schedule

### Model

Local time is divided into **slots** of 30 minutes starting at :00 and :30, in the device's time
zone (both televisions share one). A channel's day is filled slot by slot:

1. **Programme.** The next clip in the channel's order starts on a slot boundary. A programme
   longer than 30 minutes takes `ceil(watchDuration / 1800)` slots.
2. **Top-up.** The time left in its last slot is filled with the channel's **short clips** (under
   5 minutes of watch time) that fit, largest first, chosen deterministically.
3. **Card.** Whatever remains (under the shortest short clip) is an **"Up next" card**: channel
   number and name, the next programme's title and start time, over the guide's picker music.

### Time-of-day mixes

Four parts of the day, local time: **breakfast** 06–12, **afternoon** 12–18, **prime** 18–23,
**late** 23–06. A stream may carry `parts: ["prime", "late"]`. At each slot the channel draws
from the streams tagged with that part; a channel with no tagged streams for a part (the default
for every channel today) draws from all its streams. Mixes are opted into per channel in
`dial.py` (a query per part) and start with a handful of channels where they clearly fit.

### Determinism

Every television must compute the same schedule from the same lineup and clock, offline, for as
long as the lineup is cached. So the schedule is a **pure function of (channel, lineup, time)**:

- Each part's pool is laid out once per lineup as a **cycle**: programmes in list order (so
  `sequence.py`'s episode order survives), each with its top-ups and card. The layout counts
  slots only within that part, so prime time picks up tomorrow where it left off tonight, rather
  than jumping into the middle of whatever afternoon was showing.
- A programme that would cross the end of its part is deferred to the part's next day; the
  remaining slots are top-ups and a card. A programme longer than the whole part may cross.
- Top-up choice is seeded by `(channel number, slot index)`, never by `Random()` or device state.
- The slot on air: `slotIndex = floor(localEpochSeconds / 1800)`, mapped to the part and to the
  position in that part's cycle. The cycle is cached per channel and rebuilt when the lineup
  changes.

### App

- `schedule/HalfHourSchedule.kt`: the pure packer and lookup, returning what is on at an instant:
  `Programme(index, offsetSeconds, slotStart, slotEnd)` or `Card(nextIndex, until)`.
  JVM-tested: alignment, spanning, top-up, card, part boundaries, determinism across instances,
  and lookups at every second of a sample day agreeing with a straight-line replay.
- `Tuner` asks the schedule instead of `ClockRotation` when `SCHEDULE: HALF-HOUR`.
- End of a programme retunes as today. The schedule then says top-up, card, or next programme.
- The card is a Compose overlay with picker music, shown for exactly its scheduled time. Surfing
  away cancels it as it cancels anything else.
- Guide and banner show real start times: `NOW 7:30 Grand Designs · NEXT 8:00 ...`.

### Curation

- `dial.py` gains optional per-channel `PARTS = {"prime": "query", ...}`. `refresh_channels`
  fetches each part's clips and tags them; the gate treats each part as a channel for its
  collapse rule.
- `check_lineup` reports each channel's card time per day. Over 10% is a warning, not a refusal:
  it means the channel is short of short clips.

## Out of scope

- Retro commercials in the breaks (a possible later pool; the top-up step is where it would go).
- Station idents and sign-on/sign-off, which are a later nostalgia pass.
- Skipping intros and outros.

## Order of work

1. Sponsor skips: curation, then the app (contract, clock mapping, playback watcher, setting).
2. Half-hour schedule: packer, Tuner integration, card overlay, guide times, setting.
3. Time-of-day mixes: curation for the first handful of channels.

Each step is released and checked on both televisions before the next.
