package dev.musebots.core;

/**
 * A stretch of the performance on the shared timeline, e.g. "SOLO-PIANO" from 12:00:03.000 to 12:00:06.000.
 * {@code kind} is what the leading musician plays during it; {@code leader} is who that is (null for the intro,
 * which is played by whoever won the negotiation).
 */
public record Segment(String id, PhraseKind kind, Instrument leader, long startMs, long endMs) {

    public long durationMs() {
        return endMs - startMs;
    }
}
