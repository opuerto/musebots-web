package dev.musebots.server;

import dev.musebots.agents.ComposerFactory;
import dev.musebots.core.Instrument;
import dev.musebots.learning.MarkovModel;
import dev.musebots.learning.SoloModelComposer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * A way of writing the solos that a performance can be given. {@code credit} says where a learned model's data came from;
 * it is empty for the hand-written rules. {@code downloads} are the model files it plays from, by instrument
 * ({@code "sax"}...), which the server offers under the licence of their data.
 */
public record SoloOption(String id, String label, String credit, ComposerFactory composers, Map<String, Path> downloads) {

    public SoloOption {
        downloads = Map.copyOf(downloads);
    }

    public SoloOption(String id, String label, String credit, ComposerFactory composers) {
        this(id, label, credit, composers, Map.of());
    }

    public static final String RULES = "rules";
    public static final String MARKOV = "markov";

    /** How closely the learned solos follow the data: at 0.9 they come closest to the real solos' statistics (see the README). */
    public static final double SOLO_TEMPERATURE = 0.9;

    /** The environment variable naming the model for every instrument that has none of its own. */
    public static final String MODEL_VARIABLE = "MARKOV_MODEL";

    /** The band's hand-written rules, always on offer. */
    public static SoloOption rules() {
        return new SoloOption(RULES, "Rules (hand-written)", "", ComposerFactory.rules());
    }

    /** Solos from one Markov model on every instrument, with everything else as the band normally plays it. */
    public static SoloOption markov(MarkovModel model, double temperature) {
        Map<Instrument, MarkovModel> models = new EnumMap<>(Instrument.class);
        for (Instrument instrument : Instrument.values()) {
            models.put(instrument, model);
        }
        return markov(models, temperature);
    }

    /**
     * Solos from a Markov model per instrument, with everything else as the band normally plays it. An instrument with no
     * model plays its solos by the rules.
     */
    public static SoloOption markov(Map<Instrument, MarkovModel> models, double temperature) {
        return markov(models, Map.of(), temperature);
    }

    /** As above, with the files the models were read from, so they can be offered for download. */
    public static SoloOption markov(Map<Instrument, MarkovModel> models, Map<String, Path> files, double temperature) {
        Set<MarkovModel> distinct = Collections.newSetFromMap(new IdentityHashMap<>()); // one model may play several instruments
        distinct.addAll(models.values());
        int solos = distinct.stream().mapToInt(model -> model.meta().solos()).sum();
        String credit = "Solos learned from " + solos + " transcribed solos of the Weimar Jazz Database "
                + "(Jazzomat Research Project, Hochschule für Musik Franz Liszt Weimar), used under the Open Database License (ODbL)."
                + (files.isEmpty() ? "" : " The models are free to download under the same licence.");
        Map<Instrument, MarkovModel> chosen = new EnumMap<>(Instrument.class);
        chosen.putAll(models);
        ComposerFactory factory = (instrument, random) -> {
            MarkovModel model = chosen.get(instrument);
            return model == null
                    ? ComposerFactory.rules().create(instrument, random)
                    : SoloModelComposer.wrap(ComposerFactory.rules().create(instrument, random), model, random, temperature);
        };
        return new SoloOption(MARKOV, "Markov model (learned from jazz solos)", credit, factory, files);
    }

    /** The variable naming one instrument's own model, such as {@code MARKOV_MODEL_PIANO}. */
    public static String modelVariable(Instrument instrument) {
        return MODEL_VARIABLE + "_" + instrument.name();
    }

    /** What to offer, given the path of one trained model for every instrument (null or empty for none). */
    public static List<SoloOption> offered(String modelPath, java.io.PrintStream log) {
        return offered(name -> name.equals(MODEL_VARIABLE) ? modelPath : null, log);
    }

    /**
     * What to offer, reading the model paths from {@code environment}: {@code MARKOV_MODEL_SAX}, {@code _PIANO}, {@code _BASS}
     * and {@code _DRUMS} name an instrument's own model, and {@code MARKOV_MODEL} the one for every instrument without one.
     * A model that cannot be read is reported and left out rather than stopping the server, so the band still plays: its
     * instrument falls back to the shared model, or to the rules.
     */
    public static List<SoloOption> offered(Function<String, String> environment, java.io.PrintStream log) {
        Map<String, MarkovModel> loaded = new HashMap<>(); // by path, so a file named twice is read once
        MarkovModel shared = load(environment.apply(MODEL_VARIABLE), "", loaded, log);
        Map<Instrument, MarkovModel> models = new EnumMap<>(Instrument.class);
        Map<Path, List<String>> users = new java.util.LinkedHashMap<>(); // which instruments play from each file
        for (Instrument instrument : Instrument.values()) {
            String name = instrument.name().toLowerCase(Locale.ROOT);
            String ownPath = environment.apply(modelVariable(instrument));
            MarkovModel own = load(ownPath, " for the " + name, loaded, log);
            MarkovModel model = own != null ? own : shared;
            if (model != null) {
                models.put(instrument, model);
                users.computeIfAbsent(Path.of(own != null ? ownPath : environment.apply(MODEL_VARIABLE)), p -> new java.util.ArrayList<>()).add(name);
            }
        }
        // A file is offered for download once, under the name of its instrument, or "markov" if every instrument plays from it.
        Map<String, Path> files = new java.util.TreeMap<>();
        users.forEach((file, instruments) -> files.put(instruments.size() == 1 ? instruments.get(0)
                : instruments.size() == Instrument.values().length ? "markov" : String.join("-", instruments), file));
        if (models.isEmpty()) {
            if (anyNamed(environment)) {
                log.println("No solo model could be loaded: offering the rules only.");
            }
            return List.of(rules());
        }
        return List.of(rules(), markov(models, files, SOLO_TEMPERATURE));
    }

    private static boolean anyNamed(Function<String, String> environment) {
        if (!isBlank(environment.apply(MODEL_VARIABLE))) {
            return true;
        }
        for (Instrument instrument : Instrument.values()) {
            if (!isBlank(environment.apply(modelVariable(instrument)))) {
                return true;
            }
        }
        return false;
    }

    private static MarkovModel load(String modelPath, String purpose, Map<String, MarkovModel> loaded, java.io.PrintStream log) {
        if (isBlank(modelPath)) {
            return null;
        }
        if (loaded.containsKey(modelPath)) {
            return loaded.get(modelPath);
        }
        try {
            Path file = Path.of(modelPath);
            if (!Files.isRegularFile(file)) {
                throw new IOException("no such file");
            }
            MarkovModel model = MarkovModel.read(file);
            log.println("Solo model loaded" + purpose + ": " + model.describe());
            loaded.put(modelPath, model);
            return model;
        } catch (IOException | RuntimeException e) {
            log.println("Could not load the solo model" + purpose + " '" + modelPath + "' (" + e.getMessage() + ")");
            loaded.put(modelPath, null);
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
