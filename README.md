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
./gradlew test
```

`PORT` (default 7070) sets the web port and `JADE_PORT` (default 1099) the internal JADE platform port.

## How it works

```
 browser (Tone.js) <-- WebSocket: note / conversation / status --  server  --  JADE agents (3 per instrument)
        |                                                                       sax, piano, bass, drums
   schedules each note on the Web Audio clock                                   each = musician + composer + synchronizer
```

1. The browser connects, syncs its clock with the server (best of 8 ping rounds), and sends `start`.
2. The server starts one **Musebot** per instrument for that session. A Musebot is three JADE agents:

   | Agent | Job |
   |---|---|
   | **Musician** | Decides. Takes part in the ensemble's conversations, and runs the performance while it has the lead. |
   | **Composer** | Writes the notes when its musician asks (FIPA Request) and publishes them. |
   | **Synchronizer** | Keeps the timeline of the performance. When a musician asks "when does the solo start?", it answers from memory, or asks the other Musebots' synchronizers (and keeps asking until someone knows). |

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

5. **Etiquette** (`SupportPolicy`): the sax lays out while anyone else solos, and everybody lays out under a
   drum solo. Everyone plays the ending.

6. Agents publish `NoteEvent`s with absolute timestamps ahead of time. The browser converts them to audio-clock
   time, so network jitter does not affect rhythm. `SECTION` status events tell the UI what is playing and when.

The **ensemble** option in the UI chooses the full quartet (sax leading, piano, bass, drums) or the
original piano-and-bass duo.

### The instruments

Every composer writes five kinds of phrase (`PhraseKind`): intro, head, support (accompaniment), solo and ending.

| Instrument | Head | Support | Solo | Ending |
|---|---|---|---|---|
| Sax | swung melody over the changes | lays out | busy swung line using the chord scale | held root |
| Piano | comping | comping: rootless 3rd-7th-9th voicings | right-hand line plus sparse left-hand chords | held chord |
| Bass | walking | walking: root, chord tone, fifth, chromatic approach | line in the bass register | held root |
| Drums | ride, hi-hat on 2 and 4, feathered kick, ghost snare | same | ride with tom runs, snare rolls and crashes | crash and kick |

Notes are plain `NoteEvent`s: a MIDI pitch (General MIDI percussion numbers for drums), an absolute start time
in milliseconds, a duration and a velocity. Swing is just timing: `Swing.OFFBEAT` places the second eighth of a
beat two thirds of the way in. The horn-like parts share one engine (`Improviser`): it picks a rhythmic cell per
bar, opens bars on guide tones (3rd or 7th), and steps through chord tones on the beat and scale tones off it.

### Modules

| Module | Responsibility | Depends on |
|---|---|---|
| `core` | Music domain (chords, songs, clock, timeline `Segment`), events, `PhraseComposer` strategy and composers, `SupportPolicy`. No frameworks. | nothing |
| `agents` | The three JADE agents (`MusicianAgent`, `ComposerAgent`, `SynchronizerAgent`), their protocols, and `EnsembleRuntime` that hosts sessions. | `core`, JADE |
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

## Mapping to the paper

| Paper | Here |
|---|---|
| Musician agent (FSM, leader / accompanist) | `MusicianAgent` with `Leading` and `Supporting` |
| Contract Net for the introduction | `Leading.IntroNegotiation`, `Supporting.IntroResponder` |
| Sharing the song structure (instructive mode) | `Protocol.SHARE_STRUCTURE` |
| Solo, passing the lead, ending | `Leading` (`take-lead`, `accompany`), `SupportPolicy` |
| Synchronizer agent (timing shared between Musebots) | `SynchronizerAgent` |
| Composer agent (jMusic) | `ComposerAgent` and `PhraseComposer` in `core` (no jMusic: only note data is needed) |
| Ensemble Assistant agent, Musebot Conductor (Max patch, OSC) | The server and WebSocket protocol (no separate agent yet) |

## Not yet built

- An Ensemble Assistant agent per Musebot (today the server plays the Conductor's part: tempo and start/stop)
- Changing the tempo or the ensemble while playing (parts are arranged about two bars ahead, so this is now possible)
- Choosing who solos, or how many choruses each, from the UI; trading fours; a final head
- Better instrument sounds (the sax, piano and drums are Tone.js synthesis for now; SoundFonts or samples would sound more real)
- Letting third-party Musebots join over the network
