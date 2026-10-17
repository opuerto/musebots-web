package dev.musebots.agents;

import jade.core.Agent;
import jade.core.behaviours.SimpleBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import java.util.function.Consumer;

/** Waits for one reply matching a template, giving up after a timeout. */
final class AwaitReply extends SimpleBehaviour {

    private final MessageTemplate template;
    private final long deadline;
    private final Consumer<ACLMessage> onReply;
    private final Runnable onTimeout;
    private boolean replied;

    AwaitReply(Agent agent, MessageTemplate template, long timeoutMs, Consumer<ACLMessage> onReply, Runnable onTimeout) {
        super(agent);
        this.template = template;
        this.deadline = System.currentTimeMillis() + timeoutMs;
        this.onReply = onReply;
        this.onTimeout = onTimeout;
    }

    @Override
    public void action() {
        ACLMessage reply = myAgent.receive(template);
        if (reply != null) {
            replied = true;
            onReply.accept(reply);
        } else {
            block(Math.max(1, deadline - System.currentTimeMillis()));
        }
    }

    @Override
    public boolean done() {
        return replied || System.currentTimeMillis() > deadline;
    }

    @Override
    public int onEnd() {
        if (!replied) {
            onTimeout.run();
        }
        return 0;
    }
}
