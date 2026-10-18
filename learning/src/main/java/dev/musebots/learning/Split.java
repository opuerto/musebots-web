package dev.musebots.learning;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Training and held-out solos. The split is by <em>tune</em>, not by solo: several players recorded the same tune, so a
 * random split would let the model see a tune's chord changes in training and be tested on them, and look better than it is.
 */
public record Split(List<Corpus.Solo> train, List<Corpus.Solo> test) {

    /** Holds out about {@code testFraction} of the tunes (at least one if it is above zero and there are two or more), chosen by {@code seed}. */
    public static Split byTune(List<Corpus.Solo> solos, double testFraction, long seed) {
        Map<String, List<Corpus.Solo>> tunes = new TreeMap<>();
        for (Corpus.Solo solo : solos) {
            tunes.computeIfAbsent(solo.title().trim().toLowerCase(Locale.ROOT), title -> new ArrayList<>()).add(solo);
        }

        List<String> titles = new ArrayList<>(tunes.keySet()); // sorted, so the result does not depend on the corpus order
        java.util.Collections.shuffle(titles, new Random(seed));
        // Asking for none holds out none; otherwise at least one tune is held out, so there is something to test on.
        int held = testFraction <= 0 || titles.size() < 2 ? 0 : Math.max(1, (int) Math.round(titles.size() * testFraction));

        List<Corpus.Solo> train = new ArrayList<>();
        List<Corpus.Solo> test = new ArrayList<>();
        for (int i = 0; i < titles.size(); i++) {
            (i < held ? test : train).addAll(tunes.get(titles.get(i)));
        }
        return new Split(train, test);
    }
}
