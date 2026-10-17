package dev.musebots.agents;

import dev.musebots.core.EventSink;
import dev.musebots.core.Instrument;
import dev.musebots.core.PhraseComposer;
import java.time.Clock;

/** What a composer agent needs, injected at construction. */
public record ComposerConfig(String sessionId, Instrument instrument, PhraseComposer composer, EventSink sink, Clock clock) {
}
