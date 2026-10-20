package dev.musebots.core;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The saxophone. It states the melody over the head, improvises in its solo, holds the last note, and behind the others
 * plays soft pads. When it wins the intro it plays it alone, as a melody over the turnaround that leads into the head.
 */
public final class SaxComposer implements PhraseComposer {

    static final int LOW = 57; // A3
    static final int HIGH = 81; // A5
    private static final int START_NEAR = 69; // A4
    private static final int PAD_BASE = 62; // D4: pads sit in the middle of the horn's range

    private final RandomGenerator random;
    private final Improviser head;
    private final Improviser solo;

    public SaxComposer(RandomGenerator random) {
        this.random = random;
        this.head = new Improviser(Instrument.SAX,
                new Improviser.Profile(LOW, HIGH, START_NEAR, Improviser.MELODY_CELLS, false), random);
        this.solo = new Improviser(Instrument.SAX,
                new Improviser.Profile(LOW, HIGH, START_NEAR, Improviser.SOLO_CELLS, true, true), random);
    }

    @Override
    public Instrument instrument() {
        return Instrument.SAX;
    }

    @Override
    public List<NoteEvent> compose(PhraseRequest request) {
        return switch (request.kind()) {
            case INTRO, HEAD -> head.line(request);
            case SOLO -> solo.line(request);
            case ENDING -> List.of(heldNote(request));
            case SUPPORT -> pads(request, 54);
            case SUPPORT_LIGHT -> pads(request, 40);
        };
    }

    /** Behind someone else's part the horn sustains a soft guide tone every other bar. */
    private List<NoteEvent> pads(PhraseRequest request, int velocity) {
        List<NoteEvent> notes = new ArrayList<>();
        int beatsPerBar = request.clock().beatsPerBar();
        for (int bar = 0; bar < request.bars().size(); bar += 2) {
            Chord chord = request.bars().get(bar).chord();
            int pc = random.nextBoolean() ? chord.thirdPc() : chord.seventhPc();
            int midi = PAD_BASE + Math.floorMod(pc - PAD_BASE, 12);
            double beats = Math.min(2.0 * beatsPerBar - 0.5, (request.bars().size() - bar) * (double) beatsPerBar);
            notes.add(new NoteEvent(Instrument.SAX, midi, request.startMs() + request.clock().beatsToMillis(bar * (double) beatsPerBar),
                    request.clock().beatsToMillis(beats), velocity + random.nextInt(8)));
        }
        return notes;
    }

    private static NoteEvent heldNote(PhraseRequest request) {
        int root = request.bars().get(0).chord().root();
        int midi = START_NEAR + Math.floorMod(root - START_NEAR, 12) - 12;
        return new NoteEvent(Instrument.SAX, Math.max(LOW, midi), request.startMs(),
                request.clock().beatsToMillis(3), 92);
    }
}
