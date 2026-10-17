package dev.musebots.server;

/** What a browser may ask for. Tests widen the tempo range to run whole performances in seconds. */
public record ServerLimits(int minTempo, int maxTempo, int maxSoloChoruses) {

    public static final ServerLimits DEFAULT = new ServerLimits(60, 200, 2);
}
