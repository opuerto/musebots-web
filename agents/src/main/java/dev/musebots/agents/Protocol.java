package dev.musebots.agents;

import dev.musebots.core.ChordBar;
import dev.musebots.core.Instrument;
import dev.musebots.core.MusicClock;
import dev.musebots.core.PhraseKind;
import java.util.List;

/**
 * The vocabulary agents share: conversation ids and message payloads. Payloads travel as JSON in the ACL
 * message content; the FIPA performative says what the sender means by them.
 */
public final class Protocol {

    public static final String ONTOLOGY = "musebots-v1";
    public static final String LANGUAGE = "json";

    public static final String SERVICE_MUSICIAN = "musician";
    public static final String SERVICE_SYNCHRONIZER = "synchronizer";

    // Between musicians
    /** Leader to everyone: "this is the song we are playing" (INFORM, answered by CONFIRM). */
    public static final String SHARE_STRUCTURE = "share-structure";
    /** Leader to everyone: "who wants to play the intro?" (Contract Net). */
    public static final String INTRO = "intro";
    /** Leader to everyone: "back me up in this part of the tune" (REQUEST, answered by INFORM). */
    public static final String ACCOMPANY = "accompany";
    /** Leader to the next soloist: "you have the lead" (REQUEST, answered by INFORM). */
    public static final String TAKE_LEAD = "take-lead";

    // Inside a Musebot
    /** Musician to its composer: "write this" (REQUEST, answered by INFORM). */
    public static final String COMPOSE = "compose";
    /** Musician to its synchronizer: "note this part of the tune on the timeline" (INFORM). */
    public static final String SYNC_RECORD = "sync-record";
    /** Musician to its synchronizer: "when does this part happen?" (QUERY-REF, answered by INFORM or FAILURE). */
    public static final String SYNC_QUERY = "sync-query";

    // Between synchronizers
    /** Synchronizer to synchronizers: "do you know when this part happens?" (QUERY-REF, answered by INFORM or FAILURE). */
    public static final String SYNC_PEER = "sync-peer";

    private Protocol() {
    }

    /** Payload of the intro call for proposals. */
    public record IntroCall(String song) {
    }

    /** A musician's offer to play the intro. */
    public record IntroProposal(Instrument instrument, int bars) {
    }

    /** The leader accepting an offer and fixing when the intro starts. */
    public record IntroAssignment(long startMs) {
    }

    /** The intro player reporting when it will start and how long it lasts, so others can plan around it. */
    public record IntroPerformed(long startMs, long durationMs) {
    }

    /** Asks a supporter to back up the leader during the segment with this id. */
    public record Accompany(String segmentId, Instrument leader) {
    }

    /** A supporter's answer: whether it will play or lay out. */
    public record Accompanying(String segmentId, boolean playing) {
    }

    /** Hands the lead to the next soloist; {@code remaining} are the soloists who follow. */
    public record TakeLead(String segmentId, List<Instrument> remaining) {
    }

    /** The new leader acknowledging the hand-over. */
    public record LeadTaken(String segmentId) {
    }

    /** A musician asking its composer to write a phrase. */
    public record ComposeRequest(PhraseKind kind, String segmentId, List<ChordBar> bars, MusicClock clock, long startMs) {
    }

    /** A composer reporting what it wrote. */
    public record Composed(String segmentId, PhraseKind kind, Instrument instrument, int notes) {
    }

    /** Asks a synchronizer when a segment happens. */
    public record SegmentQuery(String segmentId) {
    }
}
