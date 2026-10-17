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
        assertTrue(response.body().contains("Musebot Agents"), "the page should carry its title");
        assertTrue(response.body().contains("Oscar Puerto"), "the page should credit its author");
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
        assertTrue(seen.containsAll(java.util.List.of("/app.js", "/instruments.js", "/drums.js", "/style.css",
                "/chart.js", "/stage.js", "/timeline.js", "/narrator.js", "/notes.js")), "crawled only " + seen);
    }

    /** The pictures of the band are loaded by the page as images, so nothing above would notice if one went missing. */
    @Test
    void servesAPictureForEveryInstrument() throws Exception {
        java.util.Set<String> pictures = new java.util.TreeSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("'(assets/[\\w]+\\.svg)'").matcher(get("/notes.js").body());
        while (matcher.find()) {
            pictures.add("/" + matcher.group(1));
        }
        assertEquals(java.util.Set.of("/assets/sax.svg", "/assets/piano.svg", "/assets/bass.svg", "/assets/drums.svg"), pictures);
        for (String picture : pictures) {
            HttpResponse<String> response = get(picture);
            assertEquals(200, response.statusCode(), picture);
            assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("image/svg+xml"), picture);
            assertTrue(response.body().contains("<svg"), picture + " is not an SVG");
        }
    }

    @Test
    void helloOffersTheTunesSoThePageCanPreviewTheirCharts() throws Exception {
        Client client = new Client();
        WebSocket socket = connect(client);
        try {
            waitFor(() -> !client.ofType("hello").isEmpty(), 5_000);
            JsonNode songs = client.ofType("hello").get(0).path("songs");
            assertEquals(List.of("blues", "song1"),
                    java.util.stream.StreamSupport.stream(songs.spliterator(), false).map(s -> s.path("id").asText()).toList());
            JsonNode blues = songs.get(0);
            assertEquals("Blues in F", blues.path("title").asText());
            assertEquals("ABC", blues.path("form").asText());
            assertEquals(3, blues.path("sections").size());
            assertEquals("F7", blues.path("sections").get(0).path("bars").get(0).asText());
            assertEquals("Song 1", songs.get(1).path("title").asText());
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    private static String start(int tempo, String ensemble) {
        return "{\"type\":\"start\",\"tempo\":" + tempo + ",\"soloChoruses\":1,\"ensemble\":\"" + ensemble + "\"}";
    }

    /** The parts of the tune after the intro, as the browser is told about them. */
    private static List<JsonNode> parts(Client client) {
        return client.ofType("segment").stream().filter(m -> !"INTRO".equals(m.path("kind").asText())).toList();
    }

    private static List<String> sections(Client client) {
        return parts(client).stream().map(m -> m.path("label").asText()).toList();
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
    void tellsTheBrowserTheChartBeforeThePerformanceStartsAndEachPartAsItIsArranged() throws Exception {
        Client client = new Client();
        WebSocket socket = connect(client);
        try {
            socket.sendText("{\"type\":\"start\",\"tempo\":960,\"ensemble\":\"duo\",\"song\":\"song1\"}", true).join();
            assertTrue(client.finished.await(60, TimeUnit.SECONDS), "performance did not finish");

            // The chart comes first, before anything the band does.
            JsonNode song = client.ofType("song").get(0);
            int songAt = client.messages.indexOf(song);
            assertTrue(client.messages.stream().limit(songAt).noneMatch(m -> List.of("note", "segment", "conversation").contains(m.path("type").asText())));
            assertEquals("song1", song.path("id").asText());
            assertEquals("Song 1", song.path("title").asText());
            assertEquals("AAB", song.path("form").asText());
            assertEquals(4, song.path("beatsPerBar").asInt());
            assertEquals("CM7", song.path("ending").asText());
            assertEquals(3, song.path("sections").size());
            assertEquals("A", song.path("sections").get(0).path("name").asText());
            assertEquals(List.of("Em7", "A7", "Dm7", "G7"),
                    java.util.stream.StreamSupport.stream(song.path("sections").get(0).path("bars").spliterator(), false)
                            .map(JsonNode::asText).toList());
            assertEquals(2, song.path("sections").get(2).path("bars").size());

            // Each part of the tune is announced with everything needed to draw where the band is.
            List<JsonNode> segments = client.ofType("segment");
            assertEquals(List.of("Intro", "The head", "piano solo", "bass solo", "The ending"),
                    segments.stream().map(m -> m.path("label").asText()).toList());
            JsonNode head = segments.get(1);
            assertEquals("HEAD", head.path("kind").asText());
            assertEquals("PIANO", head.path("leader").asText());
            assertEquals(960, head.path("tempoBpm").asInt());
            assertEquals(10 * 250, head.path("endMs").asLong() - head.path("startMs").asLong(), "ten bars of 250 ms");
            assertEquals(segments.get(0).path("endMs").asLong(), head.path("startMs").asLong());
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    @Test
    void rejectsAnUnknownSong() throws Exception {
        Client client = new Client();
        WebSocket socket = connect(client);
        try {
            socket.sendText("{\"type\":\"start\",\"song\":\"stairway\"}", true).join();
            waitFor(() -> !client.ofType("error").isEmpty(), 5_000);
            assertEquals(List.of("start: song must be 'blues' or 'song1'"), errors(client));
            assertTrue(client.ofType("song").isEmpty());
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
            List<JsonNode> sections = parts(client);
            long head = sections.get(0).path("startMs").asLong();
            long pianoSolo = sections.get(1).path("startMs").asLong();
            long bassSolo = sections.get(2).path("startMs").asLong();
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
