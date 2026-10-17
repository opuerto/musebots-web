package dev.musebots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SaxComposerTest {

    private final MusicClock clock = new MusicClock(120, 4);
    private final List<ChordBar> bars = Songs.bluesInF(120).allBars();

    private List<NoteEvent> compose(long seed) {
        return new SaxComposer(new Random(seed)).compose(new PhraseRequest(PhraseKind.HEAD, bars, clock, 0));
    }

    @Test
    void playsASingleLineWithoutOverlap() {
        for (long seed = 0; seed < 25; seed++) {
            List<NoteEvent> notes = compose(seed);
            assertFalse(notes.isEmpty());
            for (int i = 1; i < notes.size(); i++) {
                assertTrue(notes.get(i).startMs() >= notes.get(i - 1).endMs(),
                        "overlap at note " + i + " with seed " + seed);
            }
        }
    }

    @Test
    void staysInTheSaxRegisterAndInsideThePhrase() {
        long end = new PhraseRequest(PhraseKind.HEAD, bars, clock, 0).endMs();
        for (long seed = 0; seed < 25; seed++) {
            for (NoteEvent note : compose(seed)) {
                assertEquals(Instrument.SAX, note.instrument());
                assertTrue(note.midi() >= SaxComposer.LOW && note.midi() <= SaxComposer.HIGH);
                assertTrue(note.startMs() >= 0 && note.endMs() <= end);
            }
        }
    }

    @Test
    void opensBarsOnGuideTones() {
        for (long seed = 0; seed < 25; seed++) {
            List<NoteEvent> notes = compose(seed);
            for (int bar = 0; bar < bars.size(); bar++) {
                long barStart = clock.barsToMillis(bar);
                Chord chord = bars.get(bar).chord();
                for (NoteEvent note : notes) {
                    if (note.startMs() == barStart) {
                        int pc = note.midi() % 12;
                        assertTrue(pc == chord.thirdPc() || pc == chord.seventhPc(),
                                "bar " + bar + " opened on pitch class " + pc + " over " + chord);
                    }
                }
            }
        }
    }

    @Test
    void movesInSmallStepsBetweenNotesInABar() {
        List<NoteEvent> notes = compose(11);
        for (int i = 1; i < notes.size(); i++) {
            boolean sameBar = notes.get(i).startMs() / clock.barsToMillis(1) == notes.get(i - 1).startMs() / clock.barsToMillis(1);
            if (sameBar) {
                assertTrue(Math.abs(notes.get(i).midi() - notes.get(i - 1).midi()) <= 12);
            }
        }
    }

    @Test
    void isDeterministicForAGivenSeed() {
        assertEquals(compose(7), compose(7));
    }
}
