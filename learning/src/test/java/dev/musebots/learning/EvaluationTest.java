package dev.musebots.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.musebots.core.Chord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvaluationTest {

    private static final List<Corpus.ChordChange> F7 = List.of(new Corpus.ChordChange(0, "F7"));

    private static Corpus.Solo figure(String id, String title, int bars) {
        return Solos.solo(id, title, "as", 120, 4, F7, Solos.arpeggioOverF7(bars));
    }

    @Test
    void thereIsOneTaskForEachHeldOutSoloThatIsLongEnough() {
        List<Corpus.Solo> heldOut = List.of(figure("long", "Long", 16), figure("short", "Short", 4), figure("also long", "Also", 12));
        List<Evaluation.Task> tasks = Evaluation.tasks(heldOut);
        assertEquals(List.of("long", "also long"), tasks.stream().map(Evaluation.Task::id).toList());
        assertEquals(16, tasks.get(0).bars().size());
        assertEquals(Collections.nCopies(16, Chord.parse("F7")), tasks.get(0).bars());
    }

    @Test
    void aTasksChordsAreTheOnesInForceAtTheStartOfEachBar() {
        Corpus.Solo changes = Solos.solo("c", "Changes", "as", 120, 4,
                List.of(new Corpus.ChordChange(0, "F7"), new Corpus.ChordChange(96, "Bb7"), new Corpus.ChordChange(144, "NC")),
                Solos.arpeggioOverF7(16));
        List<Chord> bars = Evaluation.tasks(List.of(changes)).get(0).bars();
        assertEquals(Chord.parse("F7"), bars.get(0));
        assertEquals(Chord.parse("F7"), bars.get(1));
        assertEquals(Chord.parse("Bb7"), bars.get(2));
        assertEquals(Chord.parse("Bb7"), bars.get(3), "no chord leaves the last one in force");
    }

    @Test
    void aVeryLongSoloIsCutToAReasonableNumberOfBars() {
        assertEquals(96, Evaluation.tasks(List.of(figure("x", "Huge", 200))).get(0).bars().size());
    }

    private static Evaluation.System replay(String name, List<TickNote> notes) {
        return new Evaluation.System(name, (task, random) -> Evaluation.lineOver(task, notes));
    }

    @Test
    void aSystemThatReplaysTheTrainingDataCopiesAllOfIt() {
        List<Corpus.Solo> training = List.of(figure("t", "Train", 16));
        List<Corpus.Solo> heldOut = List.of(figure("h", "Held", 16));
        Evaluation.Report report = Evaluation.run(training, heldOut, List.of(replay("copycat", training.get(0).notes())), 2, 1);

        Evaluation.Row copycat = report.rows().get(1);
        assertEquals("copycat", copycat.name());
        assertEquals(2, copycat.solos(), "one task, two repetitions");
        assertEquals(1.0, copycat.copyRate(), 1e-12);
    }

    @Test
    void aSystemThatPlaysTheSameThingAsTheRealSolosIsZeroApartFromThem() {
        List<Corpus.Solo> heldOut = List.of(figure("h", "Held", 16));
        Evaluation.Report report = Evaluation.run(List.of(figure("t", "Train", 16)), heldOut,
                List.of(replay("same", heldOut.get(0).notes())), 1, 1);
        assertEquals(0, report.rows().get(1).meanDivergence(), 1e-9);
    }

    @Test
    void aSystemThatPlaysSomethingElseIsFurtherAway() {
        List<Corpus.Solo> heldOut = List.of(figure("h", "Held", 16));
        List<TickNote> sixteenths = new ArrayList<>();
        for (int i = 0; i < 16 * 16; i++) {
            sixteenths.add(new TickNote(i * 3, 3, 60 + (i * 7) % 11));
        }
        Evaluation.Report report = Evaluation.run(List.of(figure("t", "Train", 16)), heldOut,
                List.of(replay("same", heldOut.get(0).notes()), replay("busy", sixteenths)), 1, 1);
        double same = report.rows().get(1).meanDivergence();
        double busy = report.rows().get(2).meanDivergence();
        assertTrue(busy > same + 0.3, "busy " + busy + " vs same " + same);
        assertTrue(report.rows().get(2).scalars().get("notes per bar").mean() > 12);
        assertEquals(0.0, report.rows().get(2).copyRate(), 1e-12);
    }

    @Test
    void theRealSolosAreTheFirstRowAndMeasureThemselvesAsTheReference() {
        List<Corpus.Solo> heldOut = List.of(figure("h", "Held", 16));
        Evaluation.Report report = Evaluation.run(List.of(figure("t", "Train", 16)), heldOut, List.of(), 1, 1);
        assertEquals(1, report.rows().size());
        Evaluation.Row real = report.rows().get(0);
        assertEquals("real solos (held out)", real.name());
        assertEquals(0, real.meanDivergence(), 1e-12);
        assertEquals(1, report.heldOutSolos());
        assertEquals(1, report.trainingSolos());
    }

    @Test
    void theSameSeedGivesTheSameReport() {
        MarkovModel model = MarkovModel.train(List.of(figure("t", "Train", 16), Solos.solo("u", "Eighths", Solos.eighthsOverF7(16))),
                new MarkovModel.Meta("t", List.of(), 0, 0, 3, 1, List.of(), List.of()));
        List<Corpus.Solo> heldOut = List.of(figure("h", "Held", 16));
        List<Evaluation.System> systems = List.of(Systems.rules(), Systems.markov(model, 1.0));
        assertEquals(Evaluation.run(List.of(), heldOut, systems, 2, 5).rows(), Evaluation.run(List.of(), heldOut, systems, 2, 5).rows());
    }

    @Test
    void theBandsRulesAndTheModelBothRunThroughTheSamePath() {
        MarkovModel model = MarkovModel.train(List.of(Solos.solo("u", "Eighths", Solos.eighthsOverF7(16))),
                new MarkovModel.Meta("t", List.of(), 0, 0, 3, 1, List.of(), List.of()));
        List<Corpus.Solo> heldOut = List.of(figure("h", "Held", 16));
        Evaluation.Report report = Evaluation.run(List.of(), heldOut, List.of(Systems.rules(), Systems.markov(model, 1.0)), 1, 1);

        Evaluation.Row rules = report.rows().get(1);
        Evaluation.Row markov = report.rows().get(2);
        // The model was taught eight eighths a bar; the rules write between four and eight notes a bar.
        assertEquals(8.0, markov.scalars().get("notes per bar").mean(), 1e-9);
        assertTrue(rules.scalars().get("notes per bar").mean() < 8);
        assertTrue(rules.scalars().get("out of scale").mean() < 0.01, "the rules stay in the scale");
    }
}
