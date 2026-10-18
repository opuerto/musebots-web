package dev.musebots.learning;

import dev.musebots.core.MusicClock;
import dev.musebots.core.NoteEvent;
import dev.musebots.core.Swing;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns what a composer played back into the straight grid of ticks the corpus is written on, undoing the swing, so that
 * a composer's solo and a transcribed one can be measured the same way.
 */
public final class NoteTicks {

    private NoteTicks() {
    }

    /** The notes of one phrase that started at {@code phraseStartMs}, in order, as ticks from the start of the phrase. */
    public static List<TickNote> fromEvents(List<NoteEvent> events, long phraseStartMs, MusicClock clock) {
        double beatMs = clock.millisPerBeat();
        List<TickNote> notes = new ArrayList<>();
        for (NoteEvent event : events) {
            double start = Swing.straightBeats((event.startMs() - phraseStartMs) / beatMs);
            double end = Swing.straightBeats((event.endMs() - phraseStartMs) / beatMs);
            int tick = (int) Math.round(start * Corpus.TICKS_PER_BEAT);
            int duration = Math.max(1, (int) Math.round((end - start) * Corpus.TICKS_PER_BEAT));
            notes.add(new TickNote(tick, duration, event.midi()));
        }
        notes.sort(Comparator.comparingInt(TickNote::tick));
        return notes;
    }
}
