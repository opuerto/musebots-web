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

`PORT` (default 7070) sets the web port and `JADE_PORT` (default 1099) the internal JADE platform port.

## The page

- **The band on stage:** a card per musician with its picture (`public/assets/*.svg`, plain SVG files you can replace
  with your own artwork), what it is doing (leading, accompanying, playing softly, sitting out), the names of the notes
  sounding right now, and its volume and mute.
- **The chord chart,** laid out like a lead sheet: a row per section (A, A, B) with a chord in each bar. The section being
  played is highlighted, and so is the bar, which fills as it goes by. Above it is where the band is
  ("Piano solo · First chorus (AAB)"), and below it a plain-English caption of what is happening, such as who handed the
  lead to whom.
- **Two tunes:** *Blues in F* (12 bars, written as three lines) and *Song 1* (10 bars in AAB form). The chart of the chosen tune
  is shown before you press Start.
- **How it knows where the band is:** the server announces each part of the tune (`segment`: when it starts and ends, who
  leads it, and the tempo it was arranged at) and the tune itself (`song`). The page works out the section and bar from
  those and the clock it synced with the server, so the display stays in time with the sound. See `timeline.js`.

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
   (ties go to piano, then bass, then drums) and rejects the rest. Then:

   ```
   intro (4 bars) -> head (1 chorus) -> a solo chorus for each musician, in turn -> ending (1 bar)
   ```

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
bar, opens bars on guide tones (3rd or 7th), and steps through chord tones on the beat and scale tones off it.

### Modules

| Module | Responsibility | Depends on |
|---|---|---|
| `core` | Music domain (chords, songs, clock, timeline `Segment`), events, `PhraseComposer` strategy and composers, `SupportPolicy`. No frameworks. | nothing |
| `agents` | The JADE agents (`MusicianAgent`, `ComposerAgent`, `SynchronizerAgent`, `EnsembleAssistantAgent`, and the session's `ConductorAgent`), their protocols, and `EnsembleRuntime` that hosts sessions. | `core`, JADE |
| `server` | Javalin HTTP + WebSocket, browser client in `src/main/resources/public`. | `agents` |

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
| client to server | `{"type":"start","song":"blues","tempo":120,"soloChoruses":1,"ensemble":"quartet"}`, `{"type":"stop"}`, `{"type":"time.sync","t0":...}` |
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

## Not yet built

- More Conductor commands from the original platform, and speaking its OSC messages so Musebots from other developers can take part
- A tempo change heard sooner than the next part of the tune (parts are a chorus long)
- Choosing who solos, or how many choruses each, from the UI; trading fours; a final head; dynamics that follow the soloist
- Better instrument sounds (the sax, piano and drums are Tone.js synthesis for now; SoundFonts or samples would sound more real)
- Letting third-party Musebots join over the network
