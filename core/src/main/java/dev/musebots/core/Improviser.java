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

    /** How an instrument plays its line: its register and the rhythms it draws from. */
    record Profile(int low, int high, int startNear, double[][][] cells, boolean scaleOnOffbeats) {
    }

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

    /** Busier rhythms for improvising: runs of swung eighths with some space. */
    static final double[][][] SOLO_CELLS = {
            {{0, O}, {O, OL}, {1, O}, {1 + O, OL}, {2, O}, {2 + O, OL}, {3, O}, {3 + O, OL}},
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

    /** A third or seventh of the chord, one of the two nearest to where the line was. */
    private int pickGuideTone(Chord chord, int near) {
        List<Integer> candidates = candidates(near, chord.thirdPc(), chord.seventhPc());
        return candidates.get(random.nextInt(Math.min(2, candidates.size())));
    }

    /** A chord tone on the beat; off the beat, a scale tone when the profile allows passing notes. */
    private int pickNote(Chord chord, int near, boolean onBeat) {
        int[] pitchClasses = (!onBeat && profile.scaleOnOffbeats())
                ? chord.scalePitchClasses()
                : new int[] {chord.root(), chord.thirdPc(), chord.fifthPc(), chord.seventhPc(), chord.pitchClass(2)};
        List<Integer> candidates = candidates(near, pitchClasses);
        List<Integer> close = candidates.stream()
                .filter(m -> m != near && Math.abs(m - near) <= STEP_LIMIT).toList();
        return close.isEmpty() ? candidates.get(0) : close.get(random.nextInt(close.size()));
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
