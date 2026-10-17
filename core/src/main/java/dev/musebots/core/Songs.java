package dev.musebots.core;

import java.util.List;

/** A small built-in repertoire. */
public final class Songs {

    public static final String BLUES = "blues";
    public static final String SONG_1 = "song1";

    /** Every tune on offer, in the order a menu should list them. */
    public static final List<String> IDS = List.of(BLUES, SONG_1);

    private Songs() {
    }

    /** The tune with this id, or null if there is none. */
    public static SongStructure byId(String id, int tempoBpm) {
        return switch (id) {
            case BLUES -> bluesInF(tempoBpm);
            case SONG_1 -> song1(tempoBpm);
            default -> null;
        };
    }

    /** A 12-bar blues in F, a good first tune: every jazz musician knows it. Written as three lines of four bars. */
    public static SongStructure bluesInF(int tempoBpm) {
        return new SongStructure("Blues in F", tempoBpm, 4, List.of(
                section("A", "F7", "Bb7", "F7", "F7"),
                section("B", "Bb7", "Bb7", "F7", "D7"),
                section("C", "Gm7", "C7", "F7", "C7")),
                ChordBar.of("F7"));
    }

    /** A ten-bar tune in AAB form: two statements of a ii-V-ii-V, then two bars of home. */
    public static SongStructure song1(int tempoBpm) {
        return new SongStructure("Song 1", tempoBpm, 4, List.of(
                section("A", "Em7", "A7", "Dm7", "G7"),
                section("A", "Em7", "A7", "Dm7", "G7"),
                section("B", "CM7", "CM7")),
                ChordBar.of("CM7"));
    }

    private static Section section(String name, String... chords) {
        return new Section(name, java.util.Arrays.stream(chords).map(ChordBar::of).toList());
    }
}
