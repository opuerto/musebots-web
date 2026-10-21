# Musebots Web

A web version of the jazz-quartet Musebots from *A Model of Inter-musician Communication for
Artificial Musical Intelligence* (Puerto & Thue, ICCC 2017). Autonomous musician agents run on a
server, negotiate with each other using FIPA protocols, and stream the notes they compose to a browser
that plays them.

This is a clean rewrite of the original 2016 Java/JADE implementation, whose agents were standalone
programs that played through the local MIDI soundbank.

## Run it

Requires Java 17+. No other install: the Gradle wrapper fetches everything else.

```
./gradlew :server:run        # then open http://localhost:7070
./gradlew test               # the JVM tests and the browser tests (the latter need Node 20+, and are skipped without it)
node --test server/src/test/js/*.test.mjs   # just the browser tests
```

Settings are environment variables, all optional:

| Variable | Default | |
|---|---|---|
| `PORT` | 7070 | the web port |
| `JADE_PORT` | 1099 | the agent platform's own port, used inside the machine only (never publish it) |
| `MAX_PERFORMANCES` | 8 | how many bands may play at once; each is sixteen agents, so this protects the server's memory. Extra visitors are asked to try again |
| `ALLOWED_ORIGINS` | none | extra web origins allowed to open the WebSocket, comma separated. Pages served by the server itself always may; other sites are refused (a browser does not stop a WebSocket from being opened from another site, so the server checks) |
| `MARKOV_MODEL` | none (the image: `models/markov.json`) | path of a trained solo model, for every instrument; see "Learning solos from real jazz" |
| `MARKOV_MODEL_SAX`, `_PIANO`, `_BASS`, `_DRUMS` | none | an instrument's own model, used instead of `MARKOV_MODEL` for that instrument (an instrument with neither solos by the rules) |

`GET /health` answers `{"status":"ok","performances":0,"capacity":8}` for uptime checks and the hosting platform.
`GET /models` lists the learned models the server plays from, and `/models/markov.json` downloads the one the whole band plays from, under the ODbL.

**Putting it on a public domain:** the repository has a `Dockerfile` that builds one image serving the page, the agents and the learned models in `models/`; set `PORT`, `MAX_PERFORMANCES` and, if needed, `ALLOWED_ORIGINS` as above, and point a health check at `/health`. The step-by-step guide for deploying with Coolify is kept outside the repository.

## How it works

```
 browser (Tone.js) <-- WebSocket: note / conversation / status --  server  --  JADE agents (3 per instrument)
        |                                                                       sax, piano, bass, drums
   schedules each note on the Web Audio clock                                   each = musician + composer + synchronizer + assistant
        ^                                                                       ^
        |   WebSocket: conduct (tempo / volume / mute)  -->  server  -->  conductor agent  -->  every assistant
```

1. The browser connects, syncs its clock with the server (best of 8 ping rounds), and sends `start`.
2. The server starts one **Musebot** per instrument for that session. A Musebot is four JADE agents:

   | Agent | Job |
   |---|---|
   | **Musician** | Decides. Takes part in the ensemble's conversations, and runs the performance while it has the lead. |
   | **Composer** | Writes the notes when its musician asks (FIPA Request) and publishes them. |
   | **Synchronizer** | Keeps the timeline of the performance. When a musician asks "when does the solo start?", it answers from memory, or asks the other Musebots' synchronizers (and keeps asking until someone knows). |
| **Ensemble Assistant** | The go-between with the Conductor. It picks out the Conductor's commands that concern its Musebot and relays them: tempo to the musician, volume and mute to the composer. |

3. **The tune.** The opening leader waits for the ensemble (directory lookup), shares the song, then runs a
   **FIPA Contract Net** for the intro: every accompanist proposes, the leader accepts the shortest offer
   (every accompanist offers the same length, so the tie is broken by a preference order that is shuffled for each performance)
   and rejects the rest. Then:

   ```
   intro (4 bars) -> head (1 chorus) -> a solo chorus for each musician, in turn -> ending (1 bar)
   ```

   **Random turns.** By default each performance shuffles the turns (`SessionConfig.TurnOrder.RANDOM`): any melodic player (sax,
   piano or bass; a drummer cannot play a head) may open the tune and play the head, the solos go in a shuffled order
   (everyone still solos once, so the leader may solo last) and the intro goes to whoever the shuffled preference favours. The start message
   can ask for the old fixed order with `"order":"fixed"` (sax leads, then piano, bass and drums; piano wins the intro tie).
   The session's `seed` makes a shuffle reproducible.

