package dev.musebots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MusicClockTest {

    @Test
    void convertsBeatsAndBarsAt120Bpm() {
        MusicClock clock = new MusicClock(120, 4);
        assertEquals(500, clock.beatsToMillis(1));
        assertEquals(2000, clock.barsToMillis(1));
        assertEquals(750, clock.beatsToMillis(1.5));
    }

    @Test
    void rejectsNonPositiveTempo() {
        assertThrows(IllegalArgumentException.class, () -> new MusicClock(0, 4));
    }
}
