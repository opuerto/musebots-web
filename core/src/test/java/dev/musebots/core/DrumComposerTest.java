package dev.musebots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DrumComposerTest {

    private final MusicClock clock = new MusicClock(120, 4);
    private final List<ChordBar> bars = Songs.bluesInF(120).allBars();

    private List<NoteEvent> compose(long seed) {
        return new DrumComposer(new Random(seed)).compose(new PhraseRequest(PhraseKind.SUPPORT, bars, clock, 2_000));
    }

    @Test
    void onlyUsesTheKitPieces() {
        Set<Integer> kit = Set.of(DrumComposer.KICK, DrumComposer.SNARE, DrumComposer.HI_HAT_PEDAL, DrumComposer.RIDE);
        for (NoteEvent note : compose(1)) {
            assertEquals(Instrument.DRUMS, note.instrument());
            assertTrue(kit.contains(note.midi()), "unexpected piece " + note.midi());
        }
    }

    @Test
    void ridesEveryBeatAndKeepsTimeWithKickAndHat() {
        List<NoteEvent> notes = compose(2);
        long beat = clock.beatsToMillis(1);
        for (int b = 0; b < bars.size() * 4; b++) {
            long start = 2_000 + b * beat;
            assertTrue(notes.stream().anyMatch(n -> n.midi() == DrumComposer.RIDE && n.startMs() == start), "ride on beat " + b);
            assertTrue(notes.stream().anyMatch(n -> n.midi() == DrumComposer.KICK && n.startMs() == start), "kick on beat " + b);
        }
        long hats = notes.stream().filter(n -> n.midi() == DrumComposer.HI_HAT_PEDAL).count();
        assertEquals(bars.size() * 2, hats); // beats 2 and 4
    }

    @Test
    void swingsTheSkipNoteTwoThirdsIntoTheBeat() {
        NoteEvent firstSkip = compose(3).stream()
                .filter(n -> n.midi() == DrumComposer.RIDE && n.startMs() > 2_000 + clock.beatsToMillis(1)
                        && n.startMs() < 2_000 + clock.beatsToMillis(2))
                .reduce((a, b) -> b).orElseThrow();
        assertEquals(2_000 + clock.beatsToMillis(1 + Swing.OFFBEAT), firstSkip.startMs());
    }

    @Test
    void staysInsideThePhraseAndIsDeterministic() {
        PhraseRequest request = new PhraseRequest(PhraseKind.SUPPORT, bars, clock, 2_000);
        for (NoteEvent note : compose(4)) {
            assertTrue(note.startMs() >= request.startMs());
            assertTrue(note.endMs() <= request.endMs());
        }
        assertEquals(compose(9), compose(9));
    }
}
