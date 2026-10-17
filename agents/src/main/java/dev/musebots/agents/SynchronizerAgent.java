package dev.musebots.agents;

import dev.musebots.core.Segment;
import jade.core.AID;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps this Musebot's picture of the performance timeline. The musician records the parts it arranges here;
 * when it needs to know when a part happens, the synchronizer answers from memory, or asks the other
 * Musebots' synchronizers and remembers what they say.
 */
public final class SynchronizerAgent extends MusebotAgent {

    private static final long RESOLVE_TIMEOUT_MS = 3_000;
    private static final long POLL_MS = 50;
    private static final int RE_ASK_EVERY_POLLS = 6;

    private final Map<String, Segment> timeline = new HashMap<>();
    /** Questions from our musician that nobody has answered yet, by segment id. */
    private final Map<String, List<ACLMessage>> waiting = new HashMap<>();

    public SynchronizerAgent(SynchronizerConfig config) {
        super(config.sessionId(), config.sink(), config.clock());
    }

    @Override
    protected void setup() {
        registerAs(Protocol.SERVICE_SYNCHRONIZER);
        addBehaviour(new Switchboard());
    }

    /** Dispatches every incoming message by its conversation. */
    private final class Switchboard extends CyclicBehaviour {

        @Override
        public void action() {
            ACLMessage message = myAgent.receive();
            if (message == null) {
                block();
                return;
            }
            String conversation = message.getConversationId() == null ? "" : message.getConversationId();
            switch (conversation) {
                case Protocol.SYNC_RECORD -> record(message);
                case Protocol.SYNC_QUERY -> queryFromMusician(message);
                case Protocol.SYNC_PEER -> fromPeer(message);
                default -> { }
            }
        }
    }

    /** Remembers a segment, and answers right away anyone who was waiting to hear about it. */
    private void record(ACLMessage message) {
        Segment segment = Json.read(message.getContent(), Segment.class);
        timeline.put(segment.id(), segment);
        List<ACLMessage> askers = waiting.remove(segment.id());
        if (askers != null) {
            askers.forEach(asker -> answer(asker, segment));
        }
    }

    private void queryFromMusician(ACLMessage query) {
        String id = Json.read(query.getContent(), Protocol.SegmentQuery.class).segmentId();
        Segment known = timeline.get(id);
        if (known != null) {
            answer(query, known);
        } else {
            resolve(query, id);
        }
    }

    private void fromPeer(ACLMessage message) {
        switch (message.getPerformative()) {
            case ACLMessage.QUERY_REF -> {
                String id = Json.read(message.getContent(), Protocol.SegmentQuery.class).segmentId();
                Segment known = timeline.get(id);
                if (known != null) {
                    answer(message, known);
                } else {
                    ACLMessage unknown = message.createReply();
                    unknown.setPerformative(ACLMessage.FAILURE);
                    send(unknown); // silent: this is the common case while the leader is still deciding
                }
            }
            case ACLMessage.INFORM -> record(message); // an answer to our own question: remember it
            default -> { }
        }
    }

    private void answer(ACLMessage query, Segment segment) {
        ACLMessage reply = query.createReply();
        reply.setPerformative(ACLMessage.INFORM);
        reply.setContent(Json.write(segment));
        observe(reply, segment.id() + " runs from " + segment.startMs() + " to " + segment.endMs());
        send(reply);
    }

    /**
     * Nobody here knows yet: ask the other synchronizers, and keep asking until somebody knows or time runs out.
     * The leader may simply not have decided yet when the question first arrives.
     */
    private void resolve(ACLMessage query, String id) {
        waiting.computeIfAbsent(id, key -> new ArrayList<>()).add(query);
        askPeers(id, true);
        long deadline = System.currentTimeMillis() + RESOLVE_TIMEOUT_MS;
        addBehaviour(new TickerBehaviour(this, POLL_MS) {
            private int polls;

            @Override
            protected void onTick() {
                if (!stillWaiting(id, query)) {
                    stop(); // an answer arrived and record() has already replied
                } else if (System.currentTimeMillis() > deadline) {
                    stop();
                    waiting.get(id).remove(query);
                    ACLMessage unknown = query.createReply();
                    unknown.setPerformative(ACLMessage.FAILURE);
                    observe(unknown, "nobody knows when " + id + " happens");
                    send(unknown);
                } else if (++polls % RE_ASK_EVERY_POLLS == 0) {
                    askPeers(id, false);
                }
            }
        });
    }

    private boolean stillWaiting(String id, ACLMessage query) {
        List<ACLMessage> askers = waiting.get(id);
        return askers != null && askers.contains(query);
    }

    private void askPeers(String id, boolean announce) {
        for (AID peer : findPeers(Protocol.SERVICE_SYNCHRONIZER)) {
            ACLMessage question = newMessage(ACLMessage.QUERY_REF, Protocol.SYNC_PEER, new Protocol.SegmentQuery(id));
            question.setProtocol(FIPANames.InteractionProtocol.FIPA_QUERY);
            question.addReceiver(peer);
            if (announce) {
                say(question, "do you know when " + id + " happens?");
            } else {
                send(question);
            }
        }
    }
}
