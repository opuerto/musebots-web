package dev.musebots.server;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        int httpPort = intFromEnv("PORT", 7070);
        int jadePort = intFromEnv("JADE_PORT", 1099);
        PerformanceServer server = PerformanceServer.start(httpPort, jadePort);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.out.println("Musebots listening on http://localhost:" + server.port());
    }

    private static int intFromEnv(String name, int fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : Integer.parseInt(value);
    }
}
