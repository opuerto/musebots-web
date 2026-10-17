package dev.musebots.core;

/**
 * A single note scheduled on the performance clock. {@code startMs} is absolute wall-clock time
 * (epoch milliseconds), so listeners can schedule it ahead of time.
 */
public record NoteEvent(Instrument instrument, int midi, long startMs, long durationMs, int velocity)
        implements PerformanceEvent {

    public long endMs() {
        return startMs + durationMs;
    }
}
