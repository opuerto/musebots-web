package dev.musebots.core;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/** Comping with a sparse top-line: a rootless voicing on beat 1 and the "and" of 2, plus a short melody. */
public final class PianoComposer implements PhraseComposer {

    private static final int VOICING_BASE = 55; // G3: voicings sit just above this
    private static final int MELODY_BASE = 72; // C5

    private final RandomGenerator random;

    public PianoComposer(RandomGenerator random) {
        this.random = random;
    }

    @Override
    public Instrument instrument() {
        return Instrument.PIANO;
    }

    @Override
    public List<NoteEvent> compose(PhraseRequest request) {
        List<NoteEvent> notes = new ArrayList<>();
        int beatsPerBar = request.clock().beatsPerBar();

        for (int bar = 0; bar < request.bars().size(); bar++) {
            Chord chord = request.bars().get(bar).chord();
            double barStart = (double) bar * beatsPerBar;

            addVoicing(notes, request, chord, barStart, 1.5);
            addVoicing(notes, request, chord, barStart + 2.5, 1.0);
            addMelody(notes, request, chord, barStart, beatsPerBar);
        }
        return notes;
    }

    private void addVoicing(List<NoteEvent> notes, PhraseRequest request, Chord chord,
                            double startBeat, double lengthBeats) {
        int[] pitchClasses = {chord.thirdPc(), chord.seventhPc(), chord.pitchClass(2)}; // 3rd, 7th, 9th
        long start = request.startMs() + request.clock().beatsToMillis(startBeat);
        long duration = request.clock().beatsToMillis(lengthBeats);
        for (int pc : pitchClasses) {
            int midi = VOICING_BASE + Math.floorMod(pc - VOICING_BASE, 12);
            notes.add(new NoteEvent(Instrument.PIANO, midi, start, duration, 62 + random.nextInt(10)));
        }
    }

    private void addMelody(List<NoteEvent> notes, PhraseRequest request, Chord chord,
                           double barStart, int beatsPerBar) {
        int[] tones = {chord.root(), chord.thirdPc(), chord.fifthPc(), chord.seventhPc()};
        double beat = 0;
        while (beat < beatsPerBar) {
            double length = random.nextInt(3) == 0 ? 1.5 : 1.0;
            length = Math.min(length, beatsPerBar - beat);
            if (random.nextInt(4) != 0) { // leave some space
                int pc = tones[random.nextInt(tones.length)];
                int midi = MELODY_BASE + Math.floorMod(pc - MELODY_BASE, 12);
                long start = request.startMs() + request.clock().beatsToMillis(barStart + beat);
                long duration = Math.round(request.clock().beatsToMillis(length) * 0.9);
                notes.add(new NoteEvent(Instrument.PIANO, midi, start, duration, 78 + random.nextInt(14)));
            }
            beat += length;
        }
    }
}
