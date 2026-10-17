package dev.musebots.core;

import java.util.List;

/** A named part of a song (e.g. "A", "B") made of consecutive bars. */
public record Section(String name, List<ChordBar> bars) {

    public Section {
        bars = List.copyOf(bars);
    }
}
