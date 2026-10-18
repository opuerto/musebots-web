package dev.musebots.learning;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Which solos of a corpus to learn from: the ones the band can use, optionally of one instrument and tempo range. */
public record Selection(Set<String> instruments, double minTempo, double maxTempo, int minNotes) {

    /** Every usable solo of every instrument. */
    public static final Selection ALL = new Selection(Set.of(), 0, Double.MAX_VALUE, 20);

    private static final Set<String> SAX = Set.of("as", "ts", "ss", "bs", "ts-c");

    /** An instrument group ("sax", "trumpet", "trombone", "clarinet", "all") or a comma-separated list of the database's codes. */
    public static Set<String> instrumentsNamed(String name) {
        String key = name == null ? "all" : name.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "", "all" -> Set.of();
            case "sax" -> SAX;
            case "trumpet" -> Set.of("tp", "cor");
            case "trombone" -> Set.of("tb");
            case "clarinet" -> Set.of("cl", "bcl");
            default -> Arrays.stream(key.split(",")).map(String::trim).collect(Collectors.toUnmodifiableSet());
        };
    }

    /** The solos the band can use: in 4/4, with a chord chart it can read, long enough, and matching this selection. */
    public List<Corpus.Solo> apply(Corpus corpus) {
        return corpus.solos().stream()
                .filter(solo -> solo.beatsPerBar() == 4)
                .filter(solo -> solo.notes().size() >= minNotes)
                .filter(solo -> instruments.isEmpty() || instruments.contains(solo.instrument()))
                .filter(solo -> solo.tempoBpm() == null || (solo.tempoBpm() >= minTempo && solo.tempoBpm() <= maxTempo))
                .filter(solo -> ChordTimeline.of(solo).isPresent())
                .toList();
    }
}
