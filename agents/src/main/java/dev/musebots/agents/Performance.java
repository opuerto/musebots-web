package dev.musebots.agents;

import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;
import java.util.List;

/** Handle to a running performance. Stopping it removes its agents from the platform. */
public final class Performance {

    private final String sessionId;
    private final List<AgentController> agents;
    private final AgentController conductor;

    Performance(String sessionId, List<AgentController> agents, AgentController conductor) {
        this.sessionId = sessionId;
        this.agents = List.copyOf(agents);
        this.conductor = conductor;
    }

    public String sessionId() {
        return sessionId;
    }

    /** Gives the Conductor a command to pass on to the ensemble. */
    public void conduct(Protocol.Conduct command) {
        if (conductor == null) {
            throw new IllegalStateException("this performance has no conductor");
        }
        try {
            conductor.putO2AObject(command, AgentController.ASYNC);
        } catch (StaleProxyException e) {
            throw new IllegalStateException("the performance is over", e);
        }
    }

    public void stop() {
        for (AgentController musician : agents) {
            try {
                musician.kill();
            } catch (Exception e) {
                // Already gone (e.g. the platform shut down first).
            }
        }
    }
}
