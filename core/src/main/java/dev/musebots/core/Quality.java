package dev.musebots.core;

/** Chord quality, expressed as semitone intervals above the root (root, third, fifth, seventh). */
public enum Quality {
    MAJ7(0, 4, 7, 11),
    DOM7(0, 4, 7, 10),
    MIN7(0, 3, 7, 10);

    private final int[] intervals;

    Quality(int... intervals) {
        this.intervals = intervals;
    }

    public int root() {
        return intervals[0];
    }

    public int third() {
        return intervals[1];
    }

    public int fifth() {
        return intervals[2];
    }

    public int seventh() {
        return intervals[3];
    }
}
