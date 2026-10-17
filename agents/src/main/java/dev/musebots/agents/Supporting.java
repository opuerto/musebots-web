package dev.musebots.agents;

import dev.musebots.core.PhraseKind;
import dev.musebots.core.Segment;
import dev.musebots.core.SongStructure;
import dev.musebots.core.SupportPolicy;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.FIPAAgentManagement.FailureException;
import jade.domain.FIPAAgentManagement.NotUnderstoodException;
import jade.domain.FIPAAgentManagement.RefuseException;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.ContractNetResponder;

/**
 * What every musician does whoever is leading: learn the song, bid for the intro, back the leader up, and
 * take the lead when it is handed over.
 */
final class Supporting {

    private static final int INTRO_BARS = 4;

    private Supporting() {
    }

    static void attach(MusicianAgent agent) {
        agent.addBehaviour(new StructureReceiver(agent));
        agent.addBehaviour(new IntroResponder(agent));
        agent.addBehaviour(new AccompanyReceiver(agent));
        agent.addBehaviour(new TakeLeadReceiver(agent));
        // Acknowledgements are published when they are sent; receiving them needs no further action.
        agent.addBehaviour(new Drain(agent, MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                MessageTemplate.or(MessageTemplate.MatchConversationId(Protocol.COMPOSE),
                        MessageTemplate.or(MessageTemplate.MatchConversationId(Protocol.ACCOMPANY),
                                MessageTemplate.MatchConversationId(Protocol.TAKE_LEAD))))));
    }

    /** Accepts the song structure from the leader and confirms receipt. */
    private static final class StructureReceiver extends CyclicBehaviour {

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
            SongStructure song = Json.read(message.getContent(), SongStructure.class);
            agent.learn(song);
            ACLMessage confirm = message.createReply();
            confirm.setPerformative(ACLMessage.CONFIRM);
            agent.say(confirm, "got the structure of '" + song.title() + "'");
        }
    }

    /** Responder half of the FIPA Contract Net: propose to play the intro; if chosen, have it written. */
    private static final class IntroResponder extends ContractNetResponder {

        private final MusicianAgent agent;

        IntroResponder(MusicianAgent agent) {
            super(agent, MessageTemplate.and(
                    ContractNetResponder.createMessageTemplate(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET),
                    MessageTemplate.MatchConversationId(Protocol.INTRO)));
            this.agent = agent;
        }

        @Override
        protected ACLMessage handleCfp(ACLMessage cfp) throws RefuseException, FailureException, NotUnderstoodException {
            if (agent.structure() == null) {
                ACLMessage refuse = cfp.createReply();
                refuse.setPerformative(ACLMessage.REFUSE);
                agent.observe(refuse, "I don't know the song yet");
                throw new RefuseException("no structure");
            }
            ACLMessage propose = cfp.createReply();
            propose.setPerformative(ACLMessage.PROPOSE);
            propose.setContent(Json.write(new Protocol.IntroProposal(agent.config().instrument(), INTRO_BARS)));
            agent.observe(propose, "I can play a " + INTRO_BARS + "-bar intro");
            return propose;
        }

        @Override
        protected ACLMessage handleAcceptProposal(ACLMessage cfp, ACLMessage propose, ACLMessage accept) {
            Protocol.IntroAssignment assignment = Json.read(accept.getContent(), Protocol.IntroAssignment.class);
            long durationMs = agent.structure().clock().barsToMillis(INTRO_BARS);
            Segment intro = new Segment("INTRO", PhraseKind.INTRO, agent.config().instrument(),
                    assignment.startMs(), assignment.startMs() + durationMs);

            agent.compose(PhraseKind.INTRO, intro);
            agent.record(intro);

            ACLMessage inform = accept.createReply();
            inform.setPerformative(ACLMessage.INFORM);
            inform.setContent(Json.write(new Protocol.IntroPerformed(intro.startMs(), durationMs)));
            agent.observe(inform, "the intro will start at " + intro.startMs() + " and last " + durationMs + " ms");
            return inform;
        }
    }

    /**
     * Backs the leader up. The request names a segment, not a time: the musician asks its synchronizer when
     * that segment happens, then has its composer write the part (or lays out, if etiquette says so).
     */
    private static final class AccompanyReceiver extends CyclicBehaviour {

        private final MessageTemplate template = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                MessageTemplate.MatchConversationId(Protocol.ACCOMPANY));
        private final MusicianAgent agent;

        AccompanyReceiver(MusicianAgent agent) {
            super(agent);
            this.agent = agent;
        }

        @Override
        public void action() {
            ACLMessage request = myAgent.receive(template);
            if (request == null) {
                block();
                return;
            }
            Protocol.Accompany job = Json.read(request.getContent(), Protocol.Accompany.class);
            ACLMessage reply = request.createReply();
            reply.setPerformative(ACLMessage.INFORM);

            agent.whenIs(job.segmentId(), segment -> {
                PhraseKind support = segment.kind().supporting();
                boolean layOut = SupportPolicy.laysOut(agent.config().instrument(), job.leader(), support);
                if (!layOut) {
                    agent.compose(support, segment);
                }
                reply.setContent(Json.write(new Protocol.Accompanying(segment.id(), !layOut)));
                agent.observe(reply, layOut ? "laying out during " + segment.id() : "playing along in " + segment.id());
                agent.send(reply);
            });
        }
    }

    /** Takes over the performance when the leader hands over the lead. */
    private static final class TakeLeadReceiver extends CyclicBehaviour {

        private final MessageTemplate template = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                MessageTemplate.MatchConversationId(Protocol.TAKE_LEAD));
        private final MusicianAgent agent;

        TakeLeadReceiver(MusicianAgent agent) {
            super(agent);
            this.agent = agent;
        }

        @Override
        public void action() {
            ACLMessage request = myAgent.receive(template);
            if (request == null) {
                block();
                return;
            }
            Protocol.TakeLead handover = Json.read(request.getContent(), Protocol.TakeLead.class);
            ACLMessage reply = request.createReply();
            reply.setPerformative(ACLMessage.INFORM);
            reply.setContent(Json.write(new Protocol.LeadTaken(handover.segmentId())));
            agent.say(reply, "I have the lead for " + handover.segmentId());
            agent.leading().takeLead(handover);
        }
    }
}
