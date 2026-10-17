package dev.musebots.agents;

import dev.musebots.core.EventSink;
import dev.musebots.core.Instrument;
import java.time.Clock;

/** What an Ensemble Assistant agent needs, injected at construction. */
public record AssistantConfig(String sessionId, Instrument instrument, EventSink sink, Clock clock) {
}
