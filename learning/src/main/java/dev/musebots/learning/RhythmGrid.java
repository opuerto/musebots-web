package dev.musebots.learning;

import dev.musebots.core.Swing;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The rhythms jazz players think in, beat by beat: swung eighths, triplets or sixteenths. Transcriptions of real solos put
 * the same swung off-beat at different places in the beat (on twelve ticks a beat: the 6, the 8 or the 9, by how hard the
 * player swung at that moment), and some notes between the lines. A model that learns those positions as they are mixes
 * them freely, and the band then swings them a second time, so the line sounds out of time. Cleaning each beat to the
 * simplest of the three patterns that fits its notes keeps the rhythm the player meant, and leaves the swing to the band.
 */
public final class RhythmGrid {

    private static final int TPB = Corpus.TICKS_PER_BEAT;

    /** A way of dividing a beat, the points in it a note may start on, and what using it costs over plain eighths. */
    private enum Pattern {
        EIGHTHS(new int[] {0, 6}, 0),
        TRIPLETS(new int[] {0, 4, 8}, 3),
        SIXTEENTHS(new int[] {0, 3, 6, 9}, 4);

        final int[] points;
        final int complexity; // in ticks of moving notes: a beat is only heard as triplets or sixteenths when that fits clearly better

        Pattern(int[] points, int complexity) {
            this.points = points;
            this.complexity = complexity;
        }
    }

    /** Moving two notes onto one point drops one of them: as costly as moving a note half a beat. */
    private static final int COLLISION = TPB / 2;

    private RhythmGrid() {
    }

    /**
     * The line with each beat's notes moved to the simplest pattern that fits them. A note near the end of a beat may move to
     * the start of the next one, if nothing starts there. The line stays a single line in order: where two notes would start
     * together, the first is kept; and each note is cut short, if need be, where the next one starts.
     */
    public static List<TickNote> clean(List<TickNote> line) {
        List<TickNote> sorted = new ArrayList<>(line);
        sorted.sort(Comparator.comparingInt(TickNote::tick));
        List<TickNote> moved = new ArrayList<>();
        int i = 0;
        while (i < sorted.size()) {
            int beat = Math.floorDiv(sorted.get(i).tick(), TPB);
            int j = i;
            while (j < sorted.size() && Math.floorDiv(sorted.get(j).tick(), TPB) == beat) {
                j++;
            }
            List<TickNote> notes = sorted.subList(i, j);
            boolean nextBeatTaken = j < sorted.size() && sorted.get(j).tick() - (beat + 1) * TPB < 2;
            Pattern best = null;
            int bestCost = Integer.MAX_VALUE;
            for (Pattern pattern : Pattern.values()) {
                int cost = cost(notes, beat, pattern, nextBeatTaken);
                if (cost < bestCost) {
                    best = pattern;
                    bestCost = cost;
                }
            }
            for (TickNote note : notes) {
                moved.add(new TickNote(beat * TPB + nearest(note.tick() - beat * TPB, best), note.duration(), note.pitch()));
            }
            i = j;
        }

        List<TickNote> cleaned = new ArrayList<>();
        moved.sort(Comparator.comparingInt(TickNote::tick)); // stable: notes that now start together keep their order
        for (TickNote note : moved) {
            if (!cleaned.isEmpty() && cleaned.get(cleaned.size() - 1).tick() == note.tick()) {
                continue; // a single line: the first of two notes that start together
            }
            cleaned.add(note);
        }
        for (int k = 0; k + 1 < cleaned.size(); k++) {
            TickNote note = cleaned.get(k);
            int room = cleaned.get(k + 1).tick() - note.tick();
            if (note.duration() > room) {
                cleaned.set(k, new TickNote(note.tick(), room, note.pitch()));
            }
        }
        return cleaned;
    }

    /** How far the notes would move to fit the pattern, plus what it costs to use it and to lose notes to collisions. */
    private static int cost(List<TickNote> notes, int beat, Pattern pattern, boolean nextBeatTaken) {
        int cost = pattern.complexity;
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (TickNote note : notes) {
            int position = note.tick() - beat * TPB;
            int target = nearest(position, pattern);
            cost += Math.abs(target - position);
            if (!used.add(target) || (target == TPB && nextBeatTaken)) {
                cost += COLLISION;
            }
        }
        return cost;
    }

    /** The point of the pattern, or the start of the next beat, nearest to a position in the beat (the earlier one on a tie). */
    private static int nearest(int position, Pattern pattern) {
        int best = 0;
        for (int point : pattern.points) {
            if (Math.abs(point - position) < Math.abs(best - position)) {
                best = point;
            }
        }
        return Math.abs(TPB - position) < Math.abs(best - position) ? TPB : best;
    }

    /**
     * Where a cleaned line's notes fall when the band plays them, in beats from its start. A beat of eighths swings, its
     * off-beat moved to where the band swings it ({@link Swing#OFFBEAT}); a beat of triplets or sixteenths is played evenly.
     */
    public static double[] playedBeats(List<TickNote> line) {
        double[] beats = new double[line.size()];
        int i = 0;
        while (i < line.size()) {
            int beat = Math.floorDiv(line.get(i).tick(), TPB);
            int j = i;
            boolean eighths = true;
            while (j < line.size() && Math.floorDiv(line.get(j).tick(), TPB) == beat) {
                int position = line.get(j).tick() - beat * TPB;
                eighths &= position == 0 || position == TPB / 2;
                j++;
            }
            for (int k = i; k < j; k++) {
                int position = line.get(k).tick() - beat * TPB;
                beats[k] = beat + (eighths && position == TPB / 2 ? Swing.OFFBEAT : (double) position / TPB);
            }
            i = j;
        }
        return beats;
    }
}
