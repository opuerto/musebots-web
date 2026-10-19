package dev.musebots.server;

import com.fasterxml.jackson.databind.JsonNode;
import dev.musebots.agents.ComposerFactory;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;

/**
 * Serves the web client and runs one performance per browser connection.
 *
 * <p>Protocol (JSON over WebSocket at {@code /ws}). Client to server: {@code time.sync}, {@code start}
 * (tempo, soloChoruses, ensemble = quartet or duo, song = blues or song1, soloComposer = rules or markov when a model is loaded, feel = human or mechanical), {@code stop}, and {@code conduct}
 * (command = tempo, volume, mute or unmute; instrument; value). Server to client: see {@link Wire}.
 */
public final class PerformanceServer implements AutoCloseable {

    /** The band plays with a human feel (timing, dynamics, tempo-dependent swing), or exactly as written. */
    static final String FEEL_HUMAN = "human";
    static final String FEEL_MECHANICAL = "mechanical";

    /** Volume is a multiplier: 1 is as written, above 1 is louder (velocities are capped at the MIDI maximum). */
    private static final double MAX_VOLUME = 1.5;

    private final EnsembleRuntime runtime;
    private final ServerLimits limits;
    private final List<SoloOption> soloOptions;
    private final OriginPolicy origins;
    private final Javalin app;
    /** Performances running or being started; compared with the limit before another is allowed. */
    private final AtomicInteger reserved = new AtomicInteger();
    private final Map<String, Performance> running = new ConcurrentHashMap<>();
    // Stopping kills agents; never do that on an agent's own thread.
    private final ExecutorService janitor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "performance-janitor");
        thread.setDaemon(true);
        return thread;
    });

    static final String MODEL_LICENCE = "These solo models are derived from the Weimar Jazz Database (Jazzomat Research Project, "
            + "Hochschule für Musik Franz Liszt Weimar, https://jazzomat.hfm-weimar.de/) and are made available under the Open Database "
            + "License (ODbL) v1.0: https://opendatacommons.org/licenses/odbl/1-0/";

    private PerformanceServer(EnsembleRuntime runtime, ServerLimits limits, List<SoloOption> soloOptions, OriginPolicy origins, int httpPort) {
        this.runtime = runtime;
        this.limits = limits;
        this.soloOptions = List.copyOf(soloOptions);
        this.origins = origins;
        this.app = Javalin.create(config -> config.staticFiles.add("/public", Location.CLASSPATH));
        // For the hosting platform's health check: cheap, and says how busy the band is.
        app.get("/health", ctx -> ctx.json(Map.of("status", "ok", "performances", running.size(), "capacity", limits.maxPerformances())));
        // The learned models, offered under the licence of the data they were made from: the Open Database License asks that
        // whoever hears music made from a database derived from it may have that database too (section 4.6).
        Map<String, java.nio.file.Path> models = new java.util.TreeMap<>();
        this.soloOptions.forEach(option -> models.putAll(option.downloads()));
        app.get("/models", ctx -> ctx.json(Map.of(
                "licence", MODEL_LICENCE,
                "models", models.keySet().stream().map(name -> "/models/" + name + ".json").toList())));
        app.get("/models/{file}", ctx -> {
            String name = ctx.pathParam("file").replaceFirst("\\.json$", "");
            java.nio.file.Path file = models.get(name); // only the names above, so nothing else on the disk can be asked for
            if (file == null) {
                ctx.status(404).result("no such model");
                return;
            }
            ctx.contentType("application/json")
                    .header("Content-Disposition", "attachment; filename=\"musebots-markov-" + name + ".json\"")
                    .result(java.nio.file.Files.newInputStream(file));
        });
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                if (!origins.allows(ctx.header("Origin"), ctx.header("Host"))) {
                    ctx.closeSession(1008, "this page may not use the band");
                    return;
                }
                ctx.session.setIdleTimeout(Duration.ofMinutes(30));
                ctx.send(Wire.hello(System.currentTimeMillis(), this.soloOptions));
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
        return start(httpPort, jadePort, limits, List.of(SoloOption.rules()));
    }

    /** @param soloOptions the ways of writing solos a browser may choose from; the first is the default */
    public static PerformanceServer start(int httpPort, int jadePort, ServerLimits limits, List<SoloOption> soloOptions) {
        return start(httpPort, jadePort, limits, soloOptions, OriginPolicy.sameOriginOnly());
    }

    /** @param origins which web pages may connect; by default only those served by this server */
    public static PerformanceServer start(int httpPort, int jadePort, ServerLimits limits, List<SoloOption> soloOptions, OriginPolicy origins) {
        return new PerformanceServer(EnsembleRuntime.start(jadePort), limits, soloOptions, origins, httpPort);
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
            ctx.send(Wire.error("start", "song must be one of " + String.join(", ", Songs.IDS)));
            return;
        }
        String soloId = message.path("soloComposer").asText(soloOptions.get(0).id());
        SoloOption soloOption = soloOptions.stream().filter(option -> option.id().equals(soloId)).findFirst().orElse(null);
        if (soloOption == null) {
            ctx.send(Wire.error("start", "soloComposer must be one of " + String.join(", ", soloOptions.stream().map(SoloOption::id).toList())));
            return;
        }
        String feel = message.path("feel").asText(FEEL_HUMAN);
        if (!feel.equals(FEEL_HUMAN) && !feel.equals(FEEL_MECHANICAL)) {
            ctx.send(Wire.error("start", "feel must be '" + FEEL_HUMAN + "' or '" + FEEL_MECHANICAL + "'"));
            return;
        }
        String order = message.path("order").asText("random");
        if (!order.equals("random") && !order.equals("fixed")) {
            ctx.send(Wire.error("start", "order must be 'random' or 'fixed'"));
            return;
        }
        SessionConfig.TurnOrder turnOrder = order.equals("fixed") ? SessionConfig.TurnOrder.FIXED : SessionConfig.TurnOrder.RANDOM;
        String ensemble = message.path("ensemble").asText("quartet");
        if (!ensemble.equals("quartet") && !ensemble.equals("duo")) {
            ctx.send(Wire.error("start", "ensemble must be 'quartet' or 'duo'"));
            return;
        }
        if (running.containsKey(ctx.sessionId())) {
            ctx.send(Wire.error("start", "a performance is already running"));
            return;
        }
        if (reserved.incrementAndGet() > limits.maxPerformances()) {
            reserved.decrementAndGet();
            ctx.send(Wire.error("start", "the band is busy with other visitors right now; please try again in a minute"));
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
                ? SessionConfig.duo(sessionId, song, soloChoruses, turnOrder)
                : SessionConfig.quartet(sessionId, song, soloChoruses, turnOrder);
        toBrowser.send(Wire.song(songId, song)); // before the band starts, so the chart is ready for the first note
        ComposerFactory composers = feel.equals(FEEL_HUMAN) ? ComposerFactory.humanized(soloOption.composers()) : soloOption.composers();
        try {
            running.put(ctx.sessionId(), runtime.perform(session, sink, composers));
        } catch (RuntimeException e) {
            reserved.decrementAndGet(); // the band never started, so it does not hold a place
            throw e;
        }
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
            reserved.decrementAndGet();
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
