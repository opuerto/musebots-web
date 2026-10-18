package dev.musebots.server;

/**
 * What a browser may ask for, and how many performances the server runs at once. Each performance is sixteen agents, so
 * the cap is what keeps a busy day from using all of the server's memory. Tests widen the tempo range to run whole
 * performances in seconds.
 */
public record ServerLimits(int minTempo, int maxTempo, int maxSoloChoruses, int maxPerformances) {

    public static final int DEFAULT_MAX_PERFORMANCES = 8;
    public static final ServerLimits DEFAULT = new ServerLimits(60, 200, 2, DEFAULT_MAX_PERFORMANCES);

    public ServerLimits {
        if (maxPerformances < 1) {
            throw new IllegalArgumentException("the server must allow at least one performance");
        }
    }

    public ServerLimits(int minTempo, int maxTempo, int maxSoloChoruses) {
        this(minTempo, maxTempo, maxSoloChoruses, DEFAULT_MAX_PERFORMANCES);
    }

    /** The same limits with room for this many performances. */
    public ServerLimits withMaxPerformances(int performances) {
        return new ServerLimits(minTempo, maxTempo, maxSoloChoruses, performances);
    }
}
