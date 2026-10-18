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
 *
 * <p>Two more things make a line sound like phrases rather than a stream of notes:
 * <ul>
 *   <li><b>Phrases.</b> A silence of a beat or more ends a phrase. Both tables also know how far into its phrase the line is
 *       (so it learns how long phrases run, and that the last note of one is placed differently from the first), and a
 *       generated line sometimes <em>repeats</em> its last phrase, fitted to the chord it now sits over: a motif and its answer.</li>
 *   <li><b>Landing.</b> When a note is the last before the chord changes, or the last of a phrase, a third table says where it
 *       sits relative to the <em>coming</em> chord. Its weight is combined with the leap table, so the line is drawn toward
 *       notes that belong to the chord that follows, as real players do, with no rule written by hand.</li>
 * </ul>
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
                    String outcome = Math.min(ioi, MAX_TICKS) + ":" + Math.min(Math.min(note.duration(), ioi), MAX_TICKS);
                    for (String key : rhythmKeys(tickInBar, previousIoi, depth)) {
                        count(rhythm, key, outcome);
                    }
                }
                if (i >= 1) { // pitch: how far the line moves from the previous note
                    Chord chord = chords.get().at(note.tick());
                    int previousPitch = notes.get(i - 1).pitch();
                    int previousInterval = i >= 2 ? previousPitch - notes.get(i - 2).pitch() : 0;
                    String outcome = Integer.toString(clip(note.pitch() - previousPitch));
                    for (String key : pitchKeys(chord.quality(), degree(previousPitch, chord), tickInBar, previousInterval, phase(depth, ends))) {
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

    private static String[] rhythmKeys(int tickInBar, int previousIoi, int depth) {
        int beat = tickInBar / TPB;
        int tick = tickInBar % TPB;
        int previous = Math.min(previousIoi, MAX_TICKS);
        return new String[] {
                "r|d" + depthBucket(depth) + "|" + tick + "|" + previous,
                "r|" + beat + "|" + tick + "|" + previous, "r|" + tick + "|" + previous, "r|" + tick, "r"};
    }

    private static String[] pitchKeys(Quality quality, int previousDegree, int tickInBar, int previousInterval, int phase) {
        char q = switch (quality) {
            case DOM7 -> 'D';
            case MIN7 -> 'm';
            case MAJ7 -> 'M';
        };
        int position = positionClass(tickInBar);
        return new String[] {
                "p|" + q + "|" + previousDegree + "|" + position + "|" + leapBucket(previousInterval) + "|f" + phase,
                "p|" + q + "|" + previousDegree + "|" + position + "|" + leapBucket(previousInterval),
                "p|" + q + "|" + previousDegree + "|" + position,
                "p|" + q + "|" + position,
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
        int minContext = meta.minContext();
        int total = barChords.size() * BAR;
        List<TickNote> notes = new ArrayList<>();

        int position = 0;
        int previousPitch = startNear;
        int previousInterval = 0;
        int previousIoi = TPB;
        int depth = 0;
        List<TickNote> phrase = new ArrayList<>(); // the notes of the phrase being played
        int phraseLastIoi = TPB;
        List<TickNote> lastPhrase = List.of(); // the phrase before, which a new one may repeat
        int lastPhraseIoi = TPB;
        boolean justRepeated = false;

        while (position < total) {
            if (depth == 0 && !justRepeated && lastPhrase.size() >= MIN_REPLAYED_PHRASE && random.nextDouble() < motifRepeat) {
                // The figure starts at the same place in the beat as before, so its swing is the same: a little more rest if need be.
                int start = position + Math.floorMod(lastPhrase.get(0).tick() - position, TPB);
                List<TickNote> repeated = replay(lastPhrase, start, barChords, previousPitch, low, high, total);
                if (repeated.size() >= MIN_REPLAYED_PHRASE) {
                    notes.addAll(repeated);
                    TickNote last = repeated.get(repeated.size() - 1);
                    previousPitch = last.pitch();
                    previousInterval = last.pitch() - repeated.get(repeated.size() - 2).pitch();
                    previousIoi = lastPhraseIoi;
                    position = last.tick() + lastPhraseIoi;
                    lastPhrase = repeated; // the next repeat varies this one, so a figure can wander
                    justRepeated = true;
                    continue;
                }
            }
            justRepeated = false;

            Chord chord = barChords.get(position / BAR);
            int tickInBar = position % BAR;

            String[] gap = sample(rhythm, rhythmKeys(tickInBar, previousIoi, depth), minContext, temperature, random).split(":");
            int ioi = Math.max(1, Integer.parseInt(gap[0]));
            int duration = Math.max(1, Math.min(Integer.parseInt(gap[1]), Math.min(ioi, total - position)));
            boolean breath = ioi - duration >= BREATH;
            boolean ends = breath || position + ioi >= total;
            Chord next = barChords.get(Math.min(barChords.size() - 1, (position + ioi) / BAR));

            String[] pitchContexts = pitchKeys(chord.quality(), degree(previousPitch, chord), tickInBar, previousInterval, phase(depth, ends));
            int pitch = isLanding(chord, next, ends)
                    ? chooseLandingPitch(pitchContexts, landingKeys(next.quality(), ends), previousPitch, next, low, high, temperature, random)
                    : choosePitch(pitchContexts, previousPitch, low, high, temperature, random);

            TickNote note = new TickNote(position, duration, pitch);
            notes.add(note);
            phrase.add(note);
            previousInterval = pitch - previousPitch;
            previousPitch = pitch;
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
    private int chooseLandingPitch(String[] leapContexts, String[] landingContexts, int previousPitch, Chord next, int low, int high,
                                   double temperature, RandomGenerator random) {
        Map<String, Integer> leaps = pick(pitch, leapContexts, meta.minContext());
        Map<String, Integer> landings = pick(landing, landingContexts, meta.minContext());
        if (leaps == null || landings == null) {
            return choosePitch(leapContexts, previousPitch, low, high, temperature, random);
        }
        double sharpen = 1.0 / Math.max(temperature, 1e-3);
        double landingTotal = landings.values().stream().mapToInt(Integer::intValue).sum() + 12.0; // a count of one for every degree, so none is impossible
        List<Integer> pitches = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double sum = 0;
        for (Map.Entry<String, Integer> leap : leaps.entrySet()) {
            int candidate = previousPitch + Integer.parseInt(leap.getKey());
            if (candidate < low || candidate > high) {
                continue;
            }
            double toward = (landings.getOrDefault(Integer.toString(degree(candidate, next)), 0) + 1.0) / landingTotal;
            double weight = Math.pow(leap.getValue(), sharpen) * toward;
            pitches.add(candidate);
            weights.add(weight);
            sum += weight;
        }
        if (pitches.isEmpty()) {
            return choosePitch(leapContexts, previousPitch, low, high, temperature, random);
        }
        double pickAt = random.nextDouble() * sum;
        for (int i = 0; i < pitches.size(); i++) {
            pickAt -= weights.get(i);
            if (pickAt <= 0) {
                return pitches.get(i);
            }
        }
        return pitches.get(pitches.size() - 1);
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
