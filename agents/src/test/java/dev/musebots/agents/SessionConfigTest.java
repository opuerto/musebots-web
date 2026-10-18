package dev.musebots.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.musebots.core.Instrument;
import dev.musebots.core.Songs;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionConfigTest {

    private static SessionConfig quartet(SessionConfig.TurnOrder order, long seed) {
        return SessionConfig.of("s", Songs.bluesInF(120), 1, order, Instrument.SAX,
                List.of(Instrument.PIANO, Instrument.BASS, Instrument.DRUMS), seed);
    }

    @Test
    void fixedOrderIsTheClassicOne() {
        SessionConfig fixed = quartet(SessionConfig.TurnOrder.FIXED, 7);
        assertEquals(Instrument.SAX, fixed.leader());
        assertEquals(List.of(Instrument.SAX, Instrument.PIANO, Instrument.BASS, Instrument.DRUMS), fixed.soloOrder());
        assertEquals(Instrument.PIANO, fixed.introPreference().get(0));
    }

    @Test
    void randomOrderKeepsEveryoneInAndVariesAcrossSeeds() {
        Set<Instrument> leaders = new HashSet<>();
        Set<List<Instrument>> orders = new HashSet<>();
        Set<Instrument> introFavourites = new HashSet<>();
        for (long seed = 0; seed < 60; seed++) {
            SessionConfig session = quartet(SessionConfig.TurnOrder.RANDOM, seed);
            assertEquals(4, session.ensembleSize());
            assertEquals(Set.of(Instrument.values()), new HashSet<>(session.soloOrder()));
            assertEquals(4, session.soloOrder().size());
            assertNotEquals(Instrument.DRUMS, session.leader(), "a drummer cannot play the head");
            assertTrue(!session.accompanists().contains(session.leader()));
            leaders.add(session.leader());
            orders.add(session.soloOrder());
            introFavourites.add(session.introPreference().get(0));
        }
        assertEquals(Set.of(Instrument.SAX, Instrument.PIANO, Instrument.BASS), leaders);
        assertTrue(orders.size() > 10, "solo orders should vary: " + orders.size());
        assertTrue(introFavourites.size() >= 3, "intro favourites should vary");
    }

    @Test
    void theSameSeedGivesTheSameTurns() {
        assertEquals(quartet(SessionConfig.TurnOrder.RANDOM, 42).soloOrder(),
                quartet(SessionConfig.TurnOrder.RANDOM, 42).soloOrder());
    }
}
