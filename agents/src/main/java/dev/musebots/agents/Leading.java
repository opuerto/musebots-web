package dev.musebots.agents;

import dev.musebots.core.Instrument;
import dev.musebots.core.PhraseKind;
import dev.musebots.core.Segment;
import dev.musebots.core.SegmentEvent;
import dev.musebots.core.SongStructure;
import dev.musebots.core.StatusEvent;
import jade.core.AID;
import jade.core.behaviours.SimpleBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.ContractNetInitiator;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;

/**
 * What a musician does while it has the lead, following the paper's leader: the opening leader assembles the
 * ensemble, shares the song and negotiates the intro; every leader plays its part, asks the others to back it
 * up, and then hands the lead to the next soloist, or ends the tune if there is none.
 *
 * <p>Each step is a method that runs the next one when its protocol completes, and the leader works a little
 * ahead of the music rather than writing the whole performance at once.
 */
final class Leading {

    private static final int MAX_INTRO_ATTEMPTS = 3;
    private static final long ENSEMBLE_TIMEOUT_MS = 10_000;
    private static final long ACK_TIMEOUT_MS = 5_000;
    private static final long PROPOSAL_TIMEOUT_MS = 2_000;
    private static final long FINISH_GRACE_MS = 300;

    private final MusicianAgent agent;
    private final MusicianConfig config;
    /** Shorter intros win; between equal offers, the leader prefers whoever the session lists first. */
    private final Comparator<Protocol.IntroProposal> introOrder;
    private List<AID> peers = List.of();

    Leading(MusicianAgent agent) {
        this.agent = agent;
        this.config = agent.config();
        this.introOrder = Comparator.comparingInt(Protocol.IntroProposal::bars)
                .thenComparingInt(proposal -> config.introPreference().indexOf(proposal.instrument()));
    }

    // ---- The opening: assemble, share the song, negotiate the intro ----

    void openPerformance() {
        awaitEnsemble();
    }

    // Step 1: wait until every other musician has registered with the directory.
    private void awaitEnsemble() {
        long deadline = now() + ENSEMBLE_TIMEOUT_MS;
        agent.addBehaviour(new TickerBehaviour(agent, 100) {
            @Override
            protected void onTick() {
                List<AID> found = agent.musicians();
                if (found.size() >= config.ensembleSize() - 1) {
                    stop();
                    peers = found;
                    agent.emit(new StatusEvent(StatusEvent.Kind.ENSEMBLE_READY,
                            "ensemble assembled (" + (found.size() + 1) + " musicians)", now()));
                    shareStructure();
                } else if (now() > deadline) {
                    stop();
                    agent.fail("the ensemble did not assemble in time");
                }
            }
        });
    }

