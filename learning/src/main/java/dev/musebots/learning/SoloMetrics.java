package dev.musebots.learning;

import dev.musebots.core.Chord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Numbers that describe how a solo is built, so that "better solos" can be argued about with evidence. They are measured in
 * relation to the chords (not in absolute pitch), so a solo over any key can be compared with a solo in another.
 */
public final class SoloMetrics {

    private static final int BAR = 4 * Corpus.TICKS_PER_BEAT;
    private static final int WINDOW = 4; // intervals in a repeated figure
    private static final int BREATH = Corpus.TICKS_PER_BEAT; // a silence this long (a beat) ends a phrase

    private SoloMetrics() {
    }

    /** A solo ready to be measured: its notes, the chord at any tick, and how many bars it covers. */
    public record Line(List<TickNote> notes, IntFunction<Chord> chordAt, int bars) {

        public Line {
            notes = List.copyOf(notes);
        }
    }

    /** The measurements of one solo. A value is NaN where the solo has nothing to measure it on. */
    public record Scalars(double notesPerBar, double chordToneOnStrongBeat, double outOfScale, double meanAbsInterval,
                          double stepwise, double leaps, double repetition,
                          double phraseNotes, double restShare, double arrivalOnChordTone, double arrivalByStep,
                          double leadsIntoScale, double leadsIntoChordTone) {
    }

    /** The distributions of what the solos do, pooled over many solos, so two systems can be compared. */
    public static final class Distributions {
        public static final int INTERVAL_BINS = 25; // leaps from -12 to +12 semitones
        public static final int ONSET_BINS = Corpus.TICKS_PER_BEAT; // where in the beat a note starts
        public static final int DEGREE_BINS = 12; // pitch class relative to the chord's root
        public static final int IOI_BINS = 15; // the gap to the next note, 1 to 12 ticks one by one, then coarser

        public final Histogram interval = new Histogram(INTERVAL_BINS);
        public final Histogram onset = new Histogram(ONSET_BINS);
        public final Histogram degree = new Histogram(DEGREE_BINS);
        public final Histogram ioi = new Histogram(IOI_BINS);

        public void add(Line line) {
            List<TickNote> notes = line.notes();
            for (int i = 0; i < notes.size(); i++) {
                TickNote note = notes.get(i);
                onset.add(Math.floorMod(note.tick(), Corpus.TICKS_PER_BEAT));
                degree.add(Math.floorMod(note.pitch() - line.chordAt().apply(note.tick()).root(), 12));
                if (i > 0) {
                    interval.add(Math.max(-12, Math.min(12, note.pitch() - notes.get(i - 1).pitch())) + 12);
                    ioi.add(ioiBin(note.tick() - notes.get(i - 1).tick()));
                }
            }
        }

        private static int ioiBin(int ticks) {
            if (ticks <= 12) {
                return Math.max(1, ticks) - 1;
            }
            if (ticks <= 24) {
                return 12;
            }
            return ticks <= 48 ? 13 : 14;
        }
    }

    public static Scalars scalars(Line line) {
        List<TickNote> notes = line.notes();
        double notesPerBar = line.bars() == 0 ? Double.NaN : (double) notes.size() / line.bars();

        int strong = 0;
        int strongChordTones = 0;
        int outOfScale = 0;
        for (TickNote note : notes) {
            Chord chord = line.chordAt().apply(note.tick());
            int pc = Math.floorMod(note.pitch(), 12);
            if (Math.floorMod(note.tick(), BAR) % (2 * Corpus.TICKS_PER_BEAT) == 0) { // beats 1 and 3
                strong++;
                if (isChordTone(chord, pc)) {
                    strongChordTones++;
                }
            }
            if (!contains(chord.scalePitchClasses(), pc)) {
                outOfScale++;
            }
        }

        List<Integer> intervals = new ArrayList<>();
        for (int i = 1; i < notes.size(); i++) {
            intervals.add(notes.get(i).pitch() - notes.get(i - 1).pitch());
        }
        double meanAbs = Double.NaN;
        double stepwise = Double.NaN;
        double leaps = Double.NaN;
        if (!intervals.isEmpty()) {
            meanAbs = intervals.stream().mapToInt(Math::abs).average().orElse(Double.NaN);
            stepwise = share(intervals, interval -> Math.abs(interval) >= 1 && Math.abs(interval) <= 2);
            leaps = share(intervals, interval -> Math.abs(interval) >= 5);
        }

        return new Scalars(notesPerBar,
                strong == 0 ? Double.NaN : (double) strongChordTones / strong,
                notes.isEmpty() ? Double.NaN : (double) outOfScale / notes.size(),
                meanAbs, stepwise, leaps, repetition(intervals),
                phraseNotes(notes), restShare(notes, line.bars()),
                arrival(line, true), arrival(line, false),
                leadsInto(line, false), leadsInto(line, true));
    }

