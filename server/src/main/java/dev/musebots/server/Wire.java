package dev.musebots.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.musebots.core.ConversationEvent;
import java.util.List;
import dev.musebots.core.NoteEvent;
import dev.musebots.core.PerformanceEvent;
import dev.musebots.core.Section;
import dev.musebots.core.Segment;
import dev.musebots.core.SegmentEvent;
import dev.musebots.core.SongStructure;
import dev.musebots.core.Songs;
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
        } else if (event instanceof SegmentEvent part) {
            Segment segment = part.segment();
            node.put("type", "segment");
            node.put("id", segment.id());
            node.put("kind", segment.kind().name());
            node.put("leader", segment.leader() == null ? null : segment.leader().name());
            node.put("startMs", segment.startMs());
            node.put("endMs", segment.endMs());
            node.put("tempoBpm", segment.tempoBpm());
            node.put("label", part.label());
        } else if (event instanceof StatusEvent status) {
            node.put("type", "status");
            node.put("kind", status.kind().name());
            node.put("message", status.message());
            node.put("atMs", status.atMs());
        }
        return node.toString();
    }

    /** The tune about to be played: what the browser draws as the chord chart. */
    static String song(String id, SongStructure song) {
        ObjectNode node = songNode(id, song);
        node.put("type", "song");
        return node.toString();
    }

    private static ObjectNode songNode(String id, SongStructure song) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        node.put("title", song.title());
        node.put("style", Songs.styleOf(id));
        node.put("suggestedTempo", Songs.suggestedTempo(id));
        node.put("form", song.form());
        node.put("tempoBpm", song.tempoBpm());
        node.put("beatsPerBar", song.beatsPerBar());
        node.put("ending", song.ending().chord().symbol());
        var sections = node.putArray("sections");
        for (Section section : song.sections()) {
            ObjectNode entry = sections.addObject();
            entry.put("name", section.name());
            var bars = entry.putArray("bars");
            section.bars().forEach(bar -> bars.add(bar.chord().symbol()));
        }
        return node;
    }

    /** The first message of a connection: the server's clock, and the tunes on offer so the page can preview them. */
    static String hello(long serverMs, List<SoloOption> soloOptions) {
        ObjectNode node = MAPPER.createObjectNode().put("type", "hello").put("serverMs", serverMs);
        var composers = node.putArray("soloComposers");
        for (SoloOption option : soloOptions) {
            composers.addObject().put("id", option.id()).put("label", option.label()).put("credit", option.credit());
        }
        var songs = node.putArray("songs");
        for (String id : Songs.IDS) {
            songs.add(songNode(id, Songs.byId(id, 120)));
        }
        return node.toString();
    }

    static String timeSync(long clientT0, long serverMs) {
        return MAPPER.createObjectNode().put("type", "time.sync").put("t0", clientT0).put("serverMs", serverMs).toString();
    }

    /** {@code source} is the kind of client message that went wrong ("start", "conduct", ...), so the page can react. */
    static String error(String source, String message) {
        return MAPPER.createObjectNode().put("type", "error").put("source", source).put("message", message).toString();
    }
}
