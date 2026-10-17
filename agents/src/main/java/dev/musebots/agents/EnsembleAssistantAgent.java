package dev.musebots.agents;

import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

/**
 * The go-between of a Musebot and the Conductor. The Conductor speaks to the whole ensemble; the assistant picks out
 * what concerns this Musebot and passes it to the right agent: tempo to the musician, who plans the music, and
 * volume and muting to the composer, who writes the notes.
 */
public final class EnsembleAssistantAgent extends MusebotAgent {

    private final AssistantConfig config;

    public EnsembleAssistantAgent(AssistantConfig config) {
        super(config.sessionId(), config.sink(), config.clock());
        this.config = config;
    }

    @Override
    protected void setup() {
        addBehaviour(new CyclicBehaviour(this) {
            private final MessageTemplate template = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                    MessageTemplate.MatchConversationId(Protocol.CONDUCTOR));

            @Override
            public void action() {
                ACLMessage message = myAgent.receive(template);
                if (message == null) {
                    block();
                    return;
                }
                relay(Json.read(message.getContent(), Protocol.Conduct.class));
            }
        });
    }

    private void relay(Protocol.Conduct conduct) {
        if (conduct.target() != null && conduct.target() != config.instrument()) {
            return; // meant for another Musebot
        }
        switch (conduct.command()) {
            case TEMPO -> tell(Names.Part.MUSICIAN, Protocol.TEMPO, new Protocol.TempoChange((int) Math.round(conduct.value())),
                    "the tempo is " + Math.round(conduct.value()) + " bpm: telling my musician");
            case VOLUME -> tell(Names.Part.COMPOSER, Protocol.MIX, new Protocol.Mix(conduct.value(), null),
                    "volume " + Math.round(conduct.value() * 100) + "%: telling my composer");
            case MUTE -> tell(Names.Part.COMPOSER, Protocol.MIX, new Protocol.Mix(null, true),
                    "sit out: telling my composer to stop playing");
            case UNMUTE -> tell(Names.Part.COMPOSER, Protocol.MIX, new Protocol.Mix(null, false),
                    "play again: telling my composer to start playing");
        }
    }

    private void tell(Names.Part part, String conversation, Object payload, String summary) {
        ACLMessage message = newMessage(ACLMessage.INFORM, conversation, payload);
        message.addReceiver(Names.aid(config.sessionId(), config.instrument(), part));
        say(message, summary);
    }
}
