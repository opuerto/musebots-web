package dev.musebots.learning;

/**
 * A note on the straight grid the corpus is written on: {@link Corpus#TICKS_PER_BEAT} ticks to a beat, counted from the
 * start of the solo (or of the generated phrase). Swing is not part of it: eighths sit on tick 6, and are only swung when
 * they are played.
 */
public record TickNote(int tick, int duration, int pitch) {

    public int end() {
        return tick + duration;
    }
}
