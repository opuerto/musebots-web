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

    /** Starts the musicians of one session. Every event they produce goes to {@code sink}. */
    public Performance perform(SessionConfig session, EventSink sink) {
        List<AgentController> started = new ArrayList<>();
        try {
            started.add(launch(session, sink, session.leader(), Role.Kind.LEADER));
            for (Instrument instrument : session.accompanists()) {
                started.add(launch(session, sink, instrument, Role.Kind.ACCOMPANIST));
            }
        } catch (StaleProxyException e) {
            new Performance(session.sessionId(), started).stop();
            throw new IllegalStateException("Could not start session " + session.sessionId(), e);
        }
        return new Performance(session.sessionId(), started);
    }

    private AgentController launch(SessionConfig session, EventSink sink, Instrument instrument, Role.Kind role)
            throws StaleProxyException {
        MusicianConfig config = new MusicianConfig(
                session.sessionId(),
                instrument,
                role,
                Composers.defaultFor(instrument, new Random(session.seed() + instrument.ordinal())),
                sink,
                session.clock(),
                role == Role.Kind.LEADER ? session.song() : null,
                session.choruses(),
                session.ensembleSize(),
                session.leadInMs());
        AgentController controller = container.acceptNewAgent(config.agentName(), new MusicianAgent(config));
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