    // Step 2: tell everyone the song (an instruction), and wait until all have confirmed.
    private void shareStructure() {
        SongStructure song = config.song();
        Set<String> pending = new HashSet<>();
        for (AID peer : peers) {
            ACLMessage message = agent.newMessage(ACLMessage.INFORM, Protocol.SHARE_STRUCTURE, song);
            message.addReceiver(peer);
            agent.say(message, "the song is '" + song.title() + "' at " + song.tempoBpm() + " bpm");
            pending.add(peer.getName());
        }
        agent.addBehaviour(new SimpleBehaviour(agent) {
            private final long deadline = now() + ACK_TIMEOUT_MS;
            private final MessageTemplate template = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.CONFIRM),
                    MessageTemplate.MatchConversationId(Protocol.SHARE_STRUCTURE));

            @Override
            public void action() {
                ACLMessage confirm = myAgent.receive(template);
                if (confirm != null) {
                    pending.remove(confirm.getSender().getName());
                } else {
                    block(Math.max(1, deadline - now()));
                }
            }

            @Override
            public boolean done() {
                return pending.isEmpty() || now() > deadline;
            }

            @Override
            public int onEnd() {
                if (pending.isEmpty()) {
                    requestIntro(1);
                } else {
                    agent.fail("some musicians never confirmed the song structure");
                }
                return 0;
            }
        });
    }

    // Step 3: ask who wants to play the intro; retry if nobody does.
    private void requestIntro(int attempt) {
        ACLMessage cfp = agent.newMessage(ACLMessage.CFP, Protocol.INTRO, new Protocol.IntroCall(config.song().title()));
        cfp.setProtocol(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET);
        cfp.setReplyByDate(new java.util.Date(now() + PROPOSAL_TIMEOUT_MS));
        peers.forEach(cfp::addReceiver);
        agent.observe(cfp, "who wants to play the intro? (attempt " + attempt + ")");
        agent.addBehaviour(new IntroNegotiation(cfp, attempt));
    }

    // Step 4: the intro is arranged; the head follows it.
    private void introArranged(Instrument player, long startMs, long durationMs) {
        Segment intro = new Segment("INTRO", PhraseKind.INTRO, player, startMs, startMs + durationMs, agent.tempoBpm());
        agent.emit(new SegmentEvent(intro, label(intro)));
        SongStructure song = config.song();
        long headStart = startMs + durationMs;
        Segment head = new Segment("HEAD", PhraseKind.HEAD, config.instrument(), headStart,
                headStart + agent.musicClock().barsToMillis(song.allBars().size()), agent.tempoBpm());
        lead(head, PhraseKind.HEAD, config.soloOrder(), true);
    }

    // ---- Leading a part of the tune, whoever is leading ----

    /** The musician who was handed the lead: look up when its solo is, then play it. */
    void takeLead(Protocol.TakeLead handover) {
        agent.whenIs(handover.segmentId(), segment -> lead(segment, PhraseKind.SOLO, handover.remaining(), false));
    }

    /**
     * Plays a part of the tune, asks the others to back it up, and arranges what follows shortly before it ends.
     *
     * @param record whether this musician is the one who decided the segment (and so puts it on its timeline)
     */
    private void lead(Segment segment, PhraseKind myPart, List<Instrument> soloists, boolean record) {
        agent.emit(new SegmentEvent(segment, label(segment)));
        if (record) {
            agent.record(segment);
        }
        agent.compose(myPart, segment);
        askForSupport(segment);

        if (segment.kind() == PhraseKind.ENDING) {
            agent.at(segment.endMs() + FINISH_GRACE_MS, () ->
                    agent.emit(new StatusEvent(StatusEvent.Kind.FINISHED, "the performance is over", now())));
        } else {
            agent.at(segment.endMs() - config.planAheadMs(), () -> whatNext(segment.endMs(), soloists));
        }
    }

    /** The next soloist takes the lead, or if everybody has played, the tune ends. */
    private void whatNext(long startMs, List<Instrument> soloists) {
        SongStructure song = agent.structure();
        if (soloists.isEmpty()) {
            Segment ending = new Segment("ENDING", PhraseKind.ENDING, config.instrument(), startMs,
                    startMs + agent.musicClock().barsToMillis(1), agent.tempoBpm());
            lead(ending, PhraseKind.ENDING, List.of(), true);
            return;
        }

        Instrument next = soloists.get(0);
        List<Instrument> after = soloists.subList(1, soloists.size());
        Segment solo = new Segment("SOLO-" + next.name(), PhraseKind.SOLO, next, startMs,
                startMs + agent.musicClock().barsToMillis(song.allBars().size() * config.soloChoruses()), agent.tempoBpm());
        if (next == config.instrument()) {
            lead(solo, PhraseKind.SOLO, after, true);
        } else {
            agent.record(solo);
            passLead(next, solo, after);
        }
    }

    private void passLead(Instrument next, Segment solo, List<Instrument> after) {
        ACLMessage request = agent.newMessage(ACLMessage.REQUEST, Protocol.TAKE_LEAD,
                new Protocol.TakeLead(solo.id(), after));
        request.setProtocol(FIPANames.InteractionProtocol.FIPA_REQUEST);
        request.addReceiver(agent.musician(next));
        agent.say(request, "your turn, " + next.name().toLowerCase() + ": the lead is yours for " + solo.id());
    }

    private void askForSupport(Segment segment) {
        for (AID peer : agent.musicians()) {
            ACLMessage request = agent.newMessage(ACLMessage.REQUEST, Protocol.ACCOMPANY,
                    new Protocol.Accompany(segment.id(), config.instrument()));
            request.setProtocol(FIPANames.InteractionProtocol.FIPA_REQUEST);
            request.addReceiver(peer);
            agent.say(request, "back me up in " + segment.id());
        }
    }

    private static String label(Segment segment) {
        return switch (segment.kind()) {
            case INTRO -> "Intro";
            case HEAD -> "The head";
            case SOLO -> segment.leader().name().toLowerCase() + " solo";
            case ENDING -> "The ending";
            default -> segment.id();
        };
    }

    private long now() {
        return config.clock().millis();
    }

    /** Initiator half of the FIPA Contract Net for the intro. */
    private final class IntroNegotiation extends ContractNetInitiator {

        private final int attempt;
        private boolean introScheduled;
        private Instrument introPlayer;

        IntroNegotiation(ACLMessage cfp, int attempt) {
            super(agent, cfp);
            this.attempt = attempt;
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        protected void handleAllResponses(Vector responses, Vector acceptances) {
            ACLMessage best = null;
            Protocol.IntroProposal bestProposal = null;
            for (Object response : responses) {
                ACLMessage reply = (ACLMessage) response;
                if (reply.getPerformative() == ACLMessage.PROPOSE) {
                    Protocol.IntroProposal proposal = Json.read(reply.getContent(), Protocol.IntroProposal.class);
                    if (bestProposal == null || introOrder.compare(proposal, bestProposal) < 0) {
                        best = reply;
                        bestProposal = proposal;
                    }
                }
            }
            if (best == null) {
                return; // no acceptances: the protocol ends and onEnd() retries
            }
            introPlayer = bestProposal.instrument();

            long startMs = now() + config.leadInMs();
            for (Object response : responses) {
                ACLMessage reply = (ACLMessage) response;
                if (reply.getPerformative() != ACLMessage.PROPOSE) {
                    continue;
                }
                ACLMessage answer = reply.createReply();
                if (reply == best) {
                    answer.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                    answer.setContent(Json.write(new Protocol.IntroAssignment(startMs)));
                    agent.observe(answer, "you play the intro, starting at " + startMs);
                } else {
                    answer.setPerformative(ACLMessage.REJECT_PROPOSAL);
                    agent.observe(answer, "thanks, someone else will play the intro");
                }
                acceptances.add(answer);
            }
        }

        @Override
        protected void handleInform(ACLMessage inform) {
            introScheduled = true;
            Protocol.IntroPerformed performed = Json.read(inform.getContent(), Protocol.IntroPerformed.class);
            introArranged(introPlayer, performed.startMs(), performed.durationMs());
        }

        /** Runs when the protocol has ended, whether or not anyone delivered an intro. */
        @Override
        public int onEnd() {
            if (!introScheduled) {
                if (attempt < MAX_INTRO_ATTEMPTS) {
                    requestIntro(attempt + 1);
                } else {
                    agent.fail("nobody would play the intro");
                }
            }
            return super.onEnd();
        }
    }
}
