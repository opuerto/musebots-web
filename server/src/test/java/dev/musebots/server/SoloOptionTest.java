package dev.musebots.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.musebots.core.Instrument;
import dev.musebots.core.NoteEvent;
import dev.musebots.core.PhraseKind;
import dev.musebots.core.PhraseRequest;
import dev.musebots.core.SongStructure;
import dev.musebots.core.Songs;
import dev.musebots.learning.Corpus;
import dev.musebots.learning.MarkovModel;
import dev.musebots.learning.TickNote;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SoloOptionTest {

    /** A model that has only ever seen eight eighths a bar, so what it plays is easy to recognise. */
    static MarkovModel eighthsModel() {
        List<TickNote> notes = new ArrayList<>();
        int pitch = 65;
        for (int i = 0; i < 16 * 8; i++) {
            notes.add(new TickNote(i * 6, 6, pitch));
            pitch = pitch >= 76 ? 65 : pitch + 1;
        }
        Corpus.Solo solo = new Corpus.Solo("a", "Player", "Tune", "as", "BEBOP", "SWING", "F-maj", 120.0, 4,
                List.of(new Corpus.ChordChange(0, "F7")), notes);
        return MarkovModel.train(List.of(solo), new MarkovModel.Meta("test", List.of("as"), 0, 0, 3, 1, List.of("a"), List.of()));
    }

    private final ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
    private final PrintStream log = new PrintStream(logBytes, true, StandardCharsets.UTF_8);

    private String logged() {
        return logBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void withoutAModelOnlyTheHandWrittenRulesAreOffered() {
        for (String path : new String[] {null, "", "   "}) {
            List<SoloOption> offered = SoloOption.offered(path, log);
            assertEquals(List.of("rules"), offered.stream().map(SoloOption::id).toList());
            assertEquals("", offered.get(0).credit(), "the rules have no data to credit");
        }
        assertEquals("", logged());
    }

    @Test
    void aModelThatLoadsIsOfferedAfterTheRulesWithItsDataCredited(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("model.json");
        eighthsModel().write(file);

        List<SoloOption> offered = SoloOption.offered(file.toString(), log);
        assertEquals(List.of("rules", "markov"), offered.stream().map(SoloOption::id).toList(), "the rules stay the default");
        assertTrue(offered.get(1).credit().contains("Weimar Jazz Database"));
        assertTrue(offered.get(1).credit().contains("ODbL"));
        assertTrue(offered.get(1).credit().contains("1 transcribed solos"), offered.get(1).credit());
        assertTrue(logged().contains("Solo model loaded: learned from 1 solos"));
    }

    /** A model that has only ever seen one note held a whole bar, so it is easy to tell from {@link #eighthsModel()}. */
    static MarkovModel wholeNotesModel() {
        List<TickNote> notes = new ArrayList<>();
        for (int bar = 0; bar < 16; bar++) {
            notes.add(new TickNote(bar * 48, 48, 65 + bar % 2));
        }
        Corpus.Solo solo = new Corpus.Solo("b", "Player", "Tune", "p", "BEBOP", "SWING", "F-maj", 120.0, 4,
                List.of(new Corpus.ChordChange(0, "F7")), notes);
        return MarkovModel.train(List.of(solo), new MarkovModel.Meta("test", List.of("p"), 0, 0, 3, 1, List.of("b"), List.of()));
    }

    private static List<NoteEvent> solo(SoloOption option, Instrument instrument) {
        SongStructure song = Songs.bluesInF(120);
        return option.composers().create(instrument, new Random(1))
                .compose(new PhraseRequest(PhraseKind.SOLO, song.barsFor(PhraseKind.SOLO, 4), song.clock(), 0));
    }

    @Test
    void eachInstrumentCanHaveItsOwnModelAndTheSharedOneCoversTheRest(@TempDir Path dir) throws IOException {
        Path shared = dir.resolve("shared.json");
        Path piano = dir.resolve("piano.json");
        eighthsModel().write(shared);
        wholeNotesModel().write(piano);
        Map<String, String> environment = Map.of("MARKOV_MODEL", shared.toString(), "MARKOV_MODEL_PIANO", piano.toString());

        List<SoloOption> offered = SoloOption.offered(environment::get, log);
        assertEquals(List.of("rules", "markov"), offered.stream().map(SoloOption::id).toList());
        SoloOption markov = offered.get(1);
        assertTrue(markov.credit().contains("2 transcribed solos"), "both models' data is credited: " + markov.credit());
        assertTrue(logged().contains("Solo model loaded for the piano"), logged());

        // The piano's line is its own model's whole notes (with soft chords underneath); the sax and bass play the shared eighths.
        assertEquals(4, solo(markov, Instrument.PIANO).stream().filter(n -> n.velocity() >= 84).count());
        assertEquals(32, solo(markov, Instrument.SAX).size());
        assertEquals(32, solo(markov, Instrument.BASS).size());
        // The drums learn too: the shared model's rhythm on the kit, not the rules' ride pattern.
        assertNotEquals(solo(rules(), Instrument.DRUMS), solo(markov, Instrument.DRUMS));
    }

    @Test
    void anInstrumentWithNoModelSolosByTheRules(@TempDir Path dir) throws IOException {
        Path bass = dir.resolve("bass.json");
        eighthsModel().write(bass);
        SoloOption markov = SoloOption.offered(Map.of("MARKOV_MODEL_BASS", bass.toString())::get, log).get(1);
        assertEquals(32, solo(markov, Instrument.BASS).size());
        for (Instrument instrument : new Instrument[] {Instrument.SAX, Instrument.PIANO, Instrument.DRUMS}) {
            assertEquals(solo(rules(), instrument), solo(markov, instrument), instrument.toString());
        }
    }

    @Test
    void anInstrumentWhoseModelIsMissingFallsBackToTheSharedOne(@TempDir Path dir) throws IOException {
        Path shared = dir.resolve("shared.json");
        eighthsModel().write(shared);
        Map<String, String> environment = Map.of("MARKOV_MODEL", shared.toString(), "MARKOV_MODEL_SAX", dir.resolve("gone.json").toString());
        List<SoloOption> offered = SoloOption.offered(environment::get, log);
        assertTrue(logged().contains("Could not load the solo model for the sax"), logged());
        assertEquals(32, solo(offered.get(1), Instrument.SAX).size());
    }

    private static SoloOption rules() {
        return SoloOption.rules();
    }

    @Test
    void aModelThatIsMissingIsReportedAndTheBandStillPlaysWithTheRules(@TempDir Path dir) {
        List<SoloOption> offered = SoloOption.offered(dir.resolve("nothing-here.json").toString(), log);
        assertEquals(List.of("rules"), offered.stream().map(SoloOption::id).toList());
        assertTrue(logged().contains("Could not load the solo model"));
        assertTrue(logged().contains("offering the rules only"));
    }

    @Test
    void aModelThatIsNotAModelIsReportedToo(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("broken.json");
        Files.writeString(file, "this is not json");
        List<SoloOption> offered = SoloOption.offered(file.toString(), log);
        assertEquals(List.of("rules"), offered.stream().map(SoloOption::id).toList());
        assertTrue(logged().contains("Could not load the solo model"));
    }
}
