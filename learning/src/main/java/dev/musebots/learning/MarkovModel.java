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
 * Each is a table of counts at several levels of detail, and the chances of the next note blend them all (see {@link Blend}):
 * a situation seen often follows its own counts, one the data has hardly covered leans on similar ones. Where the line sits in
 * the player's register is part of the situation too, so it learns to turn back from the top and the bottom of its range.
 *
 * <p>Two more things make a line sound like phrases rather than a stream of notes:
 * <ul>
 *   <li><b>Phrases.</b> A silence of a beat or more ends a phrase. Both tables also know how far into its phrase the line is
 *       (so it learns how long phrases run, and that the last note of one is placed differently from the first), and a
 *       generated line sometimes <em>repeats</em> its last phrase, fitted to the chord it now sits over: a motif and its answer.</li>
 *   <li><b>Landing.</b> When a note is the last before the chord changes, or the last of a phrase, a third table says where it
 *       sits relative to the <em>coming</em> chord. Its weight is combined with the leap table, so the line is drawn toward
 *       notes that belong to the chord that follows, as real players do, with no rule written by hand.</li>
 *   <li><b>Memory.</b> While it solos it remembers what it played after each figure, and leans a little on that, as players
 *       develop their own ideas ({@link Memory}).</li>
 * </ul>
 *
 * <p>{@link #surprise} measures how well it predicts real solos it never saw: the measure every change to it was judged by.
 */
public record MarkovModel(Meta meta, Map<String, Map<String, Integer>> rhythm, Map<String, Map<String, Integer>> pitch,
                          Map<String, Map<String, Integer>> landing) {

    /** A model written before phrases and landing existed has neither table; it still plays, without them. */
    public MarkovModel {
        landing = landing == null ? Map.of() : landing;
    }

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
    private static final int BREATH = TPB; // a silence this long ends a phrase
    private static final int MIN_REPLAYED_PHRASE = 3; // notes
    /** How often a new phrase repeats the one before, fitted to the chord; real solos repeat their figures about a quarter of the time. */
    public static final double DEFAULT_MOTIF_REPEAT = 0.35;

    // ---- training ----

    /** Counts what the solos do. Only solos in 4/4 with a readable chord chart are used. */
    public static MarkovModel train(List<Corpus.Solo> solos, Meta meta) {
        Map<String, Map<String, Integer>> rhythm = new TreeMap<>();
        Map<String, Map<String, Integer>> pitch = new TreeMap<>();
        Map<String, Map<String, Integer>> landing = new TreeMap<>();
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
            int centre = median(notes); // the middle of this player's register in this solo

            int depth = 0; // how many notes of the phrase came before this one
            for (int i = 0; i < notes.size(); i++) {
                TickNote note = notes.get(i);
                int tickInBar = Math.floorMod(note.tick(), BAR);
                if (i > 0) {
                    depth = restAfter(notes, i - 1) >= BREATH ? 0 : depth + 1;
                }
                boolean ends = i + 1 == notes.size() || restAfter(notes, i) >= BREATH;

                if (i + 1 < notes.size()) { // rhythm: the gap to the next note, and how long this one lasts
                    int ioi = notes.get(i + 1).tick() - note.tick();
                    int previousIoi = i > 0 ? note.tick() - notes.get(i - 1).tick() : TPB;
                    int earlierIoi = i > 1 ? notes.get(i - 1).tick() - notes.get(i - 2).tick() : TPB;
                    String outcome = Math.min(ioi, MAX_TICKS) + ":" + Math.min(Math.min(note.duration(), ioi), MAX_TICKS);
                    for (String key : rhythmKeys(tickInBar, previousIoi, earlierIoi, depth)) {
                        count(rhythm, key, outcome);
                    }
                }
                if (i >= 1) { // pitch: how far the line moves from the previous note
                    Chord chord = chords.get().at(note.tick());
                    int previousPitch = notes.get(i - 1).pitch();
                    int previousInterval = i >= 2 ? previousPitch - notes.get(i - 2).pitch() : 0;
                    int earlierInterval = i >= 3 ? notes.get(i - 2).pitch() - notes.get(i - 3).pitch() : 0;
                    String outcome = Integer.toString(clip(note.pitch() - previousPitch));
                    for (String key : pitchKeys(chord.quality(), degree(previousPitch, chord), tickInBar, previousInterval, earlierInterval,
                            phase(depth, ends), register(previousPitch, centre))) {
                        count(pitch, key, outcome);
                    }
                }
                if (i + 1 < notes.size()) { // landing: where a note sits against the chord that comes next
                    Chord chord = chords.get().at(note.tick());
                    Chord next = chords.get().at(notes.get(i + 1).tick());
                    if (isLanding(chord, next, ends)) {
                        String outcome = Integer.toString(degree(note.pitch(), next));
                        for (String key : landingKeys(next.quality(), ends)) {
                            count(landing, key, outcome);
                        }
                    }
                }
            }
        }
        if (usedNotes == 0) {
            throw new IllegalArgumentException("no usable solos to learn from");
        }
        Meta used = new Meta(meta.source(), meta.instruments(), usedSolos, usedNotes, meta.minContext(), meta.seed(),
                meta.trainIds(), meta.testIds());
        return new MarkovModel(used, rhythm, pitch, landing);
    }

    /** The silence after note {@code i}, in ticks: from where it stops sounding (or the next note starts, if it overlaps) to the next note. */
    private static int restAfter(List<TickNote> notes, int i) {
        if (i + 1 >= notes.size()) {
            return 0;
        }
        TickNote note = notes.get(i);
        int ioi = notes.get(i + 1).tick() - note.tick();
        return ioi - Math.min(note.duration(), ioi);
    }

    /** A note is a landing when the chord changes after it or it ends a phrase: the moments a player aims somewhere. */
    private static boolean isLanding(Chord chord, Chord next, boolean endsPhrase) {
        return endsPhrase || !chord.equals(next);
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

    private static String[] rhythmKeys(int tickInBar, int previousIoi, int earlierIoi, int depth) {
        int beat = tickInBar / TPB;
        int tick = tickInBar % TPB;
        int previous = Math.min(previousIoi, MAX_TICKS);
        return new String[] {
                "r2|d" + depthBucket(depth) + "|" + tick + "|" + previous + "|" + Math.min(earlierIoi, MAX_TICKS),
                "r|d" + depthBucket(depth) + "|" + tick + "|" + previous,
                "r|" + beat + "|" + tick + "|" + previous, "r|" + tick + "|" + previous, "r|" + tick, "r"};
    }

    private static String[] pitchKeys(Quality quality, int previousDegree, int tickInBar, int previousInterval, int earlierInterval,
                                      int phase, int register) {
        char q = switch (quality) {
            case DOM7 -> 'D';
            case MIN7 -> 'm';
            case MAJ7 -> 'M';
        };
        int position = positionClass(tickInBar);
        return new String[] {
                "p2|" + q + "|" + previousDegree + "|" + position + "|" + leapBucket(previousInterval) + "|f" + phase + "|g" + register,
                "p|" + q + "|" + previousDegree + "|" + position + "|" + leapBucket(previousInterval) + "|f" + phase,
                "p|" + q + "|" + previousDegree + "|" + position + "|" + leapBucket(previousInterval),
                "p|" + q + "|" + previousDegree + "|" + position,
                "p|" + q + "|" + position,
                "p3|" + leapBucket(previousInterval) + "|" + leapBucket(earlierInterval) + "|g" + register,
                "p"};
    }

    private static String[] landingKeys(Quality quality, boolean endsPhrase) {
        char q = switch (quality) {
            case DOM7 -> 'D';
            case MIN7 -> 'm';
            case MAJ7 -> 'M';
        };
        return new String[] {"l|" + q + "|" + (endsPhrase ? 1 : 0), "l|" + q, "l"};
    }

    /** Where a pitch sits against the middle of the line's register: well below, below, around it, above, well above. */
    private static int register(int pitch, int centre) {
        int offset = pitch - centre;
        return offset <= -8 ? -2 : offset <= -3 ? -1 : offset < 3 ? 0 : offset < 8 ? 1 : 2;
    }

    private static int median(List<TickNote> notes) {
        int[] pitches = notes.stream().mapToInt(TickNote::pitch).sorted().toArray();
        return pitches[pitches.length / 2];
    }

    /** How far into its phrase a note is: the first, the second, the third, then in widening steps. */
    private static int depthBucket(int depth) {
        if (depth <= 2) {
            return depth;
        }
        return depth <= 4 ? 3 : depth <= 8 ? 4 : depth <= 16 ? 5 : 6;
    }

    /** 0 for the first note of a phrase, 2 for the last, 1 for the rest. */
    private static int phase(int depth, boolean endsPhrase) {
        return endsPhrase ? 2 : depth == 0 ? 0 : 1;
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
        return generate(barChords, beatsPerBar, startNear, low, high, temperature, DEFAULT_MOTIF_REPEAT, random);
    }

    /** As above; {@code motifRepeat} is the chance that a new phrase repeats the one before, fitted to the chord it is over. */
    public List<TickNote> generate(List<Chord> barChords, int beatsPerBar, int startNear, int low, int high,
                                   double temperature, double motifRepeat, RandomGenerator random) {
        if (beatsPerBar != 4) {
            throw new UnsupportedOperationException("the model was learned in 4/4");
        }
        int total = barChords.size() * BAR;
        List<TickNote> notes = new ArrayList<>();

        int position = 0;
        int centre = (low + high) / 2;
        int previousPitch = startNear;
        int previousInterval = 0;
        int earlierInterval = 0;
        int previousIoi = TPB;
        int earlierIoi = TPB;
        int depth = 0;
        List<TickNote> phrase = new ArrayList<>(); // the notes of the phrase being played
        int phraseLastIoi = TPB;
        List<TickNote> lastPhrase = List.of(); // the phrase before, which a new one may repeat
        int lastPhraseIoi = TPB;
        boolean justRepeated = false;
        Memory memory = new Memory();

        while (position < total) {
            memory.update(notes);
            if (depth == 0 && !justRepeated && lastPhrase.size() >= MIN_REPLAYED_PHRASE && random.nextDouble() < motifRepeat) {
                // The figure starts at the same place in the beat as before, so its swing is the same: a little more rest if need be.
                int start = position + Math.floorMod(lastPhrase.get(0).tick() - position, TPB);
                List<TickNote> repeated = replay(lastPhrase, start, barChords, previousPitch, low, high, total);
                if (repeated.size() >= MIN_REPLAYED_PHRASE) {
                    notes.addAll(repeated);
                    TickNote last = repeated.get(repeated.size() - 1);
                    previousPitch = last.pitch();
                    previousInterval = last.pitch() - repeated.get(repeated.size() - 2).pitch();
                    earlierInterval = repeated.size() >= 3 ? repeated.get(repeated.size() - 2).pitch() - repeated.get(repeated.size() - 3).pitch() : 0;
                    previousIoi = lastPhraseIoi;
                    earlierIoi = last.tick() - repeated.get(repeated.size() - 2).tick();
                    position = last.tick() + lastPhraseIoi;
                    lastPhrase = repeated; // the next repeat varies this one, so a figure can wander
                    justRepeated = true;
                    continue;
                }
            }
            justRepeated = false;

            Chord chord = barChords.get(position / BAR);
            int tickInBar = position % BAR;

            Map<String, Double> gaps = mix(distribution(rhythm, rhythmKeys(tickInBar, previousIoi, earlierIoi, depth)),
                    memory.rhythmAfter(notes), RHYTHM_MEMORY);
            String[] gap = draw(gaps, temperature, random).split(":");
            int ioi = Math.max(1, Integer.parseInt(gap[0]));
            int duration = Math.max(1, Math.min(Integer.parseInt(gap[1]), Math.min(ioi, total - position)));
            boolean breath = ioi - duration >= BREATH;
            boolean ends = breath || position + ioi >= total;
            Chord next = barChords.get(Math.min(barChords.size() - 1, (position + ioi) / BAR));

            String[] pitchContexts = pitchKeys(chord.quality(), degree(previousPitch, chord), tickInBar, previousInterval, earlierInterval,
                    phase(depth, ends), register(previousPitch, centre));
            Map<String, Double> leaps = leaps(pitchContexts, memory.leapAfter(notes), PITCH_MEMORY, Blend.INTERPOLATE);
            int pitch = isLanding(chord, next, ends)
                    ? chooseLandingPitch(leaps, landingKeys(next.quality(), ends), previousPitch, next, low, high, temperature, random)
                    : choosePitch(leaps, previousPitch, low, high, temperature, random);

            TickNote note = new TickNote(position, duration, pitch);
            notes.add(note);
            phrase.add(note);
            earlierInterval = previousInterval;
            previousInterval = pitch - previousPitch;
            previousPitch = pitch;
            earlierIoi = previousIoi;
            previousIoi = ioi;
            phraseLastIoi = ioi;
            position += ioi;
            if (breath) { // the phrase is over; the next one may answer it
                lastPhrase = phrase;
                lastPhraseIoi = phraseLastIoi;
                phrase = new ArrayList<>();
                depth = 0;
            } else {
                depth++;
            }
        }
        return notes;
    }

    /**
     * Plays a phrase again from {@code start}: the same rhythm and the same steps, each note moved to the nearest tone of the
     * scale of the chord it now falls on, so the idea follows the changes. The notes that would fall past the end are dropped.
     */
    private static List<TickNote> replay(List<TickNote> phrase, int start, List<Chord> barChords, int previousPitch,
                                         int low, int high, int total) {
        int shift = start - phrase.get(0).tick();
        List<TickNote> repeated = new ArrayList<>();
        int pitch = previousPitch;
        for (int i = 0; i < phrase.size(); i++) {
            TickNote note = phrase.get(i);
            int tick = note.tick() + shift;
            if (tick >= total) {
                break;
            }
            int wanted = i == 0 ? note.pitch() : pitch + (note.pitch() - phrase.get(i - 1).pitch());
            pitch = nearestScaleTone(barChords.get(tick / BAR), wanted, low, high);
            repeated.add(new TickNote(tick, Math.min(note.duration(), total - tick), pitch));
        }
        return repeated;
    }

    /** The pitch in range, on the scale of this chord, closest to {@code wanted}. */
    private static int nearestScaleTone(Chord chord, int wanted, int low, int high) {
        int clamped = Math.max(low, Math.min(high, wanted));
        for (int distance = 0; distance <= 12; distance++) {
            for (int candidate : new int[] {clamped - distance, clamped + distance}) {
                if (candidate < low || candidate > high) {
                    continue;
                }
                for (int pc : chord.scalePitchClasses()) {
                    if (Math.floorMod(candidate, 12) == pc) {
                        return candidate;
                    }
                }
            }
        }
        return clamped;
    }

    /**
     * Picks the next pitch when the note is the last before the chord changes or the last of a phrase: each leap the model
     * might make is weighted by how often real players put such a note on the resulting degree of the coming chord.
     */
    private int chooseLandingPitch(Map<String, Double> leaps, String[] landingContexts, int previousPitch, Chord next, int low, int high,
                                   double temperature, RandomGenerator random) {
        Map<String, Double> aimed = landingLeaps(leaps, landingContexts, previousPitch, next, Blend.INTERPOLATE);
        Map<String, Double> inRange = new TreeMap<>();
        aimed.forEach((leap, p) -> {
            int candidate = previousPitch + Integer.parseInt(leap);
            if (candidate >= low && candidate <= high) {
                inRange.put(leap, p);
            }
        });
        if (inRange.isEmpty()) {
            return choosePitch(leaps, previousPitch, low, high, temperature, random);
        }
        return previousPitch + Integer.parseInt(draw(inRange, temperature, random));
    }

    /** The chance of each leap into a landing note: the leap table's, times how often the note it reaches is where players land. */
    private Map<String, Double> landingLeaps(Map<String, Double> leaps, String[] landingContexts, int previousPitch, Chord next, Blend blend) {
        Map<String, Double> landings = distribution(landing, landingContexts, meta.minContext(), blend);
        if (landings == null) {
            return leaps;
        }
        Map<String, Double> weighted = new TreeMap<>();
        double sum = 0;
        for (Map.Entry<String, Double> leap : leaps.entrySet()) {
            // A twelfth of a count for every degree, so no note is impossible.
            double toward = landings.getOrDefault(Integer.toString(degree(previousPitch + Integer.parseInt(leap.getKey()), next)), 0.0) + 1.0 / 12;
            weighted.put(leap.getKey(), leap.getValue() * toward);
            sum += leap.getValue() * toward;
        }
        double total = sum;
        weighted.replaceAll((leap, w) -> w / total);
        return weighted;
    }

    /** Samples a leap; if it leaves the instrument's range, tries again, and in the end folds the note back by octaves. */
    private static int choosePitch(Map<String, Double> leaps, int previousPitch, int low, int high, double temperature, RandomGenerator random) {
        int candidate = previousPitch;
        for (int attempt = 0; attempt < RESAMPLE_ATTEMPTS; attempt++) {
            candidate = previousPitch + Integer.parseInt(draw(leaps, temperature, random));
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

    // ---- how likely each outcome is ----

    /** How the levels of detail of a table are combined into one set of chances. */
    enum Blend {
        /** Only the most detailed level seen at least {@code minContext} times (as models were sampled before). */
        BACKOFF,
        /**
         * Every level that has been seen, from the coarsest up: each level's counts, with the level below it worth
         * {@code minContext} counts for every different outcome seen there. A situation seen often, and always resolved the same
         * way, follows its own counts; one seen once or twice, or that went many ways, leans on what is known about similar
         * situations rather than copying the few times it was seen. On held-out solos this predicts real players far better.
         */
        INTERPOLATE
    }

    private Map<String, Double> distribution(Map<String, Map<String, Integer>> table, String[] contexts) {
        Map<String, Double> chances = distribution(table, contexts, meta.minContext(), Blend.INTERPOLATE);
        if (chances == null) {
            throw new IllegalStateException("the model has nothing to go on");
        }
        return chances;
    }

    /** The chance of each leap: the table's, mixed with what this solo itself has played after the same figure. */
    private Map<String, Double> leaps(String[] contexts, Map<String, Double> remembered, double memoryWeight, Blend blend) {
        Map<String, Double> learned = distribution(pitch, contexts, meta.minContext(), blend);
        if (learned == null) {
            throw new IllegalStateException("the model has nothing to go on");
        }
        return mix(learned, remembered, memoryWeight);
    }

    /** {@code learned}, with {@code weight} of the chances taken from {@code remembered} when there is anything remembered. */
    static Map<String, Double> mix(Map<String, Double> learned, Map<String, Double> remembered, double weight) {
        if (remembered == null || weight <= 0) {
            return learned;
        }
        Map<String, Double> mixed = new TreeMap<>();
        learned.forEach((outcome, p) -> mixed.put(outcome, (1 - weight) * p));
        remembered.forEach((outcome, p) -> mixed.merge(outcome, weight * p, Double::sum));
        return mixed;
    }

    // ---- the solo's memory of itself ----

    /**
     * How much of the chance of the next note comes from what this solo has already played. Players develop their own ideas:
     * after a figure they have used, they often go on the way they went before. The weights are the ones that predict
     * held-out solos best (see {@link #surprise}).
     */
    static final double PITCH_MEMORY = 0.2;
    static final double RHYTHM_MEMORY = 0.1;
    /** How many leaps or gaps make a figure the solo recognises as one it has played. */
    static final int FIGURE = 2;

    /** What a solo has played so far: after each figure of leaps, and of gaps, what came next. */
    static final class Memory {
        private final Map<String, Map<String, Integer>> leaps = new java.util.HashMap<>();
        private final Map<String, Map<String, Integer>> gaps = new java.util.HashMap<>();
        private int seen; // how many notes of the line have been taken in

        /** Takes in the notes of {@code line} it has not seen yet (the line only ever grows). */
        void update(List<TickNote> line) {
            for (; seen < line.size(); seen++) {
                int k = seen;
                if (k > FIGURE) {
                    count(leaps, leapFigure(line, k), Integer.toString(clip(line.get(k).pitch() - line.get(k - 1).pitch())));
                }
                if (k >= FIGURE + 1) { // the gap after note k-1 is known now that note k is here
                    TickNote before = line.get(k - 1);
                    int ioi = line.get(k).tick() - before.tick();
                    count(gaps, gapFigure(line, k - 1), Math.min(ioi, MAX_TICKS) + ":" + Math.min(Math.min(before.duration(), ioi), MAX_TICKS));
                }
            }
        }

        /** What this solo played after the figure that leads into the note that comes after {@code line}. */
        Map<String, Double> leapAfter(List<TickNote> line) {
            return line.size() > FIGURE ? chances(leaps.get(leapFigure(line, line.size()))) : null;
        }

        /** What gap this solo left after the figure that ends with the last note of {@code line}. */
        Map<String, Double> rhythmAfter(List<TickNote> line) {
            return line.size() > FIGURE ? chances(gaps.get(gapFigure(line, line.size() - 1))) : null;
        }

        /** The leaps into note {@code k} from the {@link #FIGURE} notes before it. */
        private static String leapFigure(List<TickNote> line, int k) {
            StringBuilder key = new StringBuilder();
            for (int j = k - FIGURE; j < k; j++) {
                key.append(clip(line.get(j).pitch() - line.get(j - 1).pitch())).append('|');
            }
            return key.toString();
        }

        /** The gaps leading up to note {@code k}. */
        private static String gapFigure(List<TickNote> line, int k) {
            StringBuilder key = new StringBuilder();
            for (int j = k - FIGURE + 1; j <= k; j++) {
                key.append(Math.min(line.get(j).tick() - line.get(j - 1).tick(), MAX_TICKS)).append('|');
            }
            return key.toString();
        }

        private static Map<String, Double> chances(Map<String, Integer> counts) {
            return counts == null ? null : normalised(counts, null, 0);
        }
    }

    /** The chance of each outcome in a situation described from the most to the least detailed by {@code contexts}, or null if none was seen. */
    static Map<String, Double> distribution(Map<String, Map<String, Integer>> table, String[] contexts, int minContext, Blend blend) {
        if (blend == Blend.BACKOFF) {
            Map<String, Integer> counts = pick(table, contexts, minContext);
            return counts == null ? null : normalised(counts, null, 0);
        }
        Map<String, Double> blended = null;
        for (int i = contexts.length - 1; i >= 0; i--) {
            Map<String, Integer> counts = table.get(contexts[i]);
            if (counts != null) {
                blended = normalised(counts, blended, Math.max(1, minContext) * counts.size());
            }
        }
        return blended;
    }

    /** {@code counts} as chances, with {@code prior} (if any) mixed in as if it were {@code weight} more counts. */
    private static Map<String, Double> normalised(Map<String, Integer> counts, Map<String, Double> prior, double weight) {
        double total = counts.values().stream().mapToInt(Integer::intValue).sum() + (prior == null ? 0 : weight);
        Map<String, Double> chances = new TreeMap<>();
        if (prior != null) {
            prior.forEach((outcome, p) -> chances.put(outcome, weight * p / total));
        }
        counts.forEach((outcome, count) -> chances.merge(outcome, count / total, Double::sum));
        return chances;
    }

    /** Picks an outcome; a temperature below 1 favours the likely ones, above 1 the unlikely. */
    private static String draw(Map<String, Double> chances, double temperature, RandomGenerator random) {
        double sharpen = 1.0 / Math.max(temperature, 1e-3);
        double sum = 0;
        for (double p : chances.values()) {
            sum += Math.pow(p, sharpen);
        }
        double pick = random.nextDouble() * sum;
        String last = null;
        for (Map.Entry<String, Double> outcome : chances.entrySet()) {
            last = outcome.getKey();
            pick -= Math.pow(outcome.getValue(), sharpen);
            if (pick <= 0) {
                return last;
            }
        }
        return last;
    }

    // ---- how well it predicts real solos ----

    /**
     * How surprised the model is by real solos, in bits per note (lower is better): for each note, how likely the model thought
     * its rhythm and its leap were, in the situation the player was in. It is the measure of what a model has learned that the
     * statistics of its own solos cannot give, since a counting model reproduces those by construction. Score solos it never
     * trained on. The middle of each solo's register is taken from the solo itself.
     */
    public record Surprise(double rhythmBits, double pitchBits, int notes) {

        public double bits() {
            return rhythmBits + pitchBits;
        }
    }

    public Surprise surprise(List<Corpus.Solo> solos) {
        return surprise(solos, Blend.INTERPOLATE, PITCH_MEMORY, RHYTHM_MEMORY);
    }

    /** As models were used before: only the most detailed situation seen often enough, and no memory of the solo. */
    public Surprise surpriseAsBefore(List<Corpus.Solo> solos) {
        return surprise(solos, Blend.BACKOFF, 0, 0);
    }

    /** As the model plays ({@link Blend#INTERPOLATE} and its memory), or another way, to compare. */
    Surprise surprise(List<Corpus.Solo> solos, Blend blend, double pitchMemory, double rhythmMemory) {
        double rhythmBits = 0;
        double pitchBits = 0;
        int rhythmNotes = 0;
        int pitchNotes = 0;
        for (Corpus.Solo solo : solos) {
            Optional<ChordTimeline> chords = ChordTimeline.of(solo);
            List<TickNote> notes = distinctOnsets(solo.notes());
            if (solo.beatsPerBar() != 4 || chords.isEmpty() || notes.size() < 2) {
                continue;
            }
            int centre = median(notes);
            int depth = 0;
            Memory memory = new Memory();
            for (int i = 0; i < notes.size(); i++) {
                TickNote note = notes.get(i);
                int tickInBar = Math.floorMod(note.tick(), BAR);
                if (i > 0) {
                    depth = restAfter(notes, i - 1) >= BREATH ? 0 : depth + 1;
                }
                boolean ends = i + 1 == notes.size() || restAfter(notes, i) >= BREATH;
                List<TickNote> before = notes.subList(0, i); // what the player had played: all the model may remember
                memory.update(before);
                if (i >= 1) {
                    Chord chord = chords.get().at(note.tick());
                    int previousPitch = notes.get(i - 1).pitch();
                    int previousInterval = i >= 2 ? previousPitch - notes.get(i - 2).pitch() : 0;
                    int earlierInterval = i >= 3 ? notes.get(i - 2).pitch() - notes.get(i - 3).pitch() : 0;
                    String[] contexts = pitchKeys(chord.quality(), degree(previousPitch, chord), tickInBar, previousInterval, earlierInterval,
                            phase(depth, ends), register(previousPitch, centre));
                    Chord next = i + 1 < notes.size() ? chords.get().at(notes.get(i + 1).tick()) : chord;
                    Map<String, Double> leaps = leaps(contexts, memory.leapAfter(before), pitchMemory, blend);
                    Map<String, Double> chances = isLanding(chord, next, ends)
                            ? landingLeaps(leaps, landingKeys(next.quality(), ends), previousPitch, next, blend)
                            : leaps;
                    pitchBits += bits(chances, Integer.toString(clip(note.pitch() - previousPitch)), 2 * MAX_LEAP + 1);
                    pitchNotes++;
                }
                if (i + 1 < notes.size()) {
                    int ioi = notes.get(i + 1).tick() - note.tick();
                    int previousIoi = i > 0 ? note.tick() - notes.get(i - 1).tick() : TPB;
                    int earlierIoi = i > 1 ? notes.get(i - 1).tick() - notes.get(i - 2).tick() : TPB;
                    String outcome = Math.min(ioi, MAX_TICKS) + ":" + Math.min(Math.min(note.duration(), ioi), MAX_TICKS);
                    Map<String, Double> chances = mix(distribution(rhythm, rhythmKeys(tickInBar, previousIoi, earlierIoi, depth), meta.minContext(), blend),
                            memory.rhythmAfter(notes.subList(0, i + 1)), rhythmMemory);
                    rhythmBits += bits(chances, outcome, RHYTHM_OUTCOMES);
                    rhythmNotes++;
                }
            }
        }
        return new Surprise(rhythmNotes == 0 ? Double.NaN : rhythmBits / rhythmNotes, pitchNotes == 0 ? Double.NaN : pitchBits / pitchNotes,
                pitchNotes);
    }

    /** Every gap and length up to a bar, which is what a rhythm can be. */
    private static final int RHYTHM_OUTCOMES = MAX_TICKS * MAX_TICKS;
    /** A little of every chance is spread over everything that could happen, so nothing real is impossible and every model is scored alike. */
    private static final double FLOOR = 1e-3;

    private static double bits(Map<String, Double> chances, String outcome, int possible) {
        double p = chances == null ? 0 : chances.getOrDefault(outcome, 0.0);
        return -Math.log((1 - FLOOR) * p + FLOOR / possible) / Math.log(2);
    }

    /** The counts of the most detailed context that has been seen at least {@code minContext} times (else the coarsest with any data), or null. */
    static Map<String, Integer> pick(Map<String, Map<String, Integer>> table, String[] contexts, int minContext) {
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
        return chosen;
    }

    /** Picks an outcome from the most detailed table that has been seen at least {@code minContext} times. */
    static String sample(Map<String, Map<String, Integer>> table, String[] contexts, int minContext, double temperature,
                         RandomGenerator random) {
        Map<String, Integer> chosen = pick(table, contexts, minContext);
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
                + rhythm.size() + " rhythm, " + pitch.size() + " pitch and " + landing.size() + " landing situations; " + meta.testIds().size()
                + " solos held out for testing";
    }
}
