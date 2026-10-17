package dev.musebots.agents;

import dev.musebots.core.EventSink;
import dev.musebots.core.Instrument;
import dev.musebots.core.PhraseComposer;
import dev.musebots.core.SongStructure;
import java.time.Clock;

/** Everything a musician needs, injected at construction so agents hold no global state. */
public record MusicianConfig(
        String sessionId,
        Instrument instrument,
        Role.Kind role,
        PhraseComposer composer,
        EventSink sink,
        Clock clock,
        /** Leader only: the song to play. Accompanists learn it from the leader. */
        SongStructure song,
        int choruses,
        int ensembleSize,
        long leadInMs) {

    public String agentName() {
        return sessionId + "-" + instrument.name().toLowerCase();
    }
}
