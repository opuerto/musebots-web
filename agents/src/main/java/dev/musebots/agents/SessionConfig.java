package dev.musebots.agents;

import dev.musebots.core.Instrument;
import dev.musebots.core.SongStructure;
import java.time.Clock;
import java.util.List;

/** One performance: which song, who leads, who accompanies. */
public record SessionConfig(
        String sessionId,
        SongStructure song,
        int choruses,
        Instrument leader,
        List<Instrument> accompanists,
        long leadInMs,
        long seed,
        Clock clock) {

    public SessionConfig {
        accompanists = List.copyOf(accompanists);
    }

    public int ensembleSize() {
        return 1 + accompanists.size();
    }

    /** A leader and a single accompanist: the smallest ensemble that can negotiate. */
    public static SessionConfig duo(String sessionId, SongStructure song, int choruses) {
        return new SessionConfig(sessionId, song, choruses, Instrument.PIANO, List.of(Instrument.BASS),
                2_000, System.nanoTime(), Clock.systemUTC());
    }
}
