package dev.musebots.agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.MessageTemplate;

/** Throws away acknowledgements that were already published when they were sent, so queues never fill up. */
final class Drain extends CyclicBehaviour {

    private final MessageTemplate template;

    Drain(Agent agent, MessageTemplate template) {
        super(agent);
        this.template = template;
    }

    @Override
    public void action() {
        if (myAgent.receive(template) == null) {
            block();
        }
    }
}
