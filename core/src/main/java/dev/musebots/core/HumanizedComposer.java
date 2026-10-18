package dev.musebots.core;

import java.util.List;
import java.util.random.RandomGenerator;

/** Plays another composer's music with a human feel. */
public final class HumanizedComposer implements PhraseComposer {

    private final PhraseComposer composer;
    private final Humanizer humanizer;
    private final RandomGenerator random;

    public HumanizedComposer(PhraseComposer composer, Humanizer humanizer, RandomGenerator random) {
        this.composer = composer;
        this.humanizer = humanizer;
        this.random = random;
    }

    @Override
    public Instrument instrument() {
        return composer.instrument();
    }

    @Override
    public List<NoteEvent> compose(PhraseRequest request) {
        return humanizer.apply(composer.compose(request), request, random);
    }
}
