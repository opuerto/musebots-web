package dev.musebots.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.musebots.core.Composers;
import dev.musebots.core.Instrument;
import dev.musebots.core.MusicClock;
import dev.musebots.core.NoteEvent;
import dev.musebots.core.PerformanceEvent;
import dev.musebots.core.PhraseKind;
import dev.musebots.core.Songs;
import jade.core.Agent;
import jade.core.behaviours.WakerBehaviour;
import jade.lang.acl.ACLMessage;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** The composer on its own: it writes a phrase at once, but hands the notes on only shortly before they are due. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComposerAgentTest {

    private static final long LOOKAHEAD_MS = 300;
    private static final long TEMPO = 960; // a bar is 250 ms

    private EnsembleRuntime runtime;
    private int sessions;

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

    /** Remembers when each note was handed over. */
    private static final class Heard {
        record At(long heardMs, NoteEvent note) {
        }

        final List<At> notes = new CopyOnWriteArrayList<>();

        void hear(PerformanceEvent event) {
            if (event instanceof NoteEvent note) {
                notes.add(new At(System.currentTimeMillis(), note));
            }
        }
    }

    /** Stands in for the musician and the Ensemble Assistant: sends the composer a request and mix changes. */
    private static final class StandIn extends Agent {
        private final String session;

        StandIn(String session) {
            this.session = session;
        }

        void send(long delayMs, int performative, String conversation, Object payload) {
            addBehaviour(new WakerBehaviour(this, delayMs) {
                @Override
                protected void onWake() {
                    ACLMessage message = new ACLMessage(performative);
                    message.setConversationId(conversation);
                    message.setContent(Json.write(payload));
                    message.addReceiver(Names.aid(session, Instrument.BASS, Names.Part.COMPOSER));
                    StandIn.this.send(message);
                }
            });
        }
    }

    private String startComposer(Heard heard) throws Exception {
        String session = "composer" + (++sessions);
        runtime.start(Names.of(session, Instrument.BASS, Names.Part.COMPOSER), new ComposerAgent(new ComposerConfig(
                session, Instrument.BASS, Composers.defaultFor(Instrument.BASS, new Random(1)), heard::hear,
                Clock.systemUTC(), LOOKAHEAD_MS)));
        return session;
    }

    /** A four-bar bass phrase (sixteen notes) starting {@code inMs} from now. */
    private static Protocol.ComposeRequest phrase(long inMs) {
        return new Protocol.ComposeRequest(PhraseKind.SUPPORT, "TEST", Songs.bluesInF((int) TEMPO).turnaround(4),
                new MusicClock((int) TEMPO, 4), System.currentTimeMillis() + inMs);
    }

    private void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
    }

    @Test
    void notesAreHandedOverJustBeforeTheyAreDueNotAllAtOnce() throws Exception {
        Heard heard = new Heard();
        String session = startComposer(heard);
        StandIn musician = new StandIn(session);
        runtime.start(session + "-musician", musician);
        musician.send(0, ACLMessage.REQUEST, Protocol.COMPOSE, phrase(2_000));

        Thread.sleep(800);
        assertTrue(heard.notes.isEmpty(), "notes 2 seconds away were handed over straight away");

        waitUntil(() -> heard.notes.size() == 16, 6_000);
        assertEquals(16, heard.notes.size());
        for (Heard.At at : heard.notes) {
            long lead = at.note().startMs() - at.heardMs();
            assertTrue(lead <= LOOKAHEAD_MS + 100, "note handed over " + lead + " ms early");
            assertTrue(lead >= -100, "note handed over " + (-lead) + " ms late");
        }
    }

    @Test
    void aMutedComposerWritesNothingAudibleAndAnUnmutedOneDoes() throws Exception {
        Heard heard = new Heard();
        String session = startComposer(heard);
        StandIn conductor = new StandIn(session);
        runtime.start(session + "-assistant", conductor);

        conductor.send(0, ACLMessage.INFORM, Protocol.MIX, new Protocol.Mix(null, true));
        conductor.send(100, ACLMessage.REQUEST, Protocol.COMPOSE, phrase(600));
        Thread.sleep(2_000);
        assertTrue(heard.notes.isEmpty(), "a muted composer played");

        conductor.send(0, ACLMessage.INFORM, Protocol.MIX, new Protocol.Mix(null, false));
        conductor.send(100, ACLMessage.REQUEST, Protocol.COMPOSE, phrase(600));
        waitUntil(() -> heard.notes.size() == 16, 6_000);
        assertEquals(16, heard.notes.size());
    }

    @Test
    void volumeScalesVelocityAndStaysInRange() throws Exception {
        Heard normal = new Heard();
        Heard quiet = new Heard();
        Heard loud = new Heard();
        for (Object[] run : new Object[][] {{normal, 1.0}, {quiet, 0.5}, {loud, 10.0}}) {
            Heard heard = (Heard) run[0];
            String session = startComposer(heard);
            StandIn conductor = new StandIn(session);
            runtime.start(session + "-assistant", conductor);
            conductor.send(0, ACLMessage.INFORM, Protocol.MIX, new Protocol.Mix((Double) run[1], null));
            conductor.send(100, ACLMessage.REQUEST, Protocol.COMPOSE, phrase(500));
            waitUntil(() -> heard.notes.size() == 16, 6_000);
            assertEquals(16, heard.notes.size());
        }
        for (int i = 0; i < 16; i++) {
            int base = normal.notes.get(i).note().velocity();
            assertEquals(Math.round(base * 0.5), quiet.notes.get(i).note().velocity(), 1, "half volume, note " + i);
            assertEquals(127, loud.notes.get(i).note().velocity(), "volume is capped at the MIDI maximum");
        }
    }
}
