package dev.musebots.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.musebots.core.Instrument;
import dev.musebots.core.PhraseKind;
import dev.musebots.core.Segment;
import jade.core.Agent;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** The synchronizers' side of the conversation, with a bare-bones agent standing in for the musician. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SynchronizerAgentTest {

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

    /** Sends one message to the synchronizer of the given instrument, after a delay, and collects its reply. */
    private static final class StandIn extends Agent {
        final CompletableFuture<ACLMessage> reply = new CompletableFuture<>();
        private final String session;

        StandIn(String session) {
            this.session = session;
        }

        void tell(Instrument synchronizerOf, int performative, String conversation, Object payload, String token, long delayMs) {
            addBehaviour(new jade.core.behaviours.WakerBehaviour(this, delayMs) {
                @Override
                protected void onWake() {
                    ACLMessage message = new ACLMessage(performative);
                    message.setConversationId(conversation);
                    message.setContent(Json.write(payload));
                    message.addReceiver(Names.aid(session, synchronizerOf, Names.Part.SYNCHRONIZER));
                    if (token != null) {
                        message.setReplyWith(token);
                    }
                    send(message);
                }
            });
        }

        void listenFor(String conversation) {
            addBehaviour(new AwaitReply(this, MessageTemplate.MatchConversationId(conversation), 10_000,
                    reply::complete, () -> reply.completeExceptionally(new AssertionError("no reply"))));
        }
    }

    private void startSynchronizers(String session, Instrument... instruments) throws Exception {
        for (Instrument instrument : instruments) {
            runtime.start(Names.of(session, instrument, Names.Part.SYNCHRONIZER), new SynchronizerAgent(
                    new SynchronizerConfig(session, instrument, event -> { }, Clock.systemUTC())));
        }
        Thread.sleep(300); // let them register with the directory
    }

    @Test
    void aSynchronizerAsksItsPeersAndKeepsAskingUntilSomeoneKnows() throws Exception {
        startSynchronizers("race", Instrument.PIANO, Instrument.BASS);
        Segment solo = new Segment("SOLO-BASS", PhraseKind.SOLO, Instrument.BASS, 5_000, 8_000, 120);

        // The bass musician asks before the piano's musician has decided anything; the piano records the
        // segment half a second later. The bass synchronizer must still come back with the answer.
        StandIn bass = new StandIn("race");
        StandIn piano = new StandIn("race");
        runtime.start("race-stand-in-bass", bass);
        runtime.start("race-stand-in-piano", piano);
        bass.listenFor(Protocol.SYNC_QUERY);
        bass.tell(Instrument.BASS, ACLMessage.QUERY_REF, Protocol.SYNC_QUERY, new Protocol.SegmentQuery("SOLO-BASS"), "t1", 0);
        piano.tell(Instrument.PIANO, ACLMessage.INFORM, Protocol.SYNC_RECORD, solo, null, 500);

        ACLMessage answer = bass.reply.get(8, TimeUnit.SECONDS);
        assertEquals(ACLMessage.INFORM, answer.getPerformative());
        assertEquals("t1", answer.getInReplyTo());
        assertEquals(solo, Json.read(answer.getContent(), Segment.class));
    }

    @Test
    void aSynchronizerAdmitsWhenNobodyKnows() throws Exception {
        startSynchronizers("nobody", Instrument.PIANO, Instrument.BASS);
        StandIn bass = new StandIn("nobody");
        runtime.start("nobody-stand-in", bass);
        bass.listenFor(Protocol.SYNC_QUERY);
        long asked = System.currentTimeMillis();
        bass.tell(Instrument.BASS, ACLMessage.QUERY_REF, Protocol.SYNC_QUERY, new Protocol.SegmentQuery("SOLO-NOBODY"), "t2", 0);

        ACLMessage answer = bass.reply.get(8, TimeUnit.SECONDS);
        assertEquals(ACLMessage.FAILURE, answer.getPerformative());
        assertTrue(System.currentTimeMillis() - asked >= 2_500, "gave up too early");
    }

    @Test
    void aSynchronizerAnswersFromMemoryWithoutAskingAnyone() throws Exception {
        startSynchronizers("memory", Instrument.PIANO);
        Segment head = new Segment("HEAD", PhraseKind.HEAD, Instrument.PIANO, 1_000, 4_000, 120);
        StandIn musician = new StandIn("memory");
        runtime.start("memory-stand-in", musician);
        musician.listenFor(Protocol.SYNC_QUERY);
        musician.tell(Instrument.PIANO, ACLMessage.INFORM, Protocol.SYNC_RECORD, head, null, 0);
        musician.tell(Instrument.PIANO, ACLMessage.QUERY_REF, Protocol.SYNC_QUERY, new Protocol.SegmentQuery("HEAD"), "t3", 200);

        ACLMessage answer = musician.reply.get(5, TimeUnit.SECONDS);
        assertEquals(head, Json.read(answer.getContent(), Segment.class));
    }
}
