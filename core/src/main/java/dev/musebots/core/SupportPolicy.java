package dev.musebots.core;

/** The etiquette of backing someone up: everybody keeps playing, but how much depends on who has the floor. */
public final class SupportPolicy {

    private SupportPolicy() {
    }

    /**
     * What the others play while {@code leader} plays a phrase of kind {@code leaderPart}. Nobody drops out;
     * behind a drum solo they play lightly, so the fills can be heard.
     */
    public static PhraseKind supportFor(Instrument leader, PhraseKind leaderPart) {
        PhraseKind support = leaderPart.supporting();
        if (support == PhraseKind.SUPPORT && leader == Instrument.DRUMS) {
            return PhraseKind.SUPPORT_LIGHT;
        }
        return support;
    }
}