4. **Passing the lead.** The leader arranges each part shortly before the previous one ends (`planAheadMs`).
   It records the part on its synchronizer's timeline, asks its composer to write its own part, and asks the
   other musicians to back it up. Those requests name a *part of the tune*, not a time: each supporter asks its
   synchronizer when that part happens. At the end of a solo the leader sends a `take-lead` request to the next
   soloist and becomes a supporter. After the last solo, the leader asks everyone for the ending.

5. **Nobody drops out** (`SupportPolicy`): after its own solo a musician goes back to accompanying. Behind a
   drum solo everyone plays a lighter part so the fills can be heard, and everyone plays the ending.

6. **The Conductor.** In the Musebot Ensemble platform the Conductor is an external program that controls the
   ensemble. Here it is the web page: the server gives each command to a per-session `ConductorAgent` (through
   JADE's object-to-agent channel), which broadcasts it to every Ensemble Assistant.

   | Command | What happens |
   |---|---|
   | `tempo` | Every assistant tells its musician. The leader uses the new tempo for the parts it arranges from then on. Each part remembers the tempo it was arranged at, so a part is always written consistently (a change is heard from the next part of the tune). |
   | `volume` (one musician) | The assistant tells its composer, which scales the velocity of every note it releases from then on. |
   | `mute` / `unmute` (one musician) | The assistant tells its composer to stop or resume handing on notes. The tune goes on; that musician just sits out. |

   A composer writes a whole phrase at once but only hands its notes to the listener about a bar before they
   are due (`noteLookaheadMs`), which is why volume and mute are heard within about a bar rather than at the next
   part of the tune.

7. Agents publish `NoteEvent`s with absolute timestamps ahead of time. The browser converts them to audio-clock
   time, so network jitter does not affect rhythm. `segment` events tell the UI which part of the tune is playing and when.

The **ensemble** option in the UI chooses the full quartet (sax leading, piano, bass, drums) or the
original piano-and-bass duo.

### The instruments

Every composer writes six kinds of phrase (`PhraseKind`): intro, head, support (accompaniment), light support (behind a drum solo), solo and ending.

| Instrument | Head | Support | Light support | Solo | Ending |
|---|---|---|---|---|---|
| Sax | swung melody over the changes | soft sustained guide tone every other bar | the same, softer | busy swung line using the chord scale | held root |
| Piano | comping | rootless 3rd-7th-9th voicings | one soft chord per bar | right-hand line plus sparse left-hand chords | held chord |
| Bass | walking | walking: root, chord tone, fifth, chromatic approach | two-feel: root and fifth, softly | line in the bass register | held root |
| Drums | ride, hi-hat on 2 and 4, feathered kick, ghost snare | same | same | ride with tom runs, snare rolls and crashes | crash and kick |

Notes are plain `NoteEvent`s: a MIDI pitch (General MIDI percussion numbers for drums), an absolute start time
in milliseconds, a duration and a velocity. Swing is just timing: `Swing.OFFBEAT` places the second eighth of a
beat two thirds of the way in. The horn-like parts share one engine (`Improviser`): it picks a rhythmic cell per
bar, opens bars on guide tones (3rd or 7th), and steps through chord tones on the beat and scale tones off it. A *solo* is also
shaped into phrases (`phrased` profiles: the sax, piano right hand and bass solos): two bars or so, the second answering the
first in the same rhythm and shape, each ending on a held chord tone and a breath, rising and falling inside the phrase, with the last note of each
bar leading to the guide tone the next chord opens on. The head is not phrased.

### Modules

| Module | Responsibility | Depends on |
|---|---|---|
| `core` | Music domain (chords, songs, clock, timeline `Segment`), events, `PhraseComposer` strategy and composers, `SupportPolicy`. No frameworks. | nothing |
| `agents` | The JADE agents (`MusicianAgent`, `ComposerAgent`, `SynchronizerAgent`, `EnsembleAssistantAgent`, and the session's `ConductorAgent`), their protocols, and `EnsembleRuntime` that hosts sessions. | `core`, JADE |
| `learning` | Learning solos from real jazz: the corpus format, a Markov baseline model, the evaluation metrics, and the command-line tools. See [Learning solos from real jazz](#learning-solos-from-real-jazz). | `core` |
| `server` | Javalin HTTP + WebSocket, browser client in `src/main/resources/public`. | `agents`, `learning` |

### Design choices

- **Leading and supporting are separate classes.** The original `Musician` agent was a 20-state FSM with static
  shared fields. Now `Supporting` (what every musician does) and `Leading` (what the musician with the lead does)
  are separate, any musician can lead, and every dependency is injected through a config record.
- **Composition is a strategy.** The composer agent calls a `PhraseComposer`; swapping in an
  algorithmic-composition technique means implementing one interface, with no agent changes.
- **Agents are observable.** Everything an agent says or plays goes to an `EventSink`, which is how the UI shows
  the negotiation and how tests assert on it.
- **Sessions are isolated by name** on one shared JADE platform (agent names and directory entries are
  prefixed with the session id).
- **Message payloads are JSON** in the ACL content (`Protocol`), with FIPA performatives carrying the
  meaning. The original used SL codec ontologies.

### Protocols

| Between | Conversation | Protocol |
|---|---|---|
| musicians | `share-structure` | INFORM, answered by CONFIRM |
| musicians | `intro` | FIPA Contract Net |
| musicians | `accompany` (back me up in this part) | REQUEST, answered by INFORM |
| musicians | `take-lead` | REQUEST, answered by INFORM |
| musician and its composer | `compose` | REQUEST, answered by INFORM |
| musician and its synchronizer | `sync-record` / `sync-query` | INFORM / QUERY-REF (answered by INFORM or FAILURE) |
| synchronizers | `sync-peer` | QUERY-REF (answered by INFORM or FAILURE) |
| Conductor and every assistant | `conductor` | INFORM (broadcast) |
| assistant and its musician | `tempo` | INFORM |
| assistant and its composer | `mix` | INFORM |

### WebSocket messages (`/ws`)

| Direction | Message |
|---|---|
| client to server | `{"type":"start","song":"blues","tempo":120,"soloChoruses":1,"ensemble":"quartet","feel":"human","order":"random"}`, `{"type":"stop"}`, `{"type":"time.sync","t0":...}` |
| client to server | `{"type":"conduct","command":"tempo","value":140}`, `{"type":"conduct","command":"volume","instrument":"bass","value":0.7}`, `{"type":"conduct","command":"mute","instrument":"sax"}` (and `unmute`) |
| server to client | `hello` (with the tunes on offer), `time.sync`, `song` (the chart of the tune about to be played), `segment` (a part of the tune: intro, head, a solo or the ending), `note`, `conversation`, `status` (`ENSEMBLE_READY`, `FINISHED`, `FAILED`), `error` (with a `source` of `start`, `conduct` or `message`) |

## Mapping to the paper

| Paper | Here |
|---|---|
| Musician agent (FSM, leader / accompanist) | `MusicianAgent` with `Leading` and `Supporting` |
| Contract Net for the introduction | `Leading.IntroNegotiation`, `Supporting.IntroResponder` |
| Sharing the song structure (instructive mode) | `Protocol.SHARE_STRUCTURE` |
| Solo, passing the lead, ending | `Leading` (`take-lead`, `accompany`), `SupportPolicy` |
| Synchronizer agent (timing shared between Musebots) | `SynchronizerAgent` |
| Composer agent (jMusic) | `ComposerAgent` and `PhraseComposer` in `core` (no jMusic: only note data is needed) |
| Ensemble Assistant agent | `EnsembleAssistantAgent` |
| Musebot Conductor (Max patch, OSC) | The web page, the server's `conduct` messages, and `ConductorAgent` as the gateway into the agents |

## How it sounds

Three layers sit between the notes the agents write and what you hear: instrument sounds, audio effects, and a human feel added to the
timing and dynamics. A **Sound** menu and a **Feel** menu (human, or mechanical as written) let you compare, since the point is to judge by ear.

**Sound** has two settings. The default is **piano and bass recordings, synthesized sax and drums**; the other is the **synthesizer** for everything.
That is the result of listening: the Salamander piano recording and the FluidR3 bass recording were judged better than their synthesized versions,
but a recorded saxophone (it sounded like a flute) and a recorded kick and crash with the synthesized kit (the drums sounded like noise) were judged clearly
worse than the synthesizer, so they were taken out rather than left as an option.

### Recordings

Played by Tone.js samplers, which shift the pitch of the nearest recording to reach every note. Both are CC BY 3.0 and are credited on the page.

| Instrument | Recording | Where from |
|---|---|---|
| Piano | Salamander Grand Piano V2 (Yamaha C5), 30 notes | Alexander Holm, via the Tone.js audio collection |
| Acoustic bass | FluidR3 GM soundfont, plucked, the notes the bass plays | midi-js-soundfonts |
| Sax, drums | **synthesized** | |

What was checked, with the real files, before relying on them (`server/src/main/resources/public/sampleSets.js`): every file decodes, and the pitch of each set
measured against its note names is within about 7 cents. They load from jsDelivr, **pinned to a commit** so an upstream change cannot alter the sound, and
are cached by the browser for a year (about 4 MB the first time). They load one file at a time, so a missing file costs a note, not an instrument; a recording
with too few files falls back to the synthesizer, and the page says so.

Other sources were looked at and not used: WebAudioFont is GPL-3, and the acoustic drum kit in the Tone.js repository comes from a repository with no licence.
Finding a recorded sax and drum kit that sound better than the synthesizer would be a real improvement; the ones tried did not.

### Effects (`mixer.js`)

Each instrument has a channel: high-pass filter (bass), three-band EQ, compression, stereo position (piano left, sax right, bass and drums centre),
and a send to one shared room reverb. Everything goes through a master compressor, a limiter and a **soft clipper**, which leaves normal levels alone
and guarantees nothing exceeds -0.4 dBFS (the limiter alone let the synthesizer peak at +0.8 dBFS).

The channel levels were set by rendering each instrument with the real code and measuring its loudness: a recording and a synthesizer voice come out at very
different levels (the bass recording is far quieter than the piano recording). Over a run of music the sax is at -21.5 dBFS RMS, bass -23.0, piano -25.2 and drums
-27.5, the whole band -17.7. A recording of the page's actual output over a live performance had no clipped samples and no silence apart from the lead-in.

### Human feel (`Humanizer`, in `core`)

Composers write notes exactly on the grid at one volume. `Humanizer` makes them sound played, using arithmetic and a seeded random generator
(the same seed gives the same performance); it never adds, removes or retunes a note:

- **Swing follows the tempo.** The long note of an eighth-note pair is about 3:1 up to 100 bpm, falling to 2:1 at 200 and straight at 300, as real
  players do (after Friberg and Sundstrom, 2002; an approximation of their findings, not their data). Only beats of swung eighths are
  swung: triplets and sixteenths keep their even spacing.
- **Each instrument has its own feel,** in milliseconds from the beat: the bass about 6 ahead, the piano 6 behind, a soloing sax 9 behind, the drums
  on it, each drifting 4 to 9 ms around that. The drift is smooth, as a player's is (one note a little late, the next a little late
  too: each keeps 60% of the last one's lean), so a fast run stays even instead of jittering note by note. Notes that start together move together; a piano chord rolls upward by 5 ms a voice. The ending is played tightly.
- **Dynamics:** each note varies by a few velocity points, and a horn's phrases swell in the middle of four bars and soften at the ends.
- **Note lengths** vary a little. A single line stays a single line (no overlaps, never reordered), and nothing runs past the end of its phrase.

It is applied by the composer agents when `feel` is `human` (the default), and not at all when it is `mechanical`, which is what the tests that check
exact timings use.

### What this does not tell you

The checks above are objective (it decodes, it is in tune, it is balanced, it does not clip, it stays in time and in order). Whether it sounds good is a
judgement by ear, and it was the listening, not the measurements, that removed the recorded sax and drums. The feel offsets and effect settings are
starting points to adjust.

## Learning solos from real jazz

The band's solos are written by hand-made rules. This is the start of replacing them with something learned, and of measuring
whether it is better. It deliberately starts with a **baseline that learns by counting, with no neural network**: that is the thing a
neural network has to beat, and without it there is nothing to say a network helped.

### The data

[The Weimar Jazz Database](https://jazzomat.hfm-weimar.de/download/download.html) (Jazzomat Research Project, Hochschule für Musik
Franz Liszt Weimar): 456 transcribed solos, with the chords. It is released under the **Open Data Commons Open Database License
(ODbL)**: credit it, and share a database you publish that is derived from it under the same terms. The corpus and the training
work stay in `data/`, which git ignores. The trained models the band plays from are in `models/`, under the ODbL (`models/README.md`):
a site whose music is made from them must offer them, so the server serves them at `/models`, and the page credits the data and
links to the download whenever the learned solos are selected.

### Running it

```
# 1. Download wjazzd.db from the page above into data/, and turn it into a plain corpus (Python 3, no packages)
python3 learning/tools/wjd_to_corpus.py data/wjazzd.db data/corpus.json

# 2. Look at it, and train one model from every instrument's solos. The first one holds out a fifth of the tunes, to judge it by:
./gradlew :learning:run --args="inspect  --corpus data/corpus.json"
./gradlew :learning:run --args="train    --corpus data/corpus.json --out data/markov-eval.json --instruments all"
./gradlew :learning:run --args="evaluate --corpus data/corpus.json --model data/markov-eval.json --instrument sax --reference sax --temperatures 0.9"
./gradlew :learning:run --args="evaluate --corpus data/corpus.json --model data/markov-eval.json --instrument piano --reference keys"
./gradlew :learning:run --args="evaluate --corpus data/corpus.json --model data/markov-eval.json --instrument bass --reference low"

# 3. The model the band plays learns from everything (no tunes held out), and goes in models/, which the image includes
./gradlew :learning:run --args="train    --corpus data/corpus.json --out models/markov.json --instruments all --test-fraction 0"

# 4. Play with it: the page gets a "Solos" menu
MARKOV_MODEL=models/markov.json ./gradlew :server:run
```

`MARKOV_MODEL_SAX`, `_PIANO`, `_BASS` and `_DRUMS` give one instrument a model of its own, if you ever want that.

**One model for the whole band.** The database is mostly horns: it has no bass or drum solos and only six piano solos. So an
instrument's own solos are too few to learn from (the piano model had 17 solos to learn from, the bass 31), and what a horn plays
over the changes (its phrases, its leaps, its licks) is what a line over them is made of. Pooling every instrument's solos gives
one model that predicts real solos on tunes it never saw better than a model of the instrument's own, and the composers put the
line in each instrument's register (`SoloModelComposer`). Measured over five different random splits of the tunes, in bits a note
(lower is better; the rhythm and the pitch together):

| Judged on | its own instrument's solos only | every instrument pooled | |
|---|---:|---:|---|
| sax solos (245 in the five test sets) | 7.52 | 7.48 | a little better: it already had 200 solos |
| piano-type solos (piano, vibraphone, guitar: 18) | 7.58 | **7.18** | much better: it had 17 to learn from |
| bass-type solos (trombone, baritone sax, bass clarinet: 42) | 8.09 | **7.68** | much better: it had 31 |

Counting the instrument's own solos three times in the pool did not help. The drums take the model's rhythm and phrasing.

### How it works

- **Corpus.** Every note and chord is put on a grid of twelve steps to the beat, which holds the straight eighths, triplets and
  sixteenths the solos are written in. Swing is not in the data: the model learns on the straight grid and the notes are swung
  when they are played (`Swing`). Chords are the database's own symbols, read as the three kinds of chord the band knows
  (`ChordSymbols`): a half-diminished chord becomes a minor seventh, an altered dominant a plain dominant.
- **Rhythm** (`RhythmGrid`). Transcribers put the same swung off-beat at different places in the beat (the 6, 8 or 9 of its
  twelve ticks, by how hard the player swung at that moment), and some notes between the lines. Each beat is cleaned to the
  simplest of swung eighths, triplets or sixteenths that fits its notes, before the model learns from it and after it writes
  a line. When the line is played, only beats of eighths swing: triplets and sixteenths are played evenly. (Before this, the
  model mixed the transcribed off-beats freely and the band swung them a second time, which sounded out of time.)
- **Model** (`MarkovModel`). It learns *when* the next note comes and *which* note it is separately. The rhythm depends on where the
  note sits in the bar, the last two gaps and **the tempo** (slow, medium, fast, very fast: a run of sixteenths in a ballad is
  impossibly fast in an up-tempo tune; this predicts held-out slow solos 0.17 bits a note better); the pitch is a leap from the previous note, depending on the chord, where the previous note
  sat **relative to the chord's root** (so one solo over F7 teaches it every dominant chord), whether the beat is strong, the last two
  leaps, and **where the line is in the player's register** (well below the middle of the solo's range up to well above it, so it
  learns to turn back from the top and the bottom). Each is a table of counts at several levels of detail.
- **Using every level of detail** (`Blend.INTERPOLATE`). The chances of the next note blend all the levels, from the coarsest up: each
  level's counts, with the level below worth `minContext` (10) counts for every different outcome seen there. A situation seen often,
  and always resolved the same way, follows its own counts; one seen once or twice, or that went many ways, leans on what is known
  of similar situations instead of copying the few times it was seen. (Models used to take only the most detailed level seen three
  times.) This is what let the more detailed situations help: with the old way they made it worse, because it memorised them.
- **A memory of its own solo** (`Memory`). Players develop their own ideas: after a figure they have used, they often go on the way
  they went before. While it solos, the model remembers what it played after every two-note figure of leaps, and of gaps, and takes a
  share of the next note's chances from that memory (20% for the pitch, 10% for the rhythm). Like a language model's cache, it is
  learned in the sense that matters: the shares are the ones that best predict held-out solos.
- **Stock phrases.** Jazz is full of them (a bebop scale run, an arpeggio into a chord), and the last few exact leaps recognise
  one. The two most detailed pitch contexts are the last three and the last four leaps (with the chord's quality, and the beat for
  three): on tunes it never saw, each predicts real solos better (by 0.04 to 0.07 bits a note across the instruments), and a
  fifth leap adds nothing. A line is also written knowing the pitch it starts from, as the solos it learned from are. Its copy
  rate (how much it plays is a run of six leaps found in the training solos) is 0.27 for the sax, where real solos on new tunes
  have 0.32 (before pooling and these contexts: 0.12 against 0.26): it uses the idiom about as often as real players, and is not
  playing the training solos back.
- A temperature controls how closely it follows the data. The band plays at **0.9**, where its solos come closest to the real ones.
- **Phrases and landing.** A silence of a beat or more ends a phrase. Both tables also know how far into its phrase the line is, so
  it learns how long phrases run and that a phrase's last note is placed differently from its first. A third table, *landing*,
  records where the last note before a chord change (or the last of a phrase) sits relative to the **coming** chord; its weights are
  multiplied into the leap table's, so the line is drawn toward notes of the chord that follows, with no rule written by hand. And a new phrase
  sometimes **repeats the one before** it (`motifRepeat`, 0.35), the same rhythm and steps with each note moved to the nearest tone of
  the scale of the chord it now falls on: a motif and its answer. Models trained before these changes still load and play, without
  what they did not learn (retrain to get it).
- **In the band** (`SoloModelComposer`). Only the solos change: every instrument solos from its model, and everything else
  (heads, accompaniment, endings) is as before. The sax, piano and bass play the model's line in their own register (the piano with
  soft chords in the left hand). The **drums** play it as a melodic drummer would: the line's rhythm and phrasing as they are, its
  rise and fall spread over the kit from the floor tom up to the snare, a kick under the first note of each phrase, a crash to open
  and the hi-hat on 2 and 4. The agents are given composers by a `ComposerFactory` and do not know the difference.
- **Split by tune, not by solo** (`Split`). Several players recorded the same tune, so a random split would test the model on
  chord changes it had trained on.

### Evaluating it

`evaluate` has each system solo over the chord changes of the held-out tunes, and measures the result with the same numbers as the real
solos played over them (`SoloMetrics`): how often strong beats land on chord tones, how often notes leave the chord's scale, the size of
the leaps, how dense the playing is, how often a four-interval figure repeats. It also gives the Jensen-Shannon distance between each
system's distributions (leaps, where notes fall in the beat, pitch against the chord, gaps between notes) and the real ones, and a
**copy rate**: how much of what it plays is a run of six leaps that also occurs in the training solos.

Those numbers cannot say which of two Markov models has learned more, because a model that counts reproduces what it counted. So
`evaluate` also measures **how well the model predicts the held-out solos**: for each note a real player played, how likely the model
thought its rhythm and its leap were, in the situation the player was in, in bits per note (lower is better). `--baseline FILE` scores an
earlier model the same way (trained with the same `--instruments`, `--test-fraction` and `--seed`, it holds out the same solos). Every
change to how the model learns was kept or dropped by this measure, on tunes it never saw:

| bits per note on held-out solos (rhythm + pitch) | sax | piano (p, vib, g) | bass (tb, bs, bcl) |
|---|---:|---:|---:|
| the model as it was (most detailed level only, `minContext` 3) | 9.29 | 10.85 | 12.43 |
| + a first, more detailed version of the longer memory and register, the old way | 12.02 | 14.50 | 16.15 |
| blending every level instead (`minContext` 10, by variety) | 7.88 | 7.52 | 8.33 |
| + the longer memory and register | 7.85 | 7.47 | 8.31 |
| + the solo's memory of itself (**the model now**) | **7.72** | **7.19** | **8.21** |

A bit less is the model finding the real note twice as likely, so 9.29 to 7.72 is the real next note about three times as likely as
before (2^1.57). The longer memory and the register help only a little, and only once the levels are blended. A memory of three-note
figures did a little worse than two.

Results for the saxophone model (trained on 200 solos; 52 held-out solos on tunes it never saw; three solos per system per tune, at the model's
temperature 1.0 unless stated). The last four columns are about phrasing and the changes: a *phrase* is a run of notes with no silence of a
beat or more, *rests* is the share of the time with nothing sounding, and the last two say how often the last note of a bar that is
followed by a different chord is already in that chord's scale, or one of its chord tones:

| | notes / bar | chord tone on strong beat | repeated figures | rests | notes / phrase | step into a new chord | bar's last note in the next scale | ...a tone of the next chord | distance from real | copy rate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| real solos | 7.87 | 0.52 | 0.25 | 0.33 | 28.1 (sd 35) | 0.54 | 0.74 | 0.42 | 0 | 0.26 |
| the band's rules, before | 5.68 | 0.88 | 0.16 | 0.25 | 17.0 | 0.42 | 0.77 | 0.38 | 0.21 | 0.16 |
| the band's rules, now | 5.37 | 0.91 | 0.19 | 0.31 | 9.3 | 0.51 | 0.88 | 0.51 | 0.19 | 0.16 |
| Markov, before (T 1.0) | 6.74 | 0.52 | 0.06 | 0.37 | 18.6 | 0.62 | 0.69 | 0.38 | 0.00 | 0.11 |
| Markov, now (T 1.0) | 6.81 | 0.53 | 0.12 | 0.37 | 19.2 | 0.58 | 0.79 | 0.44 | 0.00 | 0.12 |
| Markov, now (T 0.7) | 9.46 | 0.56 | 0.24 | 0.22 | 118.9 | 0.69 | 0.79 | 0.43 | 0.03 | 0.25 |
| **Markov, blended and remembering (T 1.0)** | 7.55 | 0.50 | 0.17 | 0.36 | 23.1 | 0.58 | 0.76 | 0.40 | 0.01 | 0.12 |
| **Markov, blended and remembering (T 0.9), what the band plays** | 8.41 | 0.52 | 0.21 | 0.32 | 34.3 | 0.62 | 0.74 | 0.39 | 0.01 | 0.15 |

What this does and does not show:

- **The rules now phrase.** They play two-bar phrases (sometimes three or four), the second usually answering the first in the same
  rhythm and shape (an answer follows a statement three times in four), take a breath at the end of each (rests rose from 25% to 31%, close to the real 33%), move in a rising and falling
  contour, and lead into each new chord: the last note of a bar is mostly a tone of the next chord, or a step of its scale beside the
  guide tone it opens on, and now and then a chromatic half step. Stepping into a new chord went from 0.42 to 0.51 (real: 0.54). They
  are still **safe**: 91% of their strong-beat notes are chord tones against 52% in real solos, they never leave the scale, and they play fewer notes. Whether safe is *worse*
  is a question about how it sounds, which this does not answer. Their phrases are shorter than real ones (9 notes against a mean of 28, which has a
  very wide spread: real solos vary from a few notes to a whole chorus without a breath).
- **The Markov model's landing is only a small gain on these numbers, because it was already close.** Without being told about the next chord it
  already put the last note of a bar in the next chord's scale 69% of the time (real: 74%); now 79%, and 44% on chord tones (real: 42%). So it
  hits the real figures about as closely as before, from the other side. The table is not wasted: in a small test where the data holds a
  clear lesson, a model with it lands on the next chord's third far more often than the same model without it. But on the real data this
  measure cannot show a big improvement, because the baseline did not leave one.
- **Blending the levels and remembering its own solo brought the model closest to the real solos yet.** At 0.9 it plays as densely
  (8.4 notes a bar against 7.9), rests as much (32% against 33%), puts as many strong beats on chord tones (0.52) and as many bars'
  last notes in the next chord's scale (0.74) as real players, and its repeated figures rose from 0.12 to 0.21 (real: 0.25) through the
  memory, not a rule. Before, a lower temperature bought repetition only by losing the breaths (118 notes a phrase at 0.7); now 0.9 keeps
  phrases of 34 notes (real: 28, with a very wide spread). Its copy rate (0.15) is still below the real solos' own (0.26): it is not
  playing back the training solos.
- **Earlier: the motif repeat doubled repeated figures (0.06 to 0.12) but left them at half the real 0.25.** Raising `motifRepeat` from 0.3 to 0.5 moved it only from
  0.12 to 0.14, so the limit is how few phrases a solo has to repeat, not the setting. At temperature 0.7 the model repeats a lot (0.24) but hardly ever
  breathes (118 notes to a phrase): sticking to the most likely rhythm leaves out the rests.
- **The Markov model still matches the real statistics by construction.** A model that counts reproduces what it counted, so its
  distance is about 0 and this table cannot rank one Markov variant against another on those. Matching the statistics is not the same as sounding good.
- **Not measured: how it sounds.** Nothing here is a listening test, and these are statistics, not musicality. The new rule-based phrasing, and
  the model's phrases and repeats, are meant to be heard; whether they are better is for ears to say.
- **Limits.** Chords are simplified to three kinds, so some of the real solos' "out of scale" notes are harmony the band cannot express;
  generation uses one chord per bar; 52 held-out solos is a small test set.

**The other instruments** (`evaluate --instrument piano` or `bass`: each system solos in that instrument's register, against that
instrument's rules; only the line is measured, not the piano's left-hand chords). The held-out sets are very small (4 piano-like solos,
8 low-horn solos), so read these as a sign, not a result:

| | notes / bar | chord tone on strong beat | leaps (5+) | notes / phrase | rests | bar's last note in the next scale |
|---|---:|---:|---:|---:|---:|---:|
| **piano:** real (p, vib, g) | 7.84 | 0.56 | 0.11 | 35.3 | 0.33 | 0.71 |
| rules | 4.98 | 0.89 | 0.06 | 8.6 | 0.37 | 0.89 |
| Markov as it was (T 1.0) | 5.46 | 0.55 | 0.12 | 14.0 | 0.40 | 0.78 |
| Markov now (T 0.9) | 6.51 | 0.47 | 0.10 | 24.5 | 0.34 | 0.74 |
| **bass:** real (tb, bs, bcl) | 5.81 | 0.53 | 0.13 | 18.9 | 0.40 | 0.83 |
| rules | 5.33 | 0.91 | 0.03 | 8.9 | 0.32 | 0.89 |
| Markov as it was (T 1.0) | 6.11 | 0.58 | 0.11 | 18.6 | 0.39 | 0.82 |
| Markov now (T 0.9) | 7.63 | 0.49 | 0.09 | 26.9 | 0.36 | 0.77 |

As with the sax, the model is close to the real lines and the rules are safer. The piano now plays longer phrases, nearer the real
ones. The bass at 0.9 overshoots: busier and with longer phrases than the low horns it learned from (at 1.0 it was closer on these),
so one temperature for the whole band is a compromise. The bass model has learned from horns, so it is a
horn's solo in the bass register, not a bassist's. The drums cannot be measured this way (the measures are pitches against chords).

### More data, and what there is not

Of everything open, the Weimar Jazz Database is the one collection of hand-checked solos: 456, almost all horns. Others exist and
were looked at, and not used. **PiJAMA** (over 200 hours of solo piano) is automatic transcription of audio, which is noisy, and
is licensed for non-commercial use only (CC BY-NC), which cannot be combined with models released under the ODbL.
**FiloBass** (48 verified bass lines, MIT) is walking bass for backing tracks, not solos. Published transcriptions such as the
Charlie Parker Omnibook are copyright. So the gains came from using the data better: pooling every instrument's solos (above), and
training the model the band plays on every tune (430 solos) instead of the four fifths kept for judging it.

The database also keeps what the corpus leaves out, for each note: its **loudness** in dB (200,000 notes) and its real onset time.
Whether the loudness can be learned was measured on held-out tunes: the notes' context (how high the note is, the leap into and out
of it, whether it is a peak, its length, where it falls in the beat and the phrase) explains only about **12%** of how much louder or
softer a note is than its solo's average (a spread of 4.5 dB), mostly that higher notes are louder (7%) and that leaps and peaks are
accented (5 to 7%); the beat explains almost nothing (0.2%), so the hand-set accent on the off-beat has no support in the data. The
drift of a player's loudness over a few notes explains a little more (neighbouring notes correlate at 0.28, falling to 0.11 eight
notes on), and the rest is note-to-note variation, much of it noise in measuring old recordings. A learned dynamic would therefore
be a small effect on top of the random variation the humanizer adds, and it is not built.

### What a better model would change

Everything above the model stays: `PhraseComposer` is the one place a composer plugs in, `ComposerFactory` chooses it per instrument,
`evaluate` compares it with these two on the same footing, and the copy rate shows whether it merely memorised. A neural model would
be trained on the same corpus (the same split, in Python) and exported, and has to predict the held-out solos in fewer bits than the
Markov model's 7.26 a note (sax, the pooled model on the standard split; 7.72 for the earlier sax-only model on another split), and beat it on repeated figures and long phrases, without a copy rate far above the real solos'.

## Not yet built

- More Conductor commands from the original platform, and speaking its OSC messages so Musebots from other developers can take part
- A tempo change heard sooner than the next part of the tune (parts are a chorus long)
- Choosing the turns (who opens, who solos when) from the UI, or how many choruses each; trading fours; a final head; dynamics that follow the soloist
- A neural solo model, a listening test, and solos that react to what the others are playing (the learning tools measure against a baseline; none of that is built)
- A recorded sax and drum kit that sound better than the synthesizer (the ones tried did not); velocity layers for the bass
- Letting third-party Musebots join over the network
