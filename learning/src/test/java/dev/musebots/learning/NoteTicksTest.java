package dev.musebots.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.musebots.core.Instrument;
import dev.musebots.core.MusicClock;
import dev.musebots.core.NoteEvent;
import dev.musebots.core.Swing;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoteTicksTest {

    private final MusicClock clock = new MusicClock(120, 4); // a beat is 500 ms

    @Test
    void anOnBeatNoteIsOnATickMultipleOfTwelve() {
        List<TickNote> notes = NoteTicks.fromEvents(List.of(
                new NoteEvent(Instrument.SAX, 65, 10_000, 450, 90), new NoteEvent(Instrument.SAX, 67, 10_500, 450, 90)), 10_000, clock);
        assertEquals(List.of(0, 12), notes.stream().map(TickNote::tick).toList());
    }

    @Test
    void aSwungOffbeatGoesBackToTheStraightEighth() {
        long swung = 10_000 + Math.round(Swing.OFFBEAT * 500);
        List<TickNote> notes = NoteTicks.fromEvents(List.of(new NoteEvent(Instrument.SAX, 65, swung, 150, 90)), 10_000, clock);
        assertEquals(6, notes.get(0).tick(), "two thirds of the way through a beat, swung, is the middle of a straight beat");
    }

    @Test
    void aSwungSixteenthGoesBackToTheStraightGrid() {
        long swung = 10_000 + Math.round(Swing.swung(3.0 / 12) * 500); // the second sixteenth of the beat, swung
        assertEquals(3, NoteTicks.fromEvents(List.of(new NoteEvent(Instrument.SAX, 65, swung, 50, 90)), 10_000, clock).get(0).tick());
    }

    @Test
    void durationsAreMeasuredOnTheStraightGridToo() {
        // An eighth note on the beat: it ends at the swung off-beat, which is the straight tick 6.
        long end = Math.round(Swing.OFFBEAT * 500);
        List<TickNote> notes = NoteTicks.fromEvents(List.of(new NoteEvent(Instrument.SAX, 65, 10_000, end, 90)), 10_000, clock);
        assertEquals(6, notes.get(0).duration());
    }

    @Test
    void notesAreReturnedInTimeOrderWhateverOrderTheyArrivedIn() {
        List<TickNote> notes = NoteTicks.fromEvents(List.of(
                new NoteEvent(Instrument.PIANO, 72, 11_000, 100, 90), new NoteEvent(Instrument.PIANO, 60, 10_000, 100, 90)), 10_000, clock);
        assertEquals(List.of(60, 72), notes.stream().map(TickNote::pitch).toList());
    }

    @Test
    void aNoteIsAlwaysAtLeastATickLong() {
        assertEquals(1, NoteTicks.fromEvents(List.of(new NoteEvent(Instrument.SAX, 65, 10_000, 1, 90)), 10_000, clock).get(0).duration());
    }

    @Test
    void theTicksAreRightAtTemposWhereABeatIsNotAWholeNumberOfMilliseconds() {
        for (int tempo : new int[] {140, 130, 960, 97}) {
            MusicClock odd = new MusicClock(tempo, 4);
            // Notes on every beat of twelve bars, written the way the band's composers write them.
            List<NoteEvent> events = new java.util.ArrayList<>();
            for (int beat = 0; beat < 48; beat++) {
                events.add(new NoteEvent(Instrument.BASS, 40, 5_000 + odd.beatsToMillis(beat), 50, 90));
            }
            List<TickNote> ticks = NoteTicks.fromEvents(events, 5_000, odd);
            for (int beat = 0; beat < 48; beat++) {
                assertEquals(beat * 12, ticks.get(beat).tick(), "tempo " + tempo + ", beat " + beat);
            }
        }
    }

    @Test
    void whatTheModelComposerPlaysComesBackAsTheTicksItWrote() {
        MarkovModel model = MarkovModel.train(List.of(Solos.solo("a", "Eighths", Solos.eighthsOverF7(8))),
                new MarkovModel.Meta("t", List.of(), 0, 0, 3, 1, List.of(), List.of()));
        java.util.Random random = new java.util.Random(3);
        List<dev.musebots.core.Chord> chords = java.util.Collections.nCopies(4, dev.musebots.core.Chord.parse("F7"));
        List<TickNote> written = model.generate(chords, 4, 69, 57, 81, 1.0, new java.util.Random(3));

        dev.musebots.core.PhraseComposer composer = SoloModelComposer.wrap(
                dev.musebots.core.Composers.defaultFor(Instrument.SAX, new java.util.Random(3)), model, new java.util.Random(3), 1.0);
        List<NoteEvent> played = composer.compose(new dev.musebots.core.PhraseRequest(dev.musebots.core.PhraseKind.SOLO,
                Evaluation.asBars(chords), clock, 0));
        List<TickNote> measured = NoteTicks.fromEvents(played, 0, clock);

        assertEquals(written.stream().map(TickNote::tick).toList(), measured.stream().map(TickNote::tick).toList(), "same onsets");
        assertEquals(written.stream().map(TickNote::pitch).toList(), measured.stream().map(TickNote::pitch).toList(), "same pitches");
    }
}
