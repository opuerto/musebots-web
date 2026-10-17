package dev.musebots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class WalkingBassComposerTest {

    private final MusicClock clock = new MusicClock(120, 4);
    private final List<ChordBar> bars = Songs.bluesInF(120).allBars();

    private List<NoteEvent> compose(long seed, long startMs) {
        return new WalkingBassComposer(new Random(seed)).compose(new PhraseRequest(PhraseKind.SUPPORT, bars, clock, startMs));
    }

    @Test
    void playsFourNotesPerBarOnTheBeat() {
        List<NoteEvent> notes = compose(1, 10_000);
        assertEquals(bars.size() * 4, notes.size());
        for (int i = 0; i < notes.size(); i++) {
            assertEquals(10_000 + i * 500L, notes.get(i).startMs());
        }
    }

    @Test
    void landsOnTheRootOnBeatOne() {
        List<NoteEvent> notes = compose(2, 0);
        for (int bar = 0; bar < bars.size(); bar++) {
            int expected = bars.get(bar).chord().root();
            assertEquals(expected, notes.get(bar * 4).midi() % 12);
        }
    }

    @Test
    void staysInTheBassRegisterAndAllNotesAreBass() {
        for (NoteEvent note : compose(3, 0)) {
            assertEquals(Instrument.BASS, note.instrument());
            assertTrue(note.midi() >= WalkingBassComposer.LOW && note.midi() <= WalkingBassComposer.HIGH);
        }
    }

    @Test
    void doesNotRunPastTheEndOfThePhrase() {
        PhraseRequest request = new PhraseRequest(PhraseKind.SUPPORT, bars, clock, 5_000);
        long end = request.endMs();
        for (NoteEvent note : new WalkingBassComposer(new Random(4)).compose(request)) {
            assertTrue(note.endMs() <= end);
        }
    }

    @Test
    void isDeterministicForAGivenSeed() {
        assertEquals(compose(7, 0), compose(7, 0));
    }
}
