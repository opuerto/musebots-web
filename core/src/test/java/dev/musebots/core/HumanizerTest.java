package dev.musebots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class HumanizerTest {

    private static final long START = 10_000;

    private static PhraseRequest request(PhraseKind kind, int tempo, int bars) {
        SongStructure song = Songs.bluesInF(tempo);
        return new PhraseRequest(kind, song.barsFor(kind, bars), song.clock(), START);
    }

    private static List<NoteEvent> written(Instrument instrument, PhraseRequest request, long seed) {
        return Composers.defaultFor(instrument, new Random(seed)).compose(request);
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private static double sd(List<Double> values) {
        double mean = mean(values);
        return Math.sqrt(values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / values.size());
    }

    /** How far each note moved, in milliseconds, matching the humanized notes to the written ones one for one. */
    private static List<Double> startShifts(List<NoteEvent> before, List<NoteEvent> after) {
        List<Double> shifts = new ArrayList<>();
        for (int i = 0; i < before.size(); i++) {
            shifts.add((double) (after.get(i).startMs() - before.get(i).startMs()));
        }
        return shifts;
    }

    // ---- swing that follows the tempo ----

    @Test
    void swingGetsStraighterAsTheTempoRises() {
        assertEquals(0.75, Swing.offbeatAtTempo(60), 1e-12, "3:1 up to 100 bpm");
        assertEquals(0.75, Swing.offbeatAtTempo(100), 1e-12);
        assertEquals(2.0 / 3, Swing.offbeatAtTempo(200), 1e-12, "the classic 2:1 at 200");
        assertEquals(0.5, Swing.offbeatAtTempo(300), 1e-12, "straight at 300");
        assertEquals(0.5, Swing.offbeatAtTempo(400), 1e-12, "and never straighter than straight");
        double previous = 1;
        for (int tempo = 60; tempo <= 320; tempo += 10) {
            double offbeat = Swing.offbeatAtTempo(tempo);
            assertTrue(offbeat <= previous + 1e-12, "swing must not increase with tempo");
            previous = offbeat;
        }
    }

    @Test
    void retargetingMovesTheOffbeatAndNothingElse() {
        assertEquals(0.75, Swing.retarget(Swing.OFFBEAT, 0.75), 1e-12);
        assertEquals(0, Swing.retarget(0, 0.75), 1e-12);
        assertEquals(1, Swing.retarget(1, 0.75), 1e-12);
        assertEquals(Swing.OFFBEAT, Swing.retarget(Swing.OFFBEAT, Swing.OFFBEAT), 1e-12, "no change if the target is the standard swing");
        double previous = -1;
        for (int step = 0; step <= 1_000; step++) {
            double moved = Swing.retarget(step / 1_000.0, 0.75);
            assertTrue(moved >= previous, "notes must keep their order");
            previous = moved;
        }
        assertEquals(3 + 0.75, Swing.retargetBeats(3 + Swing.OFFBEAT, 0.75), 1e-12);
    }

    @Test
    void theOffbeatsOfAMediumTempoTuneMoveLaterAndTheBeatsStayPut() {
        PhraseRequest request = request(PhraseKind.SUPPORT, 120, 12); // a beat is 500 ms; swing is 3:1 at this tempo
        List<NoteEvent> notes = List.of(
                new NoteEvent(Instrument.DRUMS, 51, START, 100, 80),
                new NoteEvent(Instrument.DRUMS, 51, START + 500, 100, 80),
                new NoteEvent(Instrument.DRUMS, 51, START + 500 + Math.round(Swing.OFFBEAT * 500), 100, 80)); // the skip note of beat 2
        List<NoteEvent> moved = Humanizer.standard().apply(notes, request, new Random(1));

        // Remove the feel's own jitter by comparing averages over many runs.
        double onBeat = 0;
        double offBeat = 0;
        for (int run = 0; run < 400; run++) {
            List<NoteEvent> played = Humanizer.standard().apply(notes, request, new Random(run));
            onBeat += played.get(1).startMs() - (START + 500);
            offBeat += played.get(2).startMs() - (START + 500 + Math.round(Swing.OFFBEAT * 500));
        }
        assertEquals(0, onBeat / 400, 1.0, "the beat does not move on average");
        // 3:1 puts the off-beat at 0.75 of the beat: 375 ms, against 333 ms for 2:1: about 42 ms later.
        assertEquals(500 * (Swing.offbeatAtTempo(120) - Swing.OFFBEAT), offBeat / 400, 2.0);
        assertEquals(notes.size(), moved.size());
    }

    @Test
    void atTwoHundredBpmTheStandardSwingIsLeftAlone() {
        PhraseRequest request = request(PhraseKind.SUPPORT, 200, 12);
        List<NoteEvent> notes = written(Instrument.DRUMS, request, 1);
        double offBeatShift = 0;
        int count = 0;
        for (int i = 0; i < notes.size(); i++) {
            if ((notes.get(i).startMs() - START) % 300 != 0) { // an off-beat note
                for (int run = 0; run < 50; run++) {
                    offBeatShift += Humanizer.standard().apply(notes, request, new Random(run)).get(i).startMs() - notes.get(i).startMs();
                    count++;
                }
                break;
            }
        }
        assertEquals(0, offBeatShift / count, 2.0);
    }

    // ---- timing feel ----

    @Test
    void eachInstrumentHasItsOwnFeel() {
        PhraseRequest request = request(PhraseKind.SUPPORT, 200, 24); // 200 bpm leaves the swing alone, so only the feel is left
        for (Instrument instrument : new Instrument[] {Instrument.BASS, Instrument.DRUMS}) {
            List<NoteEvent> notes = written(instrument, request, 3);
            List<Double> shifts = startShifts(notes, Humanizer.standard().apply(notes, request, new Random(7)));
            Humanizer.Feel feel = Humanizer.feelOf(instrument, PhraseKind.SUPPORT);
            assertEquals(feel.biasMs(), mean(shifts), 1.6, instrument + " should sit " + feel.biasMs() + " ms from the beat on average");
            assertTrue(sd(shifts) <= feel.jitterMs() * 1.5 + 2 && sd(shifts) > 0.4 * feel.jitterMs(), instrument + " spread " + sd(shifts));
        }
    }

    @Test
    void thePianoSitsBehindTheBeatAndRollsItsChordsOnTopOfThat() {
        PhraseRequest request = request(PhraseKind.SUPPORT, 200, 24);
        List<NoteEvent> notes = written(Instrument.PIANO, request, 3);
        List<NoteEvent> played = Humanizer.standard().apply(notes, request, new Random(7));
        Humanizer.Feel feel = Humanizer.feelOf(Instrument.PIANO, PhraseKind.SUPPORT);

        List<Double> lowest = new ArrayList<>();
        List<Double> all = startShifts(notes, played);
        for (int i = 0; i < notes.size(); i++) {
            final int note = i;
            boolean lowestOfItsChord = notes.stream().noneMatch(other -> other.startMs() == notes.get(note).startMs() && other.midi() < notes.get(note).midi());
            if (lowestOfItsChord) {
                lowest.add(all.get(i));
            }
        }
        assertEquals(feel.biasMs(), mean(lowest), 1.6, "the bottom note of a chord sits at the piano's bias");
        assertTrue(mean(all) > mean(lowest) + 2, "the upper voices come later, which is the roll");
    }

    @Test
    void theBassIsAheadOfTheBeatAndTheHornBehindIt() {
        Humanizer.Feel bass = Humanizer.feelOf(Instrument.BASS, PhraseKind.SUPPORT);
        Humanizer.Feel horn = Humanizer.feelOf(Instrument.SAX, PhraseKind.SOLO);
        assertTrue(bass.biasMs() < 0, "bass ahead");
        assertTrue(horn.biasMs() > 0, "horn behind");
        assertEquals(0, Humanizer.feelOf(Instrument.DRUMS, PhraseKind.SUPPORT).biasMs(), "the drums keep the beat");
        assertTrue(horn.biasMs() > Humanizer.feelOf(Instrument.SAX, PhraseKind.SUPPORT).biasMs(), "a soloing horn lays back more than when it backs up");
    }

    @Test
    void theEndingIsPlayedMoreTightlyThanTheRestOfTheTune() {
        for (Instrument instrument : Instrument.values()) {
            assertTrue(Humanizer.feelOf(instrument, PhraseKind.ENDING).jitterMs() < Humanizer.feelOf(instrument, PhraseKind.SUPPORT).jitterMs(), instrument.name());
            assertEquals(0, Humanizer.feelOf(instrument, PhraseKind.ENDING).biasMs(), "everyone lands on the beat together");
        }
    }

    @Test
    void notesStartedTogetherMoveTogetherAndAPianoChordRollsUpwards() {
        PhraseRequest request = request(PhraseKind.SUPPORT, 200, 4);
        List<NoteEvent> chord = List.of(
                new NoteEvent(Instrument.PIANO, 72, START, 400, 70), new NoteEvent(Instrument.PIANO, 60, START, 400, 70),
                new NoteEvent(Instrument.PIANO, 66, START, 400, 70));
        for (int run = 0; run < 50; run++) {
            List<NoteEvent> played = Humanizer.standard().apply(chord, request, new Random(run));
            List<NoteEvent> bottomUp = played.stream().sorted(Comparator.comparingInt(NoteEvent::midi)).toList();
            assertTrue(bottomUp.get(0).startMs() <= bottomUp.get(1).startMs() && bottomUp.get(1).startMs() <= bottomUp.get(2).startMs(), "rolled bottom to top");
            assertTrue(bottomUp.get(2).startMs() - bottomUp.get(0).startMs() <= 12, "a roll, not an arpeggio");
        }
    }

    @Test
    void aSingleDrumHitIsNotRolled() {
        PhraseRequest request = request(PhraseKind.SUPPORT, 200, 4);
        List<NoteEvent> hits = List.of(new NoteEvent(Instrument.DRUMS, 36, START, 60, 80), new NoteEvent(Instrument.DRUMS, 51, START, 60, 80));
        for (int run = 0; run < 30; run++) {
            List<NoteEvent> played = Humanizer.standard().apply(hits, request, new Random(run));
            assertEquals(played.get(0).startMs(), played.get(1).startMs(), "kick and ride land together");
        }
    }

    // ---- dynamics and lengths ----

    @Test
    void velocitiesVaryButStayOnAverageWhereTheyWereWritten() {
        PhraseRequest request = request(PhraseKind.SUPPORT, 200, 24);
        List<NoteEvent> notes = written(Instrument.BASS, request, 1);
        List<NoteEvent> played = Humanizer.standard().apply(notes, request, new Random(2));
        List<Double> changes = new ArrayList<>();
        for (int i = 0; i < notes.size(); i++) {
            changes.add((double) (played.get(i).velocity() - notes.get(i).velocity()));
        }
        assertEquals(0, mean(changes), 1.0);
        assertTrue(sd(changes) > 2, "velocities should vary");
        assertTrue(played.stream().allMatch(n -> n.velocity() >= 1 && n.velocity() <= 127));
    }

    @Test
    void aHornPhraseSwellsInTheMiddleAndSoftensAtTheEnds() {
        PhraseRequest request = request(PhraseKind.SOLO, 200, 24);
        // The same note, at the same written volume, at every eighth of a four-bar phrase.
        List<NoteEvent> notes = new ArrayList<>();
        for (int phrase = 0; phrase < 6; phrase++) {
            for (int eighth = 0; eighth < 32; eighth++) {
                notes.add(new NoteEvent(Instrument.SAX, 69, START + request.clock().barsToMillis(4 * phrase) + eighth * 150L, 100, 90));
            }
        }
        double atTheEdges = 0;
        double inTheMiddle = 0;
        for (int run = 0; run < 100; run++) {
            List<NoteEvent> played = Humanizer.standard().apply(notes, request, new Random(run));
            for (int i = 0; i < played.size(); i++) {
                int eighth = i % 32;
                if (eighth < 3 || eighth >= 29) {
                    atTheEdges += played.get(i).velocity();
                } else if (eighth >= 13 && eighth < 19) {
                    inTheMiddle += played.get(i).velocity();
                }
            }
        }
        double edges = atTheEdges / (100 * 6 * 6);
        double middle = inTheMiddle / (100 * 6 * 6);
        assertTrue(middle - edges > 3, "the middle of the phrase (" + middle + ") should be louder than its ends (" + edges + ")");
    }

    @Test
    void theDrumsDoNotSwellAsAPhraseDoes() {
        PhraseRequest request = request(PhraseKind.SOLO, 200, 24);
        List<NoteEvent> notes = new ArrayList<>();
        for (int eighth = 0; eighth < 32; eighth++) {
            notes.add(new NoteEvent(Instrument.DRUMS, 38, START + eighth * 150L, 50, 90));
        }
        double edges = 0;
        double middle = 0;
        for (int run = 0; run < 200; run++) {
            List<NoteEvent> played = Humanizer.standard().apply(notes, request, new Random(run));
            for (int i = 0; i < 32; i++) {
                if (i < 3 || i >= 29) {
                    edges += played.get(i).velocity();
                } else if (i >= 13 && i < 19) {
                    middle += played.get(i).velocity();
                }
            }
        }
        assertEquals(edges / (200 * 6), middle / (200 * 6), 1.5);
    }

    // ---- what must never change ----

    @Test
    void theSameSeedGivesTheSamePerformanceAndADifferentOneGivesADifferentOne() {
        PhraseRequest request = request(PhraseKind.SOLO, 140, 12);
        List<NoteEvent> notes = written(Instrument.SAX, request, 4);
        assertEquals(Humanizer.standard().apply(notes, request, new Random(5)), Humanizer.standard().apply(notes, request, new Random(5)));
        assertTrue(!Humanizer.standard().apply(notes, request, new Random(5)).equals(Humanizer.standard().apply(notes, request, new Random(6))));
    }

    @Test
    void turnedOffItReturnsExactlyWhatWasWritten() {
        PhraseRequest request = request(PhraseKind.SOLO, 120, 12);
        List<NoteEvent> notes = written(Instrument.SAX, request, 4);
        assertSame(notes, Humanizer.off().apply(notes, request, new Random(1)));
        assertTrue(Humanizer.off().apply(List.of(), request, new Random(1)).isEmpty());
    }

    @Test
    void everyInstrumentKindAndTempoKeepsItsNotesAndItsPitches() {
        for (int tempo : new int[] {60, 120, 200, 300}) {
            for (Instrument instrument : Instrument.values()) {
                for (PhraseKind kind : PhraseKind.values()) {
                    int bars = kind == PhraseKind.INTRO ? 4 : kind == PhraseKind.ENDING ? 1 : 12;
                    PhraseRequest request = request(kind, tempo, bars);
                    List<NoteEvent> notes = written(instrument, request, 2);
                    List<NoteEvent> played = Humanizer.standard().apply(notes, request, new Random(3));
                    String what = instrument + " " + kind + " at " + tempo;
                    assertEquals(notes.size(), played.size(), what);
                    for (int i = 0; i < notes.size(); i++) {
                        assertEquals(notes.get(i).midi(), played.get(i).midi(), what);
                        assertEquals(instrument, played.get(i).instrument(), what);
                    }
                }
            }
        }
    }

    @Test
    void nothingStartsMoreThanAFewMillisecondsEarlyOrRunsPastTheEndOfThePhrase() {
        for (int tempo : new int[] {60, 120, 200, 300}) {
            for (Instrument instrument : Instrument.values()) {
                for (PhraseKind kind : PhraseKind.values()) {
                    int bars = kind == PhraseKind.INTRO ? 4 : kind == PhraseKind.ENDING ? 1 : 12;
                    PhraseRequest request = request(kind, tempo, bars);
                    List<NoteEvent> notes = written(instrument, request, 2);
                    List<NoteEvent> played = Humanizer.standard().apply(notes, request, new Random(3));
                    for (int i = 0; i < played.size(); i++) {
                        String what = instrument + " " + kind + " at " + tempo + ": " + played.get(i);
                        assertTrue(played.get(i).startMs() >= request.startMs() - 26, "starts too early: " + what);
                        assertTrue(played.get(i).endMs() <= Math.max(request.endMs(), notes.get(i).endMs()) + 1, "runs past the phrase: " + what);
                        assertTrue(played.get(i).durationMs() >= 1);
                    }
                }
            }
        }
    }

    @Test
    void aSingleLineStaysASingleLineEvenAtFastTempos() {
        for (int tempo : new int[] {120, 200, 300, 960}) {
            for (Instrument instrument : new Instrument[] {Instrument.SAX, Instrument.BASS}) {
                PhraseRequest request = request(PhraseKind.SOLO, tempo, 12);
                List<NoteEvent> notes = written(instrument, request, 5);
                for (long seed = 0; seed < 5; seed++) {
                    List<NoteEvent> played = Humanizer.standard().apply(notes, request, new Random(seed));
                    List<NoteEvent> inOrder = played.stream().sorted(Comparator.comparingLong(NoteEvent::startMs)).toList();
                    assertEquals(notes.stream().map(NoteEvent::midi).toList(), inOrder.stream().map(NoteEvent::midi).collect(Collectors.toList()),
                            instrument + " notes were reordered at " + tempo);
                    for (int i = 1; i < inOrder.size(); i++) {
                        assertTrue(inOrder.get(i).startMs() >= inOrder.get(i - 1).endMs(), instrument + " overlaps at " + tempo + " bpm, note " + i);
                    }
                }
            }
        }
    }

    @Test
    void aHumanizedComposerPlaysThroughTheSameInterface() {
        PhraseRequest request = request(PhraseKind.SOLO, 140, 12);
        PhraseComposer plain = Composers.defaultFor(Instrument.SAX, new Random(4));
        PhraseComposer human = new HumanizedComposer(Composers.defaultFor(Instrument.SAX, new Random(4)), Humanizer.standard(), new Random(9));
        assertEquals(Instrument.SAX, human.instrument());
        List<NoteEvent> before = plain.compose(request);
        List<NoteEvent> after = human.compose(request);
        assertEquals(before.size(), after.size());
        assertTrue(!before.equals(after));
    }
}
