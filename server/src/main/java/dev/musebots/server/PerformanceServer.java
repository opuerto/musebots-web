package dev.musebots.server;

import com.fasterxml.jackson.databind.JsonNode;
import dev.musebots.agents.EnsembleRuntime;
import dev.musebots.agents.Performance;
import dev.musebots.agents.Protocol;
import dev.musebots.agents.SessionConfig;
import dev.musebots.core.EventSink;
import dev.musebots.core.Instrument;
import dev.musebots.core.SongStructure;
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
 * <p>Protocol (JSON over WebSocket at {@code /ws}). Client to server: {@code time.sync}, {@code start}
 * (tempo, soloChoruses, ensemble = quartet or duo, song = blues or song1), {@code stop}, and {@code conduct}
 * (command = tempo, volume, mute or unmute; instrument; value). Server to client: see {@link Wire}.
 */
public final class PerformanceServer implements AutoCloseable {

    /** Volume is a multiplier: 1 is as written, above 1 is louder (velocities are capped at the MIDI maximum). */
    private static final double MAX_VOLUME = 1.5;

    private final EnsembleRuntime runtime;
    private final ServerLimits limits;
    private final Javalin app;
    private final Map<String, Performance> running = new ConcurrentHashMap<>();
    // Stopping kills agents; never do that on an agent's own thread.
    private final ExecutorService janitor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "performance-janitor");
        thread.setDaemon(true);
        return thread;
    });

    private PerformanceServer(EnsembleRuntime runtime, ServerLimits limits, int httpPort) {
        this.runtime = runtime;
        this.limits = limits;
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
        return start(httpPort, jadePort, ServerLimits.DEFAULT);
    }

    public static PerformanceServer start(int httpPort, int jadePort, ServerLimits limits) {
        return new PerformanceServer(EnsembleRuntime.start(jadePort), limits, httpPort);
    }

    public int port() {
        return app.port();
    }

    private void handle(WsContext ctx, JsonNode message) {
        switch (message.path("type").asText()) {
            case "time.sync" -> ctx.send(Wire.timeSync(message.path("t0").asLong(), System.currentTimeMillis()));
            case "start" -> start(ctx, message);
            case "stop" -> stop(ctx.sessionId());
            case "conduct" -> conduct(ctx, message);
            default -> ctx.send(Wire.error("message", "unknown message type"));
        }
    }

    private void start(WsContext ctx, JsonNode message) {
        int tempo = message.path("tempo").asInt(120);
        int soloChoruses = message.path("soloChoruses").asInt(1);
        if (tempo < limits.minTempo() || tempo > limits.maxTempo() || soloChoruses < 1 || soloChoruses > limits.maxSoloChoruses()) {
            ctx.send(Wire.error("start", "tempo must be " + limits.minTempo() + "-" + limits.maxTempo()
                    + " and soloChoruses 1-" + limits.maxSoloChoruses()));
            return;
        }
        String songId = message.path("song").asText(Songs.BLUES);
        SongStructure song = Songs.byId(songId, tempo);
        if (song == null) {
            ctx.send(Wire.error("start", "song must be '" + Songs.BLUES + "' or '" + Songs.SONG_1 + "'"));
            return;
        }
        String ensemble = message.path("ensemble").asText("quartet");
        if (!ensemble.equals("quartet") && !ensemble.equals("duo")) {
            ctx.send(Wire.error("start", "ensemble must be 'quartet' or 'duo'"));
            return;
        }
        if (running.containsKey(ctx.sessionId())) {
            ctx.send(Wire.error("start", "a performance is already running"));
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
        SessionConfig session = ensemble.equals("duo")
                ? SessionConfig.duo(sessionId, song, soloChoruses)
                : SessionConfig.quartet(sessionId, song, soloChoruses);
        toBrowser.send(Wire.song(songId, song)); // before the band starts, so the chart is ready for the first note
        running.put(ctx.sessionId(), runtime.perform(session, sink));
    }

    /** Passes a Conductor command (tempo, volume, mute, unmute) to the performance this browser started. */
    private void conduct(WsContext ctx, JsonNode message) {
        Performance performance = running.get(ctx.sessionId());
        if (performance == null) {
            ctx.send(Wire.error("conduct", "no performance is running"));
            return;
        }
        Protocol.Command command;
        try {
            command = Protocol.Command.valueOf(message.path("command").asText().toUpperCase());
        } catch (IllegalArgumentException e) {
            ctx.send(Wire.error("conduct", "command must be tempo, volume, mute or unmute"));
            return;
        }

        Instrument target = null;
        if (command != Protocol.Command.TEMPO) {
            try {
                target = Instrument.valueOf(message.path("instrument").asText().toUpperCase());
            } catch (IllegalArgumentException e) {
                ctx.send(Wire.error("conduct", "instrument must be one of sax, piano, bass, drums"));
                return;
            }
        }
        double value = message.path("value").asDouble(0);
        if (command == Protocol.Command.TEMPO && (value < limits.minTempo() || value > limits.maxTempo())) {
            ctx.send(Wire.error("conduct", "tempo must be " + limits.minTempo() + "-" + limits.maxTempo()));
            return;
        }
        if (command == Protocol.Command.VOLUME && (value < 0 || value > MAX_VOLUME)) {
            ctx.send(Wire.error("conduct", "volume must be between 0 and " + MAX_VOLUME));
            return;
        }

        try {
            performance.conduct(new Protocol.Conduct(command, target, value));
        } catch (IllegalStateException e) {
            ctx.send(Wire.error("conduct", "the performance is over"));
        }
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
