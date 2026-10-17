package dev.musebots.core;

/** One bar of a chord chart. */
public record ChordBar(Chord chord) {

    public static ChordBar of(String symbol) {
        return new ChordBar(Chord.parse(symbol));
    }
}
