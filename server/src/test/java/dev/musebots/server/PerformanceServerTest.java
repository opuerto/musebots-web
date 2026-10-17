package dev.musebots.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PerformanceServerTest {

    private PerformanceServer server;

    @BeforeAll
    void start() throws IOException {
        // Wide tempo range so whole performances (intro, head, solos, ending) take seconds, not minutes.
        server = PerformanceServer.start(0, freePort(), new ServerLimits(60, 960, 2));
    }

    @AfterAll
    void stop() {
        server.close();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** A WebSocket client that records every JSON message and signals on a terminal status. */
    private static final class Client implements WebSocket.Listener {
        final List<JsonNode> messages = new CopyOnWriteArrayList<>();
        final CountDownLatch finished = new CountDownLatch(1);
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                try {
                    JsonNode message = Wire.MAPPER.readTree(partial.toString());
                    messages.add(message);
                    if ("status".equals(message.path("type").asText())
                            && List.of("FINISHED", "FAILED").contains(message.path("kind").asText())) {
                        finished.countDown();
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                } finally {
                    partial.setLength(0);
                }
            }
            webSocket.request(1);
            return null;
        }

        List<JsonNode> ofType(String type) {
            return messages.stream().filter(m -> type.equals(m.path("type").asText())).toList();
        }
    }

    private WebSocket connect(Client client) {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + server.port() + "/ws"), client).join();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesTheWebClient() throws Exception {
        HttpResponse<String> response = get("/");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Musebots"));
    }

    /** If the page imports a script the server does not serve, the page silently does nothing: check every one. */
    @Test
    void servesEveryScriptAndStylesheetThePageNeeds() throws Exception {
        java.util.Set<String> needed = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher page = java.util.regex.Pattern.compile("(?:src|href)=\"([^\"]+)\"").matcher(get("/").body());
        while (page.find()) {
            if (!page.group(1).startsWith("http")) {
                needed.add("/" + page.group(1));
            }
        }
        assertTrue(needed.contains("/app.js") && needed.contains("/style.css"), "the page should load app.js and style.css: " + needed);

        // Follow imports between modules: app.js imports instruments.js, which imports drums.js.
        java.util.Deque<String> queue = new java.util.ArrayDeque<>(needed);
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (!queue.isEmpty()) {
            String path = queue.poll();
            if (!seen.add(path)) {
                continue;
            }
            HttpResponse<String> response = get(path);
            assertEquals(200, response.statusCode(), path + " is not served");
            if (path.endsWith(".js")) {
                assertTrue(response.headers().firstValue("content-type").orElse("").contains("javascript"),
                        path + " is not served as JavaScript");
                java.util.regex.Matcher imports = java.util.regex.Pattern.compile("from '\\./([^']+)'").matcher(response.body());
                while (imports.find()) {
                    queue.add("/" + imports.group(1));
                }
            }
        }
        assertTrue(seen.containsAll(java.util.List.of("/app.js", "/instruments.js", "/drums.js", "/style.css")), "crawled only " + seen);
    }

    private static String start(int tempo, String ensemble) {
        return "{\"type\":\"start\",\"tempo\":" + tempo + ",\"soloChoruses\":1,\"ensemble\":\"" + ensemble + "\"}";
    }

    private static List<String> sections(Client client) {
        return client.ofType("status").stream().filter(m -> "SECTION".equals(m.path("kind").asText()))
                .map(m -> m.path("message").asText()).toList();
    }

    private static boolean played(Client client, String instrument) {
        return client.ofType("note").stream().anyMatch(n -> instrument.equals(n.path("instrument").asText()));
    }

    @Test
    void streamsANegotiatedDuoToTheBrowser() throws Exception {
        Client client = new Client();
        WebSocket socket = connect(client);
        try {
            socket.sendText("{\"type\":\"time.sync\",\"t0\":123}", true).join();
            socket.sendText(start(960, "duo"), true).join();

            assertTrue(client.finished.await(60, TimeUnit.SECONDS), "performance did not finish");

            assertEquals(1, client.ofType("hello").size());
            assertEquals(123, client.ofType("time.sync").get(0).path("t0").asLong());
            assertEquals("FINISHED", client.ofType("status").stream().reduce((a, b) -> b).orElseThrow().path("kind").asText());

            // The browser is told when each part of the tune begins.
            assertEquals(List.of("The head", "piano solo", "bass solo", "The ending"), sections(client));

            // It sees the negotiation, with the conversation each message belongs to.
            List<JsonNode> talk = client.ofType("conversation");
            assertTrue(talk.stream().anyMatch(m -> "CFP".equals(m.path("performative").asText())
                    && "intro".equals(m.path("conversationId").asText())));
            assertTrue(talk.stream().anyMatch(m -> "take-lead".equals(m.path("conversationId").asText())));
            assertTrue(talk.stream().anyMatch(m -> m.path("from").asText().endsWith("-bass-synchronizer")));
            assertTrue(talk.stream().anyMatch(m -> m.path("from").asText().endsWith("-piano-composer")));

            assertTrue(played(client, "BASS"));
            assertTrue(played(client, "PIANO"));
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    @Test
    void streamsTheFullQuartetByDefault() throws Exception {
        Client client = new Client();
        WebSocket socket = connect(client);
        try {
            socket.sendText("{\"type\":\"start\",\"tempo\":960}", true).join();
            assertTrue(client.finished.await(60, TimeUnit.SECONDS), "performance did not finish");

            assertEquals("FINISHED", client.ofType("status").stream().reduce((a, b) -> b).orElseThrow().path("kind").asText());
            assertEquals(List.of("The head", "sax solo", "piano solo", "bass solo", "drums solo", "The ending"), sections(client));
            for (String instrument : List.of("SAX", "PIANO", "BASS", "DRUMS")) {
                assertTrue(played(client, instrument), instrument + " never played");
            }
            assertEquals(3, client.ofType("conversation").stream()
                    .filter(m -> "PROPOSE".equals(m.path("performative").asText())).count());
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    @Test
    void stoppingEndsThePerformanceEarly() throws Exception {
        Client client = new Client();
        WebSocket socket = connect(client);
        try {
            socket.sendText(start(120, "duo"), true).join();
            long deadline = System.currentTimeMillis() + 10_000;
            while (client.ofType("note").isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(client.ofType("note").isEmpty());

            socket.sendText("{\"type\":\"stop\"}", true).join();
            Thread.sleep(500);
            int before = client.messages.size();
            Thread.sleep(1_500);
            // Nothing more arrives once the agents are gone (the performance would last for a minute at 120 bpm).
            assertEquals(before, client.messages.size());
            assertFalse(client.finished.getCount() == 0, "the performance carried on to the end");

            // And a new performance can start on the same connection.
            socket.sendText(start(960, "duo"), true).join();
            assertTrue(client.finished.await(60, TimeUnit.SECONDS));
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
    }

    private static List<String> errors(Client client) {
        return client.ofType("error").stream().map(m -> m.path("source").asText() + ": " + m.path("message").asText()).toList();
    }

    @Test
    void theConductorChangesTheTempoOfAPerformanceInProgress() throws Exception {
        Client client = new Client();
        WebSocket socket = connect(client);
        try {
            socket.sendText(start(480, "duo"), true).join();
            waitFor(() -> !client.ofType("note").isEmpty(), 10_000);
            socket.sendText("{\"type\":\"conduct\",\"command\":\"tempo\",\"value\":960}", true).join();

            assertTrue(client.finished.await(60, TimeUnit.SECONDS), "performance did not finish");
            assertEquals("FINISHED", client.ofType("status").stream().reduce((a, b) -> b).orElseThrow().path("kind").asText());
            assertEquals(List.of(), errors(client));

            // The head was arranged at 480 bpm (6 s), the parts after the command at 960 bpm (3 s).
            List<JsonNode> sections = client.ofType("status").stream().filter(m -> "SECTION".equals(m.path("kind").asText())).toList();
            long head = sections.get(0).path("atMs").asLong();
            long pianoSolo = sections.get(1).path("atMs").asLong();
            long bassSolo = sections.get(2).path("atMs").asLong();
            assertEquals(6_000, pianoSolo - head);
            assertEquals(3_000, bassSolo - pianoSolo);

            // The browser can watch the Conductor's command travel through the assistants to the musicians.
            List<JsonNode> talk = client.ofType("conversation");
            assertTrue(talk.stream().anyMatch(m -> "conductor".equals(m.path("conversationId").asText())
                    && m.path("from").asText().endsWith("-conductor")));
            assertEquals(2, talk.stream().filter(m -> "tempo".equals(m.path("conversationId").asText())
                    && m.path("from").asText().endsWith("-assistant")).count());
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    @Test
    void theConductorCanMuteAndSetTheVolumeOfOneMusician() throws Exception {
        Client client = new Client();
        WebSocket socket = connect(client);
        try {
            socket.sendText(start(960, "duo"), true).join();
            socket.sendText("{\"type\":\"conduct\",\"command\":\"mute\",\"instrument\":\"bass\"}", true).join();
            socket.sendText("{\"type\":\"conduct\",\"command\":\"volume\",\"instrument\":\"piano\",\"value\":0.4}", true).join();

            assertTrue(client.finished.await(60, TimeUnit.SECONDS), "performance did not finish");
            assertEquals(List.of(), errors(client));
            assertFalse(played(client, "BASS"), "the muted bass played");
            assertTrue(played(client, "PIANO"));
            assertTrue(client.ofType("note").stream().filter(n -> "PIANO".equals(n.path("instrument").asText()))
                    .allMatch(n -> n.path("velocity").asInt() <= 45), "the piano was not turned down");
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    @Test
    void theConductorRefusesCommandsItCannotCarryOut() throws Exception {
        Client client = new Client();
        WebSocket socket = connect(client);
        try {
            socket.sendText("{\"type\":\"conduct\",\"command\":\"tempo\",\"value\":120}", true).join();
            waitFor(() -> errors(client).size() == 1, 5_000);
            assertEquals(List.of("conduct: no performance is running"), errors(client));

            socket.sendText(start(120, "duo"), true).join();
            for (String bad : List.of(
                    "{\"type\":\"conduct\",\"command\":\"dance\"}",
                    "{\"type\":\"conduct\",\"command\":\"tempo\",\"value\":5000}",
                    "{\"type\":\"conduct\",\"command\":\"volume\",\"instrument\":\"kazoo\",\"value\":1}",
                    "{\"type\":\"conduct\",\"command\":\"volume\",\"instrument\":\"bass\",\"value\":9}")) {
                socket.sendText(bad, true).join();
            }
            waitFor(() -> errors(client).size() == 5, 5_000);
            assertEquals(List.of("conduct: no performance is running",
                    "conduct: command must be tempo, volume, mute or unmute",
                    "conduct: tempo must be 60-960",
                    "conduct: instrument must be one of sax, piano, bass, drums",
                    "conduct: volume must be between 0 and 1.5"), errors(client));
            // None of that disturbed the performance, which is still running.
            assertEquals(1L, client.finished.getCount());
            socket.sendText("{\"type\":\"stop\"}", true).join();
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    @Test
    void rejectsAnUnknownEnsemble() throws Exception {
        Client client = new Client();
        WebSocket socket = connect(client);
        try {
            socket.sendText("{\"type\":\"start\",\"ensemble\":\"big-band\"}", true).join();
            long deadline = System.currentTimeMillis() + 5_000;
            while (client.ofType("error").isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(client.ofType("error").isEmpty());
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    @Test
    void rejectsOutOfRangeTempo() throws Exception {
        Client client = new Client();
        WebSocket socket = connect(client);
        try {
            socket.sendText("{\"type\":\"start\",\"tempo\":5000}", true).join();
            long deadline = System.currentTimeMillis() + 5_000;
            while (client.ofType("error").isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(client.ofType("error").isEmpty());
            assertTrue(client.ofType("note").isEmpty());
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }
}
