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
    void everyInstrumentWritesEveryKindOverTheAABTuneToo() {
        SongStructure tune = Songs.song1(120);
        for (Instrument instrument : Instrument.values()) {
            for (PhraseKind kind : PhraseKind.values()) {
                int bars = kind == PhraseKind.INTRO ? 4 : kind == PhraseKind.ENDING ? 1 : 10;
                PhraseRequest request = new PhraseRequest(kind, tune.barsFor(kind, bars), tune.clock(), 1_000);
                for (NoteEvent note : composer(instrument, 6).compose(request)) {
                    assertTrue(note.startMs() >= request.startMs() && note.endMs() <= request.endMs(), instrument + " " + kind + " " + note);
                }
            }
        }
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
    void theSaxBacksOthersUpWithSoftPadsInsteadOfDroppingOut() {
        PhraseComposer sax = composer(Instrument.SAX, 1);
        List<NoteEvent> pads = sax.compose(request(PhraseKind.SUPPORT, 12));
        List<NoteEvent> melody = sax.compose(request(PhraseKind.HEAD, 12));

        assertEquals(6, pads.size(), "one pad every other bar");
        assertTrue(pads.stream().mapToInt(NoteEvent::velocity).max().orElseThrow()
                < melody.stream().mapToInt(NoteEvent::velocity).min().orElseThrow(), "pads should sit behind the melody");
        assertFalse(sax.compose(request(PhraseKind.SOLO, 12)).isEmpty());
        assertEquals(1, sax.compose(request(PhraseKind.ENDING, 1)).size());
    }

    @Test
    void theSaxPlaysTheIntroWhenItWinsIt() {
        // Only the musician who wins the intro is asked for it, and plays it alone: the sax must not leave four bars of silence.
        for (long seed = 0; seed < 20; seed++) {
            PhraseRequest request = request(PhraseKind.INTRO, 4);
            List<NoteEvent> intro = composer(Instrument.SAX, seed).compose(request);
            assertTrue(intro.size() >= 4, "seed " + seed + ": a melody over the four bars, not " + intro.size() + " notes");
            assertTrue(intro.stream().allMatch(n -> n.midi() >= SaxComposer.LOW && n.midi() <= SaxComposer.HIGH), "in the horn's range");
            assertTrue(intro.stream().anyMatch(n -> n.startMs() >= request.startMs() + request.clock().barsToMillis(3)),
                    "seed " + seed + ": it plays through to the last bar, leading into the head");
        }
    }

    @Test
    void padsAreGuideTonesAndTheLastOneEndsWithThePhrase() {
        for (long seed = 0; seed < 20; seed++) {
            PhraseRequest request = request(PhraseKind.SUPPORT, 11); // an odd number of bars
            List<NoteEvent> pads = composer(Instrument.SAX, seed).compose(request);
            for (int i = 0; i < pads.size(); i++) {
                Chord chord = request.bars().get(i * 2).chord();
                int pc = pads.get(i).midi() % 12;
                assertTrue(pc == chord.thirdPc() || pc == chord.seventhPc());
                assertTrue(pads.get(i).endMs() <= request.endMs());
            }
        }
    }

    @Test
    void behindADrumSoloTheRhythmSectionPlaysLighter() {
        for (Instrument instrument : new Instrument[] {Instrument.SAX, Instrument.PIANO, Instrument.BASS}) {
            List<NoteEvent> full = composer(instrument, 4).compose(request(PhraseKind.SUPPORT, 12));
            List<NoteEvent> light = composer(instrument, 4).compose(request(PhraseKind.SUPPORT_LIGHT, 12));
            assertFalse(light.isEmpty(), instrument + " dropped out behind the drum solo");
            assertTrue(light.stream().mapToInt(NoteEvent::velocity).max().orElseThrow()
                    < full.stream().mapToInt(NoteEvent::velocity).max().orElseThrow(), instrument + " was not softer");
            assertTrue(light.size() <= full.size(), instrument + " was not sparser");
        }
    }

    @Test
    void everyInstrumentPlaysSomethingInEveryTwoBarsOfEveryPartItBacksUp() {
        for (Instrument instrument : Instrument.values()) {
            for (PhraseKind kind : new PhraseKind[] {PhraseKind.SUPPORT, PhraseKind.SUPPORT_LIGHT, PhraseKind.HEAD, PhraseKind.SOLO}) {
                PhraseRequest request = request(kind, 12);
                List<NoteEvent> notes = composer(instrument, 9).compose(request);
                for (int bar = 0; bar < 12; bar += 2) {
                    long from = request.startMs() + clock.barsToMillis(bar);
                    long to = from + clock.barsToMillis(2);
                    assertTrue(notes.stream().anyMatch(n -> n.startMs() >= from && n.startMs() < to),
                            instrument + " " + kind + " is silent from bar " + bar);
                }
            }
        }
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
