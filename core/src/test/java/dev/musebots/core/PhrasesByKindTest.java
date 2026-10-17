package dev.musebots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** What every instrument writes for solos, support and the ending. */
class PhrasesByKindTest {

    private final SongStructure song = Songs.bluesInF(120);
    private final MusicClock clock = song.clock();

    private PhraseRequest request(PhraseKind kind, int bars) {
        return new PhraseRequest(kind, song.barsFor(kind, bars), clock, 1_000);
    }

    private static PhraseComposer composer(Instrument instrument, long seed) {
        return Composers.defaultFor(instrument, new Random(seed));
    }

    @Test
    void everyInstrumentWritesEveryKindInsideThePhraseWindow() {
        for (Instrument instrument : Instrument.values()) {
            for (PhraseKind kind : PhraseKind.values()) {
                PhraseRequest request = request(kind, kind == PhraseKind.INTRO ? 4 : kind == PhraseKind.ENDING ? 1 : 12);
                for (NoteEvent note : composer(instrument, 3).compose(request)) {
                    assertEquals(instrument, note.instrument());
                    assertTrue(note.startMs() >= request.startMs(), instrument + " " + kind + " starts early");
                    assertTrue(note.endMs() <= request.endMs(), instrument + " " + kind + " runs past the phrase: " + note);
                }
            }
        }
    }

    @Test
    void theSaxLaysOutExceptForTheHeadItsSoloAndTheEnding() {
        PhraseComposer sax = composer(Instrument.SAX, 1);
        assertTrue(sax.compose(request(PhraseKind.SUPPORT, 12)).isEmpty());
        assertTrue(sax.compose(request(PhraseKind.INTRO, 4)).isEmpty());
        assertFalse(sax.compose(request(PhraseKind.HEAD, 12)).isEmpty());
        assertFalse(sax.compose(request(PhraseKind.SOLO, 12)).isEmpty());
        assertEquals(1, sax.compose(request(PhraseKind.ENDING, 1)).size());
    }

    @Test
    void aSoloIsBusierThanTheMelody() {
        int head = 0;
        int solo = 0;
        for (long seed = 0; seed < 20; seed++) {
            head += composer(Instrument.SAX, seed).compose(request(PhraseKind.HEAD, 12)).size();
            solo += composer(Instrument.SAX, seed).compose(request(PhraseKind.SOLO, 12)).size();
        }
        assertTrue(solo > head * 1.3, "solo " + solo + " vs head " + head);
    }

    @Test
    void soloLinesAreMonophonicAndStayInRegister() {
        int[][] registers = {{57, 81}, {60, 84}, {36, 60}};
        Instrument[] lines = {Instrument.SAX, Instrument.PIANO, Instrument.BASS};
        for (int i = 0; i < lines.length; i++) {
            for (long seed = 0; seed < 15; seed++) {
                List<NoteEvent> notes = composer(lines[i], seed).compose(request(PhraseKind.SOLO, 12));
                // The piano's left-hand chords are the only simultaneous notes a soloist writes.
                List<NoteEvent> line = lines[i] == Instrument.PIANO
                        ? notes.stream().filter(n -> n.midi() >= 60).toList() : notes;
                long previousEnd = Long.MIN_VALUE;
                for (NoteEvent note : line) {
                    if (lines[i] != Instrument.PIANO || note.midi() >= registers[i][0]) {
                        assertTrue(note.midi() >= registers[i][0] && note.midi() <= registers[i][1], lines[i] + " " + note);
                    }
                }
                if (lines[i] != Instrument.PIANO) {
                    for (NoteEvent note : line) {
                        assertTrue(note.startMs() >= previousEnd, lines[i] + " overlaps at " + note);
                        previousEnd = note.endMs();
                    }
                }
            }
        }
    }

    @Test
    void theBassSoloLeavesTheWalkingPattern() {
        List<NoteEvent> walk = composer(Instrument.BASS, 5).compose(request(PhraseKind.SUPPORT, 12));
        List<NoteEvent> solo = composer(Instrument.BASS, 5).compose(request(PhraseKind.SOLO, 12));
        assertEquals(12 * 4, walk.size());
        assertTrue(solo.size() != walk.size());
    }

    @Test
    void theDrumSoloFillsWithTomsAndCrashesOnTheTopOfTheChorus() {
        Set<Integer> kit = Set.of(DrumComposer.KICK, DrumComposer.SNARE, DrumComposer.HI_HAT_PEDAL, DrumComposer.RIDE,
                DrumComposer.CRASH, DrumComposer.TOM_HIGH, DrumComposer.TOM_MID, DrumComposer.TOM_LOW, DrumComposer.TOM_FLOOR);
        boolean toms = false;
        for (long seed = 0; seed < 10; seed++) {
            List<NoteEvent> notes = composer(Instrument.DRUMS, seed).compose(request(PhraseKind.SOLO, 12));
            assertTrue(notes.stream().allMatch(n -> kit.contains(n.midi())));
            assertTrue(notes.stream().anyMatch(n -> n.midi() == DrumComposer.CRASH && n.startMs() == 1_000));
            toms |= notes.stream().anyMatch(n -> n.midi() == DrumComposer.TOM_MID);
        }
        assertTrue(toms, "never played a tom fill");
    }

    @Test
    void theEndingIsOneHitOnTheHomeChordThatEveryoneCanPlay() {
        long barMs = clock.barsToMillis(1);
        for (Instrument instrument : Instrument.values()) {
            PhraseRequest ending = request(PhraseKind.ENDING, 1);
            List<NoteEvent> notes = composer(instrument, 2).compose(ending);
            assertFalse(notes.isEmpty(), instrument + " has nothing to play at the end");
            assertTrue(notes.stream().allMatch(n -> n.startMs() == 1_000 && n.endMs() <= 1_000 + barMs));
        }
        // The home chord is F: the bass lands on an F.
        assertEquals(5, composer(Instrument.BASS, 2).compose(request(PhraseKind.ENDING, 1)).get(0).midi() % 12);
    }
}
