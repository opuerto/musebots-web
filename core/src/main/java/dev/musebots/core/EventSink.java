package dev.musebots.core;

/** Where agents publish what they play and say. Implementations must be thread-safe. */
@FunctionalInterface
public interface EventSink {

    void emit(PerformanceEvent event);
}
