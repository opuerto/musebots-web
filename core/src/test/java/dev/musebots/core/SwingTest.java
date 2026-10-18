package dev.musebots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SwingTest {

    @Test
    void theOffbeatEighthMovesToTwoThirdsOfTheBeat() {
        assertEquals(Swing.OFFBEAT, Swing.swung(0.5), 1e-12);
        assertEquals(0, Swing.swung(0), 1e-12);
        assertEquals(1, Swing.swung(1), 1e-12);
    }

    @Test
    void swingingNeverReordersNotes() {
        double previous = -1;
        for (int step = 0; step <= 1_000; step++) {
            double swung = Swing.swung(step / 1_000.0);
            assertTrue(swung >= previous, "swing must be monotonic");
            previous = swung;
        }
    }

    @Test
    void straightUndoesSwung() {
        for (int step = 0; step <= 100; step++) {
            double x = step / 100.0;
            assertEquals(x, Swing.straight(Swing.swung(x)), 1e-12);
        }
    }

    @Test
    void sixteenthsAlongsideEighthsMoveToo() {
        // On a grid of twelve steps a beat: step 3 (a sixteenth) and step 9 land on steps 4 and 10.
        assertEquals(4 / 12.0, Swing.swung(3 / 12.0), 1e-12);
        assertEquals(10 / 12.0, Swing.swung(9 / 12.0), 1e-12);
    }

    @Test
    void timesInBeatsKeepTheirWholeBeats() {
        assertEquals(3 + Swing.OFFBEAT, Swing.swungBeats(3.5), 1e-12);
        assertEquals(3.5, Swing.straightBeats(3 + Swing.OFFBEAT), 1e-12);
        assertEquals(2.0, Swing.swungBeats(2.0), 1e-12);
    }
}
