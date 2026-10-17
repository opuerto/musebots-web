# Musebots Web

A web version of the jazz-ensemble Musebots from *A Model of Inter-musician Communication for
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
 browser (Tone.js) <-- WebSocket: note / conversation / status --  server  --  JADE agents
        |                                                                       piano (leader)
   schedules each note on the Web Audio clock                                   bass  (accompanist)
```

1. The browser connects, syncs its clock with the server (best of 8 ping rounds), and sends `start`.
2. The server starts one JADE agent per musician for that session.
3. The leader waits for the ensemble (directory lookup), shares the song, then runs a **FIPA Contract
   Net** to find who plays the intro. The accepted accompanist writes the intro and reports when it
   starts and how long it lasts; the leader uses that to start the head at exactly the right moment.
4. Agents publish `NoteEvent`s with absolute timestamps ahead of time. The browser converts them to
   audio-clock time, so network jitter does not affect rhythm.

### Modules

| Module | Responsibility | Depends on |
|---|---|---|
| `core` | Music domain (chords, songs, clock), events, `PhraseComposer` strategy and composers. No frameworks. | nothing |
| `agents` | JADE agents: roles (leader, accompanist), protocol messages, `EnsembleRuntime` that hosts sessions. | `core`, JADE |
| `server` | Javalin HTTP + WebSocket, browser client in `src/main/resources/public`. | `agents` |

### Design choices

- **Roles instead of one giant state machine.** The original `Musician` agent was a 20-state FSM with
  static shared fields. Now `LeaderRole` and `AccompanistRole` each own their behaviour, and every
  dependency is injected through `MusicianConfig`.
- **Composition is a strategy.** Agents call a `PhraseComposer`; swapping in an algorithmic-composition
  technique means implementing one interface, with no agent changes.
- **Agents are observable.** Everything an agent says or plays goes to an `EventSink`, which is how the
  UI shows the negotiation and how tests assert on it.
- **Sessions are isolated by name** on one shared JADE platform (agent names and directory entries are
  prefixed with the session id).
- **Message payloads are JSON** in the ACL content (`Protocol`), with FIPA performatives carrying the
  meaning. The original used SL codec ontologies.

## Mapping to the paper

| Paper | Here |
|---|---|
| Musician agent (FSM, leader / accompanist) | `MusicianAgent` + `LeaderRole` / `AccompanistRole` |
| Contract Net for the introduction | `LeaderRole.IntroNegotiation`, `AccompanistRole.IntroResponder` |
| Sharing the song structure (instructive mode) | `Protocol.SHARE_STRUCTURE` |
| Composer agent (jMusic) | `PhraseComposer` in `core` (no jMusic: only note data is needed) |
| Musebot Conductor (Max patch, OSC) | The web UI and WebSocket protocol |

## Not yet built

- Synchronizer agent, and Composer / Ensemble Assistant as separate agents inside each Musebot
- Drums and sax, solos, passing the lead, ending the song
- Playing notes as they are written rather than the whole chorus up front (needed for tempo changes mid-song)
- Better instrument sounds (SoundFonts), and a Conductor-style live control panel
- Letting third-party Musebots join over the network
