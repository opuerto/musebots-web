package dev.musebots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class SongsTest {

    private static List<String> symbols(Section section) {
        return section.bars().stream().map(bar -> bar.chord().symbol()).toList();
    }

    @Test
    void theBluesIsTwelveBarsWrittenAsThreeLinesOfFour() {
        SongStructure blues = Songs.bluesInF(120);
        assertEquals("Blues in F", blues.title());
        assertEquals("ABC", blues.form());
        assertEquals(12, blues.allBars().size());
        assertEquals(List.of("F7", "Bb7", "F7", "F7"), symbols(blues.sections().get(0)));
        assertEquals(List.of("Gm7", "C7", "F7", "C7"), symbols(blues.sections().get(2)));
        assertEquals(List.of(ChordBar.of("F7")), blues.home());
    }

    @Test
    void songOneIsTenBarsInAABForm() {
        SongStructure song = Songs.song1(120);
        assertEquals("AAB", song.form());
        assertEquals(10, song.allBars().size());
        assertEquals(List.of("Em7", "A7", "Dm7", "G7"), symbols(song.sections().get(0)));
        assertEquals(symbols(song.sections().get(0)), symbols(song.sections().get(1)));
        assertEquals(List.of("CM7", "CM7"), symbols(song.sections().get(2)));
    }

    @Test
    void theTuneEndsOnItsTonicNotOnItsFirstChord() {
        // Song 1 starts on Em7 but is in C: its last chord is the home chord.
        assertEquals(List.of(ChordBar.of("CM7")), Songs.song1(120).home());
        assertEquals(List.of(ChordBar.of("CM7")), Songs.song1(120).barsFor(PhraseKind.ENDING, 1));
    }

    @Test
    void anIntroIsTheTurnaroundOfWhateverTheFormIs() {
        assertEquals(List.of("Dm7", "G7", "CM7", "CM7"),
                Songs.song1(120).turnaround(4).stream().map(bar -> bar.chord().symbol()).toList());
    }

    @Test
    void tunesAreFoundByIdAndUnknownOnesAreNull() {
        assertEquals("Blues in F", Songs.byId(Songs.BLUES, 100).title());
        assertEquals(100, Songs.byId(Songs.SONG_1, 100).tempoBpm());
        assertNull(Songs.byId("nonsense", 100));
    }
}
