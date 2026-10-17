package dev.musebots.agents;

import dev.musebots.core.ChordBar;
import dev.musebots.core.NoteEvent;
import dev.musebots.core.PhraseRequest;
import dev.musebots.core.SongStructure;
import dev.musebots.core.StatusEvent;
import jade.core.AID;
import jade.core.behaviours.SimpleBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.ContractNetInitiator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;

/**
 * Leads the performance, following the paper's leader: wait for the ensemble, share the song,
 * negotiate who plays the intro (Contract Net), then play the head once the intro is over.
 * Each step is a method that runs the next one when its protocol completes.
 */
final class LeaderRole implements Role {

    private static final int MAX_INTRO_ATTEMPTS = 3;
    private static final long ENSEMBLE_TIMEOUT_MS = 10_000;
    private static final long ACK_TIMEOUT_MS = 5_000;
    private static final long PROPOSAL_TIMEOUT_MS = 2_000;
    private static final long FINISH_GRACE_MS = 300;

    private final MusicianConfig config;
    private MusicianAgent agent;
    private List<AID> peers = List.of();

    LeaderRole(MusicianConfig config) {
        this.config = config;
    }

    @Override
    public void attach(MusicianAgent agent) {
        this.agent = agent;
        awaitEnsemble();
    }

    // Step 1: wait until every other musician has registered with the directory.
    private void awaitEnsemble() {
        long deadline = now() + ENSEMBLE_TIMEOUT_MS;
        agent.addBehaviour(new TickerBehaviour(agent, 100) {
            @Override
            protected void onTick() {
                List<AID> found = agent.findPeers();
                if (found.size() >= config.ensembleSize() - 1) {
                    stop();
                    peers = found;
                    agent.emit(new StatusEvent(StatusEvent.Kind.ENSEMBLE_READY,
                            "ensemble assembled (" + (found.size() + 1) + " musicians)", now()));
                    shareStructure();
                } else if (now() > deadline) {
                    stop();
                    fail("the ensemble did not assemble in time");
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
                    fail("some musicians never confirmed the song structure");
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

    // Step 4: once the intro is scheduled, play the head right after it and ask the others to join.
    private void playHead(long headStartMs) {
        SongStructure song = config.song();
        List<ChordBar> bars = new ArrayList<>();
        for (int i = 0; i < config.choruses(); i++) {
            bars.addAll(song.allBars());
        }
        PhraseRequest phrase = new PhraseRequest(bars, song.clock(), headStartMs);
        for (NoteEvent note : config.composer().compose(phrase)) {
            agent.emit(note);
        }

        Protocol.PlayRequest payload = new Protocol.PlayRequest(headStartMs, config.choruses());
        for (AID peer : peers) {
            ACLMessage request = agent.newMessage(ACLMessage.REQUEST, Protocol.PLAY_HEAD, payload);
            request.setProtocol(FIPANames.InteractionProtocol.FIPA_REQUEST);
            request.addReceiver(peer);
            agent.say(request, "join me for the head at " + headStartMs);
        }

        long finishAt = phrase.endMs() + FINISH_GRACE_MS;
        agent.addBehaviour(new WakerBehaviour(agent, Math.max(0, finishAt - now())) {
            @Override
            protected void onWake() {
                agent.emit(new StatusEvent(StatusEvent.Kind.FINISHED, "the performance is over", now()));
            }
        });
    }

    private void fail(String reason) {
        agent.emit(new StatusEvent(StatusEvent.Kind.FAILED, reason, now()));
    }

    private long now() {
        return config.clock().millis();
    }

    /** Initiator half of the FIPA Contract Net for the intro. */
    private final class IntroNegotiation extends ContractNetInitiator {

        private final int attempt;
        private boolean introScheduled;

        IntroNegotiation(ACLMessage cfp, int attempt) {
            super(agent, cfp);
            this.attempt = attempt;
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        protected void handleAllResponses(Vector responses, Vector acceptances) {
            ACLMessage best = null;
            int bestBars = Integer.MAX_VALUE;
            for (Object response : responses) {
                ACLMessage reply = (ACLMessage) response;
                if (reply.getPerformative() == ACLMessage.PROPOSE) {
                    int bars = Json.read(reply.getContent(), Protocol.IntroProposal.class).bars();
                    if (bars < bestBars) {
                        best = reply;
                        bestBars = bars;
                    }
                }
            }
            if (best == null) {
                return; // no acceptances: the protocol ends and onEnd() retries
            }

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
            agent.emit(new StatusEvent(StatusEvent.Kind.STARTED, "the intro begins", performed.startMs()));
            playHead(performed.startMs() + performed.durationMs());
        }

        /** Runs when the protocol has ended, whether or not anyone delivered an intro. */
        @Override
        public int onEnd() {
            if (!introScheduled) {
                if (attempt < MAX_INTRO_ATTEMPTS) {
                    requestIntro(attempt + 1);
                } else {
                    fail("nobody would play the intro");
                }
            }
            return super.onEnd();
        }
    }
}
