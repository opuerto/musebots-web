package dev.musebots.core;

/** What a composer is asked to write. */
public enum PhraseKind {
    /** The opening turnaround, played alone before the head. */
    INTRO,
    /** The melody of the tune, played by the leader. */
    HEAD,
    /** Accompaniment behind whoever is leading or soloing. */
    SUPPORT,
    /** Accompaniment that stays out of the way, e.g. under a drum solo. */
    SUPPORT_LIGHT,
    /** An improvised chorus. */
    SOLO,
    /** The final chord that closes the tune. */
    ENDING;

    /** What everyone else plays while the leader plays this kind of phrase. */
    public PhraseKind supporting() {
        return switch (this) {
            case HEAD, SOLO -> SUPPORT;
            default -> this;
        };
    }
}
