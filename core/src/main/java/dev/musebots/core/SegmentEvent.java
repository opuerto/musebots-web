package dev.musebots.core;

/**
 * A part of the tune has been arranged: it will run from {@code segment.startMs()} to {@code segment.endMs()}. The
 * leader announces each one a little before it is heard, so a display can show the band where it is in the form.
 */
public record SegmentEvent(Segment segment, String label) implements PerformanceEvent {
}
