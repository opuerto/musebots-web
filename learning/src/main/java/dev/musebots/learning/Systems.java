package dev.musebots.learning;

import dev.musebots.core.Composers;
import dev.musebots.core.Instrument;
import dev.musebots.core.MusicClock;
import dev.musebots.core.NoteEvent;
import dev.musebots.core.PhraseComposer;
import dev.musebots.core.PhraseKind;
import dev.musebots.core.PhraseRequest;
import java.util.List;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/** The ways of writing a solo that an evaluation compares. */
public final class Systems {

    private static final MusicClock CLOCK = new MusicClock(120, 4);

    private Systems() {
    }

    /** A system that has a band composer write the solo, exactly as it would in a performance. */
    public static Evaluation.System ofComposer(String name, Function<RandomGenerator, PhraseComposer> composers) {
        return new Evaluation.System(name, (task, random) -> {
            List<NoteEvent> events = composers.apply(random).compose(
                    new PhraseRequest(PhraseKind.SOLO, Evaluation.asBars(task.bars()), CLOCK, 0));
            return Evaluation.lineOver(task, NoteTicks.fromEvents(events, 0, CLOCK));
        });
    }

    /** The band's hand-written rules for improvising. */
    public static Evaluation.System rules() {
        return ofComposer("rules (the band today)", random -> Composers.defaultFor(Instrument.SAX, random));
    }

    /** The learned model, soloing in the band's saxophone register. */
    public static Evaluation.System markov(MarkovModel model, double temperature) {
        return ofComposer("markov (temperature " + temperature + ")", random ->
                SoloModelComposer.wrap(Composers.defaultFor(Instrument.SAX, random), model, random, temperature));
    }
}
