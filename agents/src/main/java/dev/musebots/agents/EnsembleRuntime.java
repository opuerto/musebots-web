package dev.musebots.agents;

import dev.musebots.core.Composers;
import dev.musebots.core.EventSink;
import dev.musebots.core.Instrument;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentController;
import jade.wrapper.ContainerController;
import jade.wrapper.StaleProxyException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Hosts one JADE platform and starts performances on it. Sessions share the platform but are
 * isolated by name: agent names and directory entries are prefixed with the session id.
 */
public final class EnsembleRuntime implements AutoCloseable {

    private final ContainerController container;

    private EnsembleRuntime(ContainerController container) {
        this.container = container;
    }

    public static EnsembleRuntime start(int platformPort) {
        Profile profile = new ProfileImpl();
        profile.setParameter(Profile.MAIN_HOST, "localhost");
        profile.setParameter(Profile.MAIN_PORT, Integer.toString(platformPort));
        profile.setParameter(Profile.GUI, "false");
        Runtime.instance().setCloseVM(false);
        return new EnsembleRuntime(Runtime.instance().createMainContainer(profile));
    }

    /** Starts the Musebots of one session. Every event their agents produce goes to {@code sink}. */
    public Performance perform(SessionConfig session, EventSink sink) {
        List<AgentController> started = new ArrayList<>();
        try {
            List<Instrument> everyone = new ArrayList<>(List.of(session.leader()));
            everyone.addAll(session.accompanists());
            for (Instrument instrument : everyone) {
                launchMusebot(session, sink, instrument, started);
            }
        } catch (StaleProxyException e) {
            new Performance(session.sessionId(), started).stop();
            throw new IllegalStateException("Could not start session " + session.sessionId(), e);
        }
        return new Performance(session.sessionId(), started);
    }

    /** A Musebot is three agents: the composer and synchronizer first, so they are ready when the musician is. */
    private void launchMusebot(SessionConfig session, EventSink sink, Instrument instrument,
                               List<AgentController> started) throws StaleProxyException {
        String id = session.sessionId();
        ComposerConfig composer = new ComposerConfig(id, instrument,
                Composers.defaultFor(instrument, new Random(session.seed() + instrument.ordinal())),
                sink, session.clock());
        SynchronizerConfig synchronizer = new SynchronizerConfig(id, instrument, sink, session.clock());
        boolean opens = instrument == session.leader();
        MusicianConfig musician = new MusicianConfig(id, instrument, opens, sink, session.clock(),
                opens ? session.song() : null, session.soloOrder(), session.soloChoruses(),
                session.ensembleSize(), session.leadInMs(), session.planAheadMs());

        started.add(start(Names.of(id, instrument, Names.Part.COMPOSER), new ComposerAgent(composer)));
        started.add(start(Names.of(id, instrument, Names.Part.SYNCHRONIZER), new SynchronizerAgent(synchronizer)));
        started.add(start(Names.of(id, instrument, Names.Part.MUSICIAN), new MusicianAgent(musician)));
    }

    AgentController start(String name, jade.core.Agent agent) throws StaleProxyException {
        AgentController controller = container.acceptNewAgent(name, agent);
        controller.start();
        return controller;
    }

    @Override
    public void close() {
        try {
            container.kill();
        } catch (StaleProxyException e) {
            // Already stopped.
        }
    }
}
