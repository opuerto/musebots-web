package dev.musebots.learning;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Transcribed solos on a straight grid of twelve ticks to the beat, with the chords underneath. It is the plain JSON that
 * {@code tools/wjd_to_corpus.py} writes from the Weimar Jazz Database. A corpus carries the licence of the data it was made
 * from (the Weimar Jazz Database is ODbL): keep it out of anything you publish unless you are allowed to share it.
 */
public record Corpus(String source, int ticksPerBeat, List<Solo> solos) {

    public static final int TICKS_PER_BEAT = 12;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public Corpus {
        solos = List.copyOf(solos);
        if (ticksPerBeat != TICKS_PER_BEAT) {
            throw new IllegalArgumentException("this code works on " + TICKS_PER_BEAT + " ticks to a beat, not " + ticksPerBeat);
        }
    }

    /** One solo. {@code instrument} is the database's code ("as", "ts", "tp"...); chords are in its own notation. */
    public record Solo(String id, String performer, String title, String instrument, String style, String rhythmFeel,
                       String key, Double tempoBpm, int beatsPerBar, List<ChordChange> chords, List<TickNote> notes) {

        public Solo {
            chords = List.copyOf(chords);
            notes = List.copyOf(notes);
        }

        /** The bars the solo covers, counted from the first. */
        public int bars() {
            int last = notes.isEmpty() ? 0 : notes.get(notes.size() - 1).tick();
            return Math.max(1, Math.floorDiv(last, beatsPerBar * TICKS_PER_BEAT) + 1);
        }
    }

    /** The chord, written as the database writes it, from this tick until the next change. */
    public record ChordChange(int tick, String symbol) {
    }

    public static Corpus read(Path file) throws IOException {
        return MAPPER.readValue(file.toFile(), Corpus.class);
    }

    public static void write(Corpus corpus, Path file) throws IOException {
        MAPPER.writeValue(file.toFile(), corpus);
    }

    public int noteCount() {
        return solos.stream().mapToInt(solo -> solo.notes().size()).sum();
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }

    static void ensureParent(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
    }
}
