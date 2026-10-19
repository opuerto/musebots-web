package dev.musebots.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SelectionAndSplitTest {

    private static final List<Corpus.ChordChange> CHANGES = List.of(new Corpus.ChordChange(0, "F7"));

    private static Corpus.Solo solo(String id, String title, String instrument, double tempo, int beatsPerBar) {
        return Solos.solo(id, title, instrument, tempo, beatsPerBar, CHANGES, Solos.arpeggioOverF7(8));
    }

    @Test
    void groupsOfInstrumentsAreNamedAsMusiciansNameThem() {
        assertEquals(Set.of("as", "ts", "ss", "bs", "ts-c"), Selection.instrumentsNamed("sax"));
        assertEquals(Set.of("tp", "cor"), Selection.instrumentsNamed("Trumpet"));
        assertEquals(Set.of("p", "vib", "g"), Selection.instrumentsNamed("keys"), "what the piano learns from");
        assertEquals(Set.of("tb", "bs", "bcl"), Selection.instrumentsNamed("low"), "what the bass learns from");
        assertEquals(Set.of(), Selection.instrumentsNamed("all"));
        assertEquals(Set.of(), Selection.instrumentsNamed(null));
        assertEquals(Set.of("as", "vib"), Selection.instrumentsNamed("as, vib"));
    }

    @Test
    void onlySolosTheBandCanUseAreSelected() {
        Corpus corpus = Solos.corpus(
                solo("a", "In four", "as", 120, 4),
                solo("b", "In three", "as", 120, 3),
                Solos.solo("c", "No chords", "as", 120, 4, List.of(), Solos.arpeggioOverF7(8)),
                Solos.solo("d", "Too short", "as", 120, 4, CHANGES, List.of(new TickNote(0, 6, 60))),
                solo("e", "A trumpet", "tp", 120, 4));
        assertEquals(List.of("a", "e"), Selection.ALL.apply(corpus).stream().map(Corpus.Solo::id).toList());
    }

    @Test
    void canBeNarrowedToOneInstrumentAndATempoRange() {
        Corpus corpus = Solos.corpus(solo("a", "One", "as", 100, 4), solo("b", "Two", "ts", 200, 4), solo("c", "Three", "tp", 100, 4));
        assertEquals(List.of("a", "b"), new Selection(Selection.instrumentsNamed("sax"), 0, 1_000, 20).apply(corpus).stream().map(Corpus.Solo::id).toList());
        assertEquals(List.of("a"), new Selection(Selection.instrumentsNamed("sax"), 0, 150, 20).apply(corpus).stream().map(Corpus.Solo::id).toList());
    }

    private static List<Corpus.Solo> manySolos() {
        List<Corpus.Solo> solos = new ArrayList<>();
        for (int tune = 0; tune < 20; tune++) {
            for (int player = 0; player < 1 + tune % 3; player++) { // some tunes have several solos, as in the database
                solos.add(Solos.solo("t" + tune + "p" + player, "Tune " + tune, List.of(new TickNote(0, 6, 60))));
            }
        }
        return solos;
    }

    @Test
    void aTuneIsNeverInBothTheTrainingAndTheTestSet() {
        Split split = Split.byTune(manySolos(), 0.25, 1);
        Set<String> trainTitles = new HashSet<>();
        split.train().forEach(s -> trainTitles.add(s.title()));
        for (Corpus.Solo held : split.test()) {
            assertTrue(!trainTitles.contains(held.title()), held.title() + " is in both");
        }
        assertEquals(manySolos().size(), split.train().size() + split.test().size(), "nothing is lost");
    }

    @Test
    void aboutTheRequestedShareOfTunesIsHeldOut() {
        Split split = Split.byTune(manySolos(), 0.25, 1);
        Set<String> held = new HashSet<>();
        split.test().forEach(s -> held.add(s.title()));
        assertEquals(5, held.size());
    }

    @Test
    void theSplitDependsOnTheSeedNotOnTheOrderOfTheCorpus() {
        List<Corpus.Solo> solos = manySolos();
        List<Corpus.Solo> reversed = new ArrayList<>(solos);
        java.util.Collections.reverse(reversed);

        List<String> forward = Split.byTune(solos, 0.25, 7).test().stream().map(Corpus.Solo::id).sorted().toList();
        assertEquals(forward, Split.byTune(reversed, 0.25, 7).test().stream().map(Corpus.Solo::id).sorted().toList());
        assertTrue(!forward.equals(Split.byTune(solos, 0.25, 8).test().stream().map(Corpus.Solo::id).sorted().toList()),
                "a different seed should hold out different tunes");
    }

    @Test
    void titlesThatDifferOnlyInCaseAndSpacesAreTheSameTune() {
        Split split = Split.byTune(List.of(
                Solos.solo("a", "Anthropology", List.of(new TickNote(0, 6, 60))),
                Solos.solo("b", " anthropology ", List.of(new TickNote(0, 6, 60))),
                Solos.solo("c", "Other", List.of(new TickNote(0, 6, 60)))), 0.5, 3);
        Set<String> inTest = new HashSet<>(split.test().stream().map(Corpus.Solo::id).toList());
        assertTrue(inTest.containsAll(Set.of("a", "b")) || (!inTest.contains("a") && !inTest.contains("b")));
    }

    @Test
    void askingForNothingHeldOutHoldsNothingOut() {
        Split split = Split.byTune(manySolos(), 0, 1);
        assertEquals(0, split.test().size());
        assertEquals(manySolos().size(), split.train().size());
    }

    @Test
    void aSmallFractionStillHoldsOutOneTune() {
        Split split = Split.byTune(manySolos(), 0.01, 1);
        assertEquals(1, split.test().stream().map(Corpus.Solo::title).distinct().count());
    }

    @Test
    void oneTuneCannotBeSplit() {
        Split split = Split.byTune(List.of(Solos.solo("a", "Only", List.of(new TickNote(0, 6, 60)))), 0.5, 1);
        assertEquals(1, split.train().size());
        assertEquals(0, split.test().size());
    }
}
