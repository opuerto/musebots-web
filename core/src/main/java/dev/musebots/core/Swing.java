package dev.musebots.core;

/** Jazz swing feel: the second eighth of a beat falls two thirds of the way through it (a 2:1 triplet feel). */
public final class Swing {

    /** Position of the swung off-beat within a beat, as a fraction of the beat. */
    public static final double OFFBEAT = 2.0 / 3.0;

    /** Length of the swung off-beat eighth, as a fraction of the beat. */
    public static final double OFFBEAT_LENGTH = 1.0 - OFFBEAT;

    private Swing() {
    }

    /**
     * Where a position inside a beat falls once swing is applied. Music is often written (and transcribed) with straight
     * eighths; the swung off-beat sits two thirds of the way through the beat. The first half of the beat is stretched
     * onto the first two thirds and the second half squeezed into the last third, so 0 stays 0 and 1 stays 1.
     *
     * @param fraction the position within the beat, from 0 (inclusive) to 1 (inclusive)
     */
    public static double swung(double fraction) {
        return fraction < 0.5
                ? fraction * OFFBEAT / 0.5
                : OFFBEAT + (fraction - 0.5) * (1.0 - OFFBEAT) / 0.5;
    }

    /** The inverse of {@link #swung}: where a swung position sits on the straight grid. */
    public static double straight(double swungFraction) {
        return swungFraction < OFFBEAT
                ? swungFraction * 0.5 / OFFBEAT
                : 0.5 + (swungFraction - OFFBEAT) * 0.5 / (1.0 - OFFBEAT);
    }

    /**
     * Where the swung off-beat falls at a tempo, as a fraction of the beat. Jazz players swing less as the tempo rises: the
     * long note of an eighth-note pair is about three times the short one at moderate tempos and the pair becomes nearly even
     * by around 300 beats a minute (after Friberg and Sundstrom, 2002; the figures here are an approximation, not their data).
     * The ratio is 3:1 up to 100 bpm, falls by one for every 100 bpm, and is 1:1 (straight) at 300.
     */
    public static double offbeatAtTempo(int tempoBpm) {
        double ratio = Math.max(1.0, Math.min(3.0, 3.0 - (tempoBpm - 100) / 100.0));
        return ratio / (1.0 + ratio);
    }

    /**
     * Moves a position inside a beat from where the standard swing ({@link #OFFBEAT}) puts the off-beat to where {@code offbeat}
     * puts it. Everything before the off-beat is stretched or squeezed to fit, and everything after it too, so order is kept and
     * the beat itself does not move.
     */
    public static double retarget(double fraction, double offbeat) {
        return fraction < OFFBEAT
                ? fraction * offbeat / OFFBEAT
                : offbeat + (fraction - OFFBEAT) * (1.0 - offbeat) / (1.0 - OFFBEAT);
    }

    /** {@link #retarget} for a time in beats. */
    public static double retargetBeats(double beats, double offbeat) {
        double whole = Math.floor(beats);
        return whole + retarget(beats - whole, offbeat);
    }

    /** {@link #swung} for a time in beats: the whole beats are kept and only the fraction is moved. */
    public static double swungBeats(double beats) {
        double whole = Math.floor(beats);
        return whole + swung(beats - whole);
    }

    /** {@link #straight} for a time in beats. */
    public static double straightBeats(double beats) {
        double whole = Math.floor(beats);
        return whole + straight(beats - whole);
    }
}
