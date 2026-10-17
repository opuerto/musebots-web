package dev.musebots.core;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * A swing drum kit: ride cymbal playing the "ding, ding-a-ding" pattern, hi-hat on 2 and 4, feathered
 * kick on every beat and the occasional ghost snare. Pitches are General MIDI percussion note numbers.
 */
public final class DrumComposer implements PhraseComposer {

    public static final int KICK = 36;
    public static final int SNARE = 38;
    public static final int HI_HAT_PEDAL = 44;
    public static final int RIDE = 51;
    public static final int CRASH = 49;
    public static final int TOM_HIGH = 50;
    public static final int TOM_MID = 47;
    public static final int TOM_LOW = 45;
    public static final int TOM_FLOOR = 41;

    private static final double TRIPLET = 1.0 / 3.0;

    private final RandomGenerator random;

    public DrumComposer(RandomGenerator random) {
        this.random = random;
    }

    @Override
    public Instrument instrument() {
        return Instrument.DRUMS;
    }

    @Override
    public List<NoteEvent> compose(PhraseRequest request) {
        return switch (request.kind()) {
            case SOLO -> solo(request);
            case ENDING -> ending(request);
            case INTRO, HEAD, SUPPORT, SUPPORT_LIGHT -> swing(request);
        };
    }

    /** One crash and the kick together, then silence. */
    private List<NoteEvent> ending(PhraseRequest request) {
        List<NoteEvent> notes = new ArrayList<>();
        hit(notes, request, CRASH, 0, 2.0, 100);
        hit(notes, request, KICK, 0, 0.3, 100);
        return notes;
    }

    /** Keeps the ride going through the first half of each bar and fills the second half. */
    private List<NoteEvent> solo(PhraseRequest request) {
        List<NoteEvent> notes = new ArrayList<>();
        int beatsPerBar = request.clock().beatsPerBar();

        for (int bar = 0; bar < request.bars().size(); bar++) {
            double start = (double) bar * beatsPerBar;
            if (bar == 0) { // a crash to start the solo
                hit(notes, request, CRASH, start, 2.0, 92);
            }
            hit(notes, request, RIDE, start, 0.5, 76);
            hit(notes, request, RIDE, start + 1, 0.5, 84);
            hit(notes, request, RIDE, start + 1 + Swing.OFFBEAT, 0.3, 66);
            hit(notes, request, HI_HAT_PEDAL, start + 1, 0.2, 72);
            hit(notes, request, KICK, start, 0.2, 70 + random.nextInt(12));

            switch (random.nextInt(4)) {
                case 0 -> { // comping
                    hit(notes, request, SNARE, start + 2 + Swing.OFFBEAT, 0.2, 78);
                    hit(notes, request, KICK, start + 3, 0.2, 84);
                    hit(notes, request, SNARE, start + 3 + Swing.OFFBEAT, 0.2, 74);
                }
                case 1 -> triplets(notes, request, start + 2,
                        new int[] {SNARE, SNARE, TOM_HIGH, TOM_MID, TOM_LOW, TOM_FLOOR}, 70, 5); // tom run
                case 2 -> { // snare roll into the next bar
                    hit(notes, request, KICK, start + 2, 0.2, 80);
                    triplets(notes, request, start + 3, new int[] {SNARE, SNARE, SNARE}, 66, 10);
                }
                default -> { // "bam, ba-da"
                    hit(notes, request, SNARE, start + 2, 0.2, 88);
                    hit(notes, request, KICK, start + 2 + Swing.OFFBEAT, 0.2, 82);
                    hit(notes, request, SNARE, start + 3, 0.2, 90);
                    hit(notes, request, KICK, start + 3 + Swing.OFFBEAT, 0.2, 80);
                }
            }
        }
        return notes;
    }

    private void triplets(List<NoteEvent> notes, PhraseRequest request, double startBeat, int[] pieces,
                          int firstVelocity, int crescendo) {
        for (int i = 0; i < pieces.length; i++) {
            hit(notes, request, pieces[i], startBeat + i * TRIPLET, 0.25, firstVelocity + i * crescendo);
        }
    }

    private List<NoteEvent> swing(PhraseRequest request) {
        List<NoteEvent> notes = new ArrayList<>();
        int beatsPerBar = request.clock().beatsPerBar();

        for (int bar = 0; bar < request.bars().size(); bar++) {
            double barStart = (double) bar * beatsPerBar;
            for (int beat = 0; beat < beatsPerBar; beat++) {
                boolean backbeat = beat % 2 == 1; // beats 2 and 4

                hit(notes, request, RIDE, barStart + beat, 0.5, backbeat ? 84 : 76);
                if (backbeat) {
                    hit(notes, request, RIDE, barStart + beat + Swing.OFFBEAT, 0.3, 66);
                    hit(notes, request, HI_HAT_PEDAL, barStart + beat, 0.2, 72);
                }
                hit(notes, request, KICK, barStart + beat, 0.2, 32 + random.nextInt(12)); // feathered
            }

            if (random.nextInt(100) < 40) { // ghost snare on a swung off-beat
                int beat = random.nextInt(beatsPerBar);
                hit(notes, request, SNARE, barStart + beat + Swing.OFFBEAT, 0.15, 44 + random.nextInt(16));
            }
        }
        return notes;
    }

    private void hit(List<NoteEvent> notes, PhraseRequest request, int piece, double beat,
                     double lengthBeats, int velocity) {
        long start = request.startMs() + request.clock().beatsToMillis(beat);
        notes.add(new NoteEvent(Instrument.DRUMS, piece, start, request.clock().beatsToMillis(lengthBeats), velocity));
    }
}
