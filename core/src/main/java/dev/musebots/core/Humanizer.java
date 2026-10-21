package dev.musebots.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.random.RandomGenerator;

/**
 * Makes written music sound played. A composer writes notes exactly on the grid, at one volume and for one length; a person
 * does none of those. This adds, with nothing but arithmetic and a seeded random generator:
 * <ul>
 *   <li><b>Swing that follows the tempo</b> ({@link Swing#offbeatAtTempo}): jazz players swing less as they speed up.</li>
 *   <li><b>A feel for each instrument</b>: the bass sits a little ahead of the beat, the horn a little behind, the drums on it,
 *       and each drifts a few milliseconds around that, smoothly, as a player does (one note late, the next a little late too),
 *       so a fast run stays even. Only swung eighths are swung to the tempo: triplets and sixteenths keep their even spacing. Notes that start together (a chord) move together, with the voices
 *       of a piano chord rolled a few milliseconds apart.</li>
 *   <li><b>Dynamics</b>: no two notes are quite as loud, and a horn's phrases swell and fall over four bars.</li>
 *   <li><b>Note lengths</b> that vary a little.</li>
 * </ul>
 * It never adds, removes or changes the pitch of a note. A single line stays a single line (no overlaps), and nothing runs on
 * past the end of the phrase.
 */
public final class Humanizer {

    /** How one instrument plays: the average distance from the beat and its spread, in milliseconds, and how much it varies. */
    public record Feel(double biasMs, double jitterMs, double velocityJitter, double durationJitter, double rollMs) {

        /** Exactly as written. */
        public static final Feel MECHANICAL = new Feel(0, 0, 0, 0, 0);
    }

    /** No note moves further than this, and never more than a third of the gap to its neighbours, so a line is never reordered. */
    private static final double MAX_OFFSET_MS = 25;
    private static final double MAX_SHARE_OF_GAP = 0.3;
    private static final double MIN_DURATION_MS = 12;
    private static final double MAX_DURATION_SWING = 0.25;

    private final boolean enabled;

    private Humanizer(boolean enabled) {
        this.enabled = enabled;
    }

    /** The standard feel. */
    public static Humanizer standard() {
        return new Humanizer(true);
    }

    /** Leaves everything exactly as written, for comparing and for tests that need exact times. */
    public static Humanizer off() {
        return new Humanizer(false);
    }

    /** How this instrument plays this kind of phrase. */
    public static Feel feelOf(Instrument instrument, PhraseKind kind) {
        if (kind == PhraseKind.ENDING) {
            return new Feel(0, 3, 3, 0.05, instrument == Instrument.PIANO ? 4 : 0); // everyone lands together
        }
        return switch (instrument) {
            case DRUMS -> new Feel(0, 4, 5, 0.10, 0);
            case BASS -> new Feel(-6, 5, 5, 0.06, 0);
            case PIANO -> new Feel(6, 7, 6, 0.10, 5);
            case SAX -> new Feel(kind == PhraseKind.SOLO || kind == PhraseKind.HEAD ? 9 : 4, 9, 6, 0.08, 0);
        };
    }

    // ---- applying it ----

    /** One note while it is being worked on. */
    private static final class Work {
        final NoteEvent note;
        double start;
        double end;
        int velocity;

        Work(NoteEvent note, double start, double end) {
            this.note = note;
            this.start = start;
            this.end = end;
            this.velocity = note.velocity();
        }
    }

    public List<NoteEvent> apply(List<NoteEvent> notes, PhraseRequest request, RandomGenerator random) {
        if (!enabled || notes.isEmpty()) {
            return notes;
        }
        MusicClock clock = request.clock();
        double beatMs = clock.millisPerBeat();
        double offbeat = Swing.offbeatAtTempo(clock.tempoBpm());
        double phraseStart = request.startMs();

        // Swing to the tempo: only beats of swung eighths. A beat of triplets or sixteenths keeps its even spacing.
        java.util.Set<String> eighthBeats = eighthBeats(notes, phraseStart, beatMs);
        Work[] work = new Work[notes.size()];
        for (int i = 0; i < work.length; i++) {
            NoteEvent note = notes.get(i);
            double startBeats = (note.startMs() - phraseStart) / beatMs;
            boolean swings = eighthBeats.contains(note.instrument() + "@" + (long) Math.floor(startBeats + BEAT_TOLERANCE));
            work[i] = swings
                    ? new Work(note, phraseStart + Swing.retargetBeats(startBeats, offbeat) * beatMs,
                            phraseStart + Swing.retargetBeats((note.endMs() - phraseStart) / beatMs, offbeat) * beatMs)
                    : new Work(note, note.startMs(), note.endMs());
        }

        // Then the feel of each instrument, one instrument at a time so the same seed always gives the same performance.
        for (Instrument instrument : Instrument.values()) {
            List<Work> mine = Arrays.stream(work).filter(w -> w.note.instrument() == instrument).toList();
            if (!mine.isEmpty()) {
                playLikeAPerson(mine, Humanizer.feelOf(instrument, request.kind()), request, random);
            }
        }

        List<NoteEvent> played = new ArrayList<>(work.length);
        for (Work w : work) {
            long start = Math.round(w.start);
            long duration = Math.max(1, Math.round(w.end) - start);
            played.add(new NoteEvent(w.note.instrument(), w.note.midi(), start, duration, w.velocity));
        }
        return played;
    }

    /** How close to a position in the beat a note must start to count as on it: a few milliseconds of rounding. */
    private static final double BEAT_TOLERANCE = 0.01;
    /**
     * How much of one note's timing carries over to the next. A player who is a little late tends to stay a little late for a
     * while: the line drifts around the beat rather than jumping, so a fast run stays even.
     */
    private static final double TIMING_MEMORY = 0.6;

