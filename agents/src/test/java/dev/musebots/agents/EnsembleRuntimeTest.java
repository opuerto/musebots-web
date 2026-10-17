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

    private static final int FAST_TEMPO = 480; // one beat = 125 ms, so a chorus takes 6 s

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

        List<NoteEvent> notes(Instrument instrument) {
            return events.stream().filter(NoteEvent.class::isInstance).map(NoteEvent.class::cast)
                    .filter(n -> n.instrument() == instrument).toList();
        }

        StatusEvent status(StatusEvent.Kind kind) {
            return events.stream().filter(StatusEvent.class::isInstance).map(StatusEvent.class::cast)
                    .filter(s -> s.kind() == kind).findFirst().orElse(null);
        }
    }

    private Performance start(String sessionId, Recorder recorder) {
        SessionConfig session = new SessionConfig(sessionId, Songs.bluesInF(FAST_TEMPO), 1,
                Instrument.PIANO, List.of(Instrument.BASS), 300, 42, Clock.systemUTC());
        return runtime.perform(session, recorder);
    }

    @Test
    void leaderNegotiatesTheIntroAndThenPlaysTheHead() throws InterruptedException {
        Recorder recorder = new Recorder();
        Performance performance = start("negotiation", recorder);
        try {
            assertTrue(recorder.done.await(30, TimeUnit.SECONDS), "performance did not finish");
            assertEquals(StatusEvent.Kind.FINISHED, lastStatus(recorder).kind(), lastStatus(recorder).message());

            // The conversation follows the Contract Net, in order.
            List<String> performatives = recorder.conversation().stream().map(ConversationEvent::performative).toList();
            assertEquals(List.of("INFORM", "CONFIRM", "CFP", "PROPOSE", "ACCEPT-PROPOSAL", "INFORM", "REQUEST"),
                    performatives);

            // The bass plays the intro (4 bars of 4 beats), then the head (12 bars of 4 beats).
            List<NoteEvent> bass = recorder.notes(Instrument.BASS);
            List<NoteEvent> piano = recorder.notes(Instrument.PIANO);
            assertEquals((4 + 12) * 4, bass.size());
            assertFalse(piano.isEmpty());

            long introStart = bass.get(0).startMs();
            long introEnd = introStart + 4 * 4 * 125L;
            assertEquals(introStart, recorder.status(StatusEvent.Kind.STARTED).atMs());

            // The leader waits for the intro to finish; it only plays once the intro is over.
            long firstPiano = piano.stream().mapToLong(NoteEvent::startMs).min().orElseThrow();
            assertEquals(introEnd, firstPiano);
            // The accompanist joins the head at the same moment.
            assertEquals(introEnd, bass.get(16).startMs());
        } finally {
            performance.stop();
        }
    }

    @Test
    void concurrentSessionsDoNotInterfere() throws InterruptedException {
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        Performance a = start("session-a", first);
        Performance b = start("session-b", second);
        try {
            assertTrue(first.done.await(30, TimeUnit.SECONDS));
            assertTrue(second.done.await(30, TimeUnit.SECONDS));
            for (Recorder recorder : List.of(first, second)) {
                assertEquals(StatusEvent.Kind.FINISHED, lastStatus(recorder).kind(), lastStatus(recorder).message());
                assertEquals(7, recorder.conversation().size());
                assertEquals(64, recorder.notes(Instrument.BASS).size());
            }
            // Each session's agents only ever talked to their own session.
            assertTrue(first.conversation().stream().allMatch(c -> c.from().startsWith("session-a")));
            assertTrue(second.conversation().stream().allMatch(c -> c.from().startsWith("session-b")));
        } finally {
            a.stop();
            b.stop();
        }
    }

    private static StatusEvent lastStatus(Recorder recorder) {
        return recorder.events.stream().filter(StatusEvent.class::isInstance).map(StatusEvent.class::cast)
                .reduce((x, y) -> y).orElseThrow();
    }
}
