package dev.musebots.learning;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.musebots.core.Swing;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RhythmGridTest {

    private static List<TickNote> notes(int... ticks) {
        List<TickNote> line = new ArrayList<>();
        for (int i = 0; i < ticks.length; i++) {
            int next = i + 1 < ticks.length ? ticks[i + 1] : ticks[i] + 6;
            line.add(new TickNote(ticks[i], next - ticks[i], 60 + i));
        }
        return line;
    }

    private static List<Integer> ticks(List<TickNote> line) {
        return line.stream().map(TickNote::tick).toList();
    }

    @Test
    void aSwungOffBeatIsAnEighthWhereverTheTranscriberPutIt() {
        // The same swung eighths, transcribed with the off-beat on the 6, the 8 and the 9 of the beat.
        assertEquals(List.of(0, 6, 12, 18, 24, 30), ticks(RhythmGrid.clean(notes(0, 6, 12, 20, 24, 33))));
    }

    @Test
    void realTripletsAndSixteenthsAreKept() {
        assertEquals(List.of(0, 4, 8, 12), ticks(RhythmGrid.clean(notes(0, 4, 8, 12))), "a triplet");
        assertEquals(List.of(0, 3, 6, 9, 12), ticks(RhythmGrid.clean(notes(0, 3, 6, 9, 12))), "four sixteenths");
        assertEquals(List.of(0, 4, 8, 12), ticks(RhythmGrid.clean(notes(0, 5, 8, 12))), "a triplet played a little late");
    }

    @Test
    void notesBetweenTheLinesMoveToTheNearestOneAndTheLineStaysSingle() {
        List<TickNote> cleaned = RhythmGrid.clean(notes(1, 7, 11, 13));
        assertEquals(List.of(0, 6, 12), ticks(cleaned), "11 moves to the next beat, where 13 also lands: one note is kept");
        for (int i = 0; i + 1 < cleaned.size(); i++) {
            assertTrue(cleaned.get(i).end() <= cleaned.get(i + 1).tick(), "no note runs into the next");
        }
        for (TickNote note : RhythmGrid.clean(notes(2, 5, 10, 14, 23, 25))) {
            assertTrue(List.of(0, 3, 4, 6, 8, 9).contains(note.tick() % 12), "on a line: " + note.tick());
        }
    }

    @Test
    void onlyBeatsOfEighthsSwing() {
        // A beat of eighths, a triplet, then four sixteenths.
        double[] played = RhythmGrid.playedBeats(notes(0, 6, 12, 16, 20, 24, 27, 30, 33));
        assertArrayEquals(new double[] {0, Swing.OFFBEAT, 1, 1 + 1 / 3.0, 1 + 2 / 3.0, 2, 2.25, 2.5, 2.75}, played, 1e-9,
                "the eighths swing; the triplet and the sixteenths are even");
    }
}
