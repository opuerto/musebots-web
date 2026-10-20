package dev.musebots.core;

import java.util.List;

/**
 * A small built-in repertoire of lead sheets: the chord changes of well-known forms (blues, rhythm changes, a
 * ballad, a modal tune), each with the style it is usually played in and a tempo that suits it.
 */
public final class Songs {

    public static final String BLUES = "blues";
    public static final String SONG_1 = "song1";
    public static final String MINOR_BLUES = "minor-blues";
    public static final String BIRD_BLUES = "bird-blues";
    public static final String RHYTHM_CHANGES = "rhythm-changes";
    public static final String LEAVES = "leaves";
    public static final String BALLAD = "ballad";
    public static final String MODAL = "modal";

    private record Tune(String style, int suggestedTempo, java.util.function.IntFunction<SongStructure> build) {
    }

    private static final java.util.Map<String, Tune> TUNES = new java.util.LinkedHashMap<>();

    static {
        TUNES.put(BALLAD, new Tune("Slow ballad", 66, Songs::balladInEb)); // first: the tune the page starts on
        TUNES.put(BLUES, new Tune("Medium swing blues", 120, Songs::bluesInF));
        TUNES.put(SONG_1, new Tune("Swing, AAB", 120, Songs::song1));
        TUNES.put(MINOR_BLUES, new Tune("Minor blues", 110, Songs::minorBluesInC));
        TUNES.put(BIRD_BLUES, new Tune("Bebop blues", 190, Songs::birdBluesInBb));
        TUNES.put(RHYTHM_CHANGES, new Tune("Up-tempo swing, AABA", 170, Songs::rhythmChanges));
        TUNES.put(LEAVES, new Tune("Medium swing, ii-V-I in two keys", 130, Songs::leavesChanges));
        TUNES.put(MODAL, new Tune("Modal jazz, AABA", 128, Songs::modalInD));
    }

    /** Every tune on offer, in the order a menu should list them; the page starts on the first. */
    public static final List<String> IDS = List.copyOf(TUNES.keySet());

    private Songs() {
    }

    /** The tune with this id, or null if there is none. */
    public static SongStructure byId(String id, int tempoBpm) {
        Tune tune = TUNES.get(id);
        return tune == null ? null : tune.build().apply(tempoBpm);
    }

    /** The style a tune is played in, e.g. "Slow ballad". */
    public static String styleOf(String id) {
        return TUNES.get(id).style();
    }

    /** The tempo that suits a tune, which the page offers as the starting point. */
    public static int suggestedTempo(String id) {
        return TUNES.get(id).suggestedTempo();
    }

    /** A 12-bar blues in F, a good first tune: every jazz musician knows it. Written as three lines of four bars. */
    public static SongStructure bluesInF(int tempoBpm) {
        return new SongStructure("Blues in F", tempoBpm, 4, List.of(
                section("A", "F7", "Bb7", "F7", "F7"),
                section("B", "Bb7", "Bb7", "F7", "D7"),
                section("C", "Gm7", "C7", "F7", "C7")),
                ChordBar.of("F7"));
    }

    /** A ten-bar tune in AAB form: two statements of a ii-V-ii-V, then two bars of home. */
    public static SongStructure song1(int tempoBpm) {
        return new SongStructure("Song 1", tempoBpm, 4, List.of(
                section("A", "Em7", "A7", "Dm7", "G7"),
                section("A", "Em7", "A7", "Dm7", "G7"),
                section("B", "CM7", "CM7")),
                ChordBar.of("CM7"));
    }

    /** A minor blues in C: twelve bars, the first four all on the tonic. */
    public static SongStructure minorBluesInC(int tempoBpm) {
        return new SongStructure("Minor blues in C", tempoBpm, 4, List.of(
                section("A", "Cm7", "Cm7", "Cm7", "Cm7"),
                section("B", "Fm7", "Fm7", "Cm7", "Cm7"),
                section("C", "Dm7", "G7", "Cm7", "G7")),
                ChordBar.of("Cm7"));
    }

    /** A bebop blues in Bb, in the style of Charlie Parker: the same twelve bars with a ii-V on almost every turn. */
    public static SongStructure birdBluesInBb(int tempoBpm) {
        return new SongStructure("Bird blues in Bb", tempoBpm, 4, List.of(
                section("A", "BbM7", "Eb7", "BbM7", "Fm7"),
                section("B", "Eb7", "Eb7", "Dm7", "G7"),
                section("C", "Cm7", "F7", "BbM7", "G7")),
                ChordBar.of("BbM7"));
    }

    /** Thirty-two bars in AABA form over the changes of "I Got Rhythm": a tonic cycle, with a bridge of dominants. */
    public static SongStructure rhythmChanges(int tempoBpm) {
        return new SongStructure("Rhythm changes in Bb", tempoBpm, 4, List.of(
                section("A", "BbM7", "G7", "Cm7", "F7", "Dm7", "G7", "Cm7", "F7"),
                section("A", "BbM7", "G7", "Cm7", "F7", "Dm7", "G7", "Cm7", "F7"),
                section("B", "D7", "D7", "G7", "G7", "C7", "C7", "F7", "F7"),
                section("A", "BbM7", "G7", "Cm7", "F7", "Dm7", "G7", "BbM7", "BbM7")),
                ChordBar.of("BbM7"));
    }

    /** Twenty-four bars of ii-V-I changes that move between a major key and its relative minor, in AAB form. */
    public static SongStructure leavesChanges(int tempoBpm) {
        return new SongStructure("Leaves changes in G minor", tempoBpm, 4, List.of(
                section("A", "Cm7", "F7", "BbM7", "EbM7", "Am7", "D7", "Gm7", "Gm7"),
                section("A", "Cm7", "F7", "BbM7", "EbM7", "Am7", "D7", "Gm7", "Gm7"),
                section("B", "Am7", "D7", "Gm7", "Gm7", "Cm7", "F7", "BbM7", "D7")),
                ChordBar.of("Gm7"));
    }

    /** A sixteen-bar ballad in Eb, to be played slowly. */
    public static SongStructure balladInEb(int tempoBpm) {
        return new SongStructure("Ballad in Eb", tempoBpm, 4, List.of(
                section("A", "EbM7", "Cm7", "Fm7", "Bb7", "EbM7", "Cm7", "Fm7", "Bb7"),
                section("B", "Gm7", "C7", "Fm7", "Bb7", "EbM7", "Cm7", "Fm7", "Bb7")),
                ChordBar.of("EbM7"));
    }

    /** A modal tune in AABA form: eight bars on one chord, and a bridge a semitone up. */
    public static SongStructure modalInD(int tempoBpm) {
        return new SongStructure("Modal in D", tempoBpm, 4, List.of(
                section("A", "Dm7", "Dm7", "Dm7", "Dm7", "Dm7", "Dm7", "Dm7", "Dm7"),
                section("A", "Dm7", "Dm7", "Dm7", "Dm7", "Dm7", "Dm7", "Dm7", "Dm7"),
                section("B", "Ebm7", "Ebm7", "Ebm7", "Ebm7", "Ebm7", "Ebm7", "Ebm7", "Ebm7"),
                section("A", "Dm7", "Dm7", "Dm7", "Dm7", "Dm7", "Dm7", "Dm7", "Dm7")),
                ChordBar.of("Dm7"));
    }

    private static Section section(String name, String... chords) {
        return new Section(name, java.util.Arrays.stream(chords).map(ChordBar::of).toList());
    }
}
