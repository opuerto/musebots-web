package dev.musebots.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.musebots.core.ConversationEvent;
import dev.musebots.core.NoteEvent;
import dev.musebots.core.PerformanceEvent;
import dev.musebots.core.StatusEvent;

/**
 * The JSON messages sent to the browser. Every message has a {@code type}:
 * {@code hello}, {@code time.sync}, {@code note}, {@code conversation}, {@code status}.
 */
final class Wire {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private Wire() {
    }

    static String encode(PerformanceEvent event) {
        ObjectNode node = MAPPER.createObjectNode();
        if (event instanceof NoteEvent note) {
            node.put("type", "note");
            node.put("instrument", note.instrument().name());
            node.put("midi", note.midi());
            node.put("startMs", note.startMs());
            node.put("durationMs", note.durationMs());
            node.put("velocity", note.velocity());
        } else if (event instanceof ConversationEvent talk) {
            node.put("type", "conversation");
            node.put("from", talk.from());
            node.set("to", MAPPER.valueToTree(talk.to()));
            node.put("performative", talk.performative());
            node.put("protocol", talk.protocol());
            node.put("conversationId", talk.conversationId());
            node.put("summary", talk.summary());
            node.put("atMs", talk.atMs());
        } else if (event instanceof StatusEvent status) {
            node.put("type", "status");
            node.put("kind", status.kind().name());
            node.put("message", status.message());
            node.put("atMs", status.atMs());
        }
        return node.toString();
    }

    static String hello(long serverMs) {
        return MAPPER.createObjectNode().put("type", "hello").put("serverMs", serverMs).toString();
    }

    static String timeSync(long clientT0, long serverMs) {
        return MAPPER.createObjectNode().put("type", "time.sync").put("t0", clientT0).put("serverMs", serverMs).toString();
    }

    static String error(String message) {
        return MAPPER.createObjectNode().put("type", "error").put("message", message).toString();
    }
}
