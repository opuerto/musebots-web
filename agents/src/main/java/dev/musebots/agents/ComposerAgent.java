package dev.musebots.agents;

import dev.musebots.core.NoteEvent;
import dev.musebots.core.PhraseRequest;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Writes notes when its musician asks. This is where a composition technique plugs into the Musebot.
 *
 * <p>A phrase is written all at once, but its notes are only handed on a little before they are due. That keeps
 * the listener's schedule short, and means a change of volume or a mute from the Conductor is heard within
 * the lookahead instead of at the next part of the tune.
 */
public final class ComposerAgent extends MusebotAgent {

    private static final long RELEASE_POLL_MS = 40;

    private final ComposerConfig config;
    private final PriorityQueue<NoteEvent> pending = new PriorityQueue<>(Comparator.comparingLong(NoteEvent::startMs));
    private double gain = 1.0;
    private boolean muted;

    public ComposerAgent(ComposerConfig config) {
        super(config.sessionId(), config.sink(), config.clock());
        this.config = config;
    }

    @Override
    protected void setup() {
        addBehaviour(new CyclicBehaviour(this) {
            private final MessageTemplate template = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                    MessageTemplate.MatchConversationId(Protocol.COMPOSE));

            @Override
            public void action() {
                ACLMessage request = myAgent.receive(template);
                if (request == null) {
                    block();
                    return;
                }
                compose(request);
            }
        });
        addBehaviour(new CyclicBehaviour(this) {
            private final MessageTemplate template = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                    MessageTemplate.MatchConversationId(Protocol.MIX));

            @Override
            public void action() {
                ACLMessage message = myAgent.receive(template);
                if (message == null) {
                    block();
                    return;
                }
                mix(Json.read(message.getContent(), Protocol.Mix.class));
            }
        });
        addBehaviour(new TickerBehaviour(this, RELEASE_POLL_MS) {
            @Override
            protected void onTick() {
                release();
            }
        });
    }

    private void compose(ACLMessage request) {
        Protocol.ComposeRequest job = Json.read(request.getContent(), Protocol.ComposeRequest.class);
        List<NoteEvent> notes = config.composer().compose(
                new PhraseRequest(job.kind(), job.bars(), job.clock(), job.startMs()));
        pending.addAll(notes);

        ACLMessage reply = request.createReply();
        reply.setPerformative(ACLMessage.INFORM);
        reply.setContent(Json.write(new Protocol.Composed(job.segmentId(), job.kind(), config.instrument(), notes.size())));
        observe(reply, notes.isEmpty()
                ? "nothing to play in " + job.segmentId()
                : "wrote " + notes.size() + " " + config.instrument().name().toLowerCase() + " notes for " + job.segmentId());
        send(reply);
    }

    private void mix(Protocol.Mix change) {
        if (change.gain() != null) {
            gain = Math.max(0, change.gain());
        }
        if (change.muted() != null) {
            muted = change.muted();
        }
    }

    /** Hands on the notes that are now close enough to being due, at the volume the Conductor asked for. */
    private void release() {
        long horizon = clock().millis() + config.lookaheadMs();
        while (!pending.isEmpty() && pending.peek().startMs() <= horizon) {
            NoteEvent note = pending.poll();
            if (!muted) {
                emit(new NoteEvent(note.instrument(), note.midi(), note.startMs(), note.durationMs(),
                        (int) Math.max(1, Math.min(127, Math.round(note.velocity() * gain)))));
            }
        }
    }
}
