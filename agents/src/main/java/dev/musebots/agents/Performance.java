package dev.musebots.agents;

import jade.wrapper.AgentController;
import java.util.List;

/** Handle to a running performance. Stopping it removes its agents from the platform. */
public final class Performance {

    private final String sessionId;
    private final List<AgentController> musicians;

    Performance(String sessionId, List<AgentController> musicians) {
        this.sessionId = sessionId;
        this.musicians = List.copyOf(musicians);
    }

    public String sessionId() {
        return sessionId;
    }

    public void stop() {
        for (AgentController musician : musicians) {
            try {
                musician.kill();
            } catch (Exception e) {
                // Already gone (e.g. the platform shut down first).
            }
        }
    }
}
