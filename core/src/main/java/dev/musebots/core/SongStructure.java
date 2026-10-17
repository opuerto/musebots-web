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

    /** The chords of one pass through the form, repeated to fill {@code bars} bars. */
    public List<ChordBar> cycle(int bars) {
        List<ChordBar> form = allBars();
        return java.util.stream.IntStream.range(0, bars).mapToObj(i -> form.get(i % form.size())).toList();
    }

    /** The last {@code bars} bars of the form: the turnaround that leads back to the top. */
    public List<ChordBar> turnaround(int bars) {
        List<ChordBar> form = allBars();
        return form.subList(Math.max(0, form.size() - bars), form.size());
    }

    /** The home chord, for the final bar. */
    public List<ChordBar> home() {
        return List.of(allBars().get(0));
    }

    /** The chords to write over {@code bars} bars of a phrase of the given kind. */
    public List<ChordBar> barsFor(PhraseKind kind, int bars) {
        return switch (kind) {
            case INTRO -> turnaround(bars);
            case ENDING -> home();
            default -> cycle(bars);
        };
    }

    public MusicClock clock() {
        return new MusicClock(tempoBpm, beatsPerBar);
    }
}
