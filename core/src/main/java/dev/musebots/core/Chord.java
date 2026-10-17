package dev.musebots.core;

import java.util.Map;

/** A chord: a root pitch class (0 = C ... 11 = B) and a quality. */
public record Chord(int root, Quality quality) {

    private static final Map<Character, Integer> LETTERS =
            Map.of('C', 0, 'D', 2, 'E', 4, 'F', 5, 'G', 7, 'A', 9, 'B', 11);

    public Chord {
        if (root < 0 || root > 11) {
            throw new IllegalArgumentException("root must be a pitch class 0-11, was " + root);
        }
    }

    /** Pitch class (0-11) of the given chord tone interval above the root. */
    public int pitchClass(int interval) {
        return (root + interval) % 12;
    }

    public int thirdPc() {
        return pitchClass(quality.third());
    }

    public int fifthPc() {
        return pitchClass(quality.fifth());
    }

    public int seventhPc() {
        return pitchClass(quality.seventh());
    }

    /** Parses symbols such as "F7", "Bb7", "Gm7", "Cmaj7". */
    public static Chord parse(String symbol) {
        if (symbol == null || symbol.isEmpty()) {
            throw new IllegalArgumentException("empty chord symbol");
        }
        Integer base = LETTERS.get(symbol.charAt(0));
        if (base == null) {
            throw new IllegalArgumentException("unknown chord root in '" + symbol + "'");
        }
        int index = 1;
        int root = base;
        if (index < symbol.length() && symbol.charAt(index) == '#') {
            root++;
            index++;
        } else if (index < symbol.length() && symbol.charAt(index) == 'b') {
            root--;
            index++;
        }
        String suffix = symbol.substring(index);
        Quality quality = switch (suffix) {
            case "7" -> Quality.DOM7;
            case "m7", "min7" -> Quality.MIN7;
            case "maj7", "M7" -> Quality.MAJ7;
            default -> throw new IllegalArgumentException("unknown chord quality in '" + symbol + "'");
        };
        return new Chord(Math.floorMod(root, 12), quality);
    }
}
