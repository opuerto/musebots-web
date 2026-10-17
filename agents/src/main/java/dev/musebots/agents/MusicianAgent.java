package dev.musebots.agents;

/** A musician: registers with the ensemble directory and then lets its role drive the behaviour. */
public final class MusicianAgent extends MusebotAgent {

    private final MusicianConfig config;
    private final Role role;

    public MusicianAgent(MusicianConfig config) {
        super(config.sessionId(), config.sink(), config.clock());
        this.config = config;
        this.role = Role.of(config);
    }

    MusicianConfig config() {
        return config;
    }

    @Override
    protected void setup() {
        registerAsMusician(config.instrument().name());
        role.attach(this);
    }
}
