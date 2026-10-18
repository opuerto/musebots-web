package dev.musebots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** A solo is shaped like a player's: phrases with breaths, answers that echo, and a line that leads into the next chord. */
class PhrasedSoloTest {

    private final MusicClock clock = new MusicClock(120, 4);
    private final List<ChordBar> bars = Songs.byId(Songs.RHYTHM_CHANGES, 120).allBars();

    private List<NoteEvent> solo(long seed) {
        return new SaxComposer(new Random(seed)).compose(new PhraseRequest(PhraseKind.SOLO, bars, clock, 0));
    }

    private int barOf(NoteEvent note) {
        return (int) (note.startMs() / clock.barsToMillis(1));
    }

    @Test
    void theLineHasBreathsBetweenPhrases() {
        long beat = clock.beatsToMillis(1);
        int totalBreaths = 0;
        for (long seed = 0; seed < 20; seed++) {
            List<NoteEvent> notes = solo(seed);
            int breaths = 0;
            for (int i = 1; i < notes.size(); i++) {
                if (notes.get(i).startMs() - notes.get(i - 1).endMs() >= beat) {
                    breaths++;
                }
            }
            assertTrue(breaths >= 4, "a 32-bar solo should have at least a few phrases: " + breaths + " with seed " + seed);
            assertTrue(breaths <= bars.size(), "but not a rest after every note: " + breaths);
            totalBreaths += breaths;
        }
        // A silence of a beat is also left inside sparse bars, so there are more of these than the phrase breaths (about one in two bars).
        assertTrue(totalBreaths / 20.0 >= 12, "at least a phrase every other bar or so: " + totalBreaths / 20.0 + " in 32 bars");
    }

    @Test
    void breathingTrimsTheLastBarAndHoldsItsLastNote() {
        double[][] busy = Improviser.SOLO_CELLS[0];
        double[][] trimmed = Improviser.breathe(busy);
        assertTrue(trimmed.length < busy.length);
        double[] last = trimmed[trimmed.length - 1];
        assertTrue(last[0] + last[1] <= 3.0 + 1e-9, "the last note has finished by the third beat of the bar");
        assertTrue(last[1] >= 1.0, "and is held, not clipped");
        for (int i = 0; i < trimmed.length - 1; i++) {
            assertEquals(busy[i][0], trimmed[i][0], 1e-9, "the earlier notes are as they were");
        }
        assertEquals(1, Improviser.breathe(new double[][] {{0, 3.5}}).length, "a bar with a single note keeps it");
    }

    @Test
    void theLastNoteOfABarLeadsIntoTheNextChord() {
        int leads = 0;
        int inScale = 0;
        for (long seed = 0; seed < 30; seed++) {
            List<NoteEvent> notes = solo(seed);
            for (int i = 0; i + 1 < notes.size(); i++) {
                int bar = barOf(notes.get(i));
                if (barOf(notes.get(i + 1)) != bar + 1 || bar + 1 >= bars.size()) {
                    continue; // not the last note of its bar, with another bar to come
                }
                Chord next = bars.get(bar + 1).chord();
                if (next.equals(bars.get(bar).chord())) {
                    continue;
                }
                leads++;
                int pc = notes.get(i).midi() % 12;
                for (int scalePc : next.scalePitchClasses()) {
                    if (scalePc == pc) {
                        inScale++;
                        break;
                    }
                }
            }
        }
        assertTrue(leads > 100, "there are plenty of chord changes to lead into: " + leads);
        assertTrue((double) inScale / leads > 0.75, "the last note is in the next chord's scale: " + inScale + " of " + leads);
    }

    @Test
    void aBarThatFollowsALeadingNoteOpensOnTheGuideTheLineWasLeadingTo() {
        int opened = 0;
        for (long seed = 0; seed < 20; seed++) {
            List<NoteEvent> notes = solo(seed);
            for (NoteEvent note : notes) {
                if (note.startMs() % clock.barsToMillis(1) != 0 || note.startMs() == 0) {
                    continue; // only notes on the downbeat of a bar after the first
                }
                Chord chord = bars.get(barOf(note)).chord();
                int pc = note.midi() % 12;
                if (pc == chord.thirdPc() || pc == chord.seventhPc()) {
                    opened++;
                }
            }
        }
        assertTrue(opened > 100, "bars open on a third or a seventh: " + opened);
    }

    @Test
    void anAnswerRepeatsTheRhythmOfTheStatementBefore() {
        int repeats = 0;
        for (long seed = 0; seed < 20; seed++) {
            List<List<Long>> barRhythms = new ArrayList<>();
            List<NoteEvent> notes = solo(seed);
            for (int bar = 0; bar < bars.size(); bar++) {
                List<Long> rhythm = new ArrayList<>();
                for (NoteEvent note : notes) {
                    if (barOf(note) == bar) {
                        rhythm.add(note.startMs() - bar * clock.barsToMillis(1));
                    }
                }
                barRhythms.add(rhythm);
            }
            for (int bar = 2; bar < bars.size(); bar++) {
                if (!barRhythms.get(bar).isEmpty() && barRhythms.get(bar).equals(barRhythms.get(bar - 2))) {
                    repeats++;
                }
            }
        }
        assertTrue(repeats > 40, "answers echo statements: " + repeats + " bars repeat the rhythm from two bars before");
    }

    @Test
    void remainsASingleLineInRegisterThatEndsInsideItsPhrase() {
        long end = new PhraseRequest(PhraseKind.SOLO, bars, clock, 0).endMs();
        for (long seed = 0; seed < 20; seed++) {
            List<NoteEvent> notes = solo(seed);
            assertFalse(notes.isEmpty());
            for (int i = 0; i < notes.size(); i++) {
                NoteEvent note = notes.get(i);
                assertTrue(note.midi() >= SaxComposer.LOW && note.midi() <= SaxComposer.HIGH, "register " + note.midi());
                assertTrue(note.endMs() <= end, "inside the phrase");
                assertTrue(i == 0 || note.startMs() >= notes.get(i - 1).endMs(), "no overlap at " + i + " with seed " + seed);
            }
        }
    }

    @Test
    void isDeterministicForAGivenSeedAndVariesBetweenSeeds() {
        assertEquals(solo(5), solo(5));
        assertFalse(solo(5).equals(solo(6)));
    }

    @Test
    void theHeadIsNotPhrased() {
        // The head states the melody and fills every bar; only solos are cut into phrases.
        List<NoteEvent> head = new SaxComposer(new Random(1)).compose(new PhraseRequest(PhraseKind.HEAD, bars, clock, 0));
        for (int bar = 0; bar < bars.size(); bar++) {
            int in = bar;
            assertTrue(head.stream().anyMatch(note -> barOf(note) == in), "the head plays in bar " + bar);
        }
    }

    @Test
    void everyInstrumentThatSolosPlaysPhrases() {
        for (Instrument instrument : List.of(Instrument.SAX, Instrument.PIANO, Instrument.BASS)) {
            List<NoteEvent> notes = Composers.defaultFor(instrument, new Random(3))
                    .compose(new PhraseRequest(PhraseKind.SOLO, bars, clock, 0));
            long beat = clock.beatsToMillis(1);
            List<NoteEvent> line = notes.stream().filter(n -> n.velocity() >= 78).toList(); // the right hand, not the left-hand chords
            boolean breath = false;
            for (int i = 1; i < line.size(); i++) {
                breath |= line.get(i).startMs() - line.get(i - 1).endMs() >= beat;
            }
            assertTrue(breath, instrument + " takes a breath");
        }
    }
}
