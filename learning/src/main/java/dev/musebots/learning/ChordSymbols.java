package dev.musebots.learning;

import dev.musebots.core.Chord;
import dev.musebots.core.Quality;
import java.util.Optional;

/**
 * Reads the chord symbols of the Weimar Jazz Database ("Bb7", "C-7", "Ebj7", "Am7b5", "D79b", "G7alt") as the three
 * kinds of chord the band understands. That throws information away on purpose: a half-diminished chord becomes a minor
 * seventh, an altered dominant a plain dominant, a major triad a major seventh. It is what lets a solo transcribed over
 * rich harmony be played over the band's simpler charts.
 */
public final class ChordSymbols {

    private ChordSymbols() {
    }

    /** The chord for a symbol, or empty for "NC" (no chord), blanks and anything that does not start with a note name. */
    public static Optional<Chord> parse(String symbol) {
        if (symbol == null) {
            return Optional.empty();
        }
        String text = symbol.trim();
        if (text.isEmpty() || text.equalsIgnoreCase("NC") || "ABCDEFG".indexOf(text.charAt(0)) < 0) {
            return Optional.empty();
        }

        int root = switch (text.charAt(0)) {
            case 'C' -> 0;
            case 'D' -> 2;
            case 'E' -> 4;
            case 'F' -> 5;
            case 'G' -> 7;
            case 'A' -> 9;
            default -> 11; // B
        };
        int index = 1;
        if (index < text.length() && text.charAt(index) == '#') {
            root++;
            index++;
        } else if (index < text.length() && text.charAt(index) == 'b') {
            root--;
            index++;
        }
        return Optional.of(new Chord(Math.floorMod(root, 12), qualityOf(text.substring(index))));
    }

    /** What the rest of the symbol, after the note name, says about the chord. */
    private static Quality qualityOf(String suffix) {
        if (suffix.startsWith("-") || (suffix.startsWith("m") && !suffix.startsWith("maj"))) {
            return Quality.MIN7; // minor, minor seventh, half-diminished (m7b5), minor-major
        }
        if (suffix.startsWith("o")) {
            return Quality.MIN7; // diminished: closest of the three
        }
        if (suffix.contains("j") || suffix.startsWith("maj") || suffix.startsWith("6") || suffix.startsWith("M")) {
            return Quality.MAJ7; // major seventh, sixth, 69
        }
        if (suffix.isEmpty()) {
            return Quality.MAJ7; // a plain major triad
        }
        // Whatever is left, "7", "9", "13", "7alt", "79b", "sus", "+7": a dominant.
        return Quality.DOM7;
    }
}
