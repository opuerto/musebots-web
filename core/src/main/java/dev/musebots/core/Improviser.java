package dev.musebots.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Writes a single-line melody over a chord chart; the shared engine behind every horn-like part (a sax head,
 * a piano right hand, a bass solo). Each bar picks a swung rhythmic cell and every onset moves to a nearby note.
 * Bars that open on beat 1 land on a guide tone (third or seventh), which is what makes the line follow the changes.
 */
final class Improviser {

    /**
     * How an instrument plays its line: its register and the rhythms it draws from. A {@code phrased} line is shaped like a
     * player's solo (phrases with a breath between them, an answer that echoes the statement, a contour, and a last note in
     * each bar that leads into the next chord); an unphrased one just fills every bar.
     */
    record Profile(int low, int high, int startNear, double[][][] cells, boolean scaleOnOffbeats, boolean phrased) {

        Profile(int low, int high, int startNear, double[][][] cells, boolean scaleOnOffbeats) {
            this(low, high, startNear, cells, scaleOnOffbeats, false);
        }
    }

    /** How many bars a phrase lasts; two is the most common, and now and then the player says something longer. */
    private static final int[] PHRASE_BARS = {2, 2, 2, 2, 3, 4};
    private static final double REPEAT_RHYTHM = 0.75; // how often a statement is answered, in the same rhythm and shape
    private static final double DIRECTION_BIAS = 0.7; // how firmly a note follows the phrase's contour
    private static final double APPROACH_CHORD_TONE = 0.45; // how the last note of a bar leads into the next chord
    private static final double APPROACH_SCALE = 0.40; // (the rest of the time it is a chromatic half step)
    private static final double BREATH_END = 3.0; // beat of the bar by which a phrase's last note has finished

    private static final int STEP_LIMIT = 4; // semitones between neighbouring notes
    private static final double O = Swing.OFFBEAT;
    private static final double OL = Swing.OFFBEAT_LENGTH;

    /** Calm rhythms for stating a melody. Cells are {start beat, length in beats}; gaps are rests. */
    static final double[][][] MELODY_CELLS = {
            {{0, 1}, {1, 1}, {2, 1}, {3, 1}},
            {{0, O}, {O, OL}, {1, 1}, {2, 2}},
            {{0, 1}, {1, O}, {1 + O, OL}, {2, 2}},
            {{0, 2}, {2, O}, {2 + O, OL}, {3, 1}},
            {{1, 1}, {2, 2}},
            {{0, 3.5}},
            {{0, 1}, {1 + O, OL}, {2, 1}, {3, O}, {3 + O, OL}},
    };

    /** Busier rhythms for improvising: runs of swung eighths with some space. The runs come up more often than the sparse bars. */
    static final double[][][] SOLO_CELLS = {
            {{0, O}, {O, OL}, {1, O}, {1 + O, OL}, {2, O}, {2 + O, OL}, {3, O}, {3 + O, OL}},
            {{0, O}, {O, OL}, {1, O}, {1 + O, OL}, {2, O}, {2 + O, OL}, {3, O}, {3 + O, OL}},
            {{0, O}, {O, OL}, {1, O}, {1 + O, OL}, {2, 1}, {3, O}, {3 + O, OL}},
            {{0, O}, {O, OL}, {1, O}, {1 + O, OL}, {2, 1}, {3, O}, {3 + O, OL}},
            {{0, 1}, {1, O}, {1 + O, OL}, {2, O}, {2 + O, OL}, {3, 1}},
            {{0, O}, {O, OL}, {1 + O, OL}, {2, 2}},
            {{0, 1}, {2, O}, {2 + O, OL}, {3, 1}},
            {{1, O}, {1 + O, OL}, {2, O}, {2 + O, OL}, {3, 1}},
    };

    private final Instrument instrument;
    private final Profile profile;
    private final RandomGenerator random;

    Improviser(Instrument instrument, Profile profile, RandomGenerator random) {
        this.instrument = instrument;
        this.profile = profile;
        this.random = random;
    }

    List<NoteEvent> line(PhraseRequest request) {
        if (request.clock().beatsPerBar() != 4) {
            throw new UnsupportedOperationException("improvised lines are only written in 4/4 for now");
        }
        if (profile.phrased()) {
            return phrasedLine(request);
        }
        List<NoteEvent> notes = new ArrayList<>();
        int previous = profile.startNear();

        for (int bar = 0; bar < request.bars().size(); bar++) {
            Chord chord = request.bars().get(bar).chord();
            double barStart = bar * 4.0;
            for (double[] onset : profile.cells()[random.nextInt(profile.cells().length)]) {
                boolean onBeat = onset[0] % 1 == 0;
                int pitch = onset[0] == 0 ? pickGuideTone(chord, previous) : pickNote(chord, previous, onBeat);
                previous = pitch;

                long start = request.startMs() + request.clock().beatsToMillis(barStart + onset[0]);
                long duration = Math.round(request.clock().beatsToMillis(onset[1]) * 0.9);
                int velocity = 84 + random.nextInt(10) + (onBeat ? 0 : 6);
                notes.add(new NoteEvent(instrument, pitch, start, duration, velocity));
            }
        }
        return notes;
    }

