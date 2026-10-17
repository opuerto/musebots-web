package dev.musebots.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SongStructureTest {

    private final SongStructure blues = Songs.bluesInF(120);

    @Test
    void cycleRepeatsTheFormToFillTheBars() {
        assertEquals(24, blues.cycle(24).size());
        assertEquals(blues.allBars().get(0), blues.cycle(24).get(12));
        assertEquals(5, blues.cycle(5).size());
    }

    @Test
    void theTurnaroundIsTheEndOfTheForm() {
        assertEquals(blues.allBars().subList(8, 12), blues.turnaround(4));
    }

    @Test
    void theFormIsTheSectionLetters() {
        assertEquals("ABC", blues.form());
        assertEquals("AAB", Songs.song1(120).form());
    }

    @Test
    void barsAreChosenByPhraseKind() {
        assertEquals(blues.turnaround(4), blues.barsFor(PhraseKind.INTRO, 4));
        assertEquals(blues.home(), blues.barsFor(PhraseKind.ENDING, 1));
        assertEquals(blues.cycle(12), blues.barsFor(PhraseKind.SOLO, 12));
    }

    @Test
    void millisConvertBackToBars() {
        MusicClock clock = blues.clock();
        assertEquals(12, clock.millisToBars(clock.barsToMillis(12)));
    }
}
