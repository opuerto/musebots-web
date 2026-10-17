package dev.musebots.core;

import java.util.List;

/** Something one musician said to others (an ACL message), surfaced so UIs can show the negotiation. */
public record ConversationEvent(String from, List<String> to, String performative, String protocol,
                                String summary, long atMs) implements PerformanceEvent {
}
