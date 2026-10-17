package dev.musebots.agents;

import dev.musebots.core.EventSink;
import dev.musebots.core.Instrument;
import java.time.Clock;
import java.util.List;

/** What the Conductor gateway needs: who is in the ensemble, so it can address their assistants. */
public record ConductorConfig(String sessionId, List<Instrument> ensemble, EventSink sink, Clock clock) {

    public ConductorConfig {
        ensemble = List.copyOf(ensemble);
    }
}
