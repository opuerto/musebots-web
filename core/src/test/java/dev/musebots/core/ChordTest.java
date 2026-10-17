package dev.musebots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ChordTest {

    @Test
    void parsesRootsAndQualities() {
        assertEquals(new Chord(5, Quality.DOM7), Chord.parse("F7"));
        assertEquals(new Chord(10, Quality.DOM7), Chord.parse("Bb7"));
        assertEquals(new Chord(7, Quality.MIN7), Chord.parse("Gm7"));
        assertEquals(new Chord(0, Quality.MAJ7), Chord.parse("Cmaj7"));
        assertEquals(new Chord(6, Quality.DOM7), Chord.parse("F#7"));
    }

    @Test
    void flatOnCWrapsAround() {
        assertEquals(11, Chord.parse("Cb7").root());
    }

    @Test
    void chordTonesWrapPastTheOctave() {
        Chord bb7 = Chord.parse("Bb7");
        assertEquals(2, bb7.thirdPc()); // D
        assertEquals(5, bb7.fifthPc()); // F
        assertEquals(8, bb7.seventhPc()); // Ab
    }

    @Test
    void writesChordsTheWayALeadSheetDoes() {
        assertEquals("F7", Chord.parse("F7").symbol());
        assertEquals("Bb7", Chord.parse("Bb7").symbol());
        assertEquals("Em7", Chord.parse("Em7").symbol());
        assertEquals("CM7", Chord.parse("Cmaj7").symbol());
        assertEquals("Gb7", Chord.parse("F#7").symbol());
    }

    @Test
    void everySymbolParsesBackToTheSameChord() {
        for (Quality quality : Quality.values()) {
            for (int root = 0; root < 12; root++) {
                Chord chord = new Chord(root, quality);
                assertEquals(chord, Chord.parse(chord.symbol()), chord.symbol());
            }
        }
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> Chord.parse("H7"));
        assertThrows(IllegalArgumentException.class, () -> Chord.parse("F13"));
        assertThrows(IllegalArgumentException.class, () -> Chord.parse(""));
    }
}
