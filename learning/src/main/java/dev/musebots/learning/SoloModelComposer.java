package dev.musebots.learning;

import dev.musebots.core.Chord;
import dev.musebots.core.ChordBar;
import dev.musebots.core.DrumComposer;
import dev.musebots.core.Instrument;
import dev.musebots.core.NoteEvent;
import dev.musebots.core.PhraseComposer;
import dev.musebots.core.PhraseKind;
import dev.musebots.core.PhraseRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Plays an instrument's solos from a learned model, and everything else (head, accompaniment, ending) the way the
 * instrument normally does. Only the soloing changes, which is what makes a fair comparison with the rule-based composer.
 *
 * <p>The model learned on a straight grid; the band swings, so the notes are swung as they are played.
 *
 * <p>The drums have no pitches to learn, so they play the model's line as a melodic drummer would: its rhythm and phrasing
 * as they are, and its rise and fall spread over the kit from the floor tom up to the snare.
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
            case DRUMS -> new Range(DRUM_LOW, DRUM_LOW + 4 * DRUM_STEP - 1, DRUM_LOW + 2 * DRUM_STEP); // a line to spread over the kit
        };
    }

    /** The kit from low to high: the drums' line is cut into this many bands of {@link #DRUM_STEP} semitones. */
    private static final int[] KIT = {DrumComposer.TOM_FLOOR, DrumComposer.TOM_LOW, DrumComposer.TOM_MID, DrumComposer.SNARE};
    private static final int DRUM_LOW = 48;
    private static final int DRUM_STEP = 6;

    /** Has {@code composer}'s solos written by {@code model}, and everything else as {@code composer} writes it. */
    public static PhraseComposer wrap(PhraseComposer composer, MarkovModel model, RandomGenerator random, double temperature) {
        return new SoloModelComposer(composer, model, random, temperature);
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
                temperature, MarkovModel.DEFAULT_MOTIF_REPEAT, request.clock().tempoBpm(), random);

        if (instrument() == Instrument.DRUMS) {
            return drums(line, request);
        }
        List<NoteEvent> notes = new ArrayList<>(render(line, request));
        if (instrument() == Instrument.PIANO) {
            // The right hand plays the learned line; the left hand comps softly underneath, as the band's pianist does behind a drum solo.
            notes.addAll(fallback.compose(new PhraseRequest(PhraseKind.SUPPORT_LIGHT, request.bars(), request.clock(), request.startMs())));
        }
        return notes;
    }

    /**
     * The learned line on the kit: each note a stroke on the drum its pitch falls on, a kick under the first note of each
     * phrase, a crash to open the solo, and the hi-hat on 2 and 4 so the band can still feel where the beat is.
     */
    private List<NoteEvent> drums(List<TickNote> line, PhraseRequest request) {
        List<NoteEvent> notes = new ArrayList<>();
        int beatsPerBar = request.clock().beatsPerBar();
        hit(notes, request, DrumComposer.CRASH, 0, 2.0, 92);
        for (int beat = 1; beat < request.bars().size() * beatsPerBar; beat += 2) {
            hit(notes, request, DrumComposer.HI_HAT_PEDAL, beat, 0.2, 72);
        }
        int previousEnd = Integer.MIN_VALUE;
        double[] beats = RhythmGrid.playedBeats(line);
        for (int i = 0; i < line.size(); i++) {
            TickNote note = line.get(i);
            int band = Math.max(0, Math.min(KIT.length - 1, (note.pitch() - DRUM_LOW) / DRUM_STEP));
            double beat = beats[i];
            boolean onBeat = note.tick() % Corpus.TICKS_PER_BEAT == 0;
            hit(notes, request, KIT[band], beat, 0.25, (onBeat ? 84 : 72) + random.nextInt(12));
            if (note.tick() - previousEnd >= Corpus.TICKS_PER_BEAT) { // a phrase starts after a breath
                hit(notes, request, DrumComposer.KICK, beat, 0.2, 86);
            }
            previousEnd = note.end();
        }
        return notes;
    }

    private static void hit(List<NoteEvent> notes, PhraseRequest request, int piece, double beat, double lengthBeats, int velocity) {
        long start = request.startMs() + request.clock().beatsToMillis(beat);
        long end = Math.min(request.endMs(), start + request.clock().beatsToMillis(lengthBeats));
        notes.add(new NoteEvent(Instrument.DRUMS, piece, start, Math.max(1, end - start), velocity));
    }

    /** Puts the notes on the band's clock: eighths swung, triplets and sixteenths even ({@link RhythmGrid#playedBeats}). */
    private List<NoteEvent> render(List<TickNote> line, PhraseRequest request) {
        double[] beats = RhythmGrid.playedBeats(line);
        double phraseBeats = (double) request.bars().size() * request.clock().beatsPerBar();
        List<NoteEvent> events = new ArrayList<>();
        for (int i = 0; i < line.size(); i++) {
            TickNote note = line.get(i);
            // Each time is worked out from its position in beats, as the other composers do, so nothing drifts at tempos
            // where a beat is not a whole number of milliseconds.
            double next = i + 1 < line.size() ? beats[i + 1] : phraseBeats;
            double endBeat = Math.min(next, beats[i] + (double) note.duration() / Corpus.TICKS_PER_BEAT);
            long start = request.startMs() + request.clock().beatsToMillis(beats[i]);
            long end = request.startMs() + request.clock().beatsToMillis(endBeat);
            long duration = Math.max(1, Math.round((end - start) * 0.92)); // a little air between notes, as the other composers leave
            boolean offbeat = note.tick() % Corpus.TICKS_PER_BEAT != 0;
            events.add(new NoteEvent(instrument(), note.pitch(), start, duration, 84 + random.nextInt(10) + (offbeat ? 6 : 0)));
        }
        return events;
    }
}
