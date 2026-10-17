package dev.musebots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SupportPolicyTest {

    @Test
    void everyoneBacksUpAHeadOrASoloInTheNormalWay() {
        for (Instrument leader : new Instrument[] {Instrument.SAX, Instrument.PIANO, Instrument.BASS}) {
            assertEquals(PhraseKind.SUPPORT, SupportPolicy.supportFor(leader, PhraseKind.SOLO));
            assertEquals(PhraseKind.SUPPORT, SupportPolicy.supportFor(leader, PhraseKind.HEAD));
        }
    }

    @Test
    void behindADrumSoloEveryoneKeepsPlayingButSoftly() {
        assertEquals(PhraseKind.SUPPORT_LIGHT, SupportPolicy.supportFor(Instrument.DRUMS, PhraseKind.SOLO));
    }

    @Test
    void everyoneJoinsTheEnding() {
        for (Instrument leader : Instrument.values()) {
            assertEquals(PhraseKind.ENDING, SupportPolicy.supportFor(leader, PhraseKind.ENDING));
        }
    }
}
