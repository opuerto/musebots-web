package dev.musebots.learning;

import dev.musebots.core.Chord;
import dev.musebots.core.ChordBar;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.random.RandomGenerator;

/**
 * Compares ways of writing a solo against real ones. Every system is asked to solo over the chord changes of tunes it was
 * never trained on, and is measured with the same numbers as the real solos played over those tunes.
 */
public final class Evaluation {

    private static final int BAR = 4 * Corpus.TICKS_PER_BEAT;
    private static final int COPY_WINDOW = 6; // intervals in a phrase that would count as copied
    private static final int MIN_BARS = 8;
    private static final int MAX_BARS = 96;

    private Evaluation() {
    }

    /** A solo to write: the chord of each bar, and the tempo the real solo was played at. */
    public record Task(String id, List<Chord> bars, int tempoBpm) {

        public Task(String id, List<Chord> bars) {
            this(id, bars, 120);
        }
    }

    /** Something that writes a solo over a chord chart. */
    @FunctionalInterface
    public interface Soloist {
        SoloMetrics.Line solo(Task task, RandomGenerator random);
    }

    /** A named system under test. */
    public record System(String name, Soloist soloist) {
    }

    /** Mean and standard deviation across solos. */
    public record Stat(double mean, double sd) {
    }

    /** What one system scored: how its solos are built, and how far from the real ones. */
    public record Row(String name, int solos, Map<String, Stat> scalars, Map<String, Double> divergence, double copyRate) {

        public double meanDivergence() {
            return divergence.values().stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        }
    }

    /** {@code heldOutSolos} is how many real solos on unseen tunes the systems were asked to write over. */
    public record Report(List<Row> rows, int heldOutSolos, int trainingSolos) {
    }

    // ---- building the tasks and the reference ----

    /** The real solo as a measurable line, over the chords the player really had. */
    public static Optional<SoloMetrics.Line> realLine(Corpus.Solo solo) {
        return ChordTimeline.of(solo).map(timeline -> new SoloMetrics.Line(solo.notes(), timeline::at, solo.bars()));
    }

    /** One task per held-out solo that is long enough: the chord at the start of each bar. */
    public static List<Task> tasks(List<Corpus.Solo> heldOut) {
        List<Task> tasks = new ArrayList<>();
        for (Corpus.Solo solo : heldOut) {
            Optional<ChordTimeline> timeline = ChordTimeline.of(solo);
            int bars = Math.min(solo.bars(), MAX_BARS);
            if (timeline.isEmpty() || bars < MIN_BARS) {
                continue;
            }
            List<Chord> chords = new ArrayList<>();
            for (int bar = 0; bar < bars; bar++) {
                chords.add(timeline.get().at(bar * BAR));
            }
            int tempo = solo.tempoBpm() == null || solo.tempoBpm() <= 0 ? 120 : (int) Math.round(solo.tempoBpm());
            tasks.add(new Task(solo.id(), chords, tempo));
        }
        return tasks;
    }

    public static List<ChordBar> asBars(List<Chord> chords) {
        return chords.stream().map(ChordBar::new).toList();
    }

    /** A line over a task's chords: the chord at a tick is that of its bar. */
    public static SoloMetrics.Line lineOver(Task task, List<TickNote> notes) {
        List<Chord> chords = task.bars();
        return new SoloMetrics.Line(notes, tick -> chords.get(Math.max(0, Math.min(chords.size() - 1, Math.floorDiv(tick, BAR)))),
                chords.size());
    }

    // ---- running it ----

    /**
     * @param training    the solos the systems learned from, used to check whether a system merely copies them
     * @param heldOut     the real solos on tunes the systems did not see: the reference, and the chord charts to solo over
     * @param repetitions how many solos each system writes per task
     */
    public static Report run(List<Corpus.Solo> training, List<Corpus.Solo> heldOut, List<System> systems, int repetitions, long seed) {
        Set<List<Integer>> trainedPhrases = phrases(training.stream().map(Corpus.Solo::notes).toList());

        List<SoloMetrics.Line> reference = heldOut.stream().map(Evaluation::realLine).flatMap(Optional::stream).toList();
        SoloMetrics.Distributions referenceDistribution = pool(reference);

        List<Row> rows = new ArrayList<>();
        rows.add(row("real solos (held out)", reference, referenceDistribution, trainedPhrases));

        List<Task> tasks = tasks(heldOut);
        for (System system : systems) {
            List<SoloMetrics.Line> lines = new ArrayList<>();
            for (int repetition = 0; repetition < repetitions; repetition++) {
                java.util.Random random = new java.util.Random(seed * 1_000_003L + repetition);
                for (Task task : tasks) {
                    lines.add(system.soloist().solo(task, random));
                }
            }
            rows.add(row(system.name(), lines, referenceDistribution, trainedPhrases));
        }
        return new Report(Collections.unmodifiableList(rows), tasks.size(), training.size());
    }

