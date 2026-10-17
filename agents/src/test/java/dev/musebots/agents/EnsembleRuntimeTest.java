package dev.musebots.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.musebots.core.ConversationEvent;
import dev.musebots.core.EventSink;
import dev.musebots.core.Instrument;
import dev.musebots.core.NoteEvent;
import dev.musebots.core.PerformanceEvent;
import dev.musebots.core.PhraseKind;
import dev.musebots.core.Segment;
import dev.musebots.core.SegmentEvent;
import dev.musebots.core.SongStructure;
import dev.musebots.core.Songs;
import dev.musebots.core.StatusEvent;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnsembleRuntimeTest {

    private static final int FAST_TEMPO = 960; // one beat = 62.5 ms: a bar is 250 ms and a chorus 3 s
    private static final long BAR_MS = 250;
    private static final long CHORUS_MS = 12 * BAR_MS;

    private EnsembleRuntime runtime;

    @BeforeAll
    void startPlatform() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        runtime = EnsembleRuntime.start(port);
    }

    @AfterAll
    void stopPlatform() {
        runtime.close();
    }

    /** Collects everything a session publishes and signals when it finishes or fails. */
    private static final class Recorder implements EventSink {
        final List<PerformanceEvent> events = new CopyOnWriteArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);

        @Override
        public void emit(PerformanceEvent event) {
            events.add(event);
            if (event instanceof StatusEvent status
                    && (status.kind() == StatusEvent.Kind.FINISHED || status.kind() == StatusEvent.Kind.FAILED)) {
                done.countDown();
            }
        }

        List<ConversationEvent> conversation() {
            return events.stream().filter(ConversationEvent.class::isInstance).map(ConversationEvent.class::cast).toList();
        }

        List<ConversationEvent> conversation(String conversationId, String performative) {
            return conversation().stream()
                    .filter(c -> conversationId.equals(c.conversationId()) && performative.equals(c.performative())).toList();
        }

        List<NoteEvent> notes(Instrument instrument) {
            return events.stream().filter(NoteEvent.class::isInstance).map(NoteEvent.class::cast)
                    .filter(n -> n.instrument() == instrument).toList();
        }

        List<SegmentEvent> segments() {
            return events.stream().filter(SegmentEvent.class::isInstance).map(SegmentEvent.class::cast).toList();
        }

        /** The segment announced with this label. */
        Segment segment(String label) {
            return segments().stream().filter(s -> s.label().equals(label)).findFirst()
                    .orElseThrow(() -> new AssertionError("no segment '" + label + "' in " + sectionLabels())).segment();
        }

        /** The start time of the part with this label. */
        long sectionStart(String label) {
            return segment(label).startMs();
        }

        long introStart() {
            return sectionStart("Intro");
        }

        /** The parts after the intro, in the order they were announced. */
        List<String> sectionLabels() {
            return segments().stream().map(SegmentEvent::label).filter(label -> !label.equals("Intro")).toList();
        }

        StatusEvent lastStatus() {
            return events.stream().filter(StatusEvent.class::isInstance).map(StatusEvent.class::cast)
                    .reduce((x, y) -> y).orElseThrow();
        }

        /** Notes of this instrument that start inside [from, to). */
        List<NoteEvent> notes(Instrument instrument, long from, long to) {
            return notes(instrument).stream().filter(n -> n.startMs() >= from && n.startMs() < to).toList();
        }

        void assertFinished() {
            assertEquals(StatusEvent.Kind.FINISHED, lastStatus().kind(), lastStatus().message());
        }
    }

    /** Fails if there is any stretch of two bars in [from, to) in which the instrument starts no note. */
    private static void assertPlaysThroughout(Recorder recorder, Instrument instrument, long from, long to, String what) {
        for (long window = from; window < to; window += 2 * BAR_MS) {
            assertFalse(recorder.notes(instrument, window, Math.min(window + 2 * BAR_MS, to)).isEmpty(),
                    what + " goes silent from " + (window - from) / BAR_MS + " bars in");
        }
    }

    private static int maxVelocity(Recorder recorder, Instrument instrument, long from, long to) {
        return recorder.notes(instrument, from, to).stream().mapToInt(NoteEvent::velocity).max().orElse(0);
    }

    private Recorder perform(SessionConfig session) throws InterruptedException {
        Recorder recorder = new Recorder();
        Performance performance = runtime.perform(session, recorder);
        try {
            assertTrue(recorder.done.await(60, TimeUnit.SECONDS), "performance did not finish");
        } finally {
            performance.stop();
        }
        return recorder;
    }

    private static SessionConfig session(String id, Instrument leader, List<Instrument> accompanists) {
        List<Instrument> everyone = new ArrayList<>(List.of(leader));
        everyone.addAll(accompanists);
        return new SessionConfig(id, Songs.bluesInF(FAST_TEMPO), 1, leader, accompanists, everyone,
                300, 500, 400, 42, Clock.systemUTC());
    }

    @Test
    void aDuoPlaysTheWholeTuneAndPassesTheLead() throws InterruptedException {
        Recorder recorder = perform(session("duo", Instrument.PIANO, List.of(Instrument.BASS)));
        recorder.assertFinished();

        // Intro, head, one solo each, ending: each part starts exactly when the previous one ends.
        assertEquals(List.of("The head", "piano solo", "bass solo", "The ending"), recorder.sectionLabels());
        long introStart = recorder.introStart();
        long head = recorder.sectionStart("The head");
        long pianoSolo = recorder.sectionStart("piano solo");
        long bassSolo = recorder.sectionStart("bass solo");
        long ending = recorder.sectionStart("The ending");
        assertEquals(introStart + 4 * BAR_MS, head);
        assertEquals(head + CHORUS_MS, pianoSolo);
        assertEquals(pianoSolo + CHORUS_MS, bassSolo);
        assertEquals(bassSolo + CHORUS_MS, ending);

        // The bass wins the intro (the only accompanist) and plays it alone; the leader waits for it to end.
        assertEquals(16, recorder.notes(Instrument.BASS, introStart, head).size());
        assertTrue(recorder.notes(Instrument.PIANO, 0, head).isEmpty());

        // The lead passes from the piano to the bass exactly once, and each leader asks the other to back it up.
        assertEquals(1, recorder.conversation(Protocol.TAKE_LEAD, "REQUEST").size());
        assertEquals(4, recorder.conversation(Protocol.ACCOMPANY, "REQUEST").size());

        // Everyone has something to play in every part.
        for (Instrument instrument : List.of(Instrument.PIANO, Instrument.BASS)) {
            assertFalse(recorder.notes(instrument, head, pianoSolo).isEmpty(), instrument + " in the head");
            assertFalse(recorder.notes(instrument, pianoSolo, bassSolo).isEmpty(), instrument + " in the piano solo");
            assertFalse(recorder.notes(instrument, bassSolo, ending).isEmpty(), instrument + " in the bass solo");
            assertFalse(recorder.notes(instrument, ending, ending + BAR_MS).isEmpty(), instrument + " in the ending");
        }
        assertTrue(recorder.notes(Instrument.PIANO, ending + BAR_MS, Long.MAX_VALUE).isEmpty());
    }

    @Test
    void aTenBarTuneInAABFormPlaysThroughToo() throws InterruptedException {
        SongStructure tune = Songs.song1(FAST_TEMPO);
        long chorus = 10 * BAR_MS;
        SessionConfig session = new SessionConfig("aab", tune, 1, Instrument.PIANO, List.of(Instrument.BASS),
                List.of(Instrument.PIANO, Instrument.BASS), 300, 500, 400, 42, Clock.systemUTC());
        Recorder recorder = perform(session);
        recorder.assertFinished();

        // Every part is a whole number of choruses of the ten-bar form, back to back.
        assertEquals(List.of("The head", "piano solo", "bass solo", "The ending"), recorder.sectionLabels());
        long head = recorder.sectionStart("The head");
        assertEquals(head + chorus, recorder.sectionStart("piano solo"));
        assertEquals(head + 2 * chorus, recorder.sectionStart("bass solo"));
        assertEquals(head + 3 * chorus, recorder.sectionStart("The ending"));

        // The ending is the home chord (C), not the first chord of the tune (E minor).
        long ending = recorder.sectionStart("The ending");
        assertEquals(0, recorder.notes(Instrument.BASS, ending, ending + BAR_MS).get(0).midi() % 12);
        // The bass backs the head with four notes a bar for ten bars.
        assertEquals(40, recorder.notes(Instrument.BASS, head, head + chorus).size());
    }

    @Test
    void everyPartIsAnnouncedWithWhoLeadsItWhenItRunsAndAtWhatTempo() throws InterruptedException {
        Recorder recorder = perform(session("segments", Instrument.PIANO, List.of(Instrument.BASS)));
        recorder.assertFinished();

        Segment intro = recorder.segment("Intro");
        assertEquals(PhraseKind.INTRO, intro.kind());
        assertEquals(Instrument.BASS, intro.leader(), "the intro is attributed to the musician who won it");
        assertEquals(4 * BAR_MS, intro.durationMs());
        assertEquals(FAST_TEMPO, intro.tempoBpm());

        assertEquals(Instrument.PIANO, recorder.segment("The head").leader());
        assertEquals(Instrument.BASS, recorder.segment("bass solo").leader());
        assertEquals(PhraseKind.SOLO, recorder.segment("bass solo").kind());
        // They follow one another without gaps.
        assertEquals(intro.endMs(), recorder.sectionStart("The head"));
        assertEquals(recorder.segment("The head").endMs(), recorder.sectionStart("piano solo"));
    }

    @Test
    void theQuartetTradesSolosAndLaysOutWhenEtiquetteSaysSo() throws InterruptedException {
        Recorder recorder = perform(session("quartet", Instrument.SAX,
                List.of(Instrument.PIANO, Instrument.BASS, Instrument.DRUMS)));
        recorder.assertFinished();

        assertEquals(List.of("The head", "sax solo", "piano solo", "bass solo", "drums solo", "The ending"),
                recorder.sectionLabels());
        long introStart = recorder.introStart();
        long head = recorder.sectionStart("The head");
        long saxSolo = recorder.sectionStart("sax solo");
        long pianoSolo = recorder.sectionStart("piano solo");
        long bassSolo = recorder.sectionStart("bass solo");
        long drumsSolo = recorder.sectionStart("drums solo");
        long ending = recorder.sectionStart("The ending");
        assertEquals(introStart + 4 * BAR_MS, head);
        assertEquals(head + CHORUS_MS, saxSolo);
        assertEquals(drumsSolo + CHORUS_MS, ending);

        // Three accompanists bid, the leader prefers the piano, and the piano alone plays the intro.
        assertEquals(3, recorder.conversation(Protocol.INTRO, "PROPOSE").size());
        assertEquals(2, recorder.conversation(Protocol.INTRO, "REJECT-PROPOSAL").size());
        assertFalse(recorder.notes(Instrument.PIANO, introStart, head).isEmpty());
        for (Instrument silent : List.of(Instrument.SAX, Instrument.BASS, Instrument.DRUMS)) {
            assertTrue(recorder.notes(silent, 0, head).isEmpty(), silent + " played during the intro");
        }

        // The lead is handed on three times: sax to piano to bass to drums.
        List<ConversationEvent> handovers = recorder.conversation(Protocol.TAKE_LEAD, "REQUEST");
        assertEquals(3, handovers.size());
        assertTrue(handovers.get(0).from().endsWith("-sax-musician") && handovers.get(0).to().get(0).endsWith("-piano-musician"));
        assertTrue(handovers.get(1).from().endsWith("-piano-musician") && handovers.get(1).to().get(0).endsWith("-bass-musician"));
        assertTrue(handovers.get(2).from().endsWith("-bass-musician") && handovers.get(2).to().get(0).endsWith("-drums-musician"));

        // Nobody drops out after their own solo: every instrument keeps playing, with no gap of more than two
        // bars, through every part of the tune. (This is the regression test for "they stop after soloing".)
        long[] starts = {head, saxSolo, pianoSolo, bassSolo, drumsSolo, ending};
        String[] names = {"head", "sax solo", "piano solo", "bass solo", "drums solo", "ending"};
        for (Instrument instrument : Instrument.values()) {
            for (int part = 0; part < starts.length - 1; part++) {
                assertPlaysThroughout(recorder, instrument, starts[part], starts[part + 1], instrument + " in the " + names[part]);
            }
            assertFalse(recorder.notes(instrument, ending, ending + BAR_MS).isEmpty(), instrument + " missed the ending");
        }

        // Behind someone else's solo the sax plays soft pads, not its melody.
        assertTrue(maxVelocity(recorder, Instrument.SAX, pianoSolo, ending) < 70, "the sax should sit behind the solos");
        assertTrue(maxVelocity(recorder, Instrument.SAX, head, saxSolo) >= 84, "the sax leads the head");

        // Behind the drum solo the rest of the band plays, but lightly.
        assertTrue(maxVelocity(recorder, Instrument.PIANO, drumsSolo, ending) < 60, "piano too loud under the drums");
        assertTrue(maxVelocity(recorder, Instrument.BASS, drumsSolo, ending) < 80, "bass too loud under the drums");
        assertTrue(maxVelocity(recorder, Instrument.SAX, drumsSolo, ending) < 60, "sax too loud under the drums");
        assertTrue(maxVelocity(recorder, Instrument.PIANO, bassSolo, drumsSolo) >= 62, "the piano should comp fully behind the bass");

        // Everybody closes the tune together.
        for (Instrument instrument : Instrument.values()) {
            List<NoteEvent> last = recorder.notes(instrument, ending, ending + BAR_MS);
            assertFalse(last.isEmpty(), instrument + " missed the ending");
            assertTrue(last.stream().allMatch(n -> n.startMs() == ending));
        }
    }

    @Test
    void musiciansAskTheirSynchronizersWhenThingsHappen() throws InterruptedException {
        Recorder recorder = perform(session("timeline", Instrument.PIANO, List.of(Instrument.BASS)));
        recorder.assertFinished();

        // The bass does not know when the head starts until its synchronizer asks the pianist's.
        List<ConversationEvent> bass = recorder.conversation().stream()
                .filter(c -> c.from().endsWith("-bass-synchronizer")).toList();
        assertTrue(bass.stream().anyMatch(c -> c.performative().equals("QUERY-REF")
                && c.to().get(0).endsWith("-piano-synchronizer")), "the bass synchronizer never asked the piano's");
        List<ConversationEvent> piano = recorder.conversation().stream()
                .filter(c -> c.from().endsWith("-piano-synchronizer") && c.performative().equals("INFORM")).toList();
        assertFalse(piano.isEmpty(), "the piano synchronizer never answered");

        // And each Musebot's composer reports what it wrote to its own musician.
        assertTrue(recorder.conversation().stream().anyMatch(c -> c.from().endsWith("-bass-composer")
                && c.to().get(0).endsWith("-bass-musician") && c.summary().startsWith("wrote ")));
    }

    // ---- The Conductor, through the Ensemble Assistants ----

    /** A duo at 480 bpm (a chorus takes 6 s) whose intro starts two seconds in, so commands can beat the first notes. */
    private static SessionConfig conducted(String id) {
        return new SessionConfig(id, Songs.bluesInF(480), 1, Instrument.PIANO, List.of(Instrument.BASS),
                List.of(Instrument.PIANO, Instrument.BASS), 2_000, 500, 400, 42, Clock.systemUTC());
    }

    private static Protocol.Conduct conduct(Protocol.Command command, Instrument target, double value) {
        return new Protocol.Conduct(command, target, value);
    }

    @Test
    void aTempoChangeAppliesToThePartsArrangedAfterIt() throws InterruptedException {
        Recorder recorder = new Recorder();
        Performance performance = runtime.perform(conducted("tempo"), recorder);
        try {
            Thread.sleep(1_000);
            performance.conduct(conduct(Protocol.Command.TEMPO, null, 960));
            assertTrue(recorder.done.await(60, TimeUnit.SECONDS), "performance did not finish");
        } finally {
            performance.stop();
        }
        recorder.assertFinished();

        // The head was arranged before the command, at 480 bpm (6 s); the solos and the ending after it, at 960 (3 s).
        long head = recorder.sectionStart("The head");
        long pianoSolo = recorder.sectionStart("piano solo");
        long bassSolo = recorder.sectionStart("bass solo");
        long ending = recorder.sectionStart("The ending");
        assertEquals(6_000, pianoSolo - head);
        assertEquals(3_000, bassSolo - pianoSolo);
        assertEquals(3_000, ending - bassSolo);

        // The bass backing the piano solo was written at the tempo that solo was arranged at: four beats a bar, 62.5 ms each.
        List<NoteEvent> backing = recorder.notes(Instrument.BASS, pianoSolo, bassSolo);
        assertEquals(48, backing.size());
        assertEquals(63, backing.get(1).startMs() - backing.get(0).startMs(), 1);

        // The Conductor told every assistant, and each told its own musician (not the other's).
        assertEquals(1, recorder.conversation(Protocol.CONDUCTOR, "INFORM").size());
        List<ConversationEvent> relayed = recorder.conversation(Protocol.TEMPO, "INFORM");
        assertEquals(2, relayed.size());
        assertTrue(relayed.stream().allMatch(c -> c.from().endsWith("-assistant") && c.to().get(0).endsWith("-musician")));
    }

    @Test
    void aMusicianCanBeMutedAndBroughtBackIn() throws InterruptedException {
        Recorder recorder = new Recorder();
        Performance performance = runtime.perform(conducted("mute"), recorder);
        long unmutedAt;
        try {
            Thread.sleep(300);
            performance.conduct(conduct(Protocol.Command.MUTE, Instrument.BASS, 0));
            Thread.sleep(8_500); // through the intro and most of the head
            unmutedAt = System.currentTimeMillis();
            performance.conduct(conduct(Protocol.Command.UNMUTE, Instrument.BASS, 0));
            assertTrue(recorder.done.await(60, TimeUnit.SECONDS), "performance did not finish");
        } finally {
            performance.stop();
        }
        recorder.assertFinished();

        // Muting only affects the bass: the piano plays all the way through, and the tune is not disturbed.
        assertEquals(List.of("The head", "piano solo", "bass solo", "The ending"), recorder.sectionLabels());
        assertFalse(recorder.notes(Instrument.PIANO).isEmpty());

        // While it sat out the bass played nothing, including the intro it had won. Afterwards it is back.
        List<NoteEvent> bass = recorder.notes(Instrument.BASS);
        assertFalse(bass.isEmpty(), "the bass never came back");
        assertTrue(bass.stream().allMatch(n -> n.startMs() >= unmutedAt), "the bass played while muted");
        assertEquals(2, recorder.conversation(Protocol.MIX, "INFORM").size(), "one message to the bass's composer for each command");
    }

    @Test
    void volumeChangesOnlyTheMusicianItIsFor() throws InterruptedException {
        Recorder recorder = new Recorder();
        Performance performance = runtime.perform(conducted("volume"), recorder);
        try {
            Thread.sleep(300);
            performance.conduct(conduct(Protocol.Command.VOLUME, Instrument.BASS, 0.5));
            assertTrue(recorder.done.await(60, TimeUnit.SECONDS), "performance did not finish");
        } finally {
            performance.stop();
        }
        recorder.assertFinished();

        // The bass's loudest note is about 100 at full volume; at half it cannot pass 55. The piano is untouched.
        assertTrue(maxVelocity(recorder, Instrument.BASS, 0, Long.MAX_VALUE) <= 55);
        assertTrue(maxVelocity(recorder, Instrument.BASS, 0, Long.MAX_VALUE) > 0);
        assertTrue(maxVelocity(recorder, Instrument.PIANO, 0, Long.MAX_VALUE) >= 80);
        assertEquals(1, recorder.conversation(Protocol.MIX, "INFORM").size());
    }

    @Test
    void concurrentSessionsDoNotInterfere() throws InterruptedException {
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        Performance a = runtime.perform(session("session-a", Instrument.PIANO, List.of(Instrument.BASS)), first);
        Performance b = runtime.perform(session("session-b", Instrument.PIANO, List.of(Instrument.BASS)), second);
        try {
            assertTrue(first.done.await(60, TimeUnit.SECONDS));
            assertTrue(second.done.await(60, TimeUnit.SECONDS));
            first.assertFinished();
            second.assertFinished();
            assertEquals(first.conversation().size(), second.conversation().size());
            // Each session's agents only ever talked to their own session.
            assertTrue(first.conversation().stream().allMatch(c -> c.from().startsWith("session-a")));
            assertTrue(second.conversation().stream().allMatch(c -> c.from().startsWith("session-b")));
        } finally {
            a.stop();
            b.stop();
        }
    }
}
