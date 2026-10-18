package dev.musebots.learning;

import dev.musebots.core.Chord;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The chord in force at any tick of a solo. A "no chord" marking leaves the previous chord in force. */
public final class ChordTimeline {

    private final int[] ticks;
    private final Chord[] chords;

    private ChordTimeline(int[] ticks, Chord[] chords) {
        this.ticks = ticks;
        this.chords = chords;
    }

    /** The timeline of a solo, or empty if none of its chords can be read. */
    public static Optional<ChordTimeline> of(Corpus.Solo solo) {
        List<Corpus.ChordChange> changes = new ArrayList<>(solo.chords());
        changes.sort((a, b) -> Integer.compare(a.tick(), b.tick()));

        List<Integer> at = new ArrayList<>();
        List<Chord> chord = new ArrayList<>();
        for (Corpus.ChordChange change : changes) {
            Optional<Chord> parsed = ChordSymbols.parse(change.symbol());
            if (parsed.isPresent()) {
                at.add(change.tick());
                chord.add(parsed.get());
            }
        }
        if (chord.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ChordTimeline(at.stream().mapToInt(Integer::intValue).toArray(), chord.toArray(new Chord[0])));
    }

    /** The chord at this tick; before the first change, the first chord. */
    public Chord at(int tick) {
        int found = 0;
        for (int i = 0; i < ticks.length; i++) {
            if (ticks[i] <= tick) {
                found = i;
            } else {
                break;
            }
        }
        return chords[found];
    }
}
