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
    void aBeatIsExactlyAsLongAsItIsNotJustItsRoundedMilliseconds() {
        MusicClock clock = new MusicClock(960, 4); // 62.5 ms a beat
        assertEquals(62.5, clock.millisPerBeat(), 1e-12);
        assertEquals(63, clock.beatsToMillis(1), "rounded, which is why it must not be multiplied");
        assertEquals(3000, clock.beatsToMillis(48), "but 48 beats are exactly 3000 ms, not 48 x 63");
    }

    @Test
    void rejectsNonPositiveTempo() {
        assertThrows(IllegalArgumentException.class, () -> new MusicClock(0, 4));
    }
}
