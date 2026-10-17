package dev.musebots.core;

import java.util.random.RandomGenerator;

/** Factory for the default composer of each instrument. */
public final class Composers {

    private Composers() {
    }

    public static PhraseComposer defaultFor(Instrument instrument, RandomGenerator random) {
        return switch (instrument) {
            case BASS -> new WalkingBassComposer(random);
            case PIANO -> new PianoComposer(random);
            case DRUMS -> new DrumComposer(random);
            case SAX -> new SaxComposer(random);
        };
    }
}
