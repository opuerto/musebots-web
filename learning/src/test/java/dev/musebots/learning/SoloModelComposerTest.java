package dev.musebots.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.musebots.core.Composers;
import dev.musebots.core.Instrument;
import dev.musebots.core.MusicClock;
import dev.musebots.core.NoteEvent;
import dev.musebots.core.PhraseComposer;
import dev.musebots.core.PhraseKind;
import dev.musebots.core.PhraseRequest;
import dev.musebots.core.Songs;
import dev.musebots.core.Swing;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SoloModelComposerTest {

    private static final MarkovModel EIGHTHS = MarkovModel.train(List.of(Solos.solo("a", "Eighths", Solos.eighthsOverF7(8))),
            new MarkovModel.Meta("test", List.of(), 0, 0, 3, 1, List.of(), List.of()));
    private final MusicClock clock = Songs.bluesInF(120).clock(); // a beat is 500 ms

    private PhraseRequest request(PhraseKind kind, int bars) {
        return new PhraseRequest(kind, Songs.bluesInF(120).barsFor(kind, bars), clock, 10_000);
    }

    private PhraseComposer composer(Instrument instrument, long seed) {
        return SoloModelComposer.wrap(Composers.defaultFor(instrument, new Random(seed)), EIGHTHS, new Random(seed), 1.0);
    }

    @Test
    void everythingButTheSoloIsPlayedTheNormalWay() {
        for (PhraseKind kind : new PhraseKind[] {PhraseKind.INTRO, PhraseKind.HEAD, PhraseKind.SUPPORT, PhraseKind.SUPPORT_LIGHT, PhraseKind.ENDING}) {
            for (Instrument instrument : new Instrument[] {Instrument.SAX, Instrument.PIANO, Instrument.BASS, Instrument.DRUMS}) {
                int bars = kind == PhraseKind.INTRO ? 4 : kind == PhraseKind.ENDING ? 1 : 12;
                assertEquals(Composers.defaultFor(instrument, new Random(4)).compose(request(kind, bars)),
                        composer(instrument, 4).compose(request(kind, bars)), instrument + " " + kind);
            }
        }
    }

    @Test
    void theSoloComesFromTheModel() {
        List<NoteEvent> solo = composer(Instrument.SAX, 4).compose(request(PhraseKind.SOLO, 12));
        assertNotEquals(Composers.defaultFor(Instrument.SAX, new Random(4)).compose(request(PhraseKind.SOLO, 12)), solo);
        // The model only ever saw eight eighths a bar, each a semitone above the last. The sax line starts near A (69).
        assertEquals(96, solo.size(), "eight eighths a bar for twelve bars");
        assertEquals(70, solo.get(0).midi());
        assertEquals(71, solo.get(1).midi());
    }

    @Test
    void theNotesAreSwungAsTheyArePlayed() {
        List<NoteEvent> solo = composer(Instrument.SAX, 5).compose(request(PhraseKind.SOLO, 2));
        long beat = 500;
        // Notes 0 and 1 are an eighth-note pair: the first on the beat, the second two thirds of the way through it.
        assertEquals(10_000, solo.get(0).startMs());
        assertEquals(10_000 + Math.round(Swing.OFFBEAT * beat), solo.get(1).startMs());
        assertEquals(10_000 + beat, solo.get(2).startMs());
    }

    @Test
    void theSoloFitsItsWindowStaysInRegisterAndIsASingleLine() {
        for (long seed = 0; seed < 10; seed++) {
            PhraseRequest request = request(PhraseKind.SOLO, 12);
            List<NoteEvent> solo = composer(Instrument.SAX, seed).compose(request);
            long previousEnd = Long.MIN_VALUE;
            for (NoteEvent note : solo) {
                assertTrue(note.startMs() >= request.startMs() && note.endMs() <= request.endMs(), "outside the phrase: " + note);
                assertTrue(note.midi() >= 57 && note.midi() <= 81);
                assertTrue(note.startMs() >= previousEnd, "overlap at " + note);
                assertTrue(note.velocity() >= 84 && note.velocity() < 128);
                previousEnd = note.endMs();
            }
        }
    }

    @Test
    void staysInTimeWithTheBandAtTemposWhereABeatIsNotAWholeNumberOfMilliseconds() {
        // 140 bpm is 428.57 ms a beat and 960 is 62.5: the notes on the beat must be exactly where the other composers put them.
        for (int tempo : new int[] {140, 130, 960, 97}) {
            MusicClock odd = new MusicClock(tempo, 4);
            PhraseRequest request = new PhraseRequest(PhraseKind.SOLO, Songs.bluesInF(tempo).barsFor(PhraseKind.SOLO, 12), odd, 10_000);
            List<NoteEvent> solo = composer(Instrument.SAX, 8).compose(request);
            assertEquals(96, solo.size());
            for (int i = 0; i < solo.size(); i += 2) { // the on-beat eighths: notes 0, 2, 4... are on beats 0, 1, 2...
                assertEquals(10_000 + odd.beatsToMillis(i / 2), solo.get(i).startMs(), "tempo " + tempo + ", beat " + i / 2);
            }
            assertTrue(solo.stream().allMatch(n -> n.endMs() <= request.endMs()), "runs past the phrase at " + tempo + " bpm");
        }
    }

    @Test
    void thePianoPlaysTheLearnedLineWithSoftChordsUnderneath() {
        List<NoteEvent> solo = composer(Instrument.PIANO, 6).compose(request(PhraseKind.SOLO, 12));
        List<NoteEvent> line = solo.stream().filter(n -> n.velocity() >= 84).toList();
        List<NoteEvent> chords = solo.stream().filter(n -> n.velocity() < 60).toList();
        assertEquals(96, line.size());
        assertEquals(36, chords.size(), "one soft three-note chord a bar");
        assertTrue(solo.stream().allMatch(n -> n.instrument() == Instrument.PIANO));
    }

    @Test
    void theBassSoloStaysInTheBassRegister() {
        for (NoteEvent note : composer(Instrument.BASS, 7).compose(request(PhraseKind.SOLO, 12))) {
            assertTrue(note.midi() >= 36 && note.midi() <= 60, "bass note " + note.midi());
        }
    }

    @Test
    void theDrumsDoNotPlayALineSoTheyAreLeftAlone() {
        PhraseComposer drums = Composers.defaultFor(Instrument.DRUMS, new Random(1));
        assertSame(drums, SoloModelComposer.wrap(drums, EIGHTHS, new Random(1), 1.0));
    }

    @Test
    void reportsTheInstrumentItPlays() {
        assertEquals(Instrument.SAX, composer(Instrument.SAX, 1).instrument());
        assertEquals(Instrument.BASS, composer(Instrument.BASS, 1).instrument());
    }
}
