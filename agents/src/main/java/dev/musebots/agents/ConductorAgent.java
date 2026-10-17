package dev.musebots.agents;

import dev.musebots.core.Instrument;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;

/**
 * The Musebot Conductor, as far as the agents are concerned. Whoever is conducting (here, the web server) hands it
 * commands through JADE's object-to-agent channel; it broadcasts each one to every Ensemble Assistant, which
 * work out what concerns their Musebot.
 */
public final class ConductorAgent extends MusebotAgent {

    private final ConductorConfig config;

    public ConductorAgent(ConductorConfig config) {
        super(config.sessionId(), config.sink(), config.clock());
        this.config = config;
        // Enabled here, not in setup(), so a command given the moment the performance starts is not lost.
        setEnabledO2ACommunication(true, 0);
    }

    @Override
    protected void setup() {
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                Object command = myAgent.getO2AObject();
                if (command instanceof Protocol.Conduct conduct) {
                    broadcast(conduct);
                } else {
                    block(200); // woken as soon as a command arrives; the timeout is only a safety net
                }
            }
        });
    }

    private void broadcast(Protocol.Conduct conduct) {
        ACLMessage message = newMessage(ACLMessage.INFORM, Protocol.CONDUCTOR, conduct);
        for (Instrument instrument : config.ensemble()) {
            message.addReceiver(Names.aid(config.sessionId(), instrument, Names.Part.ASSISTANT));
        }
        say(message, describe(conduct));
    }

    private static String describe(Protocol.Conduct conduct) {
        String who = conduct.target() == null ? "everyone" : conduct.target().name().toLowerCase();
        return switch (conduct.command()) {
            case TEMPO -> "the tempo is now " + Math.round(conduct.value()) + " bpm";
            case VOLUME -> who + ": volume " + Math.round(conduct.value() * 100) + "%";
            case MUTE -> who + ": sit out";
            case UNMUTE -> who + ": play again";
        };
    }
}
