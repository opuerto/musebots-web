package dev.musebots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PianoComposerTest {

    @Test
    void writesPianoNotesInsideThePhraseWindow() {
        PhraseRequest request = new PhraseRequest(PhraseKind.SUPPORT, Songs.bluesInF(100).allBars(), new MusicClock(100, 4), 1_000);
        List<NoteEvent> notes = new PianoComposer(new Random(5)).compose(request);

        assertFalse(notes.isEmpty());
        for (NoteEvent note : notes) {
            assertEquals(Instrument.PIANO, note.instrument());
            assertTrue(note.startMs() >= request.startMs());
            assertTrue(note.endMs() <= request.endMs());
            assertTrue(note.velocity() > 0 && note.velocity() < 128);
        }
    }
}
