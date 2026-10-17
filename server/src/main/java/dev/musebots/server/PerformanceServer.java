package dev.musebots.server;

import com.fasterxml.jackson.databind.JsonNode;
import dev.musebots.agents.EnsembleRuntime;
import dev.musebots.agents.Performance;
import dev.musebots.agents.SessionConfig;
import dev.musebots.core.EventSink;
import dev.musebots.core.Songs;
import dev.musebots.core.StatusEvent;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serves the web client and runs one performance per browser connection.
 *
 * <p>Protocol (JSON over WebSocket at {@code /ws}). Client to server: {@code time.sync}, {@code start},
 * {@code stop}. Server to client: see {@link Wire}.
 */
public final class PerformanceServer implements AutoCloseable {

    private static final int MIN_TEMPO = 60;
    private static final int MAX_TEMPO = 200;
    private static final int MAX_CHORUSES = 4;

    private final EnsembleRuntime runtime;
    private final Javalin app;
    private final Map<String, Performance> running = new ConcurrentHashMap<>();
    // Stopping kills agents; never do that on an agent's own thread.
    private final ExecutorService janitor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "performance-janitor");
        thread.setDaemon(true);
        return thread;
    });

    private PerformanceServer(EnsembleRuntime runtime, int httpPort) {
        this.runtime = runtime;
        this.app = Javalin.create(config -> config.staticFiles.add("/public", Location.CLASSPATH));
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                ctx.session.setIdleTimeout(Duration.ofMinutes(30));
                ctx.send(Wire.hello(System.currentTimeMillis()));
            });
            ws.onMessage(ctx -> handle(ctx, Wire.MAPPER.readTree(ctx.message())));
            ws.onClose(ctx -> stop(ctx.sessionId()));
            ws.onError(ctx -> stop(ctx.sessionId()));
        });
        app.start(httpPort);
    }

    public static PerformanceServer start(int httpPort, int jadePort) {
        return new PerformanceServer(EnsembleRuntime.start(jadePort), httpPort);
    }

    public int port() {
        return app.port();
    }

    private void handle(WsContext ctx, JsonNode message) {
        switch (message.path("type").asText()) {
            case "time.sync" -> ctx.send(Wire.timeSync(message.path("t0").asLong(), System.currentTimeMillis()));
            case "start" -> start(ctx, message);
            case "stop" -> stop(ctx.sessionId());
            default -> ctx.send(Wire.error("unknown message type"));
        }
    }

    private void start(WsContext ctx, JsonNode message) {
        int tempo = message.path("tempo").asInt(120);
        int choruses = message.path("choruses").asInt(2);
        if (tempo < MIN_TEMPO || tempo > MAX_TEMPO || choruses < 1 || choruses > MAX_CHORUSES) {
            ctx.send(Wire.error("tempo must be " + MIN_TEMPO + "-" + MAX_TEMPO + " and choruses 1-" + MAX_CHORUSES));
            return;
        }
        if (running.containsKey(ctx.sessionId())) {
            ctx.send(Wire.error("a performance is already running"));
            return;
        }

        String sessionId = "s" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        WebSocketSink toBrowser = new WebSocketSink(ctx);
        EventSink sink = event -> {
            toBrowser.emit(event);
            if (event instanceof StatusEvent status && (status.kind() == StatusEvent.Kind.FINISHED
                    || status.kind() == StatusEvent.Kind.FAILED)) {
                janitor.execute(() -> stop(ctx.sessionId()));
            }
        };
        running.put(ctx.sessionId(), runtime.perform(
                SessionConfig.duo(sessionId, Songs.bluesInF(tempo), choruses), sink));
    }

    private void stop(String connectionId) {
        Performance performance = running.remove(connectionId);
        if (performance != null) {
            performance.stop();
        }
    }

    @Override
    public void close() {
        running.keySet().forEach(this::stop);
        janitor.shutdown();
        app.stop();
        runtime.close();
    }
}
