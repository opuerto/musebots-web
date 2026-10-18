package dev.musebots.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.musebots.core.Chord;
import dev.musebots.core.Quality;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChordSymbolsTest {

    private static Chord chord(String symbol) {
        return ChordSymbols.parse(symbol).orElseThrow(() -> new AssertionError("could not read '" + symbol + "'"));
    }

    @Test
    void readsTheRootIncludingFlatsAndSharps() {
        assertEquals(10, chord("Bb7").root());
        assertEquals(3, chord("Eb7").root());
        assertEquals(6, chord("F#-7").root());
        assertEquals(6, chord("Gb7").root());
        assertEquals(11, chord("B7").root());
        assertEquals(0, chord("C").root());
    }

    @Test
    void mapsTheDatabasesSymbolsOntoTheThreeChordsTheBandKnows() {
        Map<String, Quality> expected = Map.ofEntries(
                Map.entry("Bb7", Quality.DOM7), Map.entry("F9", Quality.DOM7), Map.entry("D79b", Quality.DOM7),
                Map.entry("G7alt", Quality.DOM7), Map.entry("C13", Quality.DOM7), Map.entry("E7sus", Quality.DOM7),
                Map.entry("A+7", Quality.DOM7),
                Map.entry("C-7", Quality.MIN7), Map.entry("F-", Quality.MIN7), Map.entry("Am7b5", Quality.MIN7),
                Map.entry("Dm7b5", Quality.MIN7), Map.entry("Bbo7", Quality.MIN7), Map.entry("C-6", Quality.MIN7),
                Map.entry("Ebj7", Quality.MAJ7), Map.entry("Cj7", Quality.MAJ7), Map.entry("Bb6", Quality.MAJ7),
                Map.entry("Bb", Quality.MAJ7), Map.entry("C", Quality.MAJ7), Map.entry("Gj9", Quality.MAJ7),
                Map.entry("Cmaj7", Quality.MAJ7), Map.entry("CM7", Quality.MAJ7));
        expected.forEach((symbol, quality) -> assertEquals(quality, chord(symbol).quality(), symbol));
    }

    @Test
    void aFlatRootIsNotMistakenForAFlatNinth() {
        // "Bb" is B flat; "B7b9" is B with a flat nine.
        assertEquals(10, chord("Bb").root());
        assertEquals(11, chord("B7b9").root());
        assertEquals(Quality.DOM7, chord("B7b9").quality());
    }

    @Test
    void noChordAndNonsenseAreNotChords() {
        assertTrue(ChordSymbols.parse("NC").isEmpty());
        assertTrue(ChordSymbols.parse("nc").isEmpty());
        assertTrue(ChordSymbols.parse("").isEmpty());
        assertTrue(ChordSymbols.parse("  ").isEmpty());
        assertTrue(ChordSymbols.parse(null).isEmpty());
        assertTrue(ChordSymbols.parse("hello").isEmpty());
    }

    @Test
    void everySymbolInTheTopOfTheDatabasesChordListCanBeRead() {
        // The most common chord strings in the Weimar Jazz Database, as found in its beats table.
        for (String symbol : new String[] {"Bb7", "F7", "G7", "Eb7", "C7", "C-7", "G-7", "F-7", "D7", "Ab7", "D-7", "A-7", "A7",
                "Ebj7", "Bb-7", "Bbj7", "Eb-7", "Fj7", "Db7", "E7", "Abj7", "E-7", "Cj7", "Dbj7", "Gb7", "Am7b5", "C", "Dm7b5",
                "Gj7", "Bb", "B7", "F", "Gm7b5", "Bb6", "Dj7", "F-", "Ab", "Eb"}) {
            chord(symbol);
        }
    }
}