    // ---- a solo shaped in phrases ----

    /**
     * A solo that says things: it is cut into phrases of two bars or so; the first phrase of a pair states an idea and the
     * second answers it, usually with the same rhythm and a different contour; each phrase ends on a chord tone and then
     * the player breathes. Inside a phrase the line rises and falls, and the last note of a bar leads to the guide tone the
     * next chord opens on, from a half step or a step away, so the line follows the changes instead of just fitting each.
     */
    private List<NoteEvent> phrasedLine(PhraseRequest request) {
        List<NoteEvent> notes = new ArrayList<>();
        int bars = request.bars().size();
        int previous = profile.startNear();
        Integer nextOpening = null; // the pitch the next bar is to open on, once the bar before has led to it
        double[][][] statement = null; // the rhythm of the phrase before, for an answer to echo
        List<Integer> shape = List.of(); // the steps of that phrase, for the answer to follow

        int bar = 0;
        while (bar < bars) {
            boolean answer = statement != null && statement.length <= bars - bar && random.nextDouble() < REPEAT_RHYTHM;
            int length = answer ? statement.length : Math.min(bars - bar, PHRASE_BARS[random.nextInt(PHRASE_BARS.length)]);
            double[][][] cells = answer ? echo(statement) : freshCells(length);
            double[][][] phraseCells = cells.clone();
            phraseCells[length - 1] = breathe(phraseCells[length - 1]);
            statement = answer ? null : cells; // an answer is answered by something new
            List<Integer> heardSteps = new ArrayList<>();

            int onsets = java.util.Arrays.stream(phraseCells).mapToInt(c -> c.length).sum();
            int heard = 0;
            for (int i = 0; i < length; i++, bar++) {
                Chord chord = request.bars().get(bar).chord();
                double barStart = bar * 4.0;
                double[][] cell = phraseCells[i];
                for (int k = 0; k < cell.length; k++, heard++) {
                    double[] onset = cell[k];
                    boolean first = k == 0 && onset[0] == 0;
                    boolean lastOfBar = k == cell.length - 1;
                    boolean endsPhrase = i == length - 1 && lastOfBar;
                    boolean onBeat = onset[0] % 1 == 0;
                    int direction = contour(answer, (double) heard / onsets);

                    boolean approaching = lastOfBar && !endsPhrase && bar + 1 < bars && onset[0] > 0;
                    int pitch;
                    if (endsPhrase) {
                        pitch = pickChordTone(chord, previous);
                    } else if (approaching) {
                        int target = guideToneNear(request.bars().get(bar + 1).chord(), previous);
                        pitch = approach(request.bars().get(bar + 1).chord(), target, previous);
                        nextOpening = target;
                    } else if (first && nextOpening != null) {
                        pitch = nextOpening;
                    } else if (first) {
                        pitch = pickGuideTone(chord, previous + direction * 2);
                    } else if (answer && heard < shape.size() && !first) {
                        pitch = follow(chord, previous, shape.get(heard));
                    } else {
                        pitch = pickNote(chord, previous, onBeat, direction);
                    }
                    heardSteps.add(pitch - previous);
                    if (!approaching) {
                        nextOpening = null; // the bar that led to the arrival is the only one that uses it
                    }
                    previous = pitch;

                    long start = request.startMs() + request.clock().beatsToMillis(barStart + onset[0]);
                    long duration = Math.round(request.clock().beatsToMillis(onset[1]) * 0.9);
                    int velocity = 84 + random.nextInt(10) + (onBeat ? 0 : 6) + (endsPhrase ? -6 : 0);
                    notes.add(new NoteEvent(instrument, pitch, start, duration, velocity));
                }
            }
            shape = answer ? List.of() : heardSteps;
        }
        return notes;
    }

    /**
     * The scale tone nearest to where the statement's step would have taken the line: the same shape, fitted to this chord.
     * It is often exactly the same step, which is what makes the answer sound like the statement.
     */
    private int follow(Chord chord, int previous, int step) {
        int wanted = previous + step;
        return candidates(wanted, chord.scalePitchClasses()).get(0);
    }

    private double[][][] freshCells(int bars) {
        double[][][] cells = new double[bars][][];
        for (int i = 0; i < bars; i++) {
            cells[i] = profile.cells()[random.nextInt(profile.cells().length)];
        }
        return cells;
    }

    /** The rhythm of the statement again for the answer, perhaps with a new ending. */
    private double[][][] echo(double[][][] statement) {
        double[][][] cells = statement.clone();
        if (random.nextBoolean()) {
            cells[cells.length - 1] = profile.cells()[random.nextInt(profile.cells().length)];
        }
        return cells;
    }

