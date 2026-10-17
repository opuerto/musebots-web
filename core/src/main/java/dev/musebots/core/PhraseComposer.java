package dev.musebots.core;

import java.util.List;

/**
 * Strategy for turning a chord chart into notes for one instrument. Composition is the integration
 * point for algorithmic-composition techniques: swap in a different implementation without touching
 * the agents.
 */
public interface PhraseComposer {

    Instrument instrument();

    List<NoteEvent> compose(PhraseRequest request);
}