    /**
     * The beats (as "instrument@beat") in which each instrument plays only on the beat and on the swung off-beat: those are
     * the ones to swing to the tempo. Triplets and sixteenths are left as written.
     */
    private static java.util.Set<String> eighthBeats(List<NoteEvent> notes, double phraseStart, double beatMs) {
        Map<String, Boolean> beats = new java.util.HashMap<>();
        for (NoteEvent note : notes) {
            double beats0 = (note.startMs() - phraseStart) / beatMs;
            long beat = (long) Math.floor(beats0 + BEAT_TOLERANCE);
            double fraction = beats0 - beat;
            boolean eighth = Math.abs(fraction) < BEAT_TOLERANCE || Math.abs(fraction - Swing.OFFBEAT) < BEAT_TOLERANCE;
            beats.merge(note.instrument() + "@" + beat, eighth, Boolean::logicalAnd);
        }
        java.util.Set<String> eighths = new java.util.HashSet<>();
        beats.forEach((key, eighth) -> {
            if (eighth) {
                eighths.add(key);
            }
        });
        return eighths;
    }

    private static void playLikeAPerson(List<Work> notes, Feel feel, PhraseRequest request, RandomGenerator random) {
        // Notes that start together are one gesture: they share a timing offset.
        TreeMap<Long, List<Work>> gestures = new TreeMap<>();
        for (Work w : notes) {
            gestures.computeIfAbsent(w.note.startMs(), k -> new ArrayList<>()).add(w);
        }
        List<List<Work>> ordered = new ArrayList<>(gestures.values());

        boolean singleLine = isSingleLine(ordered);
        double[] originalStart = new double[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            originalStart[i] = ordered.get(i).get(0).start; // after the swing, before the feel
        }

        double drift = random.nextGaussian(); // where around the beat the player is, in units of the feel's spread
        for (int i = 0; i < ordered.size(); i++) {
            double gapBefore = i > 0 ? originalStart[i] - originalStart[i - 1] : Double.MAX_VALUE;
            double gapAfter = i + 1 < ordered.size() ? originalStart[i + 1] - originalStart[i] : Double.MAX_VALUE;
            double limit = Math.min(MAX_OFFSET_MS, MAX_SHARE_OF_GAP * Math.min(gapBefore, gapAfter));

            if (i > 0) { // the same spread as before, but each note leans on the one before it
                drift = TIMING_MEMORY * drift + Math.sqrt(1 - TIMING_MEMORY * TIMING_MEMORY) * random.nextGaussian();
            }
            double offset = clamp(feel.biasMs() + drift * feel.jitterMs(), limit);
            List<Work> gesture = new ArrayList<>(ordered.get(i));
            gesture.sort(Comparator.comparingInt(w -> w.note.midi())); // a chord rolls from the bottom up
            for (int voice = 0; voice < gesture.size(); voice++) {
                Work w = gesture.get(voice);
                double roll = Math.min(voice * feel.rollMs(), limit);
                double length = w.end - w.start;
                double lengthening = 1 + clamp(random.nextGaussian() * feel.durationJitter(), MAX_DURATION_SWING);
                w.start += offset + roll;
                w.end = w.start + Math.max(Math.min(length, MIN_DURATION_MS), length * lengthening);
                w.velocity = shapeVelocity(w, feel, request, random);
            }
        }

        double limit = request.endMs();
        for (Work w : notes) {
            w.end = Math.min(w.end, Math.max(limit, w.note.endMs())); // nothing runs past the end of the phrase
        }
        if (singleLine) {
            for (int i = 0; i + 1 < ordered.size(); i++) {
                Work current = ordered.get(i).get(0);
                double next = ordered.get(i + 1).get(0).start;
                if (current.end > next - 1) {
                    current.end = Math.max(current.start + 1, next - 1); // a line is one voice: one note ends as the next begins
                }
            }
        }
    }

    private static int shapeVelocity(Work w, Feel feel, PhraseRequest request, RandomGenerator random) {
        double value = w.note.velocity() + random.nextGaussian() * feel.velocityJitter();
        boolean carriesTheTune = request.kind() == PhraseKind.HEAD || request.kind() == PhraseKind.SOLO;
        if (carriesTheTune && w.note.instrument() != Instrument.DRUMS) {
            // Phrases swell and fall over four bars: up to 3 louder in the middle, 3 softer at the ends.
            double position = (((w.note.startMs() - request.startMs()) / (double) request.clock().barsToMillis(1)) % 4.0) / 4.0;
            value += -3 * Math.cos(2 * Math.PI * position);
        }
        return (int) Math.max(1, Math.min(127, Math.round(value)));
    }

    /** One note at a time, none overlapping: a horn or a bass line. */
    private static boolean isSingleLine(List<List<Work>> gestures) {
        for (int i = 0; i < gestures.size(); i++) {
            if (gestures.get(i).size() != 1) {
                return false;
            }
            if (i + 1 < gestures.size() && gestures.get(i).get(0).end > gestures.get(i + 1).get(0).start) {
                return false;
            }
        }
        return true;
    }

    private static double clamp(double value, double limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    /** Offsets by instrument, for tests and tuning: what each instrument's feel is. */
    static Map<Instrument, Feel> feels(PhraseKind kind) {
        Map<Instrument, Feel> feels = new java.util.EnumMap<>(Instrument.class);
        for (Instrument instrument : Instrument.values()) {
            feels.put(instrument, feelOf(instrument, kind));
        }
        return feels;
    }
}