    private static SoloMetrics.Distributions pool(List<SoloMetrics.Line> lines) {
        SoloMetrics.Distributions distributions = new SoloMetrics.Distributions();
        lines.forEach(distributions::add);
        return distributions;
    }

    private static Row row(String name, List<SoloMetrics.Line> lines, SoloMetrics.Distributions reference, Set<List<Integer>> trained) {
        List<SoloMetrics.Scalars> scalars = lines.stream().map(SoloMetrics::scalars).toList();
        Map<String, Stat> stats = new LinkedHashMap<>();
        stats.put("notes per bar", stat(scalars, SoloMetrics.Scalars::notesPerBar));
        stats.put("chord tone on strong beat", stat(scalars, SoloMetrics.Scalars::chordToneOnStrongBeat));
        stats.put("out of scale", stat(scalars, SoloMetrics.Scalars::outOfScale));
        stats.put("mean leap (semitones)", stat(scalars, SoloMetrics.Scalars::meanAbsInterval));
        stats.put("stepwise", stat(scalars, SoloMetrics.Scalars::stepwise));
        stats.put("leaps (5+)", stat(scalars, SoloMetrics.Scalars::leaps));
        stats.put("repeated figures", stat(scalars, SoloMetrics.Scalars::repetition));
        stats.put("notes per phrase", stat(scalars, SoloMetrics.Scalars::phraseNotes));
        stats.put("rests", stat(scalars, SoloMetrics.Scalars::restShare));
        stats.put("chord tone on a new chord", stat(scalars, SoloMetrics.Scalars::arrivalOnChordTone));
        stats.put("step into a new chord", stat(scalars, SoloMetrics.Scalars::arrivalByStep));
        stats.put("bar's last note in the next chord's scale", stat(scalars, SoloMetrics.Scalars::leadsIntoScale));
        stats.put("bar's last note a tone of the next chord", stat(scalars, SoloMetrics.Scalars::leadsIntoChordTone));

        SoloMetrics.Distributions mine = pool(lines);
        Map<String, Double> divergence = new LinkedHashMap<>();
        divergence.put("leaps", Histogram.divergence(mine.interval, reference.interval));
        divergence.put("where in the beat", Histogram.divergence(mine.onset, reference.onset));
        divergence.put("pitch against the chord", Histogram.divergence(mine.degree, reference.degree));
        divergence.put("gaps between notes", Histogram.divergence(mine.ioi, reference.ioi));
        return new Row(name, lines.size(), stats, divergence, copyRate(lines, trained));
    }

    private static Stat stat(List<SoloMetrics.Scalars> scalars, ToDoubleFunction<SoloMetrics.Scalars> value) {
        double[] values = scalars.stream().mapToDouble(value).filter(v -> !Double.isNaN(v)).toArray();
        if (values.length == 0) {
            return new Stat(Double.NaN, Double.NaN);
        }
        double mean = java.util.Arrays.stream(values).average().orElse(Double.NaN);
        double variance = java.util.Arrays.stream(values).map(v -> (v - mean) * (v - mean)).sum() / values.length;
        return new Stat(mean, Math.sqrt(variance));
    }

    // ---- copying ----

    /** Every run of {@link #COPY_WINDOW} consecutive leaps that appears in these solos. */
    static Set<List<Integer>> phrases(List<List<TickNote>> solos) {
        Set<List<Integer>> phrases = new HashSet<>();
        for (List<TickNote> solo : solos) {
            List<Integer> intervals = intervals(solo);
            for (int i = 0; i + COPY_WINDOW <= intervals.size(); i++) {
                phrases.add(List.copyOf(intervals.subList(i, i + COPY_WINDOW)));
            }
        }
        return phrases;
    }

    /**
     * The share of runs of six leaps in these lines that also occur in the training solos. Real solos on new tunes copy a
     * little too (jazz has stock phrases), so the real solos' rate is the figure to compare against.
     */
    static double copyRate(List<SoloMetrics.Line> lines, Set<List<Integer>> trained) {
        int windows = 0;
        int copied = 0;
        for (SoloMetrics.Line line : lines) {
            List<Integer> intervals = intervals(line.notes());
            for (int i = 0; i + COPY_WINDOW <= intervals.size(); i++) {
                windows++;
                if (trained.contains(intervals.subList(i, i + COPY_WINDOW))) {
                    copied++;
                }
            }
        }
        return windows == 0 ? Double.NaN : (double) copied / windows;
    }

    private static List<Integer> intervals(List<TickNote> solo) {
        List<TickNote> notes = new ArrayList<>(solo);
        notes.sort(java.util.Comparator.comparingInt(TickNote::tick));
        List<Integer> intervals = new ArrayList<>();
        for (int i = 1; i < notes.size(); i++) {
            intervals.add(notes.get(i).pitch() - notes.get(i - 1).pitch());
        }
        return intervals;
    }
}
