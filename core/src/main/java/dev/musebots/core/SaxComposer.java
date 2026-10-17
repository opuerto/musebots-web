package dev.musebots.core;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The saxophone. It states the melody over the head, improvises in its solo, holds the last note, and
 * otherwise lays out, leaving the space to the others.
 */
public final class SaxComposer implements PhraseComposer {

    static final int LOW = 57; // A3
    static final int HIGH = 81; // A5
    private static final int START_NEAR = 69; // A4

    private final Improviser head;
    private final Improviser solo;

    public SaxComposer(RandomGenerator random) {
        this.head = new Improviser(Instrument.SAX,
                new Improviser.Profile(LOW, HIGH, START_NEAR, Improviser.MELODY_CELLS, false), random);
        this.solo = new Improviser(Instrument.SAX,
                new Improviser.Profile(LOW, HIGH, START_NEAR, Improviser.SOLO_CELLS, true), random);
    }

    @Override
    public Instrument instrument() {
        return Instrument.SAX;
    }

    @Override
    public List<NoteEvent> compose(PhraseRequest request) {
        return switch (request.kind()) {
            case HEAD -> head.line(request);
            case SOLO -> solo.line(request);
            case ENDING -> List.of(heldNote(request));
            case INTRO, SUPPORT -> List.of();
        };
    }

    private static NoteEvent heldNote(PhraseRequest request) {
        int root = request.bars().get(0).chord().root();
        int midi = START_NEAR + Math.floorMod(root - START_NEAR, 12) - 12;
        return new NoteEvent(Instrument.SAX, Math.max(LOW, midi), request.startMs(),
                request.clock().beatsToMillis(3), 92);
    }
}
