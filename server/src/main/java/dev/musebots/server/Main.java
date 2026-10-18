package dev.musebots.server;

import java.util.List;

/**
 * Starts the server. Everything that differs between a laptop and a public host is an environment variable:
 * <ul>
 *   <li>{@code PORT}: the web port (default 7070).</li>
 *   <li>{@code JADE_PORT}: the agent platform's own port, used only inside the machine (default 1099).</li>
 *   <li>{@code MAX_PERFORMANCES}: how many bands may play at once (default 8); each is sixteen agents.</li>
 *   <li>{@code ALLOWED_ORIGINS}: extra web origins allowed to connect, comma separated; pages served by this server always may.</li>
 *   <li>{@code MARKOV_MODEL}: the path of a model trained with the learning tools; without it only the hand-written rules are offered.</li>
 * </ul>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        int httpPort = intFromEnv("PORT", 7070);
        int jadePort = intFromEnv("JADE_PORT", 1099);
        ServerLimits limits = ServerLimits.DEFAULT.withMaxPerformances(intFromEnv("MAX_PERFORMANCES", ServerLimits.DEFAULT_MAX_PERFORMANCES));
        OriginPolicy origins = OriginPolicy.fromList(System.getenv("ALLOWED_ORIGINS"));
        PerformanceServer server = PerformanceServer.start(httpPort, jadePort, limits,
                SoloOption.offered(System.getenv("MARKOV_MODEL"), System.out), origins);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.out.println("Musebots listening on http://localhost:" + server.port() + " (up to " + limits.maxPerformances()
                + " performances at once; extra allowed origins: " + (origins.extraOrigins().isEmpty() ? "none" : List.copyOf(origins.extraOrigins())) + ")");
    }

    private static int intFromEnv(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number, not '" + value + "'");
        }
    }
}
