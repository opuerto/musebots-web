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
}