    /** The last bar of a phrase: its notes up to the third beat, the last one held, and then the player takes a breath. */
    static double[][] breathe(double[][] cell) {
        List<double[]> kept = new ArrayList<>();
        for (double[] onset : cell) {
            if (onset[0] < 2.5 || kept.isEmpty()) {
                kept.add(onset);
            }
        }
        double[] last = kept.get(kept.size() - 1).clone();
        last[1] = Math.max(1.0, Math.min(2.0, BREATH_END - last[0]));
        kept.set(kept.size() - 1, last);
        return kept.toArray(new double[0][]);
    }

    /** Up then down in a statement; down then up in an answer. */
    private static int contour(boolean answer, double progress) {
        boolean rising = progress < 0.45;
        return answer ? (rising ? -1 : 1) : (rising ? 1 : -1);
    }

    /** The guide tone (third or seventh) of the chord that is nearest to where the line is. */
    private int guideToneNear(Chord chord, int near) {
        return candidates(near, chord.thirdPc(), chord.seventhPc()).get(0);
    }

    /**
     * The note that leads into the next chord's opening note. In real solos the last note of a bar already belongs to the
     * next chord's scale about three times in four, and is one of its chord tones about two in five, so mostly this is another
     * tone of the next chord or a step of its scale beside the target, and now and then a chromatic half step.
     */
    private int approach(Chord next, int target, int from) {
        double choice = random.nextDouble();
        int side = from == target ? (random.nextBoolean() ? 1 : -1) : Integer.signum(from - target);
        List<Integer> options;
        if (choice < APPROACH_CHORD_TONE) {
            options = candidates(from, next.root(), next.thirdPc(), next.fifthPc(), next.seventhPc());
        } else if (choice < APPROACH_CHORD_TONE + APPROACH_SCALE) {
            options = candidates(from, next.scalePitchClasses());
        } else {
            return clamp(target + side);
        }
        List<Integer> near = options.stream()
                .filter(m -> m != target && Math.abs(m - target) <= 4 && Math.abs(m - from) <= 2 * STEP_LIMIT).toList();
        return near.isEmpty() ? clamp(target + side) : near.get(random.nextInt(Math.min(2, near.size())));
    }

    private int clamp(int midi) {
        return Math.max(profile.low(), Math.min(profile.high(), midi));
    }

    /** The chord tone closest to where the line is: a phrase comes to rest on one. */
    private int pickChordTone(Chord chord, int near) {
        List<Integer> candidates = candidates(near, chord.root(), chord.thirdPc(), chord.fifthPc(), chord.seventhPc());
        return candidates.get(random.nextInt(Math.min(2, candidates.size())));
    }

    /** A third or seventh of the chord, one of the two nearest to where the line was. */
    private int pickGuideTone(Chord chord, int near) {
        List<Integer> candidates = candidates(near, chord.thirdPc(), chord.seventhPc());
        return candidates.get(random.nextInt(Math.min(2, candidates.size())));
    }

    /** A chord tone on the beat; off the beat, a scale tone when the profile allows passing notes. */
    private int pickNote(Chord chord, int near, boolean onBeat) {
        return pickNote(chord, near, onBeat, 0);
    }

    /** As above, leaning toward the direction the phrase is going (1 up, -1 down, 0 either way). */
    private int pickNote(Chord chord, int near, boolean onBeat, int direction) {
        int[] pitchClasses = (!onBeat && profile.scaleOnOffbeats())
                ? chord.scalePitchClasses()
                : new int[] {chord.root(), chord.thirdPc(), chord.fifthPc(), chord.seventhPc(), chord.pitchClass(2)};
        List<Integer> candidates = candidates(near, pitchClasses);
        List<Integer> close = candidates.stream()
                .filter(m -> m != near && Math.abs(m - near) <= STEP_LIMIT).toList();
        if (close.isEmpty()) {
            return candidates.get(0);
        }
        if (direction != 0 && random.nextDouble() < DIRECTION_BIAS) {
            List<Integer> going = close.stream().filter(m -> Integer.signum(m - near) == direction).toList();
            if (!going.isEmpty()) {
                return going.get(random.nextInt(going.size()));
            }
        }
        return close.get(random.nextInt(close.size()));
    }

    /** Notes in the instrument's register with the given pitch classes, nearest to {@code near} first. */
    private List<Integer> candidates(int near, int... pitchClasses) {
        List<Integer> result = new ArrayList<>();
        for (int midi = profile.low(); midi <= profile.high(); midi++) {
            for (int pc : pitchClasses) {
                if (midi % 12 == pc) {
                    result.add(midi);
                }
            }
        }
        result.sort(Comparator.comparingInt(m -> Math.abs(m - near)));
        return result;
    }
}
