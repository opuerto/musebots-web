package dev.musebots.core;

/** Converts musical time (beats, bars) to wall-clock milliseconds for a fixed tempo. */
public record MusicClock(int tempoBpm, int beatsPerBar) {

    public MusicClock {
        if (tempoBpm <= 0 || beatsPerBar <= 0) {
            throw new IllegalArgumentException("tempo and beatsPerBar must be positive");
        }
    }

    /** The exact length of a beat. {@code beatsToMillis(1)} is this rounded, so never multiply that by fractional beats. */
    public double millisPerBeat() {
        return 60_000.0 / tempoBpm;
    }

    public long beatsToMillis(double beats) {
        return Math.round(beats * 60_000.0 / tempoBpm);
    }

    public long barsToMillis(int bars) {
        return beatsToMillis((double) bars * beatsPerBar);
    }

    /** The number of whole bars that fit {@code millis}, rounded to the nearest bar. */
    public int millisToBars(long millis) {
        return (int) Math.round((double) millis / barsToMillis(1));
    }
}
