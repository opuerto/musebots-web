package dev.musebots.core;

/** Everything an observer can see during a performance. */
public sealed interface PerformanceEvent permits NoteEvent, ConversationEvent, SegmentEvent, StatusEvent {
}
