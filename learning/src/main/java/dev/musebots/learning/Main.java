package dev.musebots.learning;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** The tools: look at a corpus, train the baseline model, and evaluate it against the band's rules. */
public final class Main {

    private static final String USAGE = """
            usage:
              inspect  --corpus FILE
              train    --corpus FILE --out FILE [--instruments sax] [--test-fraction 0.2] [--seed 1] [--min-context 3]
              evaluate --corpus FILE --model FILE [--temperatures 1.0] [--repetitions 3] [--seed 1] [--report FILE]

            The corpus comes from tools/wjd_to_corpus.py. Run through Gradle, for example:
              ./gradlew :learning:run --args="train --corpus data/corpus.json --out data/markov.json --instruments sax"
            """;

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        System.exit(run(args, System.out));
    }

    /** Runs a command and returns the exit status. */
    static int run(String[] args, java.io.PrintStream out) throws IOException {
        if (args.length == 0) {
            out.println(USAGE);
            return 2;
        }
        try {
            Map<String, String> options = options(args);
            switch (args[0]) {
                case "inspect" -> inspect(Path.of(required(options, "corpus")), out);
                case "train" -> train(options, out);
                case "evaluate" -> evaluate(options, out);
                default -> {
                    out.println(USAGE);
                    return 2;
                }
            }
            return 0;
        } catch (IllegalArgumentException e) {
            out.println("error: " + e.getMessage());
            out.println(USAGE);
            return 2;
        }
    }

    private static void inspect(Path file, java.io.PrintStream out) throws IOException {
        Corpus corpus = Corpus.read(file);
        out.println(corpus.source());
        out.println(corpus.solos().size() + " solos, " + corpus.noteCount() + " notes");
        Map<String, Integer> byInstrument = new HashMap<>();
        corpus.solos().forEach(solo -> byInstrument.merge(solo.instrument(), 1, Integer::sum));
        byInstrument.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(entry -> out.println("  " + entry.getKey() + ": " + entry.getValue()));
        out.println(Selection.ALL.apply(corpus).size() + " usable by the band (4/4, readable chords, long enough)");
    }

    private static void train(Map<String, String> options, java.io.PrintStream out) throws IOException {
        Corpus corpus = Corpus.read(Path.of(required(options, "corpus")));
        Path destination = Path.of(required(options, "out"));
        Set<String> instruments = Selection.instrumentsNamed(options.getOrDefault("instruments", "all"));
        double testFraction = Double.parseDouble(options.getOrDefault("test-fraction", "0.2"));
        long seed = Long.parseLong(options.getOrDefault("seed", "1"));
        int minContext = Integer.parseInt(options.getOrDefault("min-context", "3"));

        List<Corpus.Solo> usable = new Selection(instruments, 0, Double.MAX_VALUE, Selection.ALL.minNotes()).apply(corpus);
        if (usable.isEmpty()) {
            throw new IllegalArgumentException("no usable solos for instruments '" + options.getOrDefault("instruments", "all") + "'");
        }
        Split split = Split.byTune(usable, testFraction, seed);

        MarkovModel.Meta meta = new MarkovModel.Meta(corpus.source(), new ArrayList<>(new TreeSet<>(instruments)), 0, 0, minContext, seed,
                split.train().stream().map(Corpus.Solo::id).toList(), split.test().stream().map(Corpus.Solo::id).toList());
        MarkovModel model = MarkovModel.train(split.train(), meta);
        model.write(destination);
        out.println("wrote " + destination);
        out.println(model.describe());
    }

    private static void evaluate(Map<String, String> options, java.io.PrintStream out) throws IOException {
        Corpus corpus = Corpus.read(Path.of(required(options, "corpus")));
        MarkovModel model = MarkovModel.read(Path.of(required(options, "model")));
        int repetitions = Integer.parseInt(options.getOrDefault("repetitions", "3"));
        long seed = Long.parseLong(options.getOrDefault("seed", "1"));

        Set<String> instruments = new HashSet<>(model.meta().instruments());
        List<Corpus.Solo> usable = new Selection(instruments, 0, Double.MAX_VALUE, Selection.ALL.minNotes()).apply(corpus);
        Set<String> trainIds = new HashSet<>(model.meta().trainIds());
        Set<String> testIds = new HashSet<>(model.meta().testIds());
        List<Corpus.Solo> training = usable.stream().filter(solo -> trainIds.contains(solo.id())).toList();
        List<Corpus.Solo> heldOut = usable.stream().filter(solo -> testIds.contains(solo.id())).toList();
        if (heldOut.isEmpty()) {
            throw new IllegalArgumentException("the model has no held-out solos to test on; train with --test-fraction above 0");
        }

        List<Evaluation.System> systems = new ArrayList<>();
        systems.add(Systems.rules());
        for (String temperature : options.getOrDefault("temperatures", "1.0").split(",")) {
            systems.add(Systems.markov(model, Double.parseDouble(temperature.trim())));
        }

        String report = ReportFormatter.markdown(Evaluation.run(training, heldOut, systems, repetitions, seed));
        out.println(report);
        if (options.containsKey("report")) {
            Path file = Path.of(options.get("report"));
            Corpus.ensureParent(file);
            Files.writeString(file, report);
            out.println("wrote " + file);
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("unexpected '" + args[i] + "'");
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("missing a value for " + args[i]);
            }
            options.put(args[i].substring(2), args[++i]);
        }
        return options;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) {
            throw new IllegalArgumentException("missing --" + name);
        }
        return value;
    }
}
