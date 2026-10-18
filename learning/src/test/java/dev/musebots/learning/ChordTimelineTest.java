package dev.musebots.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.musebots.core.Chord;
import dev.musebots.core.Quality;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChordTimelineTest {

    static Corpus.Solo solo(List<Corpus.ChordChange> chords, List<TickNote> notes) {
        return new Corpus.Solo("s", "Someone", "A Tune", "as", "BEBOP", "SWING", "Bb-maj", 120.0, 4, chords, notes);
    }

    private static ChordTimeline timeline(Corpus.ChordChange... changes) {
        return ChordTimeline.of(solo(List.of(changes), List.of(new TickNote(0, 6, 60)))).orElseThrow();
    }

    @Test
    void aChordStaysInForceUntilTheNextChange() {
        ChordTimeline timeline = timeline(new Corpus.ChordChange(0, "Bb6"), new Corpus.ChordChange(48, "G7"), new Corpus.ChordChange(96, "C-7"));
        assertEquals(new Chord(10, Quality.MAJ7), timeline.at(0));
        assertEquals(new Chord(10, Quality.MAJ7), timeline.at(47));
        assertEquals(new Chord(7, Quality.DOM7), timeline.at(48));
        assertEquals(new Chord(0, Quality.MIN7), timeline.at(500));
    }

    @Test
    void beforeTheFirstChangeTheFirstChordApplies() {
        ChordTimeline timeline = timeline(new Corpus.ChordChange(0, "F7"));
        assertEquals(new Chord(5, Quality.DOM7), timeline.at(-12));
    }

    @Test
    void changesOutOfOrderAreSorted() {
        ChordTimeline timeline = timeline(new Corpus.ChordChange(48, "G7"), new Corpus.ChordChange(0, "C-7"));
        assertEquals(Quality.MIN7, timeline.at(10).quality());
        assertEquals(Quality.DOM7, timeline.at(60).quality());
    }

    @Test
    void noChordLeavesThePreviousChordInForce() {
        ChordTimeline timeline = timeline(new Corpus.ChordChange(0, "F7"), new Corpus.ChordChange(48, "NC"), new Corpus.ChordChange(96, "Bb7"));
        assertEquals(new Chord(5, Quality.DOM7), timeline.at(60));
        assertEquals(new Chord(10, Quality.DOM7), timeline.at(96));
    }

    @Test
    void aSoloWithNoReadableChordsHasNoTimeline() {
        assertTrue(ChordTimeline.of(solo(List.of(), List.of(new TickNote(0, 6, 60)))).isEmpty());
        assertTrue(ChordTimeline.of(solo(List.of(new Corpus.ChordChange(0, "NC")), List.of(new TickNote(0, 6, 60)))).isEmpty());
    }
}
