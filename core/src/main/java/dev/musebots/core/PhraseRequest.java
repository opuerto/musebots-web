package dev.musebots.core;

import java.util.List;

/** What a composer is asked to write: these bars, starting at this wall-clock time. */
public record PhraseRequest(List<ChordBar> bars, MusicClock clock, long startMs) {

    public PhraseRequest {
        bars = List.copyOf(bars);
    }

    public long durationMs() {
        return clock.barsToMillis(bars.size());
    }

    public long endMs() {
        return startMs + durationMs();
    }
}
