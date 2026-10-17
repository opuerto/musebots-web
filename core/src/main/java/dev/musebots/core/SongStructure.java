package dev.musebots.core;

import java.util.List;

/**
 * The shared description of a song that the leader hands to the rest of the ensemble. {@code ending} is the bar
 * the tune finishes on, usually the tonic.
 */
public record SongStructure(String title, int tempoBpm, int beatsPerBar, List<Section> sections, ChordBar ending) {

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

    /** The final bar: the chord the tune ends on. */
    public List<ChordBar> home() {
        return List.of(ending);
    }

    /** The shape of the tune as letters, e.g. "AAB". */
    public String form() {
        return sections.stream().map(Section::name).collect(java.util.stream.Collectors.joining());
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
