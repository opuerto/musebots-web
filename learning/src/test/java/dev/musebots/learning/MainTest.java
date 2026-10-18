package dev.musebots.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    @TempDir
    Path dir;
    private Path corpus;

    @BeforeEach
    void writeACorpus() throws IOException {
        List<Corpus.Solo> solos = new ArrayList<>();
        for (int tune = 0; tune < 10; tune++) {
            String instrument = tune % 5 == 4 ? "tp" : "as";
            solos.add(Solos.solo("s" + tune, "Tune " + tune, instrument, 120, 4, List.of(new Corpus.ChordChange(0, "F7")),
                    tune % 2 == 0 ? Solos.arpeggioOverF7(16) : Solos.eighthsOverF7(16)));
        }
        solos.add(Solos.solo("waltz", "Waltz", "as", 120, 3, List.of(new Corpus.ChordChange(0, "F7")), Solos.arpeggioOverF7(16)));
        corpus = dir.resolve("corpus.json");
        Corpus.write(Solos.corpus(solos.toArray(new Corpus.Solo[0])), corpus);
    }

    private String run(int expectedStatus, String... args) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int status = Main.run(args, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(expectedStatus, status, output);
        return output;
    }

    @Test
    void inspectSummarisesTheCorpus() throws IOException {
        String output = run(0, "inspect", "--corpus", corpus.toString());
        assertTrue(output.contains("11 solos"));
        assertTrue(output.contains("as: 9"));
        assertTrue(output.contains("tp: 2"));
        assertTrue(output.contains("10 usable by the band"), "the waltz is not usable: " + output);
    }

    @Test
    void trainWritesAModelFromTheChosenInstrumentsAndHoldsOutTunes() throws IOException {
        Path model = dir.resolve("out/model.json");
        String output = run(0, "train", "--corpus", corpus.toString(), "--out", model.toString(),
                "--instruments", "sax", "--test-fraction", "0.25", "--seed", "3");
        assertTrue(Files.exists(model));
        assertTrue(output.contains("held out for testing"));

        MarkovModel loaded = MarkovModel.read(model);
        assertEquals(List.of("as", "bs", "ss", "ts", "ts-c"), loaded.meta().instruments());
        assertEquals(3, loaded.meta().minContext());
        assertTrue(loaded.meta().testIds().size() >= 1);
        assertTrue(loaded.meta().trainIds().stream().noneMatch(loaded.meta().testIds()::contains));
        assertTrue(loaded.meta().trainIds().stream().noneMatch(id -> id.equals("waltz")), "only usable solos are used");
    }

    @Test
    void evaluateComparesTheRulesWithTheModelOnTheHeldOutTunes() throws IOException {
        Path model = dir.resolve("model.json");
        run(0, "train", "--corpus", corpus.toString(), "--out", model.toString(), "--instruments", "sax");
        Path report = dir.resolve("reports/report.md");
        String output = run(0, "evaluate", "--corpus", corpus.toString(), "--model", model.toString(),
                "--temperatures", "0.8,1.0", "--repetitions", "2", "--report", report.toString());

        assertTrue(output.contains("# Solo evaluation"));
        assertTrue(output.contains("rules (the band today)"));
        assertTrue(output.contains("markov (temperature 0.8)"));
        assertTrue(output.contains("markov (temperature 1.0)"));
        assertEquals(output.substring(0, output.indexOf("wrote ")).strip(), Files.readString(report).strip());
    }

    @Test
    void askingForHelpOrMakingAMistakeExplainsHowToUseIt() throws IOException {
        assertTrue(run(2).contains("usage:"));
        assertTrue(run(2, "dance").contains("usage:"));
        assertTrue(run(2, "inspect").contains("missing --corpus"));
        assertTrue(run(2, "train", "--corpus", corpus.toString()).contains("missing --out"));
        assertTrue(run(2, "inspect", "--corpus").contains("missing a value for --corpus"));
        assertTrue(run(2, "inspect", "corpus").contains("unexpected 'corpus'"));
    }

    @Test
    void trainingOnAnInstrumentThereIsNoDataForSaysSo() throws IOException {
        String output = run(2, "train", "--corpus", corpus.toString(), "--out", dir.resolve("m.json").toString(), "--instruments", "tuba");
        assertTrue(output.contains("no usable solos for instruments 'tuba'"));
    }

    @Test
    void evaluatingAModelWithNothingHeldOutSaysSo() throws IOException {
        Path model = dir.resolve("model.json");
        run(0, "train", "--corpus", corpus.toString(), "--out", model.toString(), "--test-fraction", "0");
        String output = run(2, "evaluate", "--corpus", corpus.toString(), "--model", model.toString());
        assertTrue(output.contains("no held-out solos"));
    }
}
