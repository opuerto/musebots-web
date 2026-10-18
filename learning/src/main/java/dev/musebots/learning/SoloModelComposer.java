package dev.musebots.learning;

import dev.musebots.core.Chord;
import dev.musebots.core.ChordBar;
import dev.musebots.core.Instrument;
import dev.musebots.core.NoteEvent;
import dev.musebots.core.PhraseComposer;
import dev.musebots.core.PhraseKind;
import dev.musebots.core.PhraseRequest;
import dev.musebots.core.Swing;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Plays an instrument's solos from a learned model, and everything else (head, accompaniment, ending) the way the
 * instrument normally does. Only the soloing changes, which is what makes a fair comparison with the rule-based composer.
 *
 * <p>The model learned on a straight grid; the band swings, so the notes are swung as they are played.
 */
public final class SoloModelComposer implements PhraseComposer {

    private final PhraseComposer fallback;
    private final MarkovModel model;
    private final RandomGenerator random;
    private final double temperature;

    public SoloModelComposer(PhraseComposer fallback, MarkovModel model, RandomGenerator random, double temperature) {
        this.fallback = fallback;
        this.model = model;
        this.random = random;
        this.temperature = temperature;
    }

    /** The register a soloist plays in, and where its line starts. */
    private record Range(int low, int high, int startNear) {
    }

    private static Range rangeOf(Instrument instrument) {
        return switch (instrument) {
            case SAX -> new Range(57, 81, 69);
            case PIANO -> new Range(60, 84, 72);
            case BASS -> new Range(36, 60, 48);
            default -> null; // the drums do not play a line
        };
    }

    /** Wraps {@code composer} if its instrument plays a line; otherwise returns it unchanged. */
    public static PhraseComposer wrap(PhraseComposer composer, MarkovModel model, RandomGenerator random, double temperature) {
        return rangeOf(composer.instrument()) == null ? composer : new SoloModelComposer(composer, model, random, temperature);
    }

    @Override
    public Instrument instrument() {
        return fallback.instrument();
    }

    @Override
    public List<NoteEvent> compose(PhraseRequest request) {
        if (request.kind() != PhraseKind.SOLO) {
            return fallback.compose(request);
        }
        Range range = rangeOf(instrument());
        List<Chord> chords = request.bars().stream().map(ChordBar::chord).toList();
        List<TickNote> line = model.generate(chords, request.clock().beatsPerBar(), range.startNear(), range.low(), range.high(),
                temperature, random);

        List<NoteEvent> notes = new ArrayList<>(render(line, request));
        if (instrument() == Instrument.PIANO) {
            // The right hand plays the learned line; the left hand comps softly underneath, as the band's pianist does behind a drum solo.
            notes.addAll(fallback.compose(new PhraseRequest(PhraseKind.SUPPORT_LIGHT, request.bars(), request.clock(), request.startMs())));
        }
        return notes;
    }

    /** Puts the notes on the swung clock. */
    private List<NoteEvent> render(List<TickNote> line, PhraseRequest request) {
        List<NoteEvent> events = new ArrayList<>();
        for (TickNote note : line) {
            // Each time is worked out from its position in beats, as the other composers do, so nothing drifts at tempos
            // where a beat is not a whole number of milliseconds.
            long start = request.startMs() + request.clock().beatsToMillis(Swing.swungBeats((double) note.tick() / Corpus.TICKS_PER_BEAT));
            long end = request.startMs() + request.clock().beatsToMillis(Swing.swungBeats((double) note.end() / Corpus.TICKS_PER_BEAT));
            long duration = Math.max(1, Math.round((end - start) * 0.92)); // a little air between notes, as the other composers leave
            boolean offbeat = note.tick() % Corpus.TICKS_PER_BEAT != 0;
            events.add(new NoteEvent(instrument(), note.pitch(), start, duration, 84 + random.nextInt(10) + (offbeat ? 6 : 0)));
        }
        return events;
    }
}
