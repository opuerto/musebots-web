package dev.musebots.agents;

/** What a musician does in the ensemble. A role wires its behaviours into the agent once it is set up. */
public interface Role {

    enum Kind {
        LEADER,
        ACCOMPANIST
    }

    void attach(MusicianAgent agent);

    static Role of(MusicianConfig config) {
        return switch (config.role()) {
            case LEADER -> new LeaderRole(config);
            case ACCOMPANIST -> new AccompanistRole(config);
        };
    }
}
