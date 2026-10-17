package dev.musebots.agents;

import dev.musebots.core.ChordBar;
import dev.musebots.core.NoteEvent;
import dev.musebots.core.PhraseRequest;
import dev.musebots.core.SongStructure;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.FIPAAgentManagement.FailureException;
import jade.domain.FIPAAgentManagement.NotUnderstoodException;
import jade.domain.FIPAAgentManagement.RefuseException;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.ContractNetResponder;
import java.util.ArrayList;
import java.util.List;

/**
 * Supports the leader: learns the song, bids to play the intro when asked, and accompanies the head.
 * Mirrors the paper's accompanist: receive structure, accept or refuse the intro, then play along.
 */
final class AccompanistRole implements Role {

    private static final int INTRO_BARS = 4;

    private final MusicianConfig config;
    private volatile SongStructure structure;

    AccompanistRole(MusicianConfig config) {
        this.config = config;
    }

    @Override
    public void attach(MusicianAgent agent) {
        agent.addBehaviour(new StructureReceiver(agent));
        agent.addBehaviour(new IntroResponder(agent));
        agent.addBehaviour(new HeadReceiver(agent));
    }

    /** Accepts the song structure from the leader and confirms receipt. */
    private final class StructureReceiver extends CyclicBehaviour {

        private final MessageTemplate template = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                MessageTemplate.MatchConversationId(Protocol.SHARE_STRUCTURE));
        private final MusicianAgent agent;

        StructureReceiver(MusicianAgent agent) {
            super(agent);
            this.agent = agent;
        }

        @Override
        public void action() {
            ACLMessage message = myAgent.receive(template);
            if (message == null) {
                block();
                return;
            }
            structure = Json.read(message.getContent(), SongStructure.class);
            ACLMessage confirm = message.createReply();
            confirm.setPerformative(ACLMessage.CONFIRM);
            agent.say(confirm, "got the structure of '" + structure.title() + "'");
        }
    }

    /** Responder half of the FIPA Contract Net: propose to play the intro, play it if chosen. */
    private final class IntroResponder extends ContractNetResponder {

        private final MusicianAgent agent;

        IntroResponder(MusicianAgent agent) {
            super(agent, MessageTemplate.and(
                    ContractNetResponder.createMessageTemplate(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET),
                    MessageTemplate.MatchConversationId(Protocol.INTRO)));
            this.agent = agent;
        }

        @Override
        protected ACLMessage handleCfp(ACLMessage cfp) throws RefuseException, FailureException, NotUnderstoodException {
            if (structure == null) {
                ACLMessage refuse = cfp.createReply();
                refuse.setPerformative(ACLMessage.REFUSE);
                agent.observe(refuse, "I don't know the song yet");
                throw new RefuseException("no structure");
            }
            ACLMessage propose = cfp.createReply();
            propose.setPerformative(ACLMessage.PROPOSE);
            propose.setContent(Json.write(new Protocol.IntroProposal(INTRO_BARS)));
            agent.observe(propose, "I can play a " + INTRO_BARS + "-bar intro");
            return propose;
        }

        @Override
        protected ACLMessage handleAcceptProposal(ACLMessage cfp, ACLMessage propose, ACLMessage accept)
                throws FailureException {
            Protocol.IntroAssignment assignment = Json.read(accept.getContent(), Protocol.IntroAssignment.class);

            List<ChordBar> form = structure.allBars();
            List<ChordBar> turnaround = form.subList(Math.max(0, form.size() - INTRO_BARS), form.size());
            PhraseRequest request = new PhraseRequest(turnaround, structure.clock(), assignment.startMs());
            for (NoteEvent note : config.composer().compose(request)) {
                agent.emit(note);
            }

            ACLMessage inform = accept.createReply();
            inform.setPerformative(ACLMessage.INFORM);
            inform.setContent(Json.write(new Protocol.IntroPerformed(request.startMs(), request.durationMs())));
            agent.observe(inform, "intro written: starts " + request.startMs() + ", lasts " + request.durationMs() + " ms");
            return inform;
        }
    }

    /** Plays along with the leader when asked to accompany the head. */
    private final class HeadReceiver extends CyclicBehaviour {

        private final MessageTemplate template = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                MessageTemplate.MatchConversationId(Protocol.PLAY_HEAD));
        private final MusicianAgent agent;

        HeadReceiver(MusicianAgent agent) {
            super(agent);
            this.agent = agent;
        }

        @Override
        public void action() {
            ACLMessage message = myAgent.receive(template);
            if (message == null) {
                block();
                return;
            }
            Protocol.PlayRequest request = Json.read(message.getContent(), Protocol.PlayRequest.class);
            List<ChordBar> bars = new ArrayList<>();
            for (int i = 0; i < request.choruses(); i++) {
                bars.addAll(structure.allBars());
            }
            PhraseRequest phrase = new PhraseRequest(bars, structure.clock(), request.startMs());
            for (NoteEvent note : config.composer().compose(phrase)) {
                agent.emit(note);
            }
        }
    }
}
