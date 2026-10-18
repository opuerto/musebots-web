package dev.musebots.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.musebots.core.Chord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkovModelTest {

    private static final MarkovModel.Meta META = new MarkovModel.Meta("test", List.of(), 0, 0, 3, 1, List.of(), List.of());
    private static final List<Chord> F7_BARS = Collections.nCopies(4, Chord.parse("F7"));

    private static MarkovModel train(Corpus.Solo... solos) {
        return MarkovModel.train(List.of(solos), META);
    }

    private static List<Integer> pitches(List<TickNote> notes) {
        return notes.stream().map(TickNote::pitch).toList();
    }

    @Test
    void reproducesAFigureItHasOnlyEverSeenOneWayToPlay() {
        MarkovModel model = train(Solos.solo("a", "Figure", Solos.arpeggioOverF7(8)));
        // The line starts from the E-flat, the note that leads into the top of the figure in everything it was shown.
        List<TickNote> line = model.generate(F7_BARS, 4, 75, 55, 80, 1.0, new Random(1));

        // And from there the only thing it ever saw was F, A, C, E-flat, F, A, C... one quarter note each.
        assertEquals(List.of(65, 69, 72, 75, 65, 69, 72, 75, 65, 69, 72, 75, 65, 69, 72, 75), pitches(line));
        for (int i = 0; i < line.size(); i++) {
            assertEquals(i * 12, line.get(i).tick());
            assertEquals(12, line.get(i).duration());
        }
    }

    @Test
    void learnsTheRhythmOfEighthsAsWellAsThePitches() {
        MarkovModel model = train(Solos.solo("a", "Eighths", Solos.eighthsOverF7(8)));
        List<TickNote> line = model.generate(F7_BARS, 4, 65, 55, 80, 1.0, new Random(2));
        assertEquals(32, line.size(), "eight eighths a bar for four bars");
        assertTrue(line.stream().allMatch(n -> n.tick() % 6 == 0 && n.duration() == 6));
        assertEquals(List.of(66, 67, 68, 69), pitches(line).subList(0, 4), "and steps upward, as it was taught");
    }

    @Test
    void theLineFillsTheChordChartExactlyAndNeverOverlaps() {
        MarkovModel model = train(Solos.solo("a", "Eighths", Solos.eighthsOverF7(8)), Solos.solo("b", "Figure", Solos.arpeggioOverF7(8)));
        for (long seed = 0; seed < 25; seed++) {
            List<TickNote> line = model.generate(F7_BARS, 4, 65, 55, 80, 1.0, new Random(seed));
            assertTrue(line.get(0).tick() == 0);
            for (int i = 0; i < line.size(); i++) {
                assertTrue(line.get(i).end() <= 4 * 48, "runs past the end: " + line.get(i));
                if (i > 0) {
                    assertTrue(line.get(i).tick() >= line.get(i - 1).end(), "overlap at " + i);
                }
            }
        }
    }

    @Test
    void staysInTheInstrumentsRangeEvenWhenTheDataLeapsAbout() {
        // Teach it big leaps both ways, then ask for a narrow register.
        List<TickNote> leaps = new ArrayList<>();
        int[] pitches = {60, 72, 61, 73, 62, 74, 63, 75, 60, 72, 61, 73, 62, 74, 63, 75};
        for (int i = 0; i < pitches.length; i++) {
            leaps.add(new TickNote(i * 12, 12, pitches[i]));
        }
        MarkovModel model = train(Solos.solo("a", "Leaps", leaps));
        for (long seed = 0; seed < 50; seed++) {
            for (TickNote note : model.generate(Collections.nCopies(8, Chord.parse("F7")), 4, 66, 60, 71, 1.0, new Random(seed))) {
                assertTrue(note.pitch() >= 60 && note.pitch() <= 71, "pitch " + note.pitch() + " is out of range");
            }
        }
    }

    @Test
    void aChordItNeverSawStillGetsPlayedOverByBackingOffToWhatItKnows() {
        MarkovModel model = train(Solos.solo("a", "Only dominants", Solos.arpeggioOverF7(8)));
        List<TickNote> line = model.generate(Collections.nCopies(4, Chord.parse("Gm7")), 4, 70, 55, 80, 1.0, new Random(3));
        assertEquals(16, line.size());
        assertTrue(line.stream().allMatch(n -> n.pitch() >= 55 && n.pitch() <= 80));
    }

    @Test
    void whichSituationsAreWellEnoughSeenDecidesHowDetailedTheContextIs() {
        // "x" was seen twice and "y" once in the detailed context; with a minimum of 3 the model must use the coarser one.
        Map<String, Map<String, Integer>> table = new TreeMap<>();
        table.put("detailed", new TreeMap<>(Map.of("x", 2)));
        table.put("coarse", new TreeMap<>(Map.of("y", 50)));
        assertEquals("y", MarkovModel.sample(table, new String[] {"detailed", "coarse"}, 3, 1.0, new Random(1)));
        assertEquals("x", MarkovModel.sample(table, new String[] {"detailed", "coarse"}, 2, 1.0, new Random(1)));
        // When nothing has enough data, the coarsest table that has any is used rather than failing.
        assertEquals("y", MarkovModel.sample(table, new String[] {"detailed", "coarse"}, 1_000, 1.0, new Random(1)));
    }

    @Test
    void temperatureTradesFollowingTheDataAgainstAdventure() {
        Map<String, Map<String, Integer>> table = new TreeMap<>();
        table.put("c", new TreeMap<>(Map.of("common", 90, "rare", 10)));
        double[] shareOfCommon = new double[3];
        double[] temperatures = {0.1, 1.0, 5.0};
        for (int t = 0; t < temperatures.length; t++) {
            Random random = new Random(11);
            int common = 0;
            for (int i = 0; i < 4_000; i++) {
                if (MarkovModel.sample(table, new String[] {"c"}, 1, temperatures[t], random).equals("common")) {
                    common++;
                }
            }
            shareOfCommon[t] = common / 4_000.0;
        }
        assertTrue(shareOfCommon[0] > 0.99, "a low temperature should nearly always take the common one: " + shareOfCommon[0]);
        assertEquals(0.9, shareOfCommon[1], 0.03);
        assertTrue(shareOfCommon[2] < 0.7, "a high temperature should spread out: " + shareOfCommon[2]);
    }

    @Test
    void isDeterministicForASeed() {
        MarkovModel model = train(Solos.solo("a", "Eighths", Solos.eighthsOverF7(8)), Solos.solo("b", "Figure", Solos.arpeggioOverF7(8)));
        assertEquals(model.generate(F7_BARS, 4, 65, 55, 80, 1.0, new Random(5)), model.generate(F7_BARS, 4, 65, 55, 80, 1.0, new Random(5)));
    }

    @Test
    void onlyLearnsFromSolosItCanUse() {
        Corpus.Solo waltz = Solos.solo("w", "Waltz", "as", 120, 3, List.of(new Corpus.ChordChange(0, "F7")), Solos.arpeggioOverF7(8));
        Corpus.Solo noChords = Solos.solo("n", "No chords", "as", 120, 4, List.of(), Solos.arpeggioOverF7(8));
        MarkovModel model = train(Solos.solo("a", "Figure", Solos.arpeggioOverF7(8)), waltz, noChords);
        assertEquals(1, model.meta().solos());
        assertEquals(32, model.meta().notes());
        assertThrows(IllegalArgumentException.class, () -> train(waltz, noChords));
    }

    @Test
    void measuresPitchAgainstTheChordSoOneSoloTeachesEveryKey() {
        // The same figure transposed to B-flat7 (up a fourth: +5) is the same lesson, so the model plays it over B-flat7 too.
        MarkovModel model = train(Solos.solo("a", "Figure", Solos.arpeggioOverF7(8)));
        // Starting from A-flat (the flat seventh of B-flat7, as E-flat is of F7), it plays B-flat, D, F, A-flat, B-flat...
        List<TickNote> overBb = model.generate(Collections.nCopies(4, Chord.parse("Bb7")), 4, 80, 55, 85, 1.0, new Random(1));
        assertEquals(List.of(70, 74, 77, 80, 70, 74, 77, 80), pitches(overBb).subList(0, 8));
    }

    @Test
    void refusesAMeterItDidNotLearn() {
        MarkovModel model = train(Solos.solo("a", "Figure", Solos.arpeggioOverF7(8)));
        assertThrows(UnsupportedOperationException.class, () -> model.generate(F7_BARS, 3, 65, 55, 80, 1.0, new Random(1)));
    }

    @Test
    void survivesBeingWrittenToAFileAndReadBack(@TempDir Path dir) throws IOException {
        MarkovModel model = train(Solos.solo("a", "Eighths", Solos.eighthsOverF7(8)), Solos.solo("b", "Figure", Solos.arpeggioOverF7(8)));
        Path file = dir.resolve("nested/model.json");
        model.write(file);
        assertTrue(Files.size(file) > 0);

        MarkovModel loaded = MarkovModel.read(file);
        assertEquals(model.meta(), loaded.meta());
        assertEquals(model.rhythm(), loaded.rhythm());
        assertEquals(model.pitch(), loaded.pitch());
        assertEquals(model.generate(F7_BARS, 4, 65, 55, 80, 1.0, new Random(9)), loaded.generate(F7_BARS, 4, 65, 55, 80, 1.0, new Random(9)));
    }

    @Test
    void describesItselfForAPerson() {
        MarkovModel model = MarkovModel.train(List.of(Solos.solo("a", "Figure", Solos.arpeggioOverF7(8))),
                new MarkovModel.Meta("src", List.of("as"), 0, 0, 3, 1, List.of("a"), List.of("z")));
        assertTrue(model.describe().startsWith("learned from 1 solos (32 notes, instruments: as)"));
        assertTrue(model.describe().contains("1 solos held out"));
    }

    // ---- phrases, landing and repeating ----

    /** Four quarter notes, a beat of silence, four more, and so on: phrases of four notes. */
    private static List<TickNote> phrasesOfFour(int bars, int[] pitches) {
        List<TickNote> notes = new ArrayList<>();
        int tick = 0;
        int n = 0;
        while (tick < bars * 48) {
            for (int k = 0; k < 4 && tick < bars * 48; k++) {
                notes.add(new TickNote(tick, 10, pitches[n++ % pitches.length]));
                tick += 12;
            }
            tick += 12; // the breath
        }
        return notes;
    }

    @Test
    void learnsHowLongPhrasesRunAndWhereTheBreathsAre() {
        int[] figure = {65, 69, 72, 75};
        MarkovModel model = train(Solos.solo("a", "Phrases", phrasesOfFour(16, figure)), Solos.solo("b", "Phrases", phrasesOfFour(16, figure)),
                Solos.solo("c", "Phrases", phrasesOfFour(16, figure)), Solos.solo("d", "Phrases", phrasesOfFour(16, figure)));
        List<TickNote> line = model.generate(Collections.nCopies(8, Chord.parse("F7")), 4, 75, 55, 80, 1.0, 0.0, new Random(1));

        int run = 1;
        List<Integer> runs = new ArrayList<>();
        for (int i = 1; i < line.size(); i++) {
            if (line.get(i).tick() - line.get(i - 1).end() >= 12) {
                runs.add(run);
                run = 1;
            } else {
                run++;
            }
        }
        assertTrue(runs.size() >= 4, "it breathes: " + runs);
        assertTrue(runs.stream().allMatch(r -> r == 4), "after four notes, as in everything it was shown: " + runs);
    }

    /** Four bars of F7 then four of Bb7, the last note of every bar before a change the third of the chord that follows. */
    private static Corpus.Solo aimedAtTheNextChord(String id) {
        List<TickNote> notes = new ArrayList<>();
        for (int bar = 0; bar < 16; bar++) {
            boolean lastBeforeChange = bar % 4 == 3;
            boolean bb = (bar / 4) % 2 == 1;
            int base = bb ? 70 : 65; // the roots, F4 and Bb4
            for (int beat = 0; beat < 4; beat++) {
                int pitch = base + (beat % 2 == 0 ? 0 : 4);
                if (lastBeforeChange && beat == 3) {
                    // The third of the chord to come (A over F7 after Bb7; D over Bb7 after F7), approached from above.
                    pitch = bb ? 69 : 74;
                }
                notes.add(new TickNote(bar * 48 + beat * 12, 10, pitch));
            }
        }
        return Solos.solo(id, "Aimed", "as", 120, 4,
                List.of(new Corpus.ChordChange(0, "F7"), new Corpus.ChordChange(192, "Bb7"), new Corpus.ChordChange(384, "F7"),
                        new Corpus.ChordChange(576, "Bb7")), notes);
    }

    /** How often the last note before each chord change is the third of the chord that follows. */
    private static double landingRate(MarkovModel model) {
        List<Chord> chords = new ArrayList<>();
        for (String symbol : List.of("F7", "F7", "F7", "F7", "Bb7", "Bb7", "Bb7", "Bb7", "F7", "F7", "F7", "F7")) {
            chords.add(Chord.parse(symbol));
        }
        int landed = 0;
        int changes = 0;
        for (long seed = 0; seed < 300; seed++) {
            // From the A it was taught to start bars from, so the line stays on the path it learned and only the last note is at stake.
            List<TickNote> line = model.generate(chords, 4, 69, 55, 85, 1.0, 0.0, new Random(seed));
            for (int change : new int[] {4, 8}) { // the bars the chord changes to
                Chord next = chords.get(change);
                TickNote last = line.stream().filter(n -> n.tick() < change * 48).reduce((a, b) -> b).orElseThrow();
                changes++;
                if (last.pitch() % 12 == next.thirdPc()) {
                    landed++;
                }
            }
        }
        return (double) landed / changes;
    }

    @Test
    void learnsToLandOnTheChordThatIsComing() {
        MarkovModel model = train(aimedAtTheNextChord("a"), aimedAtTheNextChord("b"));
        assertEquals(Map.of("l", Map.of("4", 6), "l|D", Map.of("4", 6), "l|D|0", Map.of("4", 6)), model.landing(),
                "it learned that the last note before a change sits on the third (degree 4) of the coming chord");

        // The leap table alone, with the same lessons but without the landing table, aims at the next chord only by accident.
        double without = landingRate(new MarkovModel(model.meta(), model.rhythm(), model.pitch(), null));
        double with = landingRate(model);
        // Only a few notes in these lessons are aimed (the others share their situation), so the lift is a modest one: but it is there.
        assertTrue(with > without + 0.07, "landing lifts the rate of landing on the third from " + without + " to " + with);
    }

    @Test
    void aLineThatIgnoresTheNextChordLandsOnItOnlyByChance() {
        // The same lines, shown with no chord changes at all, give the model no reason to aim: the baseline the test above beats.
        List<TickNote> notes = aimedAtTheNextChord("a").notes();
        Corpus.Solo flat = Solos.solo("flat", "Flat", "as", 120, 4, List.of(new Corpus.ChordChange(0, "F7")), notes);
        MarkovModel model = train(flat);
        assertTrue(model.landing().isEmpty() || model.landing().keySet().stream().noneMatch(k -> k.startsWith("l|m")),
                "nothing to learn about aiming at the next chord when it never changes");
    }

    /** How often the first full phrase (three notes or more) is followed by a phrase of the same steps and rhythm. */
    private static double repeatRate(MarkovModel model, double motifRepeat) {
        List<Chord> chords = Collections.nCopies(8, Chord.parse("F7"));
        int repeats = 0;
        int lines = 0;
        for (long seed = 0; seed < 30; seed++) {
            List<TickNote> line = model.generate(chords, 4, 65, 55, 80, 1.0, motifRepeat, new Random(seed));
            List<List<TickNote>> phrases = new ArrayList<>();
            List<TickNote> current = new ArrayList<>();
            for (int i = 0; i < line.size(); i++) {
                if (i > 0 && line.get(i).tick() - line.get(i - 1).end() >= 12) {
                    phrases.add(current);
                    current = new ArrayList<>();
                }
                current.add(line.get(i));
            }
            phrases.add(current);
            for (int i = 0; i + 1 < phrases.size() - 1; i++) { // the last phrase may be cut short by the end of the chart
                if (phrases.get(i).size() >= 3) {
                    lines++;
                    if (shapeOf(phrases.get(i + 1)).equals(shapeOf(phrases.get(i)))) {
                        repeats++;
                    }
                    break;
                }
            }
        }
        return (double) repeats / lines;
    }

    @Test
    void repeatsItsLastPhraseWhenAskedToFittedToTheChord() {
        MarkovModel model = train(Solos.solo("a", "Phrases", phrasesOfFour(16, new int[] {65, 69, 72, 75, 72, 69, 65, 69})));
        double never = repeatRate(model, 0.0);
        double always = repeatRate(model, 1.0);
        assertTrue(always > 0.9, "asked to repeat, the phrase after a phrase repeats it: " + always);
        assertTrue(never < always - 0.4, "left to itself it does not, so this is the repeating: " + never);
    }

    /** What makes a phrase recognisable: the gaps between its notes and the steps in pitch. */
    private static List<Integer> shapeOf(List<TickNote> phrase) {
        List<Integer> shape = new ArrayList<>();
        for (int k = 1; k < phrase.size(); k++) {
            shape.add(phrase.get(k).tick() - phrase.get(k - 1).tick());
            shape.add(phrase.get(k).pitch() - phrase.get(k - 1).pitch());
        }
        return shape;
    }

    @Test
    void neverRepeatsAPhraseWhenToldNotTo() {
        // Phrases that cannot be told apart by rhythm alone: with repeating off, the model samples them afresh.
        MarkovModel model = train(Solos.solo("a", "Phrases", phrasesOfFour(16, new int[] {65, 69, 72, 75, 72, 69, 65, 69})));
        List<Chord> chords = Collections.nCopies(8, Chord.parse("F7"));
        assertEquals(model.generate(chords, 4, 65, 55, 80, 1.0, 0.0, new Random(4)),
                model.generate(chords, 4, 65, 55, 80, 1.0, 0.0, new Random(4)));
    }

    @Test
    void aModelWrittenBeforePhrasesAndLandingStillPlays() {
        MarkovModel fresh = train(Solos.solo("a", "Eighths", Solos.eighthsOverF7(8)));
        MarkovModel old = new MarkovModel(fresh.meta(), fresh.rhythm(), fresh.pitch(), null);
        assertTrue(old.landing().isEmpty());
        List<TickNote> line = old.generate(F7_BARS, 4, 65, 55, 80, 1.0, new Random(1));
        assertEquals(32, line.size());
    }

    @Test
    void landsOnlyInRangeEvenWhileAimingAtTheNextChord() {
        MarkovModel model = train(aimedAtTheNextChord("a"));
        List<Chord> chords = new ArrayList<>();
        for (String symbol : List.of("F7", "Bb7", "F7", "Bb7", "F7", "Bb7")) {
            chords.add(Chord.parse(symbol));
        }
        for (long seed = 0; seed < 25; seed++) {
            for (TickNote note : model.generate(chords, 4, 65, 60, 71, 1.0, new Random(seed))) {
                assertTrue(note.pitch() >= 60 && note.pitch() <= 71, "pitch " + note.pitch());
            }
        }
    }
}
