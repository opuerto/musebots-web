package dev.musebots.core;

import java.util.List;

/** The shared description of a song that the leader hands to the rest of the ensemble. */
public record SongStructure(String title, int tempoBpm, int beatsPerBar, List<Section> sections) {

    public SongStructure {
        sections = List.copyOf(sections);
    }

    /** Every bar of one pass through the form, in order. */
    public List<ChordBar> allBars() {
        return sections.stream().flatMap(s -> s.bars().stream()).toList();
    }

    public MusicClock clock() {
        return new MusicClock(tempoBpm, beatsPerBar);
    }
}
