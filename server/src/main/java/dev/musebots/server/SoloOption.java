package dev.musebots.server;

import dev.musebots.agents.ComposerFactory;
import dev.musebots.learning.MarkovModel;
import dev.musebots.learning.SoloModelComposer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A way of writing the solos that a performance can be given. {@code credit} says where a learned model's data came from;
 * it is empty for the hand-written rules.
 */
public record SoloOption(String id, String label, String credit, ComposerFactory composers) {

    public static final String RULES = "rules";
    public static final String MARKOV = "markov";

    /** The band's hand-written rules, always on offer. */
    public static SoloOption rules() {
        return new SoloOption(RULES, "Rules (hand-written)", "", ComposerFactory.rules());
    }

    /** Solos from a Markov model, with everything else as the band normally plays it. */
    public static SoloOption markov(MarkovModel model, double temperature) {
        String credit = "Solos learned from " + model.meta().solos() + " transcribed solos of the Weimar Jazz Database "
                + "(Jazzomat Research Project, Hochschule für Musik Franz Liszt Weimar), used under the Open Database License (ODbL).";
        ComposerFactory factory = (instrument, random) -> SoloModelComposer.wrap(
                ComposerFactory.rules().create(instrument, random), model, random, temperature);
        return new SoloOption(MARKOV, "Markov model (learned from jazz solos)", credit, factory);
    }

    /**
     * What to offer, given the path of a trained model (null or empty for none). A model that cannot be read is reported and
     * left out rather than stopping the server, so the band still plays.
     */
    public static List<SoloOption> offered(String modelPath, java.io.PrintStream log) {
        if (modelPath == null || modelPath.isBlank()) {
            return List.of(rules());
        }
        try {
            Path file = Path.of(modelPath);
            if (!Files.isRegularFile(file)) {
                throw new IOException("no such file");
            }
            MarkovModel model = MarkovModel.read(file);
            log.println("Solo model loaded: " + model.describe());
            return List.of(rules(), markov(model, 1.0));
        } catch (IOException | RuntimeException e) {
            log.println("Could not load the solo model '" + modelPath + "' (" + e.getMessage() + "): offering the rules only.");
            return List.of(rules());
        }
    }
}
