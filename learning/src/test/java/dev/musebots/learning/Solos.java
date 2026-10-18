package dev.musebots.learning;

import java.util.ArrayList;
import java.util.List;

/** Small solos made by hand, so what a model must learn from them is known. */
final class Solos {

    private Solos() {
    }

    static Corpus.Solo solo(String id, String title, String instrument, double tempo, int beatsPerBar,
                            List<Corpus.ChordChange> chords, List<TickNote> notes) {
        return new Corpus.Solo(id, "Player " + id, title, instrument, "BEBOP", "SWING", "F-maj", tempo, beatsPerBar, chords, notes);
    }

    static Corpus.Solo solo(String id, String title, List<TickNote> notes) {
        return solo(id, title, "as", 120, 4, List.of(new Corpus.ChordChange(0, "F7")), notes);
    }

    /** F, A, C, E-flat then back to F, one quarter note each, over F7: always the same figure, whatever the context. */
    static List<TickNote> arpeggioOverF7(int bars) {
        int[] figure = {65, 69, 72, 75};
        List<TickNote> notes = new ArrayList<>();
        for (int i = 0; i < bars * 4; i++) {
            notes.add(new TickNote(i * 12, 12, figure[i % 4]));
        }
        return notes;
    }

    /** Pairs of swung-feel eighths (on ticks 0 and 6 of each beat), every note one scale step above the last, wrapping. */
    static List<TickNote> eighthsOverF7(int bars) {
        List<TickNote> notes = new ArrayList<>();
        int pitch = 65;
        for (int i = 0; i < bars * 8; i++) {
            notes.add(new TickNote(i * 6, 6, pitch));
            pitch = pitch >= 76 ? 65 : pitch + 1;
        }
        return notes;
    }

    static Corpus corpus(Corpus.Solo... solos) {
        return new Corpus("test", Corpus.TICKS_PER_BEAT, List.of(solos));
    }
}
