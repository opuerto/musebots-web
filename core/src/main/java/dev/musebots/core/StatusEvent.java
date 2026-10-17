package dev.musebots.core;

/** Lifecycle notifications for a performance. */
public record StatusEvent(Kind kind, String message, long atMs) implements PerformanceEvent {

    public enum Kind {
        ENSEMBLE_READY,
        FINISHED,
        FAILED
    }
}
