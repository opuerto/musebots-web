package dev.musebots.learning;

import dev.musebots.core.Chord;
import dev.musebots.core.Quality;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.random.RandomGenerator;

/**
 * A baseline model of jazz solos that learns by counting, with no neural network: the thing a neural network has to beat.
 *
 * <p>It models <b>when</b> the next note comes and <b>which</b> note it is separately:
 * <ul>
 *   <li><b>Rhythm.</b> Given where the note sits in the bar and how long it has been since the previous one, how long is the
 *       gap to the next note and how long does this one last?</li>
 *   <li><b>Pitch.</b> Given the chord, where the previous note sat <em>relative to the chord's root</em>, whether this is a
 *       strong beat, and the last leap, how far does the line move? Measuring against the chord makes the model key-independent:
 *       a solo over F7 teaches it about every dominant chord.</li>
 * </ul>
 * Each is a table of counts at several levels of detail. Sampling uses the most detailed level that has been seen often
 * enough, and backs off to coarser ones for situations the data has hardly covered.
 */
public record MarkovModel(Meta meta, Map<String, Map<String, Integer>> rhythm, Map<String, Map<String, Integer>> pitch) {

    /** What the model was trained on, kept with it so an evaluation can use the solos it never saw. */
    public record Meta(String source, List<String> instruments, int solos, int notes, int minContext, long seed,
                       List<String> trainIds, List<String> testIds) {

        public Meta {
            instruments = List.copyOf(instruments);
            trainIds = List.copyOf(trainIds);
            testIds = List.copyOf(testIds);
        }
    }

    private static final int TPB = Corpus.TICKS_PER_BEAT;
    private static final int BAR = 4 * TPB;
    private static final int MAX_TICKS = 4 * TPB; // longer gaps and notes are all "a bar or more"
    private static final int MAX_LEAP = 12;
    private static final int RESAMPLE_ATTEMPTS = 12;

    // ---- training ----

    /** Counts what the solos do. Only solos in 4/4 with a readable chord chart are used. */
    public static MarkovModel train(List<Corpus.Solo> solos, Meta meta) {
        Map<String, Map<String, Integer>> rhythm = new TreeMap<>();
        Map<String, Map<String, Integer>> pitch = new TreeMap<>();
        int usedSolos = 0;
        int usedNotes = 0;

        for (Corpus.Solo solo : solos) {
            Optional<ChordTimeline> chords = ChordTimeline.of(solo);
            if (solo.beatsPerBar() != 4 || chords.isEmpty()) {
                continue;
            }
            List<TickNote> notes = distinctOnsets(solo.notes());
            if (notes.size() < 2) {
                continue;
            }
            usedSolos++;
            usedNotes += notes.size();

            for (int i = 0; i < notes.size(); i++) {
                TickNote note = notes.get(i);
                int tickInBar = Math.floorMod(note.tick(), BAR);

                if (i + 1 < notes.size()) { // rhythm: the gap to the next note, and how long this one lasts
                    int ioi = notes.get(i + 1).tick() - note.tick();
                    int previousIoi = i > 0 ? note.tick() - notes.get(i - 1).tick() : TPB;
                    String outcome = Math.min(ioi, MAX_TICKS) + ":" + Math.min(Math.min(note.duration(), ioi), MAX_TICKS);
                    for (String key : rhythmKeys(tickInBar, previousIoi)) {
                        count(rhythm, key, outcome);
                    }
                }
                if (i >= 1) { // pitch: how far the line moves from the previous note
                    Chord chord = chords.get().at(note.tick());
                    int previousPitch = notes.get(i - 1).pitch();
                    int previousInterval = i >= 2 ? previousPitch - notes.get(i - 2).pitch() : 0;
                    String outcome = Integer.toString(clip(note.pitch() - previousPitch));
                    for (String key : pitchKeys(chord.quality(), degree(previousPitch, chord), tickInBar, previousInterval)) {
                        count(pitch, key, outcome);
                    }
                }
            }
        }
        if (usedNotes == 0) {
            throw new IllegalArgumentException("no usable solos to learn from");
        }
        Meta used = new Meta(meta.source(), meta.instruments(), usedSolos, usedNotes, meta.minContext(), meta.seed(),
                meta.trainIds(), meta.testIds());
        return new MarkovModel(used, rhythm, pitch);
    }

    /** The notes in time order, keeping one note where several start together (the line is a single voice). */
    private static List<TickNote> distinctOnsets(List<TickNote> notes) {
        List<TickNote> sorted = new ArrayList<>(notes);
        sorted.sort(Comparator.comparingInt(TickNote::tick));
        List<TickNote> distinct = new ArrayList<>();
        for (TickNote note : sorted) {
            if (distinct.isEmpty() || distinct.get(distinct.size() - 1).tick() != note.tick()) {
                distinct.add(note);
            }
        }
        return distinct;
    }

    private static void count(Map<String, Map<String, Integer>> table, String key, String outcome) {
        table.computeIfAbsent(key, k -> new TreeMap<>()).merge(outcome, 1, Integer::sum);
    }

    // ---- the situations the tables are indexed by, from most to least detailed ----

    private static String[] rhythmKeys(int tickInBar, int previousIoi) {
        int beat = tickInBar / TPB;
        int tick = tickInBar % TPB;
        int previous = Math.min(previousIoi, MAX_TICKS);
        return new String[] {"r|" + beat + "|" + tick + "|" + previous, "r|" + tick + "|" + previous, "r|" + tick, "r"};
    }

