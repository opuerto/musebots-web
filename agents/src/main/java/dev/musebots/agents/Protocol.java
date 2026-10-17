package dev.musebots.agents;

/**
 * The vocabulary musicians share: conversation ids and message payloads. Payloads travel as JSON in
 * the ACL message content; the FIPA performative says what the sender means by them.
 */
public final class Protocol {

    public static final String ONTOLOGY = "musebots-v1";
    public static final String LANGUAGE = "json";
    public static final String SERVICE_TYPE = "musician";

    /** Leader to everyone: "this is the song we are playing" (INFORM, answered by CONFIRM). */
    public static final String SHARE_STRUCTURE = "share-structure";
    /** Leader to everyone: "I need someone to play the intro" (Contract Net). */
    public static final String INTRO = "intro";
    /** Leader to everyone: "start accompanying me here" (REQUEST). */
    public static final String PLAY_HEAD = "play-head";

    private Protocol() {
    }

    /** Payload of the intro call for proposals. */
    public record IntroCall(String song) {
    }

    /** A musician's offer to play the intro. */
    public record IntroProposal(int bars) {
    }

    /** The leader accepting an offer and fixing when the intro starts. */
    public record IntroAssignment(long startMs) {
    }

    /** The intro player reporting when it will start and how long it lasts, so others can plan around it. */
    public record IntroPerformed(long startMs, long durationMs) {
    }

    /** Request to accompany the leader through the given number of choruses. */
    public record PlayRequest(long startMs, int choruses) {
    }
}
