package dev.musebots.agents;

import dev.musebots.core.EventSink;
import dev.musebots.core.Instrument;
import java.time.Clock;

/** What a synchronizer agent needs, injected at construction. */
public record SynchronizerConfig(String sessionId, Instrument instrument, EventSink sink, Clock clock) {
}
