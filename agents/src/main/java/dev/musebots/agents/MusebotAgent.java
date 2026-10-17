package dev.musebots.agents;

import dev.musebots.core.ConversationEvent;
import dev.musebots.core.EventSink;
import dev.musebots.core.PerformanceEvent;
import jade.core.AID;
import jade.core.Agent;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Base class for agents that publish what they say and register as musicians of one session. */
abstract class MusebotAgent extends Agent {

    private final String sessionId;
    private final EventSink sink;
    private final Clock clock;

    MusebotAgent(String sessionId, EventSink sink, Clock clock) {
        this.sessionId = sessionId;
        this.sink = sink;
        this.clock = clock;
    }

    Clock clock() {
        return clock;
    }

    void emit(PerformanceEvent event) {
        sink.emit(event);
    }

    /** Sends a message and publishes it to observers. */
    void say(ACLMessage message, String summary) {
        observe(message, summary);
        send(message);
    }

    /** Publishes a message that the protocol machinery sends itself (e.g. Contract Net replies). */
    void observe(ACLMessage message, String summary) {
        List<String> receivers = new ArrayList<>();
        for (Iterator<?> it = message.getAllReceiver(); it.hasNext(); ) {
            receivers.add(((AID) it.next()).getLocalName());
        }
        emit(new ConversationEvent(getLocalName(), receivers, ACLMessage.getPerformative(message.getPerformative()),
                message.getProtocol(), summary, clock.millis()));
    }

    /** A new outgoing message with the shared language and ontology already set. */
    ACLMessage newMessage(int performative, String conversationId, Object payload) {
        ACLMessage message = new ACLMessage(performative);
        message.setLanguage(Protocol.LANGUAGE);
        message.setOntology(Protocol.ONTOLOGY);
        message.setConversationId(conversationId);
        message.setContent(Json.write(payload));
        return message;
    }

    void registerAsMusician(String instrument) {
        DFAgentDescription description = new DFAgentDescription();
        description.setName(getAID());
        ServiceDescription service = new ServiceDescription();
        service.setType(Protocol.SERVICE_TYPE);
        service.setName(sessionId);
        description.addServices(service);
        try {
            DFService.register(this, description);
        } catch (FIPAException e) {
            throw new IllegalStateException("Could not register " + getLocalName() + " with the directory", e);
        }
    }

    /** The other musicians registered for this session. */
    List<AID> findPeers() {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription service = new ServiceDescription();
        service.setType(Protocol.SERVICE_TYPE);
        service.setName(sessionId);
        template.addServices(service);
        List<AID> peers = new ArrayList<>();
        try {
            for (DFAgentDescription found : DFService.search(this, template)) {
                if (!found.getName().equals(getAID())) {
                    peers.add(found.getName());
                }
            }
        } catch (FIPAException e) {
            // Directory not reachable yet: report no peers and let the caller retry.
        }
        return peers;
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            // Platform is shutting down; nothing useful to do.
        }
    }
}
