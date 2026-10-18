package dev.musebots.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.musebots.core.Chord;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SoloMetricsTest {

    private static final Chord F7 = Chord.parse("F7"); // F A C E-flat; scale F G A-flat A B-flat C D E-flat

    private static SoloMetrics.Line line(int bars, TickNote... notes) {
        return new SoloMetrics.Line(List.of(notes), tick -> F7, bars);
    }

    private static TickNote note(int tick, int pitch) {
        return new TickNote(tick, 6, pitch);
    }

    @Test
    void measuresHowOftenTheStrongBeatsLandOnChordTones() {
        // Beats 1 and 3 (ticks 0 and 24): F is a chord tone; B (pitch class 11) is not. Beat 2 does not count either way.
        SoloMetrics.Scalars scalars = SoloMetrics.scalars(line(1, note(0, 65), note(12, 67), note(24, 71), note(36, 69)));
        assertEquals(0.5, scalars.chordToneOnStrongBeat(), 1e-12);
    }

    @Test
    void measuresHowOftenNotesLeaveTheChordsScale() {
        // F, G and A are in the scale of F7, B natural is not: one note in four.
        SoloMetrics.Scalars scalars = SoloMetrics.scalars(line(1, note(0, 65), note(12, 67), note(24, 71), note(36, 69)));
        assertEquals(0.25, scalars.outOfScale(), 1e-12);
    }

    @Test
    void measuresDensityPerBar() {
        assertEquals(4.0, SoloMetrics.scalars(line(1, note(0, 65), note(12, 67), note(24, 71), note(36, 69))).notesPerBar(), 1e-12);
        assertEquals(2.0, SoloMetrics.scalars(line(2, note(0, 65), note(60, 67), note(72, 71), note(84, 69))).notesPerBar(), 1e-12);
    }

    @Test
    void measuresTheSizeOfTheLeaps() {
        // Intervals +2, +4, -2: mean size 8/3, two of the three are steps, none are leaps of five or more.
        SoloMetrics.Scalars scalars = SoloMetrics.scalars(line(1, note(0, 65), note(12, 67), note(24, 71), note(36, 69)));
        assertEquals(8.0 / 3, scalars.meanAbsInterval(), 1e-12);
        assertEquals(2.0 / 3, scalars.stepwise(), 1e-12);
        assertEquals(0, scalars.leaps(), 1e-12);
    }

    @Test
    void countsBigJumpsAsLeaps() {
        SoloMetrics.Scalars scalars = SoloMetrics.scalars(line(1, note(0, 60), note(12, 72), note(24, 65), note(36, 66)));
        assertEquals(2.0 / 3, scalars.leaps(), 1e-12, "+12 and -7 are leaps; +1 is not");
    }

    @Test
    void aRepeatedFigureScoresAsRepetitionAndAClimbingScaleDoesNot() {
        // Intervals +2 +2 -1 -3 over and over: every four-interval figure turns up again.
        List<TickNote> figure = new ArrayList<>();
        int pitch = 60;
        int[] steps = {2, 2, -1, -3};
        figure.add(note(0, pitch));
        for (int i = 0; i < 12; i++) {
            pitch += steps[i % 4];
            figure.add(note((i + 1) * 12, pitch));
        }
        assertEquals(1.0, SoloMetrics.scalars(new SoloMetrics.Line(figure, tick -> F7, 4)).repetition(), 1e-12);

        // Intervals 1, 2, 3, 4, 5, 6, 7: nothing repeats.
        List<TickNote> climb = new ArrayList<>();
        pitch = 40;
        climb.add(note(0, pitch));
        for (int i = 1; i <= 7; i++) {
            pitch += i;
            climb.add(note(i * 12, pitch));
        }
        assertEquals(0.0, SoloMetrics.scalars(new SoloMetrics.Line(climb, tick -> F7, 2)).repetition(), 1e-12);
    }

    @Test
    void whatCannotBeMeasuredIsNotANumber() {
        SoloMetrics.Scalars empty = SoloMetrics.scalars(line(2));
        assertTrue(Double.isNaN(empty.chordToneOnStrongBeat()));
        assertTrue(Double.isNaN(empty.outOfScale()));
        assertTrue(Double.isNaN(empty.meanAbsInterval()));
        assertTrue(Double.isNaN(empty.repetition()));
        assertEquals(0.0, empty.notesPerBar(), 0);

        SoloMetrics.Scalars noStrongBeat = SoloMetrics.scalars(line(1, note(12, 65), note(36, 67)));
        assertTrue(Double.isNaN(noStrongBeat.chordToneOnStrongBeat()));
    }

    @Test
    void usesTheChordInForceAtEachNoteSoChangesInsideABarCount() {
        // F7 for the first two beats, then G-minor 7: B-flat is a chord tone of Gm7 (and on beat 3 it is strong).
        SoloMetrics.Line line = new SoloMetrics.Line(List.of(note(0, 65), note(24, 70)),
                tick -> tick < 24 ? Chord.parse("F7") : Chord.parse("Gm7"), 1);
        assertEquals(1.0, SoloMetrics.scalars(line).chordToneOnStrongBeat(), 1e-12);
    }

    @Test
    void pitchIsMeasuredAgainstTheChordSoTransposingChangesNothing() {
        SoloMetrics.Scalars inF = SoloMetrics.scalars(new SoloMetrics.Line(List.of(note(0, 65), note(24, 69), note(36, 72)), tick -> Chord.parse("F7"), 1));
        SoloMetrics.Scalars inBb = SoloMetrics.scalars(new SoloMetrics.Line(List.of(note(0, 70), note(24, 74), note(36, 77)), tick -> Chord.parse("Bb7"), 1));
        assertEquals(inF, inBb);
    }

    @Test
    void thePooledDistributionsPutEachNoteInItsBin() {
        SoloMetrics.Distributions distributions = new SoloMetrics.Distributions();
        // Notes at ticks 0, 6, 18 (gaps of 6 and 12) with pitches F, then up 20 semitones (clipped to +12), then down 3.
        distributions.add(line(1, note(0, 60), note(6, 80), new TickNote(18, 6, 77)));

        assertEquals(3, distributions.onset.total());
        assertEquals(1.0 / 3, distributions.onset.shares()[0], 1e-12); // tick 0
        assertEquals(2.0 / 3, distributions.onset.shares()[6], 1e-12); // ticks 6 and 18 are both the off-beat
        assertEquals(2, distributions.interval.total());
        assertEquals(0.5, distributions.interval.shares()[24], 1e-12, "+20 is clipped to +12 (bin 24)");
        assertEquals(0.5, distributions.interval.shares()[9], 1e-12, "-3 is bin 9");
        assertEquals(0.5, distributions.ioi.shares()[5], 1e-12, "a gap of 6 ticks");
        assertEquals(0.5, distributions.ioi.shares()[11], 1e-12, "a gap of 12 ticks");
        assertEquals(1.0 / 3, distributions.degree.shares()[7], 1e-12); // pitch 60 against root F (5): pitch class 0, degree 7
    }

    @Test
    void longGapsFallIntoTheCoarseBins() {
        SoloMetrics.Distributions distributions = new SoloMetrics.Distributions();
        distributions.add(line(4, note(0, 60), note(20, 60), note(56, 60), note(120, 60)));
        double[] shares = distributions.ioi.shares();
        assertEquals(1.0 / 3, shares[12], 1e-12); // 20 ticks: up to two beats
        assertEquals(1.0 / 3, shares[13], 1e-12); // 36 ticks: up to a bar
        assertEquals(1.0 / 3, shares[14], 1e-12); // 64 ticks: more than a bar
    }

    // ---- phrases, rests and the changes ----

    private static final Chord BB7 = Chord.parse("Bb7"); // B-flat D F A-flat; scale B-flat C D-flat D E-flat F G A-flat

    /** F7 for the first bar and B-flat7 from the second. */
    private static SoloMetrics.Line overTwoChords(int bars, TickNote... notes) {
        return new SoloMetrics.Line(List.of(notes), tick -> tick < 48 ? F7 : BB7, bars);
    }

    @Test
    void aPhraseEndsAtASilenceOfABeatOrMore() {
        // Four notes, a beat of silence (the last note ends at 42, the next starts at 60), then two more: two phrases of 3.
        SoloMetrics.Scalars scalars = SoloMetrics.scalars(line(2,
                note(0, 65), note(12, 67), note(24, 69), note(36, 70), note(60, 72), note(72, 74)));
        assertEquals(3.0, scalars.phraseNotes(), 1e-12);
        // Without the silence it is one run.
        assertEquals(6.0, SoloMetrics.scalars(line(2,
                note(0, 65), note(12, 67), note(24, 69), note(36, 70), note(48, 72), note(60, 74))).phraseNotes(), 1e-12);
    }

    @Test
    void restsAreTheShareOfTheTimeWithNothingSounding() {
        // One bar of 48 ticks with 24 ticks of notes (four of 6).
        assertEquals(0.5, SoloMetrics.scalars(line(1, note(0, 65), note(12, 67), note(24, 69), note(36, 70))).restShare(), 1e-12);
    }

    @Test
    void measuresWhetherTheFirstNoteOfANewChordIsAToneOfItAndHowFarItCameFrom() {
        // Bar 2 (over B-flat7) opens on D (chord tone) reached from E-flat by a whole step: both count.
        SoloMetrics.Scalars good = SoloMetrics.scalars(overTwoChords(2, note(36, 75), note(48, 74)));
        assertEquals(1.0, good.arrivalOnChordTone(), 1e-12);
        assertEquals(1.0, good.arrivalByStep(), 1e-12);
        // It opens on C (not a tone of B-flat7) reached from F7's A by a leap of three: neither counts.
        SoloMetrics.Scalars bad = SoloMetrics.scalars(overTwoChords(2, note(36, 69), note(48, 72)));
        assertEquals(0.0, bad.arrivalOnChordTone(), 1e-12);
        assertEquals(0.0, bad.arrivalByStep(), 1e-12);
        // No change of chord, no arrival to measure.
        assertTrue(Double.isNaN(SoloMetrics.scalars(line(2, note(36, 69), note(48, 72))).arrivalOnChordTone()));
    }

    @Test
    void measuresWhetherTheLastNoteOfABarAlreadyBelongsToTheNextChord() {
        // The last note of bar 1 is E-flat (pitch class 3): in B-flat7's scale, but not one of its chord tones.
        SoloMetrics.Scalars scale = SoloMetrics.scalars(overTwoChords(2, note(36, 63), note(48, 74)));
        assertEquals(1.0, scale.leadsIntoScale(), 1e-12);
        assertEquals(0.0, scale.leadsIntoChordTone(), 1e-12);
        // D is a tone of B-flat7.
        assertEquals(1.0, SoloMetrics.scalars(overTwoChords(2, note(36, 62), note(48, 74))).leadsIntoChordTone(), 1e-12);
        // B natural (pitch class 11) is not in B-flat7's scale.
        assertEquals(0.0, SoloMetrics.scalars(overTwoChords(2, note(36, 71), note(48, 74))).leadsIntoScale(), 1e-12);
    }
}
