package dev.musebots.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.musebots.core.ConversationEvent;
import dev.musebots.core.EventSink;
import dev.musebots.core.Instrument;
import dev.musebots.core.NoteEvent;
import dev.musebots.core.PerformanceEvent;
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

        List<StatusEvent> statuses(StatusEvent.Kind kind) {
            return events.stream().filter(StatusEvent.class::isInstance).map(StatusEvent.class::cast)
                    .filter(s -> s.kind() == kind).toList();
        }

        /** The start time of the section with this label. */
        long sectionStart(String label) {
            return statuses(StatusEvent.Kind.SECTION).stream().filter(s -> s.message().equals(label))
                    .findFirst().orElseThrow(() -> new AssertionError("no section '" + label + "'")).atMs();
        }

        List<String> sectionLabels() {
            return statuses(StatusEvent.Kind.SECTION).stream().map(StatusEvent::message).toList();
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
                300, 500, 42, Clock.systemUTC());
    }

    @Test
    void aDuoPlaysTheWholeTuneAndPassesTheLead() throws InterruptedException {
        Recorder recorder = perform(session("duo", Instrument.PIANO, List.of(Instrument.BASS)));
        recorder.assertFinished();

        // Intro, head, one solo each, ending: each part starts exactly when the previous one ends.
        assertEquals(List.of("The head", "piano solo", "bass solo", "The ending"), recorder.sectionLabels());
        long introStart = recorder.statuses(StatusEvent.Kind.STARTED).get(0).atMs();
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
    void theQuartetTradesSolosAndLaysOutWhenEtiquetteSaysSo() throws InterruptedException {
        Recorder recorder = perform(session("quartet", Instrument.SAX,
                List.of(Instrument.PIANO, Instrument.BASS, Instrument.DRUMS)));
        recorder.assertFinished();

        assertEquals(List.of("The head", "sax solo", "piano solo", "bass solo", "drums solo", "The ending"),
                recorder.sectionLabels());
        long introStart = recorder.statuses(StatusEvent.Kind.STARTED).get(0).atMs();
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

        // The sax plays the head, its own solo and the ending, and otherwise lays out.
        assertFalse(recorder.notes(Instrument.SAX, head, saxSolo).isEmpty());
        assertFalse(recorder.notes(Instrument.SAX, saxSolo, pianoSolo).isEmpty());
        assertTrue(recorder.notes(Instrument.SAX, pianoSolo, ending).isEmpty(), "the sax played behind someone else's solo");
        assertFalse(recorder.notes(Instrument.SAX, ending, ending + BAR_MS).isEmpty());

        // Behind the piano and bass solos the rest of the rhythm section keeps playing.
        assertFalse(recorder.notes(Instrument.BASS, pianoSolo, bassSolo).isEmpty());
        assertFalse(recorder.notes(Instrument.DRUMS, pianoSolo, bassSolo).isEmpty());
        assertFalse(recorder.notes(Instrument.PIANO, bassSolo, drumsSolo).isEmpty());
        assertFalse(recorder.notes(Instrument.DRUMS, bassSolo, drumsSolo).isEmpty());

        // During the drum solo everyone else lays out.
        assertFalse(recorder.notes(Instrument.DRUMS, drumsSolo, ending).isEmpty());
        for (Instrument silent : List.of(Instrument.SAX, Instrument.PIANO, Instrument.BASS)) {
            assertTrue(recorder.notes(silent, drumsSolo, ending).isEmpty(), silent + " played under the drum solo");
        }

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
