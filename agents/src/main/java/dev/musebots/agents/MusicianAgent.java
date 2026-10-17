package dev.musebots.agents;

import dev.musebots.core.MusicClock;
import dev.musebots.core.PhraseKind;
import dev.musebots.core.Segment;
import dev.musebots.core.SongStructure;
import jade.core.AID;
import jade.core.behaviours.WakerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.FIPANames;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The musician of a Musebot. It decides what to do: it takes part in the ensemble's conversations
 * ({@link Supporting}) and, when it has the lead, runs the performance ({@link Leading}). Writing notes is
 * its composer's job and keeping the timeline is its synchronizer's.
 */
public final class MusicianAgent extends MusebotAgent {

    private static final long SYNC_TIMEOUT_MS = 5_000;

    private final MusicianConfig config;
    private final Leading leading;
    private final AtomicInteger queries = new AtomicInteger();
    private volatile SongStructure structure;
    /** Set when the Conductor changes the tempo; until then the song's own tempo applies. */
    private volatile Integer conductedTempo;

    public MusicianAgent(MusicianConfig config) {
        super(config.sessionId(), config.sink(), config.clock());
        this.config = config;
        this.structure = config.song();
        this.leading = new Leading(this);
    }

    MusicianConfig config() {
        return config;
    }

    Leading leading() {
        return leading;
    }

    SongStructure structure() {
        return structure;
    }

    void learn(SongStructure song) {
        this.structure = song;
    }

    /** The tempo for anything this musician arranges from now on. */
    int tempoBpm() {
        return conductedTempo != null ? conductedTempo : structure.tempoBpm();
    }

    void conductTempo(int bpm) {
        this.conductedTempo = bpm;
    }

    /** The beat of the music at the current tempo. */
    MusicClock musicClock() {
        return new MusicClock(tempoBpm(), structure.beatsPerBar());
    }

    @Override
    protected void setup() {
        registerAs(Protocol.SERVICE_MUSICIAN);
        Supporting.attach(this);
        if (config.opensPerformance()) {
            leading.openPerformance();
        }
    }

    List<AID> musicians() {
        return findPeers(Protocol.SERVICE_MUSICIAN);
    }

    AID musician(dev.musebots.core.Instrument instrument) {
        return Names.aid(config.sessionId(), instrument, Names.Part.MUSICIAN);
    }

    /** Asks this Musebot's composer to write a phrase for the segment. */
    void compose(PhraseKind kind, Segment segment) {
        // Write it at the tempo the segment was arranged at, not at whatever the tempo is now.
        MusicClock clock = new MusicClock(segment.tempoBpm(), structure.beatsPerBar());
        int bars = clock.millisToBars(segment.durationMs());
        Protocol.ComposeRequest job = new Protocol.ComposeRequest(
                kind, segment.id(), structure.barsFor(kind, bars), clock, segment.startMs());
        ACLMessage request = newMessage(ACLMessage.REQUEST, Protocol.COMPOSE, job);
        request.setProtocol(FIPANames.InteractionProtocol.FIPA_REQUEST);
        request.addReceiver(Names.aid(config.sessionId(), config.instrument(), Names.Part.COMPOSER));
        say(request, "write the " + kind.name().toLowerCase() + " for " + segment.id() + " (" + bars + " bars)");
    }

    /** Tells this Musebot's synchronizer about a part of the tune. */
    void record(Segment segment) {
        ACLMessage message = newMessage(ACLMessage.INFORM, Protocol.SYNC_RECORD, segment);
        message.addReceiver(Names.aid(config.sessionId(), config.instrument(), Names.Part.SYNCHRONIZER));
        say(message, "note on the timeline: " + segment.id() + " runs from " + segment.startMs() + " to " + segment.endMs());
    }

    /** Asks this Musebot's synchronizer when a part of the tune happens, and carries on once it knows. */
    void whenIs(String segmentId, Consumer<Segment> then) {
        String token = "q-" + getLocalName() + "-" + queries.incrementAndGet();
        ACLMessage query = newMessage(ACLMessage.QUERY_REF, Protocol.SYNC_QUERY, new Protocol.SegmentQuery(segmentId));
        query.setProtocol(FIPANames.InteractionProtocol.FIPA_QUERY);
        query.addReceiver(Names.aid(config.sessionId(), config.instrument(), Names.Part.SYNCHRONIZER));
        query.setReplyWith(token);
        say(query, "when does " + segmentId + " happen?");

        MessageTemplate reply = MessageTemplate.and(
                MessageTemplate.MatchConversationId(Protocol.SYNC_QUERY), MessageTemplate.MatchInReplyTo(token));
        addBehaviour(new AwaitReply(this, reply, SYNC_TIMEOUT_MS,
                answer -> {
                    if (answer.getPerformative() == ACLMessage.INFORM) {
                        then.accept(Json.read(answer.getContent(), Segment.class));
                    } else {
                        fail("nobody could tell me when " + segmentId + " happens");
                    }
                },
                () -> fail("my synchronizer did not answer about " + segmentId)));
    }

    /** Runs the task at the given wall-clock time (immediately if that has passed). */
    void at(long timeMs, Runnable task) {
        addBehaviour(new WakerBehaviour(this, Math.max(0, timeMs - clock().millis())) {
            @Override
            protected void onWake() {
                task.run();
            }
        });
    }
}
