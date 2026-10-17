package dev.musebots.core;

import java.util.List;

/** A small built-in repertoire. */
public final class Songs {

    private Songs() {
    }

    /** A 12-bar blues in F, a good first tune: every jazz musician knows it. */
    public static SongStructure bluesInF(int tempoBpm) {
        List<ChordBar> form = List.of(
                ChordBar.of("F7"), ChordBar.of("Bb7"), ChordBar.of("F7"), ChordBar.of("F7"),
                ChordBar.of("Bb7"), ChordBar.of("Bb7"), ChordBar.of("F7"), ChordBar.of("D7"),
                ChordBar.of("Gm7"), ChordBar.of("C7"), ChordBar.of("F7"), ChordBar.of("C7"));
        return new SongStructure("Blues in F", tempoBpm, 4, List.of(new Section("A", form)));
    }
}
