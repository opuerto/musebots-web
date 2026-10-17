package dev.musebots.core;

/** Jazz swing feel: the second eighth of a beat falls two thirds of the way through it (a 2:1 triplet feel). */
public final class Swing {

    /** Position of the swung off-beat within a beat, as a fraction of the beat. */
    public static final double OFFBEAT = 2.0 / 3.0;

    /** Length of the swung off-beat eighth, as a fraction of the beat. */
    public static final double OFFBEAT_LENGTH = 1.0 - OFFBEAT;

    private Swing() {
    }
}
