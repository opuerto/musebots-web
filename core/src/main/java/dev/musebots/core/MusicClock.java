package dev.musebots.core;

/** Converts musical time (beats, bars) to wall-clock milliseconds for a fixed tempo. */
public record MusicClock(int tempoBpm, int beatsPerBar) {

    public MusicClock {
        if (tempoBpm <= 0 || beatsPerBar <= 0) {
            throw new IllegalArgumentException("tempo and beatsPerBar must be positive");
        }
    }

    public long beatsToMillis(double beats) {
        return Math.round(beats * 60_000.0 / tempoBpm);
    }

    public long barsToMillis(int bars) {
        return beatsToMillis((double) bars * beatsPerBar);
    }
}
