package dev.musebots.learning;

import dev.musebots.core.Composers;
import dev.musebots.core.Instrument;
import dev.musebots.core.MusicClock;
import dev.musebots.core.NoteEvent;
import dev.musebots.core.PhraseComposer;
import dev.musebots.core.PhraseKind;
import dev.musebots.core.PhraseRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/** The ways of writing a solo that an evaluation compares. */
public final class Systems {

    private static final MusicClock CLOCK = new MusicClock(120, 4);

    private Systems() {
    }

    /**
     * A system that has a band composer write the solo, exactly as it would in a performance. Only its line is measured: a
     * pianist's left-hand chords are left out (see {@link #topLine}).
     */
    public static Evaluation.System ofComposer(String name, Function<RandomGenerator, PhraseComposer> composers) {
        return new Evaluation.System(name, (task, random) -> {
            List<NoteEvent> events = composers.apply(random).compose(
                    new PhraseRequest(PhraseKind.SOLO, Evaluation.asBars(task.bars()), CLOCK, 0));
            return Evaluation.lineOver(task, topLine(NoteTicks.fromEvents(events, 0, CLOCK)));
        });
    }

    /** The band's hand-written rules for improvising, on the saxophone. */
    public static Evaluation.System rules() {
        return rules(Instrument.SAX);
    }

    /** The band's hand-written rules for improvising on {@code instrument}. */
    public static Evaluation.System rules(Instrument instrument) {
        requireLine(instrument);
        return ofComposer("rules (the band today)", random -> Composers.defaultFor(instrument, random));
    }

    /** The learned model, soloing in the band's saxophone register. */
    public static Evaluation.System markov(MarkovModel model, double temperature) {
        return markov(model, temperature, Instrument.SAX);
    }

    /** The learned model, soloing in {@code instrument}'s register. */
    public static Evaluation.System markov(MarkovModel model, double temperature, Instrument instrument) {
        requireLine(instrument);
        return ofComposer("markov (temperature " + temperature + ")", random ->
                SoloModelComposer.wrap(Composers.defaultFor(instrument, random), model, random, temperature));
    }

    /** The measures are about pitches against chords, which a drum kit does not have. */
    private static void requireLine(Instrument instrument) {
        if (instrument == Instrument.DRUMS) {
            throw new IllegalArgumentException("the drums play no pitches, so their solos cannot be measured against the chords");
        }
    }

    /**
     * The single line in these notes (in time order): three or more notes starting together are a chord and are left out, and
     * of two the higher is kept. A line note struck with a chord goes too, the same for every system.
     */
    static List<TickNote> topLine(List<TickNote> notes) {
        List<TickNote> line = new ArrayList<>();
        int i = 0;
        while (i < notes.size()) {
            int j = i;
            TickNote top = notes.get(i);
            while (j < notes.size() && notes.get(j).tick() == top.tick()) {
                top = notes.get(j).pitch() > top.pitch() ? notes.get(j) : top;
                j++;
            }
            if (j - i < 3) {
                line.add(top);
            }
            i = j;
        }
        return line;
    }
}
