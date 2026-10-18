package dev.musebots.core;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/** Four-to-the-bar walking bass: root, chord tone, fifth, then a chromatic approach to the next root. */
public final class WalkingBassComposer implements PhraseComposer {

    static final int LOW = 36; // C2
    static final int HIGH = 55; // G3

    private final RandomGenerator random;
    private final Improviser solo;

    public WalkingBassComposer(RandomGenerator random) {
        this.random = random;
        this.solo = new Improviser(Instrument.BASS,
                new Improviser.Profile(LOW, 60, 48, Improviser.SOLO_CELLS, true, true), random);
    }

    @Override
    public Instrument instrument() {
        return Instrument.BASS;
    }

    @Override
    public List<NoteEvent> compose(PhraseRequest request) {
        return switch (request.kind()) {
            case SOLO -> solo.line(request);
            case ENDING -> List.of(heldRoot(request));
            case SUPPORT_LIGHT -> twoFeel(request);
            case INTRO, HEAD, SUPPORT -> walk(request);
        };
    }

    /** Half-time feel: the root on beat 1 and the fifth on beat 3, softly, leaving room for the drums. */
    private List<NoteEvent> twoFeel(PhraseRequest request) {
        List<NoteEvent> notes = new ArrayList<>();
        int beatsPerBar = request.clock().beatsPerBar();
        for (int bar = 0; bar < request.bars().size(); bar++) {
            Chord chord = request.bars().get(bar).chord();
            double barStart = (double) bar * beatsPerBar;
            notes.add(twoFeelNote(request, place(chord.root()), barStart, 62 + random.nextInt(8)));
            notes.add(twoFeelNote(request, place(chord.fifthPc()), barStart + beatsPerBar / 2.0, 54 + random.nextInt(8)));
        }
        return notes;
    }

    private static NoteEvent twoFeelNote(PhraseRequest request, int midi, double startBeat, int velocity) {
        return new NoteEvent(Instrument.BASS, midi, request.startMs() + request.clock().beatsToMillis(startBeat),
                Math.round(request.clock().beatsToMillis(request.clock().beatsPerBar() / 2.0) * 0.9), velocity);
    }

    private static NoteEvent heldRoot(PhraseRequest request) {
        int midi = place(request.bars().get(0).chord().root());
        return new NoteEvent(Instrument.BASS, midi, request.startMs(), request.clock().beatsToMillis(3), 100);
    }

    private List<NoteEvent> walk(PhraseRequest request) {
        List<ChordBar> bars = request.bars();
        List<NoteEvent> notes = new ArrayList<>();
        int beatsPerBar = request.clock().beatsPerBar();

        for (int bar = 0; bar < bars.size(); bar++) {
            Chord chord = bars.get(bar).chord();
            Chord next = bars.get((bar + 1) % bars.size()).chord();

            int[] line = new int[beatsPerBar];
            line[0] = place(chord.root());
            for (int beat = 1; beat < beatsPerBar - 1; beat++) {
                int pc = (beat == 1) ? (random.nextBoolean() ? chord.thirdPc() : chord.fifthPc())
                                     : chord.fifthPc();
                line[beat] = place(pc);
            }
            if (beatsPerBar > 1) {
                int target = place(next.root());
                int approach = random.nextBoolean() ? target - 1 : target + 1;
                line[beatsPerBar - 1] = clamp(approach);
            }

            for (int beat = 0; beat < beatsPerBar; beat++) {
                long start = request.startMs() + request.clock().beatsToMillis((double) bar * beatsPerBar + beat);
                long duration = Math.round(request.clock().beatsToMillis(1) * 0.92);
                int velocity = (beat == 0 ? 96 : 84) + random.nextInt(8);
                notes.add(new NoteEvent(Instrument.BASS, line[beat], start, duration, velocity));
            }
        }
        return notes;
    }

    /** The lowest MIDI note with this pitch class inside the bass register. */
    static int place(int pitchClass) {
        int midi = LOW + Math.floorMod(pitchClass - LOW, 12);
        return midi;
    }

    private static int clamp(int midi) {
        return Math.max(LOW, Math.min(HIGH, midi));
    }
}