    private static String[] pitchKeys(Quality quality, int previousDegree, int tickInBar, int previousInterval) {
        char q = switch (quality) {
            case DOM7 -> 'D';
            case MIN7 -> 'm';
            case MAJ7 -> 'M';
        };
        int position = positionClass(tickInBar);
        return new String[] {
                "p|" + q + "|" + previousDegree + "|" + position + "|" + leapBucket(previousInterval),
                "p|" + q + "|" + previousDegree + "|" + position,
                "p|" + q + "|" + position,
                "p"};
    }

    /** 0 on beats 1 and 3, 1 on beats 2 and 4, 2 anywhere else. */
    private static int positionClass(int tickInBar) {
        if (tickInBar % (2 * TPB) == 0) {
            return 0;
        }
        return tickInBar % TPB == 0 ? 1 : 2;
    }

    private static int leapBucket(int interval) {
        if (interval <= -5) {
            return -2;
        }
        if (interval < 0) {
            return -1;
        }
        if (interval == 0) {
            return 0;
        }
        return interval <= 4 ? 1 : 2;
    }

    private static int degree(int pitch, Chord chord) {
        return Math.floorMod(pitch - chord.root(), 12);
    }

    private static int clip(int interval) {
        return Math.max(-MAX_LEAP, Math.min(MAX_LEAP, interval));
    }

    // ---- generating ----

    /**
     * Writes a line over these chords (one per bar, in 4/4).
     *
     * @param startNear  the pitch the line starts near
     * @param low        the lowest pitch it may use
     * @param high       the highest pitch it may use
     * @param temperature 1 follows the data; below 1 sticks to what is most common; above 1 is more adventurous
     */
    public List<TickNote> generate(List<Chord> barChords, int beatsPerBar, int startNear, int low, int high,
                                   double temperature, RandomGenerator random) {
        if (beatsPerBar != 4) {
            throw new UnsupportedOperationException("the model was learned in 4/4");
        }
        int minContext = meta.minContext();
        int total = barChords.size() * BAR;
        List<TickNote> notes = new ArrayList<>();

        int position = 0;
        int previousPitch = startNear;
        int previousInterval = 0;
        int previousIoi = TPB;
        while (position < total) {
            Chord chord = barChords.get(position / BAR);
            int tickInBar = position % BAR;

            String[] pitchContexts = pitchKeys(chord.quality(), degree(previousPitch, chord), tickInBar, previousInterval);
            int pitch = choosePitch(pitchContexts, previousPitch, low, high, temperature, random);

            String[] gap = sample(rhythm, rhythmKeys(tickInBar, previousIoi), minContext, temperature, random).split(":");
            int ioi = Math.max(1, Integer.parseInt(gap[0]));
            int duration = Math.max(1, Math.min(Integer.parseInt(gap[1]), Math.min(ioi, total - position)));

            notes.add(new TickNote(position, duration, pitch));
            previousInterval = pitch - previousPitch;
            previousPitch = pitch;
            previousIoi = ioi;
            position += ioi;
        }
        return notes;
    }

    /** Samples a leap; if it leaves the instrument's range, tries again, and in the end folds the note back by octaves. */
    private int choosePitch(String[] contexts, int previousPitch, int low, int high, double temperature, RandomGenerator random) {
        int candidate = previousPitch;
        for (int attempt = 0; attempt < RESAMPLE_ATTEMPTS; attempt++) {
            candidate = previousPitch + Integer.parseInt(sample(pitch, contexts, meta.minContext(), temperature, random));
            if (candidate >= low && candidate <= high) {
                return candidate;
            }
        }
        while (candidate > high) {
            candidate -= 12;
        }
        while (candidate < low) {
            candidate += 12;
        }
        return Math.min(candidate, high);
    }

    /** Picks an outcome from the most detailed table that has been seen at least {@code minContext} times. */
    static String sample(Map<String, Map<String, Integer>> table, String[] contexts, int minContext, double temperature,
                         RandomGenerator random) {
        Map<String, Integer> chosen = null;
        for (String context : contexts) {
            Map<String, Integer> outcomes = table.get(context);
            if (outcomes == null) {
                continue;
            }
            chosen = outcomes; // remember the coarsest table with any data, in case none has enough
            if (outcomes.values().stream().mapToInt(Integer::intValue).sum() >= minContext) {
                break;
            }
        }
        if (chosen == null) {
            throw new IllegalStateException("the model has nothing to go on");
        }

        double sharpen = 1.0 / Math.max(temperature, 1e-3);
        double sum = 0;
        for (int count : chosen.values()) {
            sum += Math.pow(count, sharpen);
        }
        double pick = random.nextDouble() * sum;
        String last = null;
        for (Map.Entry<String, Integer> outcome : chosen.entrySet()) {
            last = outcome.getKey();
            pick -= Math.pow(outcome.getValue(), sharpen);
            if (pick <= 0) {
                return last;
            }
        }
        return last;
    }

    // ---- files ----

    public static MarkovModel read(Path file) throws IOException {
        return Corpus.mapper().readValue(file.toFile(), MarkovModel.class);
    }

    public void write(Path file) throws IOException {
        Corpus.ensureParent(file);
        Corpus.mapper().writeValue(file.toFile(), this);
    }

    /** A line summarising the model, for a person. */
    public String describe() {
        return "learned from " + meta.solos() + " solos (" + meta.notes() + " notes, instruments: "
                + (meta.instruments().isEmpty() ? "all" : String.join(", ", meta.instruments())) + "); "
                + rhythm.size() + " rhythm and " + pitch.size() + " pitch situations; " + meta.testIds().size()
                + " solos held out for testing";
    }
}