    /** The notes in time order. */
    private static List<TickNote> inOrder(List<TickNote> notes) {
        List<TickNote> sorted = new ArrayList<>(notes);
        sorted.sort(java.util.Comparator.comparingInt(TickNote::tick));
        return sorted;
    }

    /** How many notes the player plays before taking a breath: the mean length of a phrase, a phrase ending at a silence of a beat or more. */
    static double phraseNotes(List<TickNote> notes) {
        List<TickNote> sorted = inOrder(notes);
        if (sorted.isEmpty()) {
            return Double.NaN;
        }
        int phrases = 1;
        for (int i = 1; i < sorted.size(); i++) {
            TickNote previous = sorted.get(i - 1);
            if (sorted.get(i).tick() - (previous.tick() + previous.duration()) >= BREATH) {
                phrases++;
            }
        }
        return (double) sorted.size() / phrases;
    }

    /** The share of the solo's time with nothing sounding: the space the player leaves. */
    static double restShare(List<TickNote> notes, int bars) {
        if (bars == 0 || notes.isEmpty()) {
            return Double.NaN;
        }
        double sounding = 0;
        for (TickNote note : inOrder(notes)) {
            sounding += note.duration();
        }
        return Math.max(0, 1 - sounding / ((double) bars * BAR));
    }

    /**
     * Whether the line sees the next chord coming: of the last notes played in a bar that is followed by a different chord,
     * the share that already belong to the next chord's scale (or, if {@code chordTone}, are one of its chord tones). A line
     * that ignores the changes lands there only by chance.
     */
    static double leadsInto(Line line, boolean chordTone) {
        List<TickNote> notes = inOrder(line.notes());
        int leads = 0;
        int hits = 0;
        for (int i = 0; i < notes.size(); i++) {
            TickNote note = notes.get(i);
            int bar = Math.floorDiv(note.tick(), BAR);
            if (i + 1 < notes.size() && Math.floorDiv(notes.get(i + 1).tick(), BAR) == bar) {
                continue; // not the last note of its bar
            }
            Chord next = line.chordAt().apply((bar + 1) * BAR);
            if (bar + 1 >= line.bars() || next.equals(line.chordAt().apply(note.tick()))) {
                continue; // nothing follows, or the chord stays
            }
            leads++;
            int pc = Math.floorMod(note.pitch(), 12);
            if (chordTone ? isChordTone(next, pc) : contains(next.scalePitchClasses(), pc)) {
                hits++;
            }
        }
        return leads == 0 ? Double.NaN : (double) hits / leads;
    }

    /**
     * What the line does when the chord changes: of the first notes played in a bar whose chord differs from the previous
     * bar's, the share that are chord tones of the new chord ({@code chordTone}), or the share that are reached by a step of
     * two semitones or less from the note before (so the line follows the changes smoothly).
     */
    static double arrival(Line line, boolean chordTone) {
        List<TickNote> notes = inOrder(line.notes());
        int arrivals = 0;
        int hits = 0;
        for (int i = 1; i < notes.size(); i++) {
            TickNote previous = notes.get(i - 1);
            TickNote note = notes.get(i);
            int bar = Math.floorDiv(note.tick(), BAR);
            if (bar == Math.floorDiv(previous.tick(), BAR)) {
                continue; // not the first note of its bar
            }
            Chord chord = line.chordAt().apply(note.tick());
            if (chord.equals(line.chordAt().apply(previous.tick()))) {
                continue; // the chord did not change
            }
            arrivals++;
            if (chordTone ? isChordTone(chord, Math.floorMod(note.pitch(), 12)) : Math.abs(note.pitch() - previous.pitch()) <= 2) {
                hits++;
            }
        }
        return arrivals == 0 ? Double.NaN : (double) hits / arrivals;
    }

    /** The share of four-interval figures that occur more than once in the solo: how much it repeats its own ideas. */
    static double repetition(List<Integer> intervals) {
        if (intervals.size() < WINDOW + 1) {
            return Double.NaN;
        }
        Map<List<Integer>, Integer> seen = new HashMap<>();
        for (int i = 0; i + WINDOW <= intervals.size(); i++) {
            seen.merge(intervals.subList(i, i + WINDOW), 1, Integer::sum);
        }
        int windows = intervals.size() - WINDOW + 1;
        int repeated = seen.values().stream().filter(count -> count > 1).mapToInt(Integer::intValue).sum();
        return (double) repeated / windows;
    }

    private static boolean isChordTone(Chord chord, int pitchClass) {
        return pitchClass == chord.root() || pitchClass == chord.thirdPc() || pitchClass == chord.fifthPc()
                || pitchClass == chord.seventhPc();
    }

    private static boolean contains(int[] pitchClasses, int pitchClass) {
        for (int candidate : pitchClasses) {
            if (candidate == pitchClass) {
                return true;
            }
        }
        return false;
    }

    private static double share(List<Integer> values, java.util.function.Predicate<Integer> test) {
        return (double) values.stream().filter(test).count() / values.size();
    }
}
