package dev.musebots.core;

/** The etiquette of backing someone up: who plays and who lays out while another musician has the floor. */
public final class SupportPolicy {

    private SupportPolicy() {
    }

    /**
     * @param self    the musician deciding
     * @param leader  whoever is leading or soloing
     * @param support what the others are playing ({@link PhraseKind#SUPPORT} or {@link PhraseKind#ENDING})
     */
    public static boolean laysOut(Instrument self, Instrument leader, PhraseKind support) {
        if (support == PhraseKind.ENDING) {
            return false; // everybody plays the last chord
        }
        if (self == Instrument.SAX) {
            return true; // the horn leaves space for the others
        }
        return leader == Instrument.DRUMS; // nobody comps under a drum solo
    }
}
