package dev.musebots.agents;

import dev.musebots.core.NoteEvent;
import dev.musebots.core.PhraseRequest;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import java.util.List;

/** Writes notes when its musician asks. This is where a composition technique plugs into the Musebot. */
public final class ComposerAgent extends MusebotAgent {

    private final ComposerConfig config;

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
    }

    private void compose(ACLMessage request) {
        Protocol.ComposeRequest job = Json.read(request.getContent(), Protocol.ComposeRequest.class);
        List<NoteEvent> notes = config.composer().compose(
                new PhraseRequest(job.kind(), job.bars(), job.clock(), job.startMs()));
        notes.forEach(this::emit);

        ACLMessage reply = request.createReply();
        reply.setPerformative(ACLMessage.INFORM);
        reply.setContent(Json.write(new Protocol.Composed(job.segmentId(), job.kind(), config.instrument(), notes.size())));
        observe(reply, notes.isEmpty()
                ? "nothing to play in " + job.segmentId()
                : "wrote " + notes.size() + " " + config.instrument().name().toLowerCase() + " notes for " + job.segmentId());
        send(reply);
    }
}
