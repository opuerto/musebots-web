package dev.musebots.agents;

import dev.musebots.core.Instrument;
import dev.musebots.core.SongStructure;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** One performance: which song, who leads, who accompanies, and who takes a solo. */
public record SessionConfig(
        String sessionId,
        SongStructure song,
        int soloChoruses,
        Instrument leader,
        List<Instrument> accompanists,
        /** Who solos, in order. */
        List<Instrument> soloOrder,
        long leadInMs,
        /** How long before a part ends the leader starts arranging the next one. */
        long planAheadMs,
        long seed,
        Clock clock) {

    public SessionConfig {
        accompanists = List.copyOf(accompanists);
        soloOrder = List.copyOf(soloOrder);
    }

    public int ensembleSize() {
        return 1 + accompanists.size();
    }

    /** The jazz quartet from the paper: a sax leading, with piano, bass and drums, each taking a solo. */
    public static SessionConfig quartet(String sessionId, SongStructure song, int soloChoruses) {
        return of(sessionId, song, soloChoruses, Instrument.SAX,
                List.of(Instrument.PIANO, Instrument.BASS, Instrument.DRUMS));
    }

    /** A leader and a single accompanist: the smallest ensemble that can negotiate. */
    public static SessionConfig duo(String sessionId, SongStructure song, int soloChoruses) {
        return of(sessionId, song, soloChoruses, Instrument.PIANO, List.of(Instrument.BASS));
    }

    private static SessionConfig of(String sessionId, SongStructure song, int soloChoruses, Instrument leader,
                                    List<Instrument> accompanists) {
        List<Instrument> everyone = new ArrayList<>(List.of(leader));
        everyone.addAll(accompanists);
        long planAheadMs = Math.max(1_500, song.clock().barsToMillis(2));
        return new SessionConfig(sessionId, song, soloChoruses, leader, accompanists, everyone,
                2_000, planAheadMs, System.nanoTime(), Clock.systemUTC());
    }
}
