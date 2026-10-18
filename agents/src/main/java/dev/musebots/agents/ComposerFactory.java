package dev.musebots.agents;

import dev.musebots.core.Composers;
import dev.musebots.core.HumanizedComposer;
import dev.musebots.core.Humanizer;
import dev.musebots.core.Instrument;
import dev.musebots.core.PhraseComposer;
import java.util.random.RandomGenerator;

/**
 * Chooses what composes for each instrument of a performance. The agents ask for a composer and do not care how it writes,
 * so hand-written rules and a learned model can play in the same band.
 */
@FunctionalInterface
public interface ComposerFactory {

    PhraseComposer create(Instrument instrument, RandomGenerator random);

    /** The band's hand-written rules. */
    static ComposerFactory rules() {
        return Composers::defaultFor;
    }

    /** Whatever {@code base} composes, played with a human feel ({@link Humanizer}) instead of exactly as written. */
    static ComposerFactory humanized(ComposerFactory base) {
        return (instrument, random) -> new HumanizedComposer(base.create(instrument, random), Humanizer.standard(), random);
    }
}
