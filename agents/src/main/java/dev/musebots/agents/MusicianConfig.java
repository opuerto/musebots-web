package dev.musebots.agents;

import dev.musebots.core.EventSink;
import dev.musebots.core.Instrument;
import dev.musebots.core.SongStructure;
import java.time.Clock;
import java.util.List;

/** Everything a musician needs, injected at construction so agents hold no global state. */
public record MusicianConfig(
        String sessionId,
        Instrument instrument,
        /** True for the musician who leads the opening; the others wait to be led. */
        boolean opensPerformance,
        EventSink sink,
        Clock clock,
        /** The opening leader's song. Everyone else learns it from the leader. */
        SongStructure song,
        /** Who solos, in order. */
        List<Instrument> soloOrder,
        int soloChoruses,
        int ensembleSize,
        long leadInMs,
        /** How long before a part ends the leader starts arranging the next one. */
        long planAheadMs) {

    public MusicianConfig {
        soloOrder = List.copyOf(soloOrder);
    }
}
