package dev.musebots.core;

/**
 * A stretch of the performance on the shared timeline, e.g. "SOLO-PIANO" from 12:00:03.000 to 12:00:06.000.
 * {@code kind} is what the leading musician plays during it; {@code leader} is who that is (null for the intro,
 * which is played by whoever won the negotiation). {@code tempoBpm} is the tempo it was arranged at: the Conductor
 * may change the tempo, but a segment already arranged keeps the one it was written for.
 */
public record Segment(String id, PhraseKind kind, Instrument leader, long startMs, long endMs, int tempoBpm) {

    public long durationMs() {
        return endMs - startMs;
    }
}
