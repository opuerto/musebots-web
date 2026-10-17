package dev.musebots.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SupportPolicyTest {

    @Test
    void theSaxLaysOutBehindOtherSoloists() {
        assertTrue(SupportPolicy.laysOut(Instrument.SAX, Instrument.PIANO, PhraseKind.SUPPORT));
    }

    @Test
    void theRhythmSectionBacksUpHornsAndKeysButNotADrumSolo() {
        assertFalse(SupportPolicy.laysOut(Instrument.BASS, Instrument.SAX, PhraseKind.SUPPORT));
        assertFalse(SupportPolicy.laysOut(Instrument.DRUMS, Instrument.PIANO, PhraseKind.SUPPORT));
        assertTrue(SupportPolicy.laysOut(Instrument.BASS, Instrument.DRUMS, PhraseKind.SUPPORT));
        assertTrue(SupportPolicy.laysOut(Instrument.PIANO, Instrument.DRUMS, PhraseKind.SUPPORT));
    }

    @Test
    void everyoneJoinsTheEnding() {
        for (Instrument self : Instrument.values()) {
            assertFalse(SupportPolicy.laysOut(self, Instrument.DRUMS, PhraseKind.ENDING));
        }
    }
}
