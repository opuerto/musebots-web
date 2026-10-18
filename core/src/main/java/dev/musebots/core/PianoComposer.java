package dev.musebots.core;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/** Comping: a rootless voicing on beat 1 and another on the swung "and" of 3, leaving the melody to the horn. */
public final class PianoComposer implements PhraseComposer {

    private static final int VOICING_BASE = 55; // G3: voicings sit just above this

    private final RandomGenerator random;
    private final Improviser rightHand;

    public PianoComposer(RandomGenerator random) {
        this.random = random;
        this.rightHand = new Improviser(Instrument.PIANO,
                new Improviser.Profile(60, 84, 72, Improviser.SOLO_CELLS, true, true), random);
    }

    @Override
    public Instrument instrument() {
        return Instrument.PIANO;
    }

    @Override
    public List<NoteEvent> compose(PhraseRequest request) {
        return switch (request.kind()) {
            case SOLO -> solo(request);
            case ENDING -> ending(request);
            case SUPPORT_LIGHT -> lightComp(request);
            case INTRO, HEAD, SUPPORT -> comp(request);
        };
    }

    /** A single-line right hand with a sparse left-hand chord every other bar. */
    private List<NoteEvent> solo(PhraseRequest request) {
        List<NoteEvent> notes = new ArrayList<>(rightHand.line(request));
        for (int bar = 0; bar < request.bars().size(); bar += 2) {
            addVoicing(notes, request, request.bars().get(bar).chord(), (double) bar * request.clock().beatsPerBar(), 1.5, 62);
        }
        return notes;
    }

    /** A single soft chord at the top of each bar. */
    private List<NoteEvent> lightComp(PhraseRequest request) {
        List<NoteEvent> notes = new ArrayList<>();
        for (int bar = 0; bar < request.bars().size(); bar++) {
            addVoicing(notes, request, request.bars().get(bar).chord(), (double) bar * request.clock().beatsPerBar(), 2.5, 38);
        }
        return notes;
    }

    private List<NoteEvent> ending(PhraseRequest request) {
        List<NoteEvent> notes = new ArrayList<>();
        addVoicing(notes, request, request.bars().get(0).chord(), 0, 3.0, 62);
        return notes;
    }

    private List<NoteEvent> comp(PhraseRequest request) {
        List<NoteEvent> notes = new ArrayList<>();
        int beatsPerBar = request.clock().beatsPerBar();

        for (int bar = 0; bar < request.bars().size(); bar++) {
            Chord chord = request.bars().get(bar).chord();
            double barStart = (double) bar * beatsPerBar;

            addVoicing(notes, request, chord, barStart, 1.5, 62);
            addVoicing(notes, request, chord, barStart + 2 + Swing.OFFBEAT, 1.0, 62);
        }
        return notes;
    }

    private void addVoicing(List<NoteEvent> notes, PhraseRequest request, Chord chord,
                            double startBeat, double lengthBeats, int baseVelocity) {
        int[] pitchClasses = {chord.thirdPc(), chord.seventhPc(), chord.pitchClass(2)}; // 3rd, 7th, 9th
        long start = request.startMs() + request.clock().beatsToMillis(startBeat);
        long duration = request.clock().beatsToMillis(lengthBeats);
        for (int pc : pitchClasses) {
            int midi = VOICING_BASE + Math.floorMod(pc - VOICING_BASE, 12);
            notes.add(new NoteEvent(Instrument.PIANO, midi, start, duration, baseVelocity + random.nextInt(10)));
        }
    }
}
