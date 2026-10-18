package dev.musebots.agents;

import dev.musebots.core.Instrument;
import dev.musebots.core.SongStructure;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

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
        /** How long before a note is due its composer hands it to the listener. */
        long noteLookaheadMs,
        long seed,
        Clock clock,
        /** When several musicians offer an intro of the same length, the leader picks whoever is listed first. */
        List<Instrument> introPreference) {

    /** How the turns are decided: the classic order, or a different one every time. */
    public enum TurnOrder { FIXED, RANDOM }

    private static final List<Instrument> CLASSIC_INTRO_PREFERENCE =
            List.of(Instrument.PIANO, Instrument.BASS, Instrument.DRUMS, Instrument.SAX);

    public SessionConfig {
        accompanists = List.copyOf(accompanists);
        soloOrder = List.copyOf(soloOrder);
        introPreference = List.copyOf(introPreference);
    }

    /** A session with the classic intro preference (piano, bass, drums, sax). */
    public SessionConfig(String sessionId, SongStructure song, int soloChoruses, Instrument leader,
                         List<Instrument> accompanists, List<Instrument> soloOrder, long leadInMs, long planAheadMs,
                         long noteLookaheadMs, long seed, Clock clock) {
        this(sessionId, song, soloChoruses, leader, accompanists, soloOrder, leadInMs, planAheadMs, noteLookaheadMs,
                seed, clock, CLASSIC_INTRO_PREFERENCE);
    }

    public int ensembleSize() {
        return 1 + accompanists.size();
    }

    /** The jazz quartet from the paper, with the turns decided afresh each time. */
    public static SessionConfig quartet(String sessionId, SongStructure song, int soloChoruses) {
        return quartet(sessionId, song, soloChoruses, TurnOrder.RANDOM);
    }

    /** The quartet: with a fixed order the sax leads, and piano, bass and drums follow it. */
    public static SessionConfig quartet(String sessionId, SongStructure song, int soloChoruses, TurnOrder order) {
        return of(sessionId, song, soloChoruses, order, Instrument.SAX,
                List.of(Instrument.PIANO, Instrument.BASS, Instrument.DRUMS), System.nanoTime());
    }

    /** A leader and a single accompanist: the smallest ensemble that can negotiate. */
    public static SessionConfig duo(String sessionId, SongStructure song, int soloChoruses) {
        return duo(sessionId, song, soloChoruses, TurnOrder.RANDOM);
    }

    public static SessionConfig duo(String sessionId, SongStructure song, int soloChoruses, TurnOrder order) {
        return of(sessionId, song, soloChoruses, order, Instrument.PIANO, List.of(Instrument.BASS), System.nanoTime());
    }

    /**
     * Random turns: any melodic player may open the tune (a drummer cannot play a head), the solos go in a shuffled
     * order, and ties between intro offers are broken by a shuffled preference. The seed makes it reproducible.
     */
    static SessionConfig of(String sessionId, SongStructure song, int soloChoruses, TurnOrder order, Instrument leader,
                            List<Instrument> accompanists, long seed) {
        List<Instrument> everyone = new ArrayList<>(List.of(leader));
        everyone.addAll(accompanists);
        List<Instrument> soloOrder = everyone;
        List<Instrument> introPreference = CLASSIC_INTRO_PREFERENCE;
        if (order == TurnOrder.RANDOM) {
            Random random = new Random(seed);
            List<Instrument> melodic = everyone.stream().filter(i -> i != Instrument.DRUMS).toList();
            leader = melodic.get(random.nextInt(melodic.size()));
            Instrument opener = leader;
            accompanists = everyone.stream().filter(i -> i != opener).toList();
            soloOrder = new ArrayList<>(everyone);
            Collections.shuffle(soloOrder, random);
            introPreference = new ArrayList<>(CLASSIC_INTRO_PREFERENCE);
            Collections.shuffle(introPreference, random);
        }
        long planAheadMs = Math.max(1_500, song.clock().barsToMillis(2));
        long lookaheadMs = Math.max(1_000, song.clock().barsToMillis(1));
        return new SessionConfig(sessionId, song, soloChoruses, leader, accompanists, soloOrder,
                2_000, planAheadMs, lookaheadMs, seed, Clock.systemUTC(), introPreference);
    }
}
