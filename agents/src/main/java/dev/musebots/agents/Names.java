package dev.musebots.agents;

import dev.musebots.core.Instrument;
import jade.core.AID;

/** Each Musebot is three agents; this is where their names come from. */
final class Names {

    enum Part {
        MUSICIAN,
        COMPOSER,
        SYNCHRONIZER
    }

    private Names() {
    }

    static String of(String sessionId, Instrument instrument, Part part) {
        return sessionId + "-" + instrument.name().toLowerCase() + "-" + part.name().toLowerCase();
    }

    static AID aid(String sessionId, Instrument instrument, Part part) {
        return new AID(of(sessionId, instrument, part), AID.ISLOCALNAME);
    }
}
